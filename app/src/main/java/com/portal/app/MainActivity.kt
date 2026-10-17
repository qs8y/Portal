package com.portal.app

import android.Manifest
import android.view.MotionEvent
import androidx.core.widget.doAfterTextChanged
import android.webkit.WebResourceError
import android.graphics.Canvas
import android.graphics.Rect
import android.view.PixelCopy
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.ByteArrayInputStream
import android.animation.ValueAnimator
import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import android.widget.ImageView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity() {
    private val home: String? get() = prefs.getString("site_url", null) ?: loadSites().firstOrNull()?.second
    private lateinit var web: WebView
    private lateinit var swipe: FrameLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var topPill: View
    private lateinit var bottomBar: View
    private lateinit var topWrap: View
    private lateinit var welcome: View
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var bottomWrap: View
    private var barF = 0f
    private var barAnim: ValueAnimator? = null
    private var scrollAcc = 0
    private var pendingSeek = -1.0
    private var pendingSeekUntil = 0L
    private val FRAME_JS = Scripts.FRAME_JS
    private var ignoreScrollUntil = 0L
    private lateinit var titleText: TextView
    private lateinit var fullscreen: ViewGroup
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private val prefs by lazy { getSharedPreferences("s", MODE_PRIVATE) }
    private var client: OkHttpClient = OkHttpClient()
    private val tabs = CopyOnWriteArrayList<Tab>()
    private var cur: Tab? = null
    private val DARK_ON = Scripts.DARK_ON
    private val DARK_OFF = Scripts.DARK_OFF
    private var ua = ""
    private var lastBack = 0L
    private lateinit var pipBtn: View
    private val ui = Handler(Looper.getMainLooper())
    private var pip = false
    private var pendingRestore: Pair<Int, Double>? = null
    private val hidePipRunnable = Runnable {
        pipBtn.animate().alpha(0f).setDuration(250).withEndAction { pipBtn.visibility = View.GONE }.start()
    }

    private lateinit var hint: TextView
    private lateinit var lockCover: View
    private var rotLocked = false
    private var unlocked = false
    private var authInProgress = false
    private val REPO_URL = "https://github.com/qs8y/Portal"
    private val DEFAULT_UPDATE = "https://api.github.com/repos/qs8y/Portal/releases/latest"
    private val clients = ConcurrentHashMap<Int, OkHttpClient>()
    private fun clientFor(m: Int): OkHttpClient = clients.getOrPut(m) { makeClient(m) }
    private fun hostOf(u: String?): String = Uri.parse(u ?: "").host?.removePrefix("www.") ?: ""
    private fun siteBool(key: String, def: Boolean, host: String) = SiteCfg.bool(prefs, host, key, pref(key, def))
    private var lastLeave = 0L
    private var volAcc = -1f
    private var lastFind = ""
    private val hideHint = Runnable { hint.visibility = View.GONE }
    private val STATE_JS = Scripts.STATE_JS

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        authInProgress = false
        if (r.resultCode == RESULT_OK) { unlocked = true; lockCover.visibility = View.GONE }
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri -> if (uri != null) writeBackup(uri) }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readBackup(uri) }

    private fun pref(k: String, d: Boolean) = prefs.getBoolean(k, d)

    private val dnsNames = arrayOf("System (default)", "Cloudflare", "Google", "AdGuard", "Quad9")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(prefs.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        Style.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkCrash()
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress); fullscreen = findViewById(R.id.fullscreen)
        pipBtn = findViewById(R.id.tools)
        findViewById<View>(R.id.toolPip).setOnClickListener { enterPip(false) }
        findViewById<View>(R.id.toolFloat).setOnClickListener { toolFloat() }
        findViewById<View>(R.id.toolPlayer).setOnClickListener { toolPlayer() }
        findViewById<View>(R.id.toolSave).setOnClickListener { saveSnapshot() }
        hint = findViewById(R.id.gestureHint)
        lockCover = findViewById(R.id.lockCover)
        findViewById<View>(R.id.btnUnlock).setOnClickListener { promptAuth() }
        findViewById<View>(R.id.toolRot).setOnClickListener {
            rotLocked = !rotLocked
            requestedOrientation = if (rotLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            toast(if (rotLocked) "Rotation locked" else "Rotation unlocked")
            showPip()
        }
        (fullscreen as TouchFrame).onTouch = { volAcc = -1f; if (customView != null) showPip() }
        (fullscreen as TouchFrame).onVSwipe = { left, dy -> if (pref("gestures", true)) gesture(left, dy) }
        Downloads.init(this)
        Library.init(this)
        Snaps.init(this)
        Recents.init(this)
        AdBlock.load(this)
        client = makeClient(prefs.getInt("dns", 0))

        topPill = findViewById(R.id.topPill)
        topWrap = findViewById(R.id.topWrap)
        welcome = findViewById(R.id.welcome)
        errorPanel = findViewById(R.id.errorPanel)
        errorText = findViewById(R.id.errorText)
        findViewById<View>(R.id.errRetry).setOnClickListener { hideError(); web.reload() }
        findViewById<View>(R.id.errOpen).setOnClickListener { openExternal() }
        findViewById<View>(R.id.wAdd).setOnClickListener { addFirstSite() }
        bottomWrap = findViewById(R.id.bottomWrap)
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyBars(barF) }
        topWrap.addOnLayoutChangeListener(relayout)
        bottomWrap.addOnLayoutChangeListener(relayout)
        bottomBar = findViewById(R.id.bottomBar)
        titleText = findViewById(R.id.pageTitle)
        findViewById<View>(R.id.siteRow).setOnClickListener { pickSite() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { web.reload() }
        findViewById<View>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() else toast("Nothing to go back to") }
        findViewById<View>(R.id.btnHome).setOnClickListener { val h = home; if (h != null) web.loadUrl(h) else toast("Add a website first") }
        findViewById<View>(R.id.btnDl).setOnClickListener { onDownload() }
        findViewById<View>(R.id.btnDownloads).setOnClickListener { startActivity(Intent(this, DownloadsActivity::class.java)) }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMore() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> web.webChromeClient?.onHideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBack < 2000) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                        else { lastBack = now; toast("Swipe back again to exit the app") }
                    }
                }
            }
        })

        if (savedInstanceState != null) newTab(null, savedInstanceState)
        else {
            val act = intent?.action
            val launch = act == null || act == Intent.ACTION_MAIN
            val viewUrl = if (act == Intent.ACTION_VIEW) intent?.dataString?.takeIf { it.startsWith("http") } else null
            newTab(viewUrl ?: (if (launch) home else null))
            if (act == "portal.open.state" || act == Intent.ACTION_SEND) handleIntent(intent)
            if (launch) showContinue()
        }
        applyPrefs()
        refreshShortcuts()
        updateWelcome()
        if (AdBlock.lastUpdated(this) > 0L && System.currentTimeMillis() - AdBlock.lastUpdated(this) > 7L * 86400000L) AdBlock.update(this) { _, _ -> }
        if ((prefs.getString("update_url", DEFAULT_UPDATE) ?: "").isNotBlank() && System.currentTimeMillis() - prefs.getLong("update_at", 0L) > 86400000L) {
            prefs.edit().putLong("update_at", System.currentTimeMillis()).apply()
            checkUpdate(false)
        }
        if (pref("lock", false)) lockCover.visibility = View.VISIBLE
    }

    private fun checkCrash() {
        val f = java.io.File(filesDir, "last_crash.txt")
        if (!f.exists()) return
        val report = try { f.readText() } catch (e: Exception) { "" }
        f.delete()
        MaterialAlertDialogBuilder(this).setTitle("Portal closed unexpectedly")
            .setMessage("You can send the error report to help fix it.")
            .setPositiveButton("Share report") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share report"))
            }
            .setNegativeButton("Dismiss", null).show()
    }

    private fun showError(msg: String) { errorText.text = msg; errorPanel.visibility = View.VISIBLE; setBars(true) }
    private fun hideError() { if (::errorPanel.isInitialized) errorPanel.visibility = View.GONE }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun configure(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
        }
        if (ua.isEmpty()) ua = w.settings.userAgentString
        applyWebPrefs(w)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.setOnScrollChangeListener { _, _, y, _, _ ->
            if (w === web && customView == null && y <= 0) setBars(true)
        }
        var touchY = 0f
        var touchAcc = 0f
        w.setOnTouchListener { _, e ->
            if (w === web && customView == null && e.pointerCount == 1) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { touchY = e.rawY; touchAcc = 0f }
                    MotionEvent.ACTION_MOVE -> {
                        val d = touchY - e.rawY
                        touchY = e.rawY
                        if (d * touchAcc < 0) touchAcc = 0f
                        touchAcc += d
                        if (touchAcc > dp(28)) { setBars(false); touchAcc = 0f }
                        else if (touchAcc < -dp(20)) { setBars(true); touchAcc = 0f }
                    }
                }
            }
            false
        }
        w.setDownloadListener { url, agent, cd, mime, _ -> sysDownload(url, agent, cd, mime) }
        w.addJavascriptInterface(Bridge(), "AnimeBridge")
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addWebMessageListener(w, "AnimeTime", setOf("*")) { view, message, _, _, reply ->
                    onFrameMsg(view, message.data, reply)
                }
                WebViewCompat.addDocumentStartJavaScript(w, FRAME_JS, setOf("*"))
            }
        } catch (e: Exception) { }
        w.setOnLongClickListener {
            val r = w.hitTestResult
            val u = r.extra
            if ((r.type == WebView.HitTestResult.IMAGE_TYPE || r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && u != null) {
                askMedia("image", u); true
            } else if (r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && u != null) {
                linkDialog(u); true
            } else false
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                !(r.url.scheme == "http" || r.url.scheme == "https") ||
                    (pref("adblock", true) && AdBlock.blocked(r.url.host))
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                val t = tabOf(v)
                t?.captured?.clear(); t?.url = u
                if (v === web) hideError()
                if (v === web) { progress.visibility = View.VISIBLE; setBars(true) }
            }
            override fun onPageCommitVisible(v: WebView, u: String) {
                padJs().takeIf { it.isNotEmpty() }?.let { v.evaluateJavascript(it, null) }
                if (siteBool("dark", false, hostOf(u))) v.evaluateJavascript(DARK_ON, null)
            }
            override fun onPageFinished(v: WebView, u: String) {
                if (v === web) progress.visibility = View.INVISIBLE
                pendingRestore?.let { (y, t) ->
                    pendingRestore = null
                    v.evaluateJavascript("(function(){window.scrollTo(0,$y);setTimeout(function(){window.scrollTo(0,$y)},1500);var t=$t;if(t>=0){var n=0;var iv=setInterval(function(){var v=document.querySelector('video');if(v&&v.readyState>0){v.currentTime=t;clearInterval(iv);}else if(++n>40){clearInterval(iv);}},500);}})();", null)
                }
                padJs().takeIf { it.isNotEmpty() }?.let { v.evaluateJavascript(it, null) }
                if (v === web) rememberLast()
                recordVisit(v, u)
                pageTools(v, u)
                v.evaluateJavascript(ZOOM_JS, null); v.evaluateJavascript(IMG_JS, null); v.evaluateJavascript(MEDIA_JS, null)
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame && v === web) showError("This page didn't load. Check your connection, or the site may be blocking this app.")
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, res: WebResourceResponse) {
                if (r.isForMainFrame && v === web && res.statusCode in listOf(403, 429, 503))
                    showError("The site blocked this request (" + res.statusCode + "). Try Retry, or open it in your browser.")
            }
            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (pref("adblock", true) && AdBlock.blocked(req.url.host))
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                val path = req.url.path ?: ""
                if (path.endsWith(".m3u8") || path.endsWith(".mp4")) {
                    val u = req.url.toString()
                    val cap = tabOf(v)?.captured
                    if (cap != null && cap.none { it.url == u })
                        cap.add(Media(u, req.requestHeaders["Referer"], req.requestHeaders["User-Agent"] ?: ua))
                }
                val dnsMode = SiteCfg.dns(prefs, hostOf(tabOf(v)?.url ?: req.url.toString()))
                if (dnsMode == 0 || req.method != "GET" || req.isForMainFrame ||
                    (req.url.host ?: "").endsWith("cloudflare.com") || (req.url.path ?: "").contains("/cdn-cgi/")) return null
                return try {
                    val url = req.url.toString()
                    val b = Request.Builder().url(url)
                    req.requestHeaders.forEach { (k, v2) -> if (!k.equals("Accept-Encoding", true)) b.header(k, v2) }
                    CookieManager.getInstance().getCookie(url)?.let { b.header("Cookie", it) }
                    val r = clientFor(dnsMode).newCall(b.build()).execute()
                    r.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(url, it) }
                    if (r.code in 300..399) { r.close(); return null }
                    val ct = r.header("Content-Type") ?: "application/octet-stream"
                    val mime = ct.substringBefore(";").trim()
                    val enc = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)?.trim()
                    val hdrs = r.headers.toMultimap().mapValues { it.value.joinToString(",") }
                    WebResourceResponse(mime, enc, r.code, r.message.ifEmpty { "OK" }, hdrs, r.body?.byteStream())
                } catch (e: Exception) { null }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { if (v === web) progress.setProgressCompat(p, true) }
            override fun onReceivedTitle(v: WebView, t: String?) {
                val label = if (t.isNullOrBlank()) (loadSites().firstOrNull { it.second == home }?.first ?: "Portal") else t
                tabOf(v)?.title = label
                if (v === web) titleText.text = label
            }
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                customView = view; customCb = cb
                fullscreen.addView(view); fullscreen.visibility = View.VISIBLE
                setFullscreen(true)
                showPip()
            }
            override fun onHideCustomView() {
                fullscreen.removeAllViews(); fullscreen.visibility = View.GONE
                customCb?.onCustomViewHidden(); customView = null
                setFullscreen(false)
                hidePip()
            }
        }
    }


    // ---------- Auto-hiding bars ----------
    private fun padJs(): String {
        val th = topWrap.height
        val bh = bottomWrap.height
        if (th <= 0 || web.width <= 0) return ""
        return "(function(){var k=window.innerWidth/" + web.width + ";var s=document.getElementById('__portal_pad');" +
            "if(!s){s=document.createElement('style');s.id='__portal_pad';(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent='html{padding-top:'+(" + th + "*k)+'px!important;padding-bottom:'+(" + bh + "*k)+'px!important;box-sizing:border-box!important}';})();"
    }

    private fun applyBars(f: Float) {
        barF = f
        val th = topWrap.height.toFloat()
        val bh = bottomWrap.height.toFloat()
        topWrap.translationY = -th * f
        bottomWrap.translationY = bh * f
    }
    private fun setBars(show: Boolean) {
        val target = if (show) 0f else 1f
        if (barAnim == null && barF == target) return
        if (barAnim != null && (barAnim!!.animatedValue as Float) == target) return
        barAnim?.cancel()
        val a = ValueAnimator.ofFloat(barF, target)
        a.duration = 220
        a.interpolator = android.view.animation.DecelerateInterpolator(1.4f)
        topWrap.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        bottomWrap.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        a.addUpdateListener { applyBars(it.animatedValue as Float) }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: android.animation.Animator) {
                topWrap.setLayerType(View.LAYER_TYPE_NONE, null)
                bottomWrap.setLayerType(View.LAYER_TYPE_NONE, null)
                if (barAnim === a) barAnim = null
            }
            override fun onAnimationCancel(an: android.animation.Animator) {
                topWrap.setLayerType(View.LAYER_TYPE_NONE, null)
                bottomWrap.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        })
        barAnim = a
        a.start()
    }

    // ---------- Tools over the web video ----------
    private fun capturedStream(): Media? {
        val cap = cur?.captured
        return cap?.firstOrNull { it.url.substringBefore('?').endsWith(".m3u8") } ?: cap?.firstOrNull()
    }

    private fun leaveWebFullscreen() { if (customView != null) web.webChromeClient?.onHideCustomView() }

    private fun silenceWeb() {
        web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
        web.onPause()
        ui.postDelayed({ web.onResume() }, 1000)
    }

    private fun toolPlayer() {
        val m = capturedStream()
        if (m == null) { toast("Couldn't find the video stream"); return }
        leaveWebFullscreen()
        PlayerActivity.start(this, m.url, m.referer, m.ua, cleanTitle(web.title), -1, web.url)
    }

    private fun toolFloat() {
        val m = capturedStream()
        if (m == null) { toast("Couldn't find the video stream"); return }
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow \"Display over other apps\", then tap Float again")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        leaveWebFullscreen()
        PlayerHolder.start(this, m.url, m.referer, m.ua, cleanTitle(web.title), -1, web.url)
        val svc = Intent(this, FloatingPlayerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        silenceWeb()
    }

    // ---------- Mini player (PiP) ----------
    private fun showPip() {
        if (pip) return
        ui.removeCallbacks(hidePipRunnable)
        pipBtn.animate().cancel()
        pipBtn.alpha = 1f; pipBtn.visibility = View.VISIBLE
        ui.postDelayed(hidePipRunnable, 5000)
    }

    private fun hidePip() {
        ui.removeCallbacks(hidePipRunnable)
        pipBtn.animate().cancel()
        pipBtn.visibility = View.GONE
    }

    private fun enterPip(silent: Boolean) {
        if (customView == null) return
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            if (!silent) toast("Mini player isn't supported on this device")
            return
        }
        try {
            pip = true
            hidePip()
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } catch (e: Exception) { pip = false; if (!silent) toast("Can't open the mini player") }
    }

    // ---------- Saved states ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun onFrameMsg(view: WebView, data: String?, reply: JavaScriptReplyProxy) {
        try {
            val o = JSONObject(data ?: return)
            val tab = tabOf(view) ?: return
            val d = o.optDouble("d", 0.0)
            if (o.has("r")) {
                if (view === web && pendingSeek > 0 && System.currentTimeMillis() < pendingSeekUntil && d > 60 && pendingSeek < d) {
                    reply.postMessage("seek:" + pendingSeek)
                    pendingSeek = -1.0
                }
            } else if (o.has("t") && d > 60) {
                tab.vt = o.getDouble("t"); tab.vd = d
            }
        } catch (e: Exception) { }
    }

    private fun snapshot(cb: (Bitmap?) -> Unit) {
        val target: View = if (customView != null) fullscreen else web
        if (target.width == 0 || target.height == 0) { cb(null); return }
        val bmp = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT >= 26) {
            val loc = IntArray(2)
            target.getLocationInWindow(loc)
            try {
                PixelCopy.request(window, Rect(loc[0], loc[1], loc[0] + target.width, loc[1] + target.height), bmp,
                    { r -> cb(if (r == PixelCopy.SUCCESS) bmp else null) }, ui)
            } catch (e: Exception) { cb(null) }
        } else {
            try { target.draw(Canvas(bmp)); cb(bmp) } catch (e: Exception) { cb(null) }
        }
    }

    private fun saveSnapshot() {
        val tab = cur ?: return
        val url = web.url
        if (url == null) { toast("Nothing to save yet"); return }
        val title0 = web.title ?: url
        web.evaluateJavascript(STATE_JS) { r ->
            var y = 0
            var tm = -1.0
            try {
                val o = JSONObject(JSONTokener(r).nextValue() as String)
                y = o.optInt("y"); tm = o.optDouble("t", -1.0)
            } catch (e: Exception) { }
            var dur = 0.0
            if (tab.vt > 0) { tm = tab.vt; dur = tab.vd }
            val stream = capturedStream()
            val bundle = Bundle()
            web.saveState(bundle)
            val time = tm
            val duration = dur
            val scroll = y
            snapshot { bmp ->
                val et = EditText(this)
                et.setText(title0)
                val box = FrameLayout(this)
                box.setPadding(dp(24), dp(8), dp(24), 0)
                box.addView(et)
                val info = if (time > 0) "Saves the page and the video at " + Snaps.fmt(time) else "Saves the page exactly as it is"
                MaterialAlertDialogBuilder(this).setTitle("Save state").setMessage(info).setView(box)
                    .setPositiveButton("Save") { _, _ ->
                        Thread {
                            Snaps.init(this)
                            val now = System.currentTimeMillis()
                            Snaps.add(Snap(now, et.text.toString().ifBlank { title0 }, url, now, "web", time, duration, scroll,
                                stream?.url, stream?.referer, stream?.ua), bundle, bmp)
                            toast("State saved")
                        }.start()
                    }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun openSnapshot(s: Snap) {
        if (s.type == "player" && s.stream != null) {
            PlayerActivity.start(this, s.stream, s.referer, s.ua ?: ua, s.title, -1, s.url, (maxOf(s.t, 0.0) * 1000).toLong())
            return
        }
        if (s.stream != null && s.t > 5) {
            MaterialAlertDialogBuilder(this).setTitle(s.title)
                .setItems(arrayOf("Resume on the page", "Resume in app player (" + Snaps.fmt(s.t) + ")")) { _, i ->
                    if (i == 0) restoreWeb(s)
                    else PlayerActivity.start(this, s.stream, s.referer, s.ua ?: ua, s.title, -1, s.url, (s.t * 1000).toLong())
                }.show()
        } else restoreWeb(s)
    }

    private fun restoreWeb(s: Snap) {
        pendingSeek = s.t
        pendingSeekUntil = System.currentTimeMillis() + 90000
        pendingRestore = Pair(s.y, s.t)
        val b = Snaps.session(s.id)
        if (b != null && web.restoreState(b) != null) return
        web.loadUrl(s.url)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_VIEW -> i.dataString?.takeIf { it.startsWith("http") }?.let { web.loadUrl(it) }
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { handleShared(it) }
            "portal.open.state" -> {
                Snaps.init(this)
                val id = i.getLongExtra("id", -1L)
                Snaps.list().firstOrNull { it.id == id }?.let { openSnapshot(it) }
            }
        }
    }

    private fun handleShared(text: String) {
        val url = Regex("https?://\\S+").find(text)?.value
        if (url == null) { toast("No link found in what was shared"); return }
        val host = Uri.parse(url).host ?: return
        val known = loadSites().any { site ->
            val h = Uri.parse(site.second).host ?: ""
            h.isNotEmpty() && (host == h || host.endsWith(".$h") || h.endsWith(".$host"))
        }
        if (known) { web.loadUrl(url); return }
        MaterialAlertDialogBuilder(this).setTitle("Open link from $host?")
            .setMessage("This website isn't in your list yet.")
            .setPositiveButton("Add and open") { _, _ ->
                val l = loadSites()
                l.add(Pair(host, "https://$host/")); saveSites(l)
                web.loadUrl(url)
            }
            .setNeutralButton("Open once") { _, _ -> web.loadUrl(url) }
            .setNegativeButton("Cancel", null).show()
    }

    private val ZOOM_JS = Scripts.ZOOM_JS
    private val IMG_JS = Scripts.IMG_JS
    private val MEDIA_JS = Scripts.MEDIA_JS

    inner class Bridge {
        @JavascriptInterface
        fun media(kind: String, url: String) { runOnUiThread { askMedia(kind, url) } }
    }

    private fun askMedia(kind: String, url: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast(if (url.startsWith("blob:") || url.isEmpty()) "This video is streamed. Play it, then use Download video in the menu" else "Can't download this one")
            return
        }
        if (!ensurePerms()) return
        MaterialAlertDialogBuilder(this).setTitle("Download this $kind?")
            .setMessage(url.substringBefore('?').substringAfterLast('/').take(80))
            .setPositiveButton("Download") { _, _ ->
                if (kind == "video") {
                    val t = URLUtil.guessFileName(url, null, null).substringBeforeLast('.')
                        .replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "video" }
                    startDownload(Media(url, web.url, ua), t)
                } else sysDownload(url, ua, null, null)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun sysDownload(url: String, agent: String, cd: String?, mime: String?) {
        if (!ensurePerms()) return
        try {
            val r = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", agent)
                web.url?.let { addRequestHeader("Referer", it) }
                if (!mime.isNullOrEmpty()) setMimeType(mime)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime))
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            toast("Download started, saved to Downloads")
        } catch (e: Exception) { toast("Download failed: ${e.message}") }
    }

    // ---------- Download ----------
    private fun onDownload() {
        val cap = cur?.captured
        val m = cap?.firstOrNull { it.url.substringBefore('?').endsWith(".m3u8") } ?: cap?.firstOrNull()
        if (m == null) { toast("Start playing the video first, then tap Download video"); return }
        ensurePerms()
        val title = cleanTitle(web.title)
        MaterialAlertDialogBuilder(this).setTitle("Download this video?").setMessage(title)
            .setPositiveButton("Download") { _, _ -> startDownload(m, title) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun ensurePerms(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            toast("Allow storage access, then tap Download again")
            return false
        }
        return true
    }

    private fun pickVariant(vs: List<Variant>): Variant? {
        if (vs.size <= 1) return vs.firstOrNull()
        var res: Variant? = null
        val latch = CountDownLatch(1)
        runOnUiThread {
            MaterialAlertDialogBuilder(this).setTitle("Quality")
                .setItems(vs.map { it.label }.toTypedArray()) { _, i -> res = vs[i]; latch.countDown() }
                .setOnCancelListener { latch.countDown() }.show()
        }
        latch.await()
        return res
    }

    private fun startDownload(m: Media, title: String) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ensurePerms(); return
        }
        val http = Downloads.httpClient()
        Thread {
            try {
                if (!m.url.substringBefore('?').endsWith(".m3u8")) {
                    Downloads.enqueue(title, "mp4", m.url, m.referer, m.ua)
                } else {
                    var url = m.url
                    var name = title
                    val text = Hls.text(http, url, m.referer, m.ua)
                    if (text.contains("#EXT-X-STREAM-INF")) {
                        val v = pickVariant(Hls.variants(url, text)) ?: return@Thread
                        url = v.url; name = "$title ${v.label}"
                    }
                    Downloads.enqueue(name, "hls", url, m.referer, m.ua)
                }
                runOnUiThread {
                    Snackbar.make(web, "Added to Downloads", Snackbar.LENGTH_LONG)
                        .setAction("View") { startActivity(Intent(this, DownloadsActivity::class.java)) }.show()
                }
            } catch (e: Exception) { toast("Download failed: ${e.message}") }
        }.start()
    }

    // ---------- UI ----------
    private fun setFullscreen(on: Boolean) {
        rotLocked = false
        if (!on) { val w = window.attributes; w.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; window.attributes = w }
        topWrap.visibility = if (on) View.GONE else View.VISIBLE
        bottomWrap.visibility = if (on) View.GONE else View.VISIBLE
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val a = window.attributes
            a.layoutInDisplayCutoutMode = if (on) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = a
        }
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun saveSites(l: List<Pair<String, String>>) {
        Sites.save(prefs, l)
        refreshShortcuts()
        updateWelcome()
    }

    private fun loadSites(): MutableList<Pair<String, String>> = Sites.load(prefs)

    private fun pickSite() {
        val l = loadSites()
        if (l.isEmpty()) { addSiteDialog(); return }
        val cur = l.indexOfFirst { it.second == home }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle("Change site")
            .setSingleChoiceItems(l.map { it.first }.toTypedArray(), cur) { d, i ->
                prefs.edit().putString("site_url", l[i].second).apply()
                d.dismiss(); web.loadUrl(l[i].second)
            }.show()
    }

    private fun siteDialog(title: String, name0: String, url0: String, onSave: (String, String) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        val n = EditText(this); n.hint = "Name (optional)"; n.setText(name0)
        val u = EditText(this); u.hint = "https://example.com"; u.setText(url0)
        u.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        box.addView(n); box.addView(u)
        MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ ->
                var url = u.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                val host = Uri.parse(url).host
                if (host.isNullOrEmpty()) { toast("That doesn't look like a website"); return@setPositiveButton }
                onSave(n.text.toString().trim().ifEmpty { host }, url)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addSiteDialog() {
        siteDialog("Add your own website", "", "") { name, url ->
            val l = loadSites()
            l.add(Pair(name, url)); saveSites(l)
            prefs.edit().putString("site_url", url).apply()
            web.loadUrl(url); toast("Added $name")
        }
    }

    private fun manageSites() {
        val l = loadSites()
        if (l.isEmpty()) { toast("No websites yet. Add your first one."); addSiteDialog(); return }
        val labels = l.map { it.first + "\n" + it.second }
        MaterialAlertDialogBuilder(this).setTitle("Websites")
            .setItems(labels.toTypedArray()) { _, i ->
                val site = l[i]
                MaterialAlertDialogBuilder(this).setTitle(site.first).setItems(arrayOf("Edit", "Delete", "Add to home screen")) { _, a ->
                    if (a == 2) pinSite(site.first, site.second)
                    else if (a == 0) {
                        siteDialog("Edit website", site.first, site.second) { name, url ->
                            l[i] = Pair(name, url); saveSites(l)
                            if (prefs.getString("site_url", null) == site.second) prefs.edit().putString("site_url", url).apply()
                            toast("Saved"); manageSites()
                        }
                    } else {
                        l.removeAt(i); saveSites(l)
                        if (prefs.getString("site_url", null) == site.second) prefs.edit().remove("site_url").apply()
                        toast("Removed " + site.first); manageSites()
                    }
                }.show()
            }.setNegativeButton("Close", null).show()
    }

    private fun updateWelcome() {
        if (!::welcome.isInitialized) return
        welcome.visibility = if (loadSites().isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addFirstSite() {
        var url = findViewById<EditText>(R.id.wUrl).text.toString().trim()
        if (url.isEmpty()) { toast("Type the website address first"); return }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        val host = Uri.parse(url).host
        if (host.isNullOrEmpty()) { toast("That doesn't look like a website"); return }
        val name = findViewById<EditText>(R.id.wName).text.toString().trim().ifEmpty { host }
        val l = loadSites()
        l.add(Pair(name, url)); saveSites(l)
        prefs.edit().putString("site_url", url).apply()
        web.loadUrl(url)
    }

    private fun sheet(v: View): BottomSheetDialog {
        val d = BottomSheetDialog(this)
        d.setContentView(v)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        return d
    }

    private fun showMore() {
        val v = layoutInflater.inflate(R.layout.sheet_menu, null)
        val d = sheet(v)
        val rows = v.findViewById<LinearLayout>(R.id.rows)
        val tiles = listOf(
            Triple(R.drawable.ic_tabs, "Tabs (" + tabs.size + ")", { showTabs() }),
            Triple(R.drawable.ic_play, "Continue", { showShelf() }),
            Triple(R.drawable.ic_globe, "This site", { showSiteSettings() }),
            Triple(R.drawable.ic_library, "Library", { startActivity(Intent(this, LibraryActivity::class.java)) }),
            Triple(R.drawable.ic_favorite, "Favorite", { toggleFavorite() }),
            Triple(R.drawable.ic_bookmark, "Save state", { saveSnapshot() }),
            Triple(R.drawable.ic_bookmarks, "Saved states", { startActivity(Intent(this, SavedStatesActivity::class.java)) }),
            Triple(R.drawable.ic_swap, "Change site", { pickSite() }),
            Triple(R.drawable.ic_search, "Find in page", { findInPage() }),
            Triple(R.drawable.ic_copy, "Copy link", { copyLink() }),
            Triple(R.drawable.ic_share, "Share link", { shareLink() }),
            Triple(R.drawable.ic_open, "Open in browser", { openExternal() }),
            Triple(R.drawable.ic_refresh, "Refresh", { web.reload() }),
            Triple(R.drawable.ic_tune, "Settings", { showSettings() }),
            Triple(R.drawable.ic_power, "Exit", { finishAndRemoveTask() })
        )
        for (chunk in tiles.chunked(3)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (t in chunk) {
                val tv = layoutInflater.inflate(R.layout.item_tile, row, false)
                tv.findViewById<android.widget.ImageView>(R.id.tileIcon).setImageResource(t.first)
                tv.findViewById<TextView>(R.id.tileLabel).text = t.second
                tv.setOnClickListener { d.dismiss(); t.third() }
                row.addView(tv)
            }
            for (i in chunk.size until 3) {
                val sp = View(this)
                sp.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                row.addView(sp)
            }
            rows.addView(row)
        }
        d.show()
    }

    private fun bindSwitch(v: View, id: Int, key: String, def: Boolean, onChange: (Boolean) -> Unit = {}) {
        val s = v.findViewById<MaterialSwitch>(id)
        s.isChecked = prefs.getBoolean(key, def)
        s.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply(); onChange(c) }
    }

    private fun textOf(v: View): String = when (v) {
        is TextView -> v.text.toString()
        is ViewGroup -> (0 until v.childCount).joinToString(" ") { textOf(v.getChildAt(it)) }
        else -> ""
    }

    private fun filterSettings(root: ViewGroup, q: String) {
        val query = q.trim().lowercase()
        for (s in 0 until root.childCount) {
            val sec = root.getChildAt(s) as? ViewGroup ?: continue
            if (sec.tag != "section") continue
            val header = (0 until sec.childCount).map { sec.getChildAt(it) }.firstOrNull { it.tag == "header" }
            val groups = ArrayList<MutableList<View>>()
            for (i in 0 until sec.childCount) {
                val c = sec.getChildAt(i)
                when (c.tag) {
                    "row" -> groups.add(mutableListOf(c))
                    "sub" -> groups.lastOrNull()?.add(c)
                }
            }
            val headerMatch = query.isEmpty() || (header != null && textOf(header).lowercase().contains(query))
            var any = false
            for (g in groups) {
                val show = query.isEmpty() || headerMatch || g.any { textOf(it).lowercase().contains(query) }
                g.forEach { it.visibility = if (show) View.VISIBLE else View.GONE }
                if (show) any = true
            }
            val visible = any || query.isEmpty()
            header?.visibility = if (visible) View.VISIBLE else View.GONE
            sec.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.sheet_settings, null)
        val d = sheet(v)
        val modes = intArrayOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
        val tIds = intArrayOf(R.id.t_sys, R.id.t_light, R.id.t_dark)
        val tg = v.findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        tg.check(tIds[modes.indexOf(prefs.getInt("theme", modes[0])).coerceAtLeast(0)])
        val sIds = intArrayOf(R.id.st_clean, R.id.st_expr, R.id.st_dark)
        val sg = v.findViewById<MaterialButtonToggleGroup>(R.id.styleGroup)
        sg.check(sIds[Style.index(this)])
        sg.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                prefs.edit().putInt(Style.KEY, sIds.indexOf(id).coerceAtLeast(0)).apply()
                toast("Restart Portal to apply the new style")
            }
        }
        tg.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                val m = modes[tIds.indexOf(id).coerceAtLeast(0)]
                prefs.edit().putInt("theme", m).apply()
                d.dismiss(); AppCompatDelegate.setDefaultNightMode(m)
            }
        }
        val dIds = intArrayOf(R.id.d0, R.id.d1, R.id.d2, R.id.d3, R.id.d4)
        val cg = v.findViewById<ChipGroup>(R.id.dnsGroup)
        cg.check(dIds[prefs.getInt("dns", 0).coerceIn(0, 4)])
        cg.setOnCheckedStateChangeListener { _, ids ->
            val i = dIds.indexOf(ids.firstOrNull() ?: -1)
            if (i >= 0) {
                prefs.edit().putInt("dns", i).apply()
                client = makeClient(i); web.reload()
            }
        }
        v.findViewById<EditText>(R.id.settingsSearch).doAfterTextChanged {
            filterSettings(v.findViewById(R.id.settingsRoot), it?.toString() ?: "")
        }
        bindSwitch(v, R.id.sw_adblock, "adblock", true) { web.reload() }
        val adStatus = v.findViewById<TextView>(R.id.adStatus)
        adStatus.text = AdBlock.status(this)
        v.findViewById<View>(R.id.btnAdUpdate).setOnClickListener {
            adStatus.text = "Updating..."
            AdBlock.update(this) { n, err ->
                runOnUiThread { adStatus.text = if (err == null) "Updated: " + n + " extra hosts" else "Update failed: " + err }
            }
        }
        v.findViewById<View>(R.id.btnSetUpdate).setOnClickListener { d.dismiss(); editUpdateSource() }
        v.findViewById<View>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate(true) }
        v.findViewById<TextView>(R.id.aboutVersion).text = "Portal " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" })
        v.findViewById<View>(R.id.btnGithub).setOnClickListener { openLink(REPO_URL) }
        v.findViewById<View>(R.id.btnIssue).setOnClickListener { openLink("$REPO_URL/issues") }
        bindSwitch(v, R.id.sw_continue, "continue", true)
        bindSwitch(v, R.id.sw_gestures, "gestures", true)
        bindSwitch(v, R.id.sw_dark, "dark", false) { applyDark() }
        bindSwitch(v, R.id.sw_scripts, "scripts", false) { web.reload() }
        bindSwitch(v, R.id.sw_delafter, "delete_after", false)
        v.findViewById<View>(R.id.btnEditScripts).setOnClickListener { d.dismiss(); editScripts() }
        bindSwitch(v, R.id.sw_wifi, "wifi_only", false)
        bindSwitch(v, R.id.sw_lock, "lock", false) { on ->
            applyPrefs()
            if (on) {
                if (!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) {
                    toast("Set a screen lock on your phone first")
                    prefs.edit().putBoolean("lock", false).apply(); applyPrefs()
                    v.findViewById<MaterialSwitch>(R.id.sw_lock).isChecked = false
                } else unlocked = true
            }
        }
        bindSwitch(v, R.id.sw_incognito, "incognito", false) { on -> applyPrefs(); if (on) web.clearCache(true) }
        v.findViewById<View>(R.id.btnAddSite).setOnClickListener { d.dismiss(); addSiteDialog() }
        v.findViewById<View>(R.id.btnManageSites).setOnClickListener { d.dismiss(); manageSites() }
        v.findViewById<View>(R.id.btnExport).setOnClickListener { d.dismiss(); exportLauncher.launch("anime-backup.json") }
        v.findViewById<View>(R.id.btnImport).setOnClickListener { d.dismiss(); importLauncher.launch(arrayOf("*/*")) }
        d.show()
    }

    // ---------- Prefs applied to the WebView / window ----------
    private fun applyWebPrefs(w: WebView) {
        w.settings.cacheMode = if (pref("incognito", false)) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        w.settings.userAgentString = null
    }

    private fun applyPrefs() {
        for (t in tabs) applyWebPrefs(t.web)
        if (pref("lock", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ---------- Continue watching ----------
    private fun rememberLast(thumb: Boolean = false) {
        if (!pref("continue", true) || pref("incognito", false)) return
        val u = web.url ?: return
        if ((Uri.parse(u).path ?: "/").length <= 1) return
        val title = web.title ?: u
        web.evaluateJavascript(STATE_JS) { r ->
            var y = 0
            var t = -1.0
            try {
                val o = JSONObject(JSONTokener(r).nextValue() as String)
                y = o.optInt("y"); t = o.optDouble("t", -1.0)
            } catch (e: Exception) { }
            val ft = cur?.vt ?: -1.0
            if (ft > 0) t = ft
            prefs.edit().putString("last_url", u).putString("last_title", title)
                .putInt("last_y", y).putFloat("last_t", t.toFloat()).apply()
            if (t > 5.0) {
                Recents.touch(u, title, t, y)
                if (thumb && customView == null) snapshot { bmp -> if (bmp != null) Thread { Recents.saveThumb(u, bmp) }.start() }
            }
        }
    }

    private fun showContinue() {
        if (!pref("continue", true) || pref("incognito", false) || loadSites().isEmpty()) return
        if (Recents.list().isNotEmpty()) { ui.postDelayed({ if (!isFinishing) showShelf() }, 1200); return }
        val u = prefs.getString("last_url", null) ?: return
        val title = prefs.getString("last_title", u) ?: u
        ui.postDelayed({
            Snackbar.make(web, "Continue: $title", 8000).setAction("Continue") {
                val lt = prefs.getFloat("last_t", -1f).toDouble()
                pendingRestore = Pair(prefs.getInt("last_y", 0), lt)
                pendingSeek = lt
                pendingSeekUntil = System.currentTimeMillis() + 90000
                web.loadUrl(u)
            }.show()
        }, 1200)
    }

    // ---------- App lock ----------
    private fun promptAuth() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) { lockCover.visibility = View.GONE; unlocked = true; return }
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS) {
            authInProgress = true
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authInProgress = false; unlocked = true; lockCover.visibility = View.GONE
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { authInProgress = false }
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Portal").setAllowedAuthenticators(auth).build())
            return
        }
        val i = km.createConfirmDeviceCredentialIntent("Portal", "Unlock to continue")
        if (i == null) { lockCover.visibility = View.GONE; unlocked = true; return }
        authInProgress = true
        authLauncher.launch(i)
    }

    // ---------- Fullscreen gestures ----------
    private fun showHint(s: String) {
        hint.text = s; hint.visibility = View.VISIBLE
        ui.removeCallbacks(hideHint); ui.postDelayed(hideHint, 800)
    }

    private fun gesture(left: Boolean, dy: Float) {
        if (left) {
            val lp = window.attributes
            var b = lp.screenBrightness
            if (b < 0) b = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (e: Exception) { 0.5f }
            b = (b + dy * 1.3f).coerceIn(0.02f, 1f)
            lp.screenBrightness = b
            window.attributes = lp
            showHint("Brightness " + (b * 100).toInt() + "%")
        } else {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (volAcc < 0) volAcc = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            volAcc = (volAcc + dy * max * 1.3f).coerceIn(0f, max.toFloat())
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volAcc.toInt(), 0)
            showHint("Volume " + (volAcc / max * 100).toInt() + "%")
        }
    }

    // ---------- Tabs ----------
    private fun tabOf(v: WebView): Tab? = tabs.firstOrNull { it.web === v }

    private fun newTab(url: String?, restore: Bundle? = null) {
        val w = WebView(this)
        configure(w)
        val t = Tab(w)
        tabs.add(t)
        switchTo(t)
        if (restore != null) w.restoreState(restore) else if (url != null) w.loadUrl(url)
    }

    private fun switchTo(t: Tab) {
        val old = cur
        if (old != null && old !== t) { swipe.removeView(old.web); old.web.onPause() }
        cur = t; web = t.web
        if (t.web.parent == null) swipe.addView(t.web, FrameLayout.LayoutParams(-1, -1))
        t.web.onResume()
        titleText.text = t.title
        hideError()
        progress.visibility = View.INVISIBLE
        setBars(true)
    }

    private fun closeTab(t: Tab) {
        if (tabs.size == 1) { home?.let { t.web.loadUrl(it) }; return }
        val i = tabs.indexOf(t)
        tabs.remove(t)
        if (cur === t) switchTo(tabs[minOf(i, tabs.size - 1)])
        try { swipe.removeView(t.web); t.web.destroy() } catch (e: Exception) { }
    }

    private fun showTabs() {
        val v = layoutInflater.inflate(R.layout.sheet_tabs, null)
        val d = sheet(v)
        val list = v.findViewById<LinearLayout>(R.id.tabList)
        fun fill() {
            list.removeAllViews()
            for (t in tabs) {
                val row = layoutInflater.inflate(R.layout.item_tab, list, false)
                val tt = row.findViewById<TextView>(R.id.tabTitle)
                tt.text = t.title
                tt.setTypeface(null, if (t === cur) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                row.findViewById<TextView>(R.id.tabUrl).text = t.web.url ?: t.url
                row.setOnClickListener { d.dismiss(); switchTo(t) }
                row.findViewById<View>(R.id.tabClose).setOnClickListener { closeTab(t); fill() }
                list.addView(row)
            }
        }
        fill()
        v.findViewById<View>(R.id.btnNewTab).setOnClickListener { d.dismiss(); newTab(home) }
        d.show()
    }

    private fun linkDialog(u: String) {
        MaterialAlertDialogBuilder(this).setTitle(u.take(80)).setItems(arrayOf("Open in new tab", "Copy link")) { _, i ->
            if (i == 0) newTab(u)
            else {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", u))
                toast("Link copied")
            }
        }.show()
    }

    // ---------- History, favorites, player, sleep ----------
    private fun recordVisit(v: WebView, u: String) {
        if (pref("incognito", false)) return
        if ((Uri.parse(u).path ?: "/").length <= 1) return
        Library.visit(u, v.title ?: u)
    }

    private fun toggleFavorite() {
        val u = web.url ?: return
        val on = Library.toggleFav(u, web.title ?: u)
        toast(if (on) "Added to favorites" else "Removed from favorites")
    }

    // ---------- Per-site settings ----------
    private fun bindGroup(v: View, groupId: Int, ids: IntArray, vals: IntArray, host: String, key: String, onSet: () -> Unit) {
        val g = v.findViewById<ChipGroup>(groupId)
        g.check(ids[vals.indexOf(SiteCfg.get(prefs, host, key)).coerceAtLeast(0)])
        g.setOnCheckedStateChangeListener { _, checked ->
            val i = ids.indexOf(checked.firstOrNull() ?: -1)
            if (i >= 0) { SiteCfg.set(prefs, host, key, vals[i]); onSet() }
        }
    }

    private fun showSiteSettings() {
        val host = hostOf(web.url)
        if (host.isEmpty()) { toast("Open a website first"); return }
        val v = layoutInflater.inflate(R.layout.sheet_site, null)
        val d = sheet(v)
        v.findViewById<TextView>(R.id.siteHost).text = host
        val tri = intArrayOf(-1, 1, 0)
        bindGroup(v, R.id.siteDark, intArrayOf(R.id.sd_def, R.id.sd_on, R.id.sd_off), tri, host, "dark") { applyDark() }
        bindGroup(v, R.id.siteScripts, intArrayOf(R.id.ss_def, R.id.ss_on, R.id.ss_off), tri, host, "scripts") { web.reload() }
        bindGroup(v, R.id.siteDns, intArrayOf(R.id.sn_def, R.id.sn0, R.id.sn1, R.id.sn2, R.id.sn3, R.id.sn4),
            intArrayOf(-1, 0, 1, 2, 3, 4), host, "dns") { web.reload() }
        d.show()
    }

    // ---------- Continue watching shelf ----------
    private fun showShelf() {
        val items = Recents.list()
        if (items.isEmpty()) { toast("Nothing to continue yet"); return }
        val v = layoutInflater.inflate(R.layout.sheet_shelf, null)
        val d = sheet(v)
        val row = v.findViewById<LinearLayout>(R.id.shelfRow)
        for (r in items) {
            val c = layoutInflater.inflate(R.layout.item_shelf, row, false)
            c.findViewById<TextView>(R.id.shTitle).text = cleanTitle(r.title)
            c.findViewById<TextView>(R.id.shSub).text = "Resume at " + Snaps.fmt(r.t)
            val f = Recents.thumbFile(r.url)
            if (f.exists()) c.findViewById<ImageView>(R.id.shThumb).setImageURI(Uri.fromFile(f))
            c.setOnClickListener { d.dismiss(); resumeRecent(r) }
            c.setOnLongClickListener {
                MaterialAlertDialogBuilder(this).setMessage("Remove from Continue watching?")
                    .setPositiveButton("Remove") { _, _ -> Recents.remove(r.url); d.dismiss() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            row.addView(c)
        }
        d.show()
    }

    private fun resumeRecent(r: Recent) {
        pendingRestore = Pair(r.y, r.t)
        pendingSeek = r.t
        pendingSeekUntil = System.currentTimeMillis() + 90000
        web.loadUrl(r.url)
    }

    // ---------- Updates ----------
    private fun openLink(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast("Can't open the link") }
    }

    private fun checkUpdate(manual: Boolean) {
        val src = prefs.getString("update_url", DEFAULT_UPDATE) ?: ""
        if (src.isBlank()) { if (manual) toast("Set an update source first"); return }
        Thread {
            try {
                val info = Updater.check(this, src)
                runOnUiThread {
                    if (info != null) {
                        MaterialAlertDialogBuilder(this).setTitle("Update available")
                            .setMessage("Portal " + info.version + " is available.")
                            .setPositiveButton("Download") { _, _ ->
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url))) } catch (e: Exception) { toast("Can't open the link") }
                            }.setNegativeButton("Later", null).show()
                    } else if (manual) toast("You're up to date")
                }
            } catch (e: Exception) { if (manual) toast("Couldn't check for updates: " + (e.message ?: "unknown error")) }
        }.start()
    }

    private fun editUpdateSource() {
        val et = EditText(this)
        et.hint = "https://..."
        et.setText(prefs.getString("update_url", DEFAULT_UPDATE) ?: "")
        val box = FrameLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(et)
        MaterialAlertDialogBuilder(this).setTitle("Update source")
            .setMessage("Paste the link to a GitHub \"latest release\" API address, or to a JSON file like {\"versionName\":\"1.2\",\"url\":\"https://...\"}.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> prefs.edit().putString("update_url", et.text.toString().trim()).apply(); toast("Saved") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun applyDark() {
        for (t in tabs) t.web.evaluateJavascript(if (siteBool("dark", false, hostOf(t.web.url))) DARK_ON else DARK_OFF, null)
    }

    private fun pageTools(v: WebView, u: String) {
        val site = hostOf(u)
        if (siteBool("dark", false, site)) v.evaluateJavascript(DARK_ON, null)
        if (!siteBool("scripts", false, site)) return
        val hosts = (prefs.getString("user_hosts", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val h = Uri.parse(u).host ?: ""
        if (hosts.isNotEmpty() && hosts.none { h.endsWith(it) }) return
        val css = prefs.getString("user_css", "") ?: ""
        val js = prefs.getString("user_js", "") ?: ""
        if (css.isNotBlank())
            v.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=" + JSONObject.quote(css) + ";document.head.appendChild(s);})();", null)
        if (js.isNotBlank())
            v.evaluateJavascript("(function(){try{" + js + "\n}catch(e){}})();", null)
    }

    private fun editScripts() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        fun field(hint: String, value: String, lines: Int): EditText {
            val e = EditText(this)
            e.hint = hint; e.setText(value)
            if (lines > 1) { e.setLines(lines); e.gravity = android.view.Gravity.TOP or android.view.Gravity.START; e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            box.addView(e)
            return e
        }
        val hosts = field("Only on these sites, e.g. animepahe.pw, hanime.tv (empty = all)", prefs.getString("user_hosts", "") ?: "", 1)
        val css = field("CSS, e.g. .ad-banner { display: none }", prefs.getString("user_css", "") ?: "", 5)
        val js = field("JavaScript", prefs.getString("user_js", "") ?: "", 5)
        val sv = android.widget.ScrollView(this)
        sv.addView(box)
        MaterialAlertDialogBuilder(this).setTitle("Scripts and styles").setView(sv)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("user_hosts", hosts.text.toString()).putString("user_css", css.text.toString())
                    .putString("user_js", js.text.toString()).apply()
                toast("Saved. Turn on Custom scripts in Settings, then reload.")
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- Page tools ----------
    private fun copyLink() {
        val u = web.url ?: return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", u))
        toast("Link copied")
    }

    private fun shareLink() {
        val u = web.url ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "Share link"))
    }

    private fun openExternal() {
        val u = web.url ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast("No browser found") }
    }

    private fun findInPage() {
        val et = EditText(this)
        et.hint = "Find in page"
        et.setSingleLine()
        val box = FrameLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(et)
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Find in page").setView(box)
            .setPositiveButton("Next", null).setNeutralButton("Prev", null)
            .setNegativeButton("Close") { _, _ -> web.clearMatches(); lastFind = "" }.create()
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val q = et.text.toString()
                if (q.isNotEmpty()) { if (q != lastFind) { lastFind = q; web.findAllAsync(q) } else web.findNext(true) }
            }
            dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { if (et.text.isNotEmpty()) web.findNext(false) }
        }
        dlg.show()
    }

    private fun cleanTitle(raw: String?): String {
        var t = raw ?: "video"
        t = t.replace(Regex("(?i)\\s*[:|\\-\u2013\u2014]{1,2}\\s*(animepahe|hanime)[^|]*$"), "")
        t = t.replace(Regex("(?i)^watch\\s+"), "").replace(Regex("(?i)\\s+(online|english subbed|english dubbed|free)\\b"), "")
        t = t.replace(Regex("(?i)episode\\s*(\\d+)")) { "E" + it.groupValues[1].padStart(2, '0') }
        return t.replace(Regex("[^A-Za-z0-9 ._-]"), "").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "video" }
    }

    // ---------- Shortcuts ----------
    private fun shortcutFor(name: String, url: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(this, "site_" + url.hashCode())
            .setShortLabel(name).setLongLabel(name)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity::class.java))
            .build()

    private fun refreshShortcuts() {
        try { ShortcutManagerCompat.setDynamicShortcuts(this, loadSites().take(4).map { shortcutFor(it.first, it.second) }) } catch (e: Exception) { }
    }

    private fun pinSite(name: String, url: String) {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) ShortcutManagerCompat.requestPinShortcut(this, shortcutFor(name, url), null)
        else toast("Your launcher doesn't support home screen shortcuts")
    }

    // ---------- Backup ----------
    private val boolKeys = listOf("adblock", "continue", "gestures", "wifi_only", "lock", "incognito")

    private fun writeBackup(uri: Uri) {
        try {
            loadSites()
            val o = JSONObject()
            o.put("sites", JSONArray(prefs.getString("sites", "[]")))
            Snaps.init(this)
            o.put("snaps", Snaps.metaJson())
            val st = JSONObject()
            for (key in boolKeys) if (prefs.contains(key)) st.put(key, prefs.getBoolean(key, false))
            if (prefs.contains("theme")) st.put("theme", prefs.getInt("theme", -1))
            if (prefs.contains("dns")) st.put("dns", prefs.getInt("dns", 0))
            o.put("settings", st)
            contentResolver.openOutputStream(uri)?.use { it.write(o.toString(2).toByteArray()) }
            toast("Backup saved")
        } catch (e: Exception) { toast("Backup failed: " + e.message) }
    }

    private fun readBackup(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
            val o = JSONObject(text)
            val ed = prefs.edit()
            o.optJSONArray("sites")?.let { ed.putString("sites", it.toString()) }
            Snaps.init(this)
            o.optJSONArray("snaps")?.let { Snaps.importMeta(it) }
            o.optJSONObject("settings")?.let { st ->
                for (key in boolKeys) if (st.has(key)) ed.putBoolean(key, st.getBoolean(key))
                if (st.has("theme")) ed.putInt("theme", st.getInt("theme"))
                if (st.has("dns")) ed.putInt("dns", st.getInt("dns"))
            }
            ed.apply()
            client = makeClient(prefs.getInt("dns", 0))
            applyPrefs(); refreshShortcuts(); updateWelcome()
            toast("Backup imported")
        } catch (e: Exception) { toast("Import failed: " + e.message) }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }
    override fun onPause() { rememberLast(true); super.onPause(); if (!pip) web.onPause(); CookieManager.getInstance().flush() }
    override fun onStart() {
        super.onStart()
        if (pref("lock", false)) {
            val away = System.currentTimeMillis() - lastLeave > 20000
            if (!unlocked || away) {
                unlocked = false; lockCover.visibility = View.VISIBLE
                if (!authInProgress) promptAuth()
            }
        } else lockCover.visibility = View.GONE
    }
    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        handleIntent(i)
    }
    override fun onDestroy() {
        if (isFinishing && pref("incognito", false)) {
            CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
            web.clearCache(true); web.clearHistory(); WebStorage.getInstance().deleteAllData()
        }
        for (t in tabs) try { t.web.destroy() } catch (e: Exception) { }
        super.onDestroy()
    }
    override fun onResume() { super.onResume(); pip = false; web.onResume() }
    override fun onStop() { super.onStop(); lastLeave = System.currentTimeMillis(); if (pip) { pip = false; web.onPause() } }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (customView != null) enterPip(true) }
    override fun onPictureInPictureModeChanged(isIn: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(isIn, cfg)
        pip = isIn
        if (isIn) hidePip()
    }
}
