package com.appforge.shell

import android.app.Application
import com.onesignal.OneSignal
import com.onesignal.notifications.INotificationClickEvent
import com.onesignal.notifications.INotificationClickListener
import org.json.JSONObject

class ShellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val cfg = JSONObject(assets.open("appforge/config.json").bufferedReader().use { it.readText() })
            val push = cfg.optJSONObject("push")
            val id = push?.optString("oneSignalAppId") ?: ""
            if (push != null && push.optBoolean("enabled") && id.isNotBlank()) {
                OneSignal.initWithContext(this, id)
                OneSignal.Notifications.addClickListener(object : INotificationClickListener {
                    override fun onClick(event: INotificationClickEvent) {
                        val url = event.notification.additionalData?.optString("target_url") ?: ""
                        if (url.isNotBlank()) MainActivity.pendingUrl = url
                    }
                })
            }
        } catch (e: Exception) {
        }
    }
}
