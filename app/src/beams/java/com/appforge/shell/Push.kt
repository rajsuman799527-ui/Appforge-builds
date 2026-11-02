package com.appforge.shell

import android.app.Application
import android.util.Log
import android.webkit.CookieManager
import com.pusher.pushnotifications.BeamsCallback
import com.pusher.pushnotifications.PushNotifications
import com.pusher.pushnotifications.PusherCallbackError
import com.pusher.pushnotifications.auth.AuthData
import com.pusher.pushnotifications.auth.AuthDataGetter
import com.pusher.pushnotifications.auth.BeamsTokenProvider
import org.json.JSONArray
import org.json.JSONObject

// Pusher Beams provider (official SDK). The Beams secret key never ships in the APK.
object Push {
    private var active = false
    private var authUrl = ""

    fun init(app: Application, cfg: JSONObject) {
        val push = cfg.optJSONObject("push") ?: return
        if (!push.optBoolean("enabled") || push.optString("provider") != "beams") return
        val b = push.optJSONObject("beams") ?: return
        val id = b.optString("instanceId")
        if (id.isBlank()) return
        PushNotifications.start(app, id)
        active = true
        authUrl = b.optString("authUrl")
        val list = b.optJSONArray("defaultInterests") ?: JSONArray()
        for (i in 0 until list.length()) {
            val n = list.optString(i)
            if (n.isNotBlank()) try { PushNotifications.addDeviceInterest(n) } catch (e: Exception) { Log.w("AppForge", "Beams interest " + n + ": " + e.message) }
        }
    }

    fun token(): String = ""
    fun addInterest(name: String): Boolean = try { if (active) { PushNotifications.addDeviceInterest(name); true } else false } catch (e: Exception) { false }
    fun removeInterest(name: String): Boolean = try { if (active) { PushNotifications.removeDeviceInterest(name); true } else false } catch (e: Exception) { false }
    fun interests(): String = try { if (active) JSONArray(PushNotifications.getDeviceInterests().toList()).toString() else "[]" } catch (e: Exception) { "[]" }

    // Authenticated users: Beams calls authUrl?user_id=<id> with the WebView's cookies for the site.
    fun setUserId(userId: String, done: (Boolean, String) -> Unit) {
        if (!active) return done(false, "Pusher Beams is not enabled")
        if (authUrl.isBlank()) return done(false, "No Beams auth URL is configured for this app")
        try {
            val provider = BeamsTokenProvider(authUrl, object : AuthDataGetter {
                override fun getAuthData(): AuthData {
                    val cookie = CookieManager.getInstance().getCookie(authUrl) ?: ""
                    return AuthData(headers = if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie), queryParams = emptyMap())
                }
            })
            PushNotifications.setUserId(userId, provider, object : BeamsCallback<Void, PusherCallbackError> {
                override fun onSuccess(vararg values: Void) { done(true, "") }
                override fun onFailure(error: PusherCallbackError) { done(false, error.message) }
            })
        } catch (e: Exception) { done(false, e.message ?: "failed") }
    }

    fun clearUser() { try { if (active) PushNotifications.clearAllState() } catch (e: Exception) {} }
}
