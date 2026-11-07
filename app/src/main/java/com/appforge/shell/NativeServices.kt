package com.appforge.shell

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.TimeUnit

// Local key-value storage (SharedPreferences) behind AppBridge.saveData / getData.
object NativeStore {
    private const val MAX_VALUE = 500000
    private fun prefs(c: Context) = c.getSharedPreferences("appbridge_store", Context.MODE_PRIVATE)

    fun save(c: Context, key: String, value: String): Boolean {
        if (key.isBlank() || key.length > 200 || value.length > MAX_VALUE) return false
        return prefs(c).edit().putString(key, value).commit()
    }
    fun get(c: Context, key: String): String? = prefs(c).getString(key, null)
    fun remove(c: Context, key: String) { prefs(c).edit().remove(key).commit() }
    fun clear(c: Context) { prefs(c).edit().clear().commit() }
    fun keys(c: Context): JSONArray {
        val a = JSONArray()
        prefs(c).all.keys.sorted().forEach { a.put(it) }
        return a
    }
}

// Local notifications (no push server needed).
object NativeNotify {
    private const val CHANNEL = "appbridge_local"

    fun allowed(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(c).areNotificationsEnabled()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun show(c: Context, title: String, message: String): Boolean {
        if (!allowed(c)) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "App alerts", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = if (c.applicationInfo.icon != 0) c.applicationInfo.icon else android.R.drawable.ic_dialog_info
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify((System.currentTimeMillis() % 1000000000L).toInt(), n)
        return true
    }
}

// Usage access (UsageStatsManager). The user grants it in Android Settings, never in code.
object NativeUsage {
    fun hasAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // One row per app that was in the foreground in the range, most used first.
    @Suppress("DEPRECATION")
    fun query(c: Context, startMs: Long, endMs: Long): JSONArray {
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val map = usm.queryAndAggregateUsageStats(startMs, endMs) ?: return JSONArray()
        val pm = c.packageManager
        val rows = ArrayList<JSONObject>()
        for (entry in map.entries) {
            val u = entry.value
            if (u.totalTimeInForeground <= 0L) continue
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(entry.key, 0)).toString() } catch (e: Exception) { entry.key }
            rows.add(
                JSONObject()
                    .put("packageName", entry.key)
                    .put("appName", label)
                    .put("totalTimeForeground", u.totalTimeInForeground)
                    .put("lastTimeUsed", u.lastTimeUsed)
            )
        }
        rows.sortByDescending { it.optLong("totalTimeForeground") }
        val out = JSONArray()
        rows.forEach { out.put(it) }
        return out
    }
}

// Android system screens the website may open. Only Settings screens and a few safe VIEW schemes are allowed.
object NativeIntents {
    fun screenIntent(c: Context, screen: String): Intent? = when (screen) {
        "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "app_settings" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
        "notification_settings" ->
            if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
        "battery_optimization" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
        "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
        "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        "settings" -> Intent(Settings.ACTION_SETTINGS)
        else -> null
    }

    fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    // spec: { action, data }. Allowed: android.settings.* actions, and VIEW / DIAL / SENDTO with http, https, tel, mailto, sms, smsto or geo.
    fun custom(spec: JSONObject): Intent? {
        val action = spec.optString("action")
        val data = spec.optString("data")
        val uri = if (data.isBlank()) null else Uri.parse(data)
        val scheme = (uri?.scheme ?: "").lowercase()
        if (action.startsWith("android.settings.")) {
            val i = Intent(action)
            if (uri != null && scheme == "package") i.data = uri
            return i
        }
        val safe = listOf("https", "http", "tel", "mailto", "sms", "smsto", "geo")
        if ((action == Intent.ACTION_VIEW || action == Intent.ACTION_DIAL || action == Intent.ACTION_SENDTO) && uri != null && safe.contains(scheme)) {
            return Intent(action, uri)
        }
        return null
    }
}

// Background task: saves a "today so far" usage snapshot and optionally uploads it to the https URL set in the builder.
class UsageCollectWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val c = applicationContext
        if (!NativeUsage.hasAccess(c)) return Result.success()
        return try {
            val cal = Calendar.getInstance()
            val end = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val snap = JSONObject()
                .put("collectedAt", end)
                .put("startMs", start)
                .put("endMs", end)
                .put("stats", NativeUsage.query(c, start, end))
            NativeStore.save(c, "usage_stats_latest", snap.toString())
            val url = inputData.getString("uploadUrl") ?: ""
            if (url.startsWith("https://") && !upload(url, c, snap)) {
                return if (runAttemptCount < 3) Result.retry() else Result.success()
            }
            Result.success()
        } catch (e: Throwable) {
            Result.failure()
        }
    }

    private fun upload(url: String, c: Context, snap: JSONObject): Boolean {
        val body = JSONObject()
            .put("packageName", c.packageName)
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("snapshot", snap)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}

object NativeScheduler {
    private const val WORK = "appbridge_usage_collect"

    // WorkManager never runs periodic work more often than every 15 minutes.
    fun start(c: Context, minutes: Long, uploadUrl: String) {
        val url = if (uploadUrl.startsWith("https://")) uploadUrl else ""
        val constraints = Constraints.Builder()
        if (url.isNotEmpty()) constraints.setRequiredNetworkType(NetworkType.CONNECTED)
        val req = PeriodicWorkRequestBuilder<UsageCollectWorker>(maxOf(15L, minutes), TimeUnit.MINUTES)
            .setConstraints(constraints.build())
            .setInputData(workDataOf("uploadUrl" to url))
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun stop(c: Context) { WorkManager.getInstance(c).cancelUniqueWork(WORK) }

    fun isActive(c: Context): Boolean = try {
        WorkManager.getInstance(c).getWorkInfosForUniqueWork(WORK).get().any { !it.state.isFinished }
    } catch (e: Exception) {
        false
    }

    // Called on every launch: starts the schedule chosen in the builder.
    fun applyConfig(c: Context, cfg: JSONObject) {
        val n = cfg.optJSONObject("native") ?: return
        if (!n.optBoolean("bridge") || !n.optBoolean("usageStats")) return
        val bg = n.optJSONObject("background") ?: return
        if (!bg.optBoolean("enabled")) return
        start(c, bg.optLong("intervalMinutes", 15L), bg.optString("uploadUrl"))
    }
}
