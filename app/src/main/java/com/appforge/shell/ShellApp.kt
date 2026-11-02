package com.appforge.shell

import android.app.Application
import org.json.JSONObject

class ShellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("appforge_safety", MODE_PRIVATE)
        // Safety net: if push crashed the app, record it and open without push next time so the app still works.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val trace = android.util.Log.getStackTraceString(e)
            val pushCrash = trace.contains("pusher") || trace.contains("firebase") || trace.contains("onesignal")
            prefs.edit().putString("lastCrash", trace.take(4000)).putBoolean("pushDisabled", pushCrash).putInt("crashVersion", BuildConfig.VERSION_CODE).commit()
            prev?.uncaughtException(t, e)
        }
        // A new version gets a fresh chance to start push.
        if (prefs.getInt("crashVersion", -1) != BuildConfig.VERSION_CODE) prefs.edit().putBoolean("pushDisabled", false).apply()
        if (prefs.getBoolean("pushDisabled", false)) {
            android.util.Log.e("AppForge", "Push disabled after a crash: " + prefs.getString("lastCrash", ""))
            return
        }
        try {
            val cfg = JSONObject(assets.open("appforge/config.json").bufferedReader().use { it.readText() })
            Push.init(this, cfg)
        } catch (e: Throwable) {
            android.util.Log.e("AppForge", "Push init failed", e)
        }
    }
}
