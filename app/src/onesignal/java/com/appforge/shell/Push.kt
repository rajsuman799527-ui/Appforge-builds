package com.appforge.shell

import android.app.Application
import com.onesignal.OneSignal
import com.onesignal.notifications.INotificationClickEvent
import com.onesignal.notifications.INotificationClickListener
import org.json.JSONObject

// OneSignal provider (default; unchanged behaviour for existing projects).
object Push {
    private var active = false

    fun init(app: Application, cfg: JSONObject) {
        val push = cfg.optJSONObject("push") ?: return
        val id = push.optString("oneSignalAppId")
        if (!push.optBoolean("enabled") || push.optString("provider", "onesignal") == "beams" || id.isBlank()) return
        OneSignal.initWithContext(app, id)
        OneSignal.Notifications.addClickListener(object : INotificationClickListener {
            override fun onClick(event: INotificationClickEvent) {
                val url = event.notification.additionalData?.optString("target_url") ?: ""
                if (url.isNotBlank()) MainActivity.pendingUrl = url
            }
        })
        active = true
    }

    fun token(): String = try { if (active) OneSignal.User.pushSubscription.id else "" } catch (e: Exception) { "" }
    fun addInterest(name: String): Boolean = try { if (active) { OneSignal.User.addTag(name, "1"); true } else false } catch (e: Exception) { false }
    fun removeInterest(name: String): Boolean = try { if (active) { OneSignal.User.removeTag(name); true } else false } catch (e: Exception) { false }
    fun interests(): String = "[]"
    fun status(): String = JSONObject().put("provider", "onesignal").put("active", active).toString()
    fun setUserId(userId: String, done: (Boolean, String) -> Unit) {
        if (!active) return done(false, "Push is not enabled")
        try { OneSignal.login(userId); done(true, "") } catch (e: Exception) { done(false, e.message ?: "failed") }
    }
    fun clearUser() { try { if (active) OneSignal.logout() } catch (e: Exception) {} }
}
