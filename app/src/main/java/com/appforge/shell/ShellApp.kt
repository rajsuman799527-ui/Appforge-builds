package com.appforge.shell

import android.app.Application
import org.json.JSONObject

class ShellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val cfg = JSONObject(assets.open("appforge/config.json").bufferedReader().use { it.readText() })
            Push.init(this, cfg)
        } catch (e: Exception) {
        }
    }
}
