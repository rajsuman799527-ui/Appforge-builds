package com.appforge.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    companion object { var pendingUrl: String? = null }

    private lateinit var cfg: JSONObject
    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private var startUrl = ""
    private var lastUrl = ""
    private var showingError = false
    private var lastBack = 0L
    private var injectStart = ""
    private var injectEnd = ""
    private var splash: View? = null
    private var splashMinUntil = 0L
    private var splashFade = true
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private var pendingPermission: PermissionRequest? = null
    private var customView: View? = null

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        var uris: Array<Uri>? = null
        if (res.resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            uris = if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cb = geoCallback
        if (cb != null) {
            cb.invoke(geoOrigin, result[Manifest.permission.ACCESS_FINE_LOCATION] == true || has(Manifest.permission.ACCESS_FINE_LOCATION), false)
            geoCallback = null
        }
        val req = pendingPermission
        if (req != null) {
            val granted = req.resources.filter { r ->
                (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && has(Manifest.permission.CAMERA)) ||
                    (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && has(Manifest.permission.RECORD_AUDIO))
            }
            if (granted.isEmpty()) req.deny() else req.grant(granted.toTypedArray())
            pendingPermission = null
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun asset(path: String): String = try { assets.open(path).bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
    private fun color(v: String?, fallback: Int): Int = try { if (v.isNullOrBlank()) fallback else Color.parseColor(v) } catch (e: Exception) { fallback }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun sec(name: String): JSONObject = cfg.optJSONObject(name) ?: JSONObject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = try { JSONObject(asset("appforge/config.json")) } catch (e: Exception) { JSONObject() }
        injectStart = asset("appforge/inject_start.js")
        injectEnd = asset("appforge/inject_end.js")
        startUrl = cfg.optString("url", "https://example.com")
        val branding = sec("branding")
        val nav = sec("navigation")
        val feat = sec("features")
        applyWindow(branding, feat)

        val root = FrameLayout(this)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        val toolbarCfg = nav.optJSONObject("toolbar") ?: JSONObject()
        if (toolbarCfg.optBoolean("show", false)) column.addView(buildToolbar(toolbarCfg, nav), LinearLayout.LayoutParams(-1, dp(56)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        progress.progressTintList = ColorStateList.valueOf(color(nav.optString("progressColor"), Color.BLUE))
        if (nav.optBoolean("progressBar", true)) column.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        swipe = SwipeRefreshLayout(this)
        web = WebView(this)
        swipe.addView(web, ViewGroup.LayoutParams(-1, -1))
        swipe.isEnabled = nav.optBoolean("pullToRefresh", true)
        swipe.setOnRefreshListener { web.reload() }
        column.addView(swipe, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabs = nav.optJSONArray("tabs") ?: JSONArray()
        if (tabs.length() > 0) column.addView(buildTabs(tabs, nav), LinearLayout.LayoutParams(-1, dp(58)))
        val fab = nav.optJSONObject("fab")
        if (fab != null && fab.optBoolean("show") && fab.optString("url").isNotBlank()) {
            val lp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
            lp.setMargins(0, 0, dp(20), dp(if (tabs.length() > 0) 80 else 24))
            root.addView(buildFab(fab, branding), lp)
        }
        setContentView(root)
        setupWeb(feat)
        showSplash(root, branding)
        setupBack(nav)
        watchNetwork()
        maybeRate(feat)
        if (Build.VERSION.SDK_INT >= 33 && sec("push").optBoolean("enabled")) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        }
        val deep = intent?.data?.toString()
        web.loadUrl(if (deep != null && deep.startsWith("http")) deep else startUrl, headers())
    }

    override fun onResume() {
        super.onResume()
        val p = pendingUrl
        if (p != null) { pendingUrl = null; web.loadUrl(p, headers()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = intent.data?.toString()
        if (u != null && u.startsWith("http")) web.loadUrl(u, headers())
    }

    private fun applyWindow(b: JSONObject, f: JSONObject) {
        window.statusBarColor = color(b.optString("statusBarColor"), Color.BLACK)
        window.navigationBarColor = color(b.optString("navBarColor"), Color.BLACK)
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        ctl.isAppearanceLightStatusBars = b.optString("statusBarStyle") == "dark"
        if (f.optBoolean("fullscreen")) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (f.optBoolean("keepAwake")) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (f.optBoolean("secure")) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        requestedOrientation = when (f.optString("orientation")) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun buildToolbar(t: JSONObject, nav: JSONObject): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), 0, dp(8), 0)
        bar.setBackgroundColor(color(t.optString("color"), Color.DKGRAY))
        val fg = color(t.optString("textColor"), Color.WHITE)
        val title = TextView(this)
        title.text = t.optString("title").ifBlank { getString(R.string.app_name) }
        title.setTextColor(fg)
        title.textSize = 18f
        title.maxLines = 1
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val items = nav.optJSONArray("drawer") ?: JSONArray()
        if (items.length() > 0) {
            val btn = TextView(this)
            btn.text = "\u2630"
            btn.textSize = 22f
            btn.setTextColor(fg)
            btn.setPadding(dp(12), dp(8), dp(12), dp(8))
            btn.setOnClickListener { v ->
                val menu = PopupMenu(this, v)
                for (i in 0 until items.length()) menu.menu.add(0, i, i, items.getJSONObject(i).optString("label"))
                menu.setOnMenuItemClickListener { mi -> handleMenu(items.getJSONObject(mi.itemId)); true }
                menu.show()
            }
            bar.addView(btn)
        }
        return bar
    }

    private fun handleMenu(item: JSONObject) {
        val url = item.optString("url")
        when (item.optString("type", "link")) {
            "external" -> openExternal(url)
            "share" -> share(sec("extras").optString("shareText"), if (url.isBlank()) startUrl else url)
            "rate" -> openExternal("market://details?id=" + packageName)
            else -> web.loadUrl(url, headers())
        }
    }

    private fun buildTabs(tabs: JSONArray, nav: JSONObject): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(color(nav.optString("tabBg"), Color.WHITE))
        val active = color(nav.optString("tabActive"), Color.BLUE)
        val inactive = color(nav.optString("tabInactive"), Color.GRAY)
        val views = mutableListOf<TextView>()
        for (i in 0 until minOf(tabs.length(), 5)) {
            val t = tabs.getJSONObject(i)
            val tv = TextView(this)
            tv.text = t.optString("label")
            tv.gravity = Gravity.CENTER
            tv.textSize = 13f
            tv.setTextColor(if (i == 0) active else inactive)
            tv.setOnClickListener {
                views.forEach { it.setTextColor(inactive) }
                tv.setTextColor(active)
                web.loadUrl(t.optString("url"), headers())
            }
            views.add(tv)
            bar.addView(tv, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return bar
    }

    private fun buildFab(f: JSONObject, b: JSONObject): View {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(color(f.optString("color"), color(b.optString("themeColor"), Color.BLUE)))
        val tv = TextView(this)
        tv.text = f.optString("label", "+").take(2)
        tv.gravity = Gravity.CENTER
        tv.textSize = 20f
        tv.setTextColor(Color.WHITE)
        tv.background = bg
        tv.elevation = dp(6).toFloat()
        tv.setOnClickListener {
            val u = f.optString("url")
            if (isInternal(Uri.parse(u))) web.loadUrl(u, headers()) else openExternal(u)
        }
        return tv
    }

    private fun headers(): Map<String, String> {
        val m = HashMap<String, String>()
        val arr = sec("code").optJSONArray("headers") ?: return m
        for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            val k = h.optString("key")
            if (k.isNotBlank()) m[k] = h.optString("value")
        }
        return m
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(f: JSONObject) {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(f.optBoolean("zoom", false))
        s.builtInZoomControls = f.optBoolean("zoom", false)
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = !f.optBoolean("autoplay", false)
        s.setGeolocationEnabled(f.optBoolean("location", false))
        s.javaScriptCanOpenWindowsAutomatically = true
        if (f.optBoolean("mixedContent", false)) s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = defaultCacheMode()
        val code = sec("code")
        val ua = code.optString("userAgent")
        val base = if (code.optString("userAgentMode") == "replace" && ua.isNotBlank()) ua else s.userAgentString + (if (ua.isNotBlank()) " " + ua else "")
        s.userAgentString = base + " AppForge/" + BuildConfig.VERSION_NAME
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, f.optBoolean("thirdPartyCookies", true))
        web.isVerticalScrollBarEnabled = f.optBoolean("scrollbars", true)
        web.overScrollMode = if (f.optBoolean("overscroll", true)) View.OVER_SCROLL_IF_CONTENT_SCROLLS else View.OVER_SCROLL_NEVER
        if (!f.optBoolean("textSelection", true)) {
            web.setOnLongClickListener { true }
            web.isLongClickable = false
            web.isHapticFeedbackEnabled = false
        }
        web.addJavascriptInterface(Bridge(), "AppForgeNative")
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart && injectStart.isNotBlank()) WebViewCompat.addDocumentStartJavaScript(web, injectStart, setOf("*"))

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = route(request.url)
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!docStart && injectStart.isNotBlank()) view.evaluateJavascript(injectStart, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                swipe.isRefreshing = false
                progress.visibility = View.GONE
                if (!url.startsWith("file:")) { lastUrl = url; showingError = false }
                if (!docStart && injectStart.isNotBlank()) view.evaluateJavascript(injectStart, null)
                if (injectEnd.isNotBlank() && !url.startsWith("file:")) view.evaluateJavascript(injectEnd, null)
                if (sec("branding").optJSONObject("splash")?.optBoolean("hideOnLoad", true) != false) hideSplash()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if (!online() && sec("offline").optString("mode") == "cache" && view.settings.cacheMode != WebSettings.LOAD_CACHE_ONLY) {
                    view.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
                    view.loadUrl(request.url.toString())
                    return
                }
                val type = if (!online()) "offline" else if (error.errorCode == ERROR_TIMEOUT) "timeout" else "offline"
                showError(type, request.url.toString())
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
                if (!request.isForMainFrame || !sec("offline").optBoolean("httpErrors", true)) return
                val c = resp.statusCode
                if (c == 404) showError("404", request.url.toString()) else if (c >= 500) showError("5xx", request.url.toString())
            }
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                if (sec("features").optBoolean("ignoreSsl", false)) handler.proceed() else { handler.cancel(); showError("ssl", error.url) }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.progress = p
                progress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (!sec("features").optBoolean("fileUpload", true)) return false
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val i = params.createIntent()
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try { fileLauncher.launch(i); true } catch (e: ActivityNotFoundException) { fileCallback = null; false }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) callback.invoke(origin, true, false)
                else {
                    geoOrigin = origin
                    geoCallback = callback
                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val need = mutableListOf<String>()
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
                    if (need.isEmpty()) request.grant(request.resources) else { pendingPermission = request; permLauncher.launch(need.toTypedArray()) }
                }
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(-1, -1))
            }
            override fun onHideCustomView() {
                customView?.let { (window.decorView as FrameLayout).removeView(it) }
                customView = null
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!sec("features").optBoolean("downloads", true)) openExternal(url)
            else download(url, URLUtil.guessFileName(url, contentDisposition, mimeType), mimeType, userAgent)
        }
    }

    private fun defaultCacheMode(): Int = if (sec("offline").optString("cacheMode") == "cache-first") WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT

    private fun download(url: String, name: String, mime: String?, ua: String?) {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
            if (mime != null) req.setMimeType(mime)
            req.addRequestHeader("User-Agent", ua ?: web.settings.userAgentString)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            openExternal(url)
        }
    }

    private fun showError(type: String, failed: String) {
        if (failed.isNotBlank() && !failed.startsWith("file:")) lastUrl = failed
        showingError = true
        swipe.isRefreshing = false
        hideSplash()
        web.loadUrl("file:///android_asset/appforge/offline.html?type=" + type)
    }

    private fun retry() {
        showingError = false
        web.settings.cacheMode = defaultCacheMode()
        web.loadUrl(if (lastUrl.isBlank()) startUrl else lastUrl, headers())
    }

    private fun glob(pattern: String, value: String): Boolean {
        if (pattern.isBlank()) return false
        val rx = Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)
        return rx.matches(value)
    }

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val startHost = (Uri.parse(startUrl).host ?: "").removePrefix("www.")
        if (host.equals(startHost, true) || host.endsWith("." + startHost, true)) return true
        val arr = sec("navigation").optJSONArray("internalDomains") ?: return false
        for (i in 0 until arr.length()) if (glob(arr.optString(i).trim(), host)) return true
        return false
    }

    private fun route(uri: Uri): Boolean {
        val url = uri.toString()
        val scheme = uri.scheme ?: ""
        if (scheme == "file") return false
        if (scheme == "intent") {
            try {
                val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                try { startActivity(i) } catch (e: ActivityNotFoundException) {
                    val fb = i.getStringExtra("browser_fallback_url")
                    if (fb != null) web.loadUrl(fb)
                }
            } catch (e: Exception) {
            }
            return true
        }
        if (scheme != "http" && scheme != "https") { openExternal(url); return true }
        val ext = sec("navigation").optJSONArray("externalPatterns")
        if (ext != null) for (i in 0 until ext.length()) if (glob(ext.optString(i).trim(), url)) { openExternal(url); return true }
        if (url.contains("play.google.com/store") || uri.host == "wa.me" || uri.host == "api.whatsapp.com") { openExternal(url); return true }
        if (isInternal(uri)) return false
        openExternal(url)
        return true
    }

    private fun openExternal(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(text: String, url: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, (text + " " + url).trim())
        startActivity(Intent.createChooser(i, "Share"))
    }

    private fun showSplash(root: FrameLayout, b: JSONObject) {
        val s = b.optJSONObject("splash") ?: return
        val dur = s.optDouble("duration", 1.5)
        val hideOnLoad = s.optBoolean("hideOnLoad", true)
        if (dur <= 0 && !hideOnLoad) return
        val box = FrameLayout(this)
        box.setBackgroundColor(color(s.optString("bg"), Color.WHITE))
        box.isClickable = true
        splashFade = s.optBoolean("fadeOut", true)
        val lottieSplash = s.optString("type") == "lottie" && asset("appforge/splash.html").isNotEmpty()
        if (lottieSplash) {
            val wv = WebView(this)
            wv.settings.javaScriptEnabled = true
            wv.setBackgroundColor(color(s.optString("bg"), Color.WHITE))
            wv.isVerticalScrollBarEnabled = false
            wv.loadUrl("file:///android_asset/appforge/splash.html")
            box.addView(wv, FrameLayout.LayoutParams(-1, -1))
        } else try {
            val bmp = assets.open("appforge/splash.png").use { BitmapFactory.decodeStream(it) }
            val img = ImageView(this)
            img.setImageBitmap(bmp)
            box.addView(img, FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER))
            when (s.optString("animation")) {
                "zoom" -> { img.scaleX = 0.6f; img.scaleY = 0.6f; img.animate().scaleX(1f).scaleY(1f).setDuration(600).start() }
                "fade" -> { img.alpha = 0f; img.animate().alpha(1f).setDuration(600).start() }
                else -> {}
            }
        } catch (e: Exception) {
        }
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
        splash = box
        val min = (dur * 1000).toLong()
        splashMinUntil = System.currentTimeMillis() + min
        Handler(Looper.getMainLooper()).postDelayed({ removeSplash() }, if (hideOnLoad) maxOf(min, 8000L) else min)
    }

    private fun hideSplash() {
        val wait = splashMinUntil - System.currentTimeMillis()
        if (wait > 0) Handler(Looper.getMainLooper()).postDelayed({ removeSplash() }, wait) else removeSplash()
    }

    private fun removeSplash() {
        val v = splash ?: return
        splash = null
        if (!splashFade) { (v.parent as? ViewGroup)?.removeView(v); return }
        v.animate().alpha(0f).setDuration(350).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
    }

    private fun setupBack(nav: JSONObject) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val cv = customView
                if (cv != null) { (window.decorView as FrameLayout).removeView(cv); customView = null; return }
                if (web.canGoBack() && !showingError) { web.goBack(); return }
                when (nav.optString("backBehavior", "history")) {
                    "doubleExit" -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBack < 2000) finish() else {
                            lastBack = now
                            Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "confirm" -> {
                        AlertDialog.Builder(this@MainActivity).setMessage("Exit the app?")
                            .setPositiveButton("Exit") { _, _ -> finish() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    else -> finish()
                }
            }
        })
    }

    private fun online(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (showingError && sec("offline").optBoolean("autoReload", true)) retry() }
            }
        })
    }

    private fun maybeRate(f: JSONObject) {
        val n = f.optInt("rateAfter", 0)
        if (n <= 0) return
        val p = getSharedPreferences("appforge", MODE_PRIVATE)
        val c = p.getInt("launches", 0) + 1
        p.edit().putInt("launches", c).apply()
        if (c == n) {
            AlertDialog.Builder(this).setTitle("Enjoying the app?").setMessage("Please take a moment to rate us.")
                .setPositiveButton("Rate") { _, _ -> openExternal("market://details?id=" + packageName) }
                .setNegativeButton("Later", null).show()
        }
    }

    inner class Bridge {
        @JavascriptInterface fun share(text: String?, url: String?) { runOnUiThread { this@MainActivity.share(text ?: "", url ?: "") } }
        @JavascriptInterface fun toast(msg: String?) { runOnUiThread { Toast.makeText(this@MainActivity, msg ?: "", Toast.LENGTH_SHORT).show() } }
        @JavascriptInterface fun vibrate(ms: Int) {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(maxOf(1, ms).toLong(), VibrationEffect.DEFAULT_AMPLITUDE)) else v.vibrate(ms.toLong())
        }
        @JavascriptInterface fun openExternal(url: String?) { runOnUiThread { this@MainActivity.openExternal(url ?: "") } }
        @JavascriptInterface fun getDeviceInfo(): String = JSONObject()
            .put("model", Build.MANUFACTURER + " " + Build.MODEL)
            .put("osVersion", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("packageName", packageName)
            .put("online", online()).toString()
        @JavascriptInterface fun setStatusBarColor(c: String?) { runOnUiThread { window.statusBarColor = color(c, window.statusBarColor) } }
        @JavascriptInterface fun requestPermission(name: String?) {
            val p = when (name) {
                "camera" -> Manifest.permission.CAMERA
                "microphone" -> Manifest.permission.RECORD_AUDIO
                "location" -> Manifest.permission.ACCESS_FINE_LOCATION
                "notifications" -> "android.permission.POST_NOTIFICATIONS"
                else -> null
            }
            if (p != null) runOnUiThread { ActivityCompat.requestPermissions(this@MainActivity, arrayOf(p), 9) }
        }
        @JavascriptInterface fun hasPermission(name: String?): Boolean = when (name) {
            "camera" -> has(Manifest.permission.CAMERA)
            "microphone" -> has(Manifest.permission.RECORD_AUDIO)
            "location" -> has(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> false
        }
        @JavascriptInterface fun saveFile(url: String?, name: String?) {
            if (url.isNullOrBlank()) return
            runOnUiThread { download(url, if (name.isNullOrBlank()) URLUtil.guessFileName(url, null, null) else name, null, null) }
        }
        @JavascriptInterface fun getPushToken(): String = try { com.onesignal.OneSignal.User.pushSubscription.id } catch (e: Exception) { "" }
        @JavascriptInterface fun retry() { runOnUiThread { this@MainActivity.retry() } }
        @JavascriptInterface fun openSaved() { runOnUiThread { showingError = false; web.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY; web.loadUrl(if (lastUrl.isBlank()) startUrl else lastUrl) } }
    }
}
