package com.appforge.shell

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

// Exposed to the page as AppBridgeNative. The injected appbridge.js wraps it as window.AppBridge.
// Every method returns JSON: { ok: true, data } or { ok: false, error }. Calls only work on pages of your own site.
class AppBridgeNative(private val activity: Activity, private val cfg: JSONObject, private val trusted: () -> Boolean) {
    private val ctx: Context = activity.applicationContext

    private fun native(): JSONObject = cfg.optJSONObject("native") ?: JSONObject()
    private fun on(flag: String): Boolean = native().optBoolean(flag, false)
    private fun ok(data: Any?): String = JSONObject().put("ok", true).put("data", data ?: JSONObject.NULL).toString()
    private fun fail(code: String): String = JSONObject().put("ok", false).put("error", code).toString()

    private fun guarded(feature: String?, block: () -> String): String {
        if (!trusted()) return fail("blocked_origin")
        if (feature != null && !on(feature)) return fail("feature_disabled:" + feature)
        return try {
            block()
        } catch (e: SecurityException) {
            fail("permission_denied")
        } catch (e: Throwable) {
            fail(e.message ?: "error")
        }
    }

    private fun <T> onUi(block: () -> T): T? {
        val task = FutureTask<T> { block() }
        activity.runOnUiThread(task)
        return try { task.get(3, TimeUnit.SECONDS) } catch (e: Exception) { null }
    }

    private fun pluginNames(): JSONArray {
        val a = JSONArray()
        CustomPlugins.all.forEach { a.put(it.name) }
        return a
    }

    @JavascriptInterface fun getCapabilities(): String = guarded(null) {
        val n = native()
        ok(
            JSONObject()
                .put("version", 1)
                .put("usageStats", n.optBoolean("usageStats"))
                .put("storage", n.optBoolean("storage"))
                .put("notifications", n.optBoolean("notifications"))
                .put("intents", n.optBoolean("intents"))
                .put("background", n.optJSONObject("background")?.optBoolean("enabled") == true)
                .put("plugins", pluginNames())
        )
    }

    // ---- Usage stats ----
    @JavascriptInterface fun hasUsageAccess(): String = guarded("usageStats") { ok(NativeUsage.hasAccess(ctx)) }

    @JavascriptInterface fun openUsageSettings(): String = guarded("usageStats") {
        val i = NativeIntents.screenIntent(ctx, "usage_access") ?: return@guarded fail("not_supported")
        ok(onUi { NativeIntents.start(activity, i) } ?: false)
    }

    // startMs / endMs are epoch milliseconds. 0 means: end = now, start = 24 hours before end.
    @JavascriptInterface fun getUsageStats(startMs: Long, endMs: Long): String = guarded("usageStats") {
        val end = if (endMs > 0L) endMs else System.currentTimeMillis()
        val start = if (startMs > 0L) startMs else end - 24L * 3600L * 1000L
        if (start >= end) return@guarded fail("invalid_range")
        if (!NativeUsage.hasAccess(ctx)) return@guarded fail("usage_access_not_granted")
        ok(NativeUsage.query(ctx, start, end))
    }

    // ---- Intents / system screens ----
    @JavascriptInterface fun openSettings(screen: String?): String = guarded("intents") {
        val i = NativeIntents.screenIntent(ctx, screen ?: "") ?: return@guarded fail("unknown_screen")
        ok(onUi { NativeIntents.start(activity, i) } ?: false)
    }

    @JavascriptInterface fun openIntent(specJson: String?): String = guarded("intents") {
        val spec = try { JSONObject(specJson ?: "{}") } catch (e: Exception) { return@guarded fail("invalid_json") }
        val i = NativeIntents.custom(spec) ?: return@guarded fail("intent_not_allowed")
        ok(onUi { NativeIntents.start(activity, i) } ?: false)
    }

    // ---- Local notifications ----
    // Returns true when shown. false means the user has not allowed notifications yet (the permission prompt is shown).
    @JavascriptInterface fun showNotification(title: String?, message: String?): String = guarded("notifications") {
        val t = (title ?: "").take(200)
        val m = (message ?: "").take(2000)
        if (t.isBlank() && m.isBlank()) return@guarded fail("empty_notification")
        if (NativeNotify.show(ctx, t, m)) {
            ok(true)
        } else {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.runOnUiThread { ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9) }
            }
            ok(false)
        }
    }

    // ---- Native storage ----
    @JavascriptInterface fun saveData(key: String?, value: String?): String = guarded("storage") { ok(NativeStore.save(ctx, key ?: "", value ?: "")) }
    @JavascriptInterface fun getData(key: String?): String = guarded("storage") { ok(NativeStore.get(ctx, key ?: "")) }
    @JavascriptInterface fun removeData(key: String?): String = guarded("storage") { NativeStore.remove(ctx, key ?: ""); ok(true) }
    @JavascriptInterface fun listDataKeys(): String = guarded("storage") { ok(NativeStore.keys(ctx)) }
    @JavascriptInterface fun clearData(): String = guarded("storage") { NativeStore.clear(ctx); ok(true) }

    // ---- Background usage collection (WorkManager) ----
    @JavascriptInterface fun startBackgroundCollection(minutes: Long): String = guarded("usageStats") {
        val bg = native().optJSONObject("background")
        val every = if (minutes > 0L) minutes else (bg?.optLong("intervalMinutes", 15L) ?: 15L)
        NativeScheduler.start(ctx, every, bg?.optString("uploadUrl") ?: "")
        ok(true)
    }
    @JavascriptInterface fun stopBackgroundCollection(): String = guarded("usageStats") { NativeScheduler.stop(ctx); ok(true) }
    @JavascriptInterface fun getBackgroundStatus(): String = guarded("usageStats") { ok(JSONObject().put("active", NativeScheduler.isActive(ctx))) }
    @JavascriptInterface fun getLatestUsageSnapshot(): String = guarded("usageStats") {
        val raw = NativeStore.get(ctx, "usage_stats_latest")
        ok(if (raw == null) null else JSONObject(raw))
    }

    // ---- Custom native code (plugins you uploaded in the builder) ----
    @JavascriptInterface fun callPlugin(name: String?, method: String?, argsJson: String?): String = guarded(null) {
        val p = CustomPlugins.all.firstOrNull { it.name == name } ?: return@guarded fail("plugin_not_found")
        val raw = p.call(activity, method ?: "", argsJson ?: "{}")
        val parsed: Any? = try { JSONTokener(raw).nextValue() } catch (e: Exception) { raw }
        ok(parsed)
    }
}
