package com.appforge.shell

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class OfflineCache(
    ctx: Context,
    private val maxBytes: Long,
    private val cacheFirst: Boolean,
    private val saveNoStore: Boolean,
    skip: List<String>,
    private val ua: String,
    private val online: () -> Boolean
) {
    @Volatile var forceOffline = false
    @Volatile var onStale: (() -> Unit)? = null
    @Volatile var onFresh: (() -> Unit)? = null

    private val dir = File(ctx.filesDir, "appforge_offline")
    private val total = AtomicLong(0L)
    private val lock = Any()
    private val pool = Executors.newFixedThreadPool(3)
    private var pages: JSONObject? = null
    private val skipRx = skip.filter { it.isNotBlank() }.map { p ->
        Regex("^" + p.split("*").joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)
    }
    // Sign-in, payment and similar URLs are never touched: they are one-time or private.
    private val neverRx = Regex("(^|[/?&=_.-])(login|logout|signin|signout|sign-in|sign-out|oauth2?|callback|authorize|auth|sso|saml|unsubscribe|checkout|payment|add-to-cart|add_to_cart)([/?&=_.-]|$)|[?&](code|token|state)=", RegexOption.IGNORE_CASE)

    init {
        dir.mkdirs()
        Thread { total.set(scan()) }.start()
    }

    private fun scan(): Long {
        var n = 0L
        dir.listFiles()?.forEach { n += it.length() }
        return n
    }

    fun size(): Long = total.get()

    private fun sha(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    private fun clean(u: String): String {
        val i = u.indexOf('#')
        return if (i >= 0) u.substring(0, i) else u
    }

    private fun docKey(url: String): String = sha("DOC|" + clean(url))

    private fun keyOf(r: WebResourceRequest, url: String): String {
        if (r.isForMainFrame) return docKey(url)
        val h = r.requestHeaders ?: emptyMap<String, String>()
        return sha(url + "|" + (h["Accept"] ?: "") + "|" + (h["RSC"] ?: "") + "|" + (h["X-Requested-With"] ?: ""))
    }

    fun has(url: String): Boolean = File(dir, docKey(url) + ".m").exists()

    fun intercept(r: WebResourceRequest, quiet: Boolean): WebResourceResponse? {
        return try { handle(r, quiet) } catch (e: Exception) { null }
    }

    private fun handle(r: WebResourceRequest, quiet: Boolean): WebResourceResponse? {
        if (!r.method.equals("GET", true)) return null
        val scheme = r.url.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val h = r.requestHeaders ?: emptyMap<String, String>()
        if (h.containsKey("Range") || h.containsKey("range")) return null
        val url = clean(r.url.toString())
        val pq = (r.url.encodedPath ?: "") + "?" + (r.url.encodedQuery ?: "")
        if (neverRx.containsMatchIn(pq)) return null
        if (skipRx.any { it.matches(url) }) return null
        val key = keyOf(r, url)
        val main = r.isForMainFrame
        if (forceOffline || !online()) {
            val res = read(key)
            if (res != null && main && !quiet) onStale?.invoke()
            return res
        }
        if (cacheFirst && File(dir, key + ".m").exists()) {
            val res = read(key)
            if (res != null) {
                pool.execute { try { fetch(url, key, h, main, true) } catch (e: Exception) { } }
                return res
            }
        }
        return try {
            val net = fetch(url, key, h, main, false)
            if (net != null && main && !quiet) onFresh?.invoke()
            net
        } catch (e: IOException) {
            val res = read(key)
            if (res != null && main && !quiet) onStale?.invoke()
            res
        }
    }

    // Returns null when the WebView should load the URL itself (redirects, errors, media). Throws IOException when the network is unreachable.
    private fun fetch(url: String, key: String, h: Map<String, String>, main: Boolean, bg: Boolean): WebResourceResponse? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 10000
        c.readTimeout = 25000
        c.requestMethod = "GET"
        var hasUa = false
        for ((k, v) in h) {
            if (k.equals("Accept-Encoding", true) || k.equals("Cookie", true) || k.equals("Host", true) || k.equals("Connection", true)) continue
            if (k.equals("User-Agent", true)) hasUa = true
            c.setRequestProperty(k, v)
        }
        if (!hasUa) c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        val cookie = CookieManager.getInstance().getCookie(url)
        if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
        val code = c.responseCode
        if (code >= 500) { c.disconnect(); throw IOException("http " + code) }
        if (code != 200) { c.disconnect(); return null }
        val ct = c.contentType ?: "application/octet-stream"
        val mime = ct.substringBefore(';').trim().lowercase()
        if (mime.startsWith("video/") || mime.startsWith("audio/") || mime == "text/event-stream") { c.disconnect(); return null }
        if (c.contentLengthLong > 25L * 1024L * 1024L) { c.disconnect(); return null }
        var charset: String? = null
        val ci = ct.lowercase().indexOf("charset=")
        if (ci >= 0) charset = ct.substring(ci + 8).substringBefore(';').trim().trim('"')
        val hdr = JSONObject()
        for ((k, vals) in c.headerFields) {
            if (k == null) continue
            if (k.equals("Set-Cookie", true)) { for (v in vals) CookieManager.getInstance().setCookie(url, v); continue }
            val kl = k.lowercase()
            if (kl == "content-encoding" || kl == "content-length" || kl == "transfer-encoding" || kl == "connection" || kl == "keep-alive" || kl == "content-type") continue
            hdr.put(k, vals.joinToString(", "))
        }
        val cc = (c.getHeaderField("Cache-Control") ?: "").lowercase()
        val cacheable = saveNoStore || !cc.contains("no-store")
        val reason = (c.responseMessage ?: "").ifBlank { "OK" }
        val input = c.inputStream
        val limit = 12 * 1024 * 1024
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(16384)
        var complete = true
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > limit) { complete = false; break }
        }
        if (!complete) {
            // Too big to keep: hand the rest of the stream straight to the WebView.
            if (bg) { c.disconnect(); return null }
            val rest = object : FilterInputStream(SequenceInputStream(ByteArrayInputStream(buf.toByteArray()), input)) {
                override fun close() {
                    try { super.close() } catch (e: Exception) { }
                    c.disconnect()
                }
            }
            return WebResourceResponse(mime, charset, code, reason, toMap(hdr), rest)
        }
        input.close()
        c.disconnect()
        val bytes = buf.toByteArray()
        if (cacheable) store(key, url, mime, charset, code, reason, hdr, bytes, main)
        return WebResourceResponse(mime, charset, code, reason, toMap(hdr), ByteArrayInputStream(bytes))
    }

    private fun toMap(j: JSONObject): MutableMap<String, String> {
        val m = HashMap<String, String>()
        val ks = j.keys()
        while (ks.hasNext()) { val k = ks.next(); m[k] = j.optString(k) }
        return m
    }

    private fun store(key: String, url: String, mime: String, charset: String?, code: Int, reason: String, hdr: JSONObject, bytes: ByteArray, main: Boolean) {
        try {
            val meta = JSONObject().put("u", url).put("m", mime).put("s", code).put("r", reason).put("h", hdr).put("t", System.currentTimeMillis())
            if (charset != null) meta.put("c", charset)
            val b = File(dir, key + ".b")
            val m = File(dir, key + ".m")
            val before = b.length() + m.length()
            val tmp = File(dir, key + "." + Thread.currentThread().id + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(b)
            m.writeText(meta.toString())
            total.addAndGet(b.length() + m.length() - before)
            if (main) notePage(url)
            if (total.get() > maxBytes) trim()
        } catch (e: Exception) { }
    }

    private fun read(key: String): WebResourceResponse? {
        val m = File(dir, key + ".m")
        val b = File(dir, key + ".b")
        if (!m.exists() || !b.exists()) return null
        return try {
            val j = JSONObject(m.readText())
            m.setLastModified(System.currentTimeMillis())
            val enc = j.optString("c")
            WebResourceResponse(
                j.optString("m", "application/octet-stream"),
                if (enc.isBlank()) null else enc,
                j.optInt("s", 200),
                j.optString("r", "OK").ifBlank { "OK" },
                toMap(j.optJSONObject("h") ?: JSONObject()),
                FileInputStream(b)
            )
        } catch (e: Exception) { null }
    }

    private fun trim() {
        synchronized(lock) {
            val files = dir.listFiles() ?: return
            val metas = files.filter { it.name.endsWith(".m") }.sortedBy { it.lastModified() }
            var t = 0L
            for (f in files) t += f.length()
            val target = maxBytes / 10L * 9L
            for (m in metas) {
                if (t <= target) break
                val b = File(dir, m.name.removeSuffix(".m") + ".b")
                t -= m.length() + b.length()
                m.delete()
                b.delete()
            }
            total.set(t)
        }
    }

    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            pages = JSONObject()
            total.set(0L)
        }
    }

    // ---- Index of saved pages (for the "Saved on this phone" list) ----
    private fun idx(): JSONObject = synchronized(lock) {
        val cur = pages
        if (cur != null) cur else {
            val loaded = try { JSONObject(File(dir, "pages.json").readText()) } catch (e: Exception) { JSONObject() }
            pages = loaded
            loaded
        }
    }

    private fun savePages() {
        try { File(dir, "pages.json").writeText(idx().toString()) } catch (e: Exception) { }
    }

    private fun notePage(url: String) {
        synchronized(lock) {
            val p = idx()
            val old = p.optJSONObject(url)
            p.put(url, JSONObject().put("t", old?.optString("t") ?: "").put("time", System.currentTimeMillis()))
            if (p.length() > 40) {
                val names = ArrayList<String>()
                val ks = p.keys()
                while (ks.hasNext()) names.add(ks.next())
                names.sortBy { p.optJSONObject(it)?.optLong("time") ?: 0L }
                for (i in 0 until names.size - 40) p.remove(names[i])
            }
            savePages()
        }
    }

    fun setTitle(url: String, title: String) {
        val u = clean(url)
        synchronized(lock) {
            val e = idx().optJSONObject(u) ?: return
            if (title.isNotBlank() && e.optString("t") != title) {
                e.put("t", title)
                savePages()
            }
        }
    }

    fun savedPages(): JSONArray {
        val out = JSONArray()
        synchronized(lock) {
            val p = idx()
            val list = ArrayList<JSONObject>()
            val ks = p.keys()
            while (ks.hasNext()) {
                val u = ks.next()
                val e = p.optJSONObject(u) ?: continue
                if (!has(u)) continue
                list.add(JSONObject().put("url", u).put("title", e.optString("t")).put("time", e.optLong("time")))
            }
            list.sortByDescending { it.optLong("time") }
            for (i in 0 until minOf(10, list.size)) out.put(list[i])
        }
        return out
    }
}
