package dev.relay.claude

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors

/**
 * Сам Claude, открытый в WebView. Весь трафик WebView принудительно идёт
 * через локальный прокси → VDS (ProxyController). Прямого выхода нет:
 *  • прокси без запасного DIRECT — если VDS недоступна, страница просто не грузится;
 *  • override прокси не снимается никогда (фоновые запросы не уйдут напрямую);
 *  • WebRTC вырезан до загрузки страницы — он умеет светить настоящий IP в обход прокси;
 *  • скачивание файлов идёт тоже через VDS, а не через системный загрузчик;
 *  • любые веб-ссылки (вход Google/Apple, оплата, чужие сайты) открываются внутри, через VDS —
 *    в браузер телефона не уходит ничего; всплывающие окна входа — отдельный WebView поверх;
 *  • ссылка для входа из письма (claude.ai/magic-link) открывается здесь же.
 */
class WebActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var popup: WebView? = null
    private var popupBox: View? = null
    /** Прокси уже стоит — до этого ничего не грузим, чтобы не уйти напрямую. */
    private var proxyReady = false
    private var pendingLink: String? = null
    private lateinit var overlay: LinearLayout
    private lateinit var overlayText: TextView
    private lateinit var overlaySpinner: View
    private lateinit var retryBox: LinearLayout
    private lateinit var topBar: ProgressBar
    private var overlayHidden = false
    private var proxyPort: Int = 0
    private val executor = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()

    // прикрепление файлов/фото к сообщению
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        filePathCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        filePathCallback = null
    }

    // голосовой ввод
    private var pendingMic: PermissionRequest? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val req = pendingMic ?: return@registerForActivityResult
        pendingMic = null
        if (ok) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
    }

    companion object {
        /** Домены, которые обязаны открываться внутри (через VDS). */
        private val INTERNAL = listOf(
            // Anthropic / Claude — все известные домены
            "claude.ai", "claude.com", "anthropic.com", "claude.site", "claudeusercontent.com",
            "claudemcpclient.com", "servd-anthropic-website.b-cdn.net",
            // вход и оплата
            "google.com", "googleusercontent.com", "gstatic.com", "googleapis.com", "accounts.google.com",
            "apple.com", "appleid.apple.com", "stripe.com", "stripe.network", "link.com",
            // капча и защита
            "cloudflare.com", "challenges.cloudflare.com", "hcaptcha.com", "recaptcha.net",
            // сервисы самого сайта
            "sentry.io", "datadoghq.com", "intercom.io", "intercomcdn.com", "statsig.com", "segment.io",
        )

        fun isInternal(host: String): Boolean =
            INTERNAL.any { host == it || host.endsWith(".$it") }

        /** Ссылка claude.ai, которую надо открыть вместо главной (например, вход из письма). */
        const val EXTRA_LINK = "link"

        /** Экран с Claude открыт (и прокси на нём уже стоит). */
        @Volatile var alive = false

        /** Ссылка для входа из письма Claude. */
        val MAGIC = Regex("""https://(?:www\.)?claude\.ai/magic-link[^\s"'<>]*""")

    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proxyPort = intent.getIntExtra("port", 0)
        if (proxyPort == 0) {
            finish()
            return
        }
        alive = true

        // в тестовой (debug) сборке страницу можно смотреть через chrome://inspect
        if (isDebuggable()) WebView.setWebContentsDebuggingEnabled(true)
        (application as RelayApp).applyLocale() // Android мог сбросить язык после смены настроек
        web = WebView(this)
        setupWebView(web)

        web.addJavascriptInterface(BlobSaver(), "RelayDownloads")
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                topBar.progress = newProgress
                if (newProgress >= 100) topBar.animate().alpha(0f).setDuration(300).start()
                else if (topBar.alpha < 1f) topBar.animate().alpha(1f).setDuration(150).start()
                // страница почти готова — убираем заставку, не дожидаясь всех картинок
                if (newProgress >= 70) hideOverlay()
            }

            /**
             * Всплывающие окна (вход через Google/Apple, оплата) — отдельный WebView поверх
             * Claude, тоже через VDS. Раньше окно отдавалось этому же WebView, и Android
             * падал («Parent WebView cannot host its own popup window») — приложение
             * перезапускалось при нажатии «Войти через Google».
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = openPopup()
                resultMsg.sendToTarget()
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, false, false)
            }

            /** Скрепка в Claude: выбрать файлы/фото. */
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean = chooseFiles(callback, params)

            /** Голосовой ввод: только микрофон, камеру и прочее не даём. */
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val host = req.origin?.host ?: ""
                if (!isInternal(host) || PermissionRequest.RESOURCE_AUDIO_CAPTURE !in req.resources) {
                    req.deny()
                    return
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingMic = req
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                keepInside(view, request)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && url.startsWith("http")) hideOverlay()
                if (url != null && Uri.parse(url).path?.startsWith("/login") == true) maybeAdviseMailLinks()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                val why = when (error?.errorCode) {
                    ERROR_PROXY_AUTHENTICATION, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_HOST_LOOKUP ->
                        "Нет связи с VDS. Проверьте интернет или что сервер на VDS запущен."
                    else -> "Не удалось открыть Claude: ${error?.description ?: "ошибка сети"}"
                }
                showOverlayError(why)
            }
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#262624"))
            addView(web)
            addView(buildOverlay(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            topBar = ProgressBar(this@WebActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D97757"))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            }
            addView(topBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(3)))
        }
        setContentView(root)

        applyProxy()
    }

    // ---------- навигация: всё открывается внутри, через VDS ----------

    /**
     * Любая веб-ссылка (claude.ai, вход через Google/Apple, оплата, чужие сайты) открывается
     * здесь же, через VDS. В браузер телефона не уходит ничего — там был бы российский IP.
     * Наружу отдаём только не-веб ссылки: mailto:, tel:, магазин приложений.
     */
    private fun keepInside(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        if (!request.isForMainFrame) return false
        val scheme = url.scheme?.lowercase() ?: return false
        if (scheme == "https" || scheme == "http" || scheme == "about" || scheme == "blob" || scheme == "data") return false
        if (scheme == "intent") {
            // intent://…#Intent;…;S.browser_fallback_url=https…;end — веб-вариант открываем внутри
            val i = runCatching { Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
            val fallback = i?.getStringExtra("browser_fallback_url")
            if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                view?.loadUrl(fallback)
                return true
            }
            if (i != null) {
                i.component = null
                i.selector = null
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                runCatching { startActivity(i) }
            }
            return true
        }
        // почта, телефон, Google Play — системе
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)) }
        return true
    }

    /** Окно входа Google/Apple и т.п.: отдельный WebView поверх, с кнопкой «Закрыть». */
    private fun openPopup(): WebView {
        closePopup()
        val p = WebView(this)
        setupWebView(p)
        p.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                keepInside(view, request)
        }
        p.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) = closePopup()
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?,
            ) { callback?.invoke(origin, false, false) }
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean = chooseFiles(callback, params)
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#262624"))
            isClickable = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(6), dp(6))
            setBackgroundColor(Color.parseColor("#1F1E1D"))
        }
        bar.addView(TextView(this).apply {
            text = "Вход · через VDS"
            setTextColor(Color.parseColor("#A6A39A"))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Закрыть"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#D97757"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { closePopup() }
        })
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        popup = p
        popupBox = box
        return p
    }

    private fun closePopup() {
        val p = popup ?: return
        popup = null
        popupBox?.let { root.removeView(it) }
        popupBox = null
        runCatching { p.stopLoading(); p.destroy() }
    }

    private fun chooseFiles(callback: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        return try {
            val i = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            pickFiles.launch(Intent.createChooser(i, "Прикрепить"))
            true
        } catch (_: Exception) {
            filePathCallback = null
            false
        }
    }

    // ---------- ссылка для входа из письма ----------

    /** Ссылку из письма (claude.ai/magic-link…) прислали сюда — открываем в этом же Claude. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val link = linkFrom(intent) ?: return
        openLink(link)
    }

    private fun openLink(link: String) {
        closePopup()
        if (proxyReady && this::web.isInitialized) web.loadUrl(link) else pendingLink = link
    }

    private fun linkFrom(i: Intent?): String? {
        val s = i?.getStringExtra(EXTRA_LINK) ?: return null
        val u = Uri.parse(s)
        return if (u.scheme == "https" && isInternal(u.host ?: "")) s else null
    }

    /**
     * Если человек скопировал ссылку для входа из письма (долгое нажатие → «Копировать»)
     * и вернулся сюда — открываем её сами. Один раз на ссылку.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !this::web.isInitialized) return
        val clip = runCatching {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        }.getOrNull() ?: return
        val m = MAGIC.find(clip) ?: return
        val link = m.value
        val sp = getSharedPreferences("web", MODE_PRIVATE)
        if (sp.getString("lastMagic", null) == link) return
        sp.edit().putString("lastMagic", link).apply()
        toast("Открываю ссылку для входа из буфера")
        openLink(link)
    }

    /**
     * Android 12+ сам не отдаёт ссылки claude.ai стороннему приложению — по умолчанию
     * ссылка из письма открылась бы в браузере телефона (и с российским IP).
     * Один раз подсказываем включить «Открывать поддерживаемые ссылки».
     */
    private fun maybeAdviseMailLinks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (linksApproved()) return
        val sp = getSharedPreferences("web", MODE_PRIVATE)
        if (sp.getBoolean("mailAdviceShown", false)) return
        sp.edit().putBoolean("mailAdviceShown", true).apply()
        MailLinks.advise(this)
    }

    private fun linksApproved(): Boolean = MailLinks.approved(this)

    // ---------- общая настройка WebView (основного и всплывающих окон) ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(w: WebView) {
        val settings: WebSettings = w.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // настоящий Chrome той же версии, что и движок, без пометки «WebView» —
        // меньше поводов для проверок Cloudflare и отказов Google-входа
        settings.userAgentString = WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace(Regex("Version/\\S+ "), "")
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = true // для прикрепления фото из галереи
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        // не сообщаем сайтам, что это приложение (заголовок X-Requested-With)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()) }
        }
        // до загрузки любой страницы вырезаем WebRTC (второй слой после сетевой защиты)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(w, PrivacyScript.SCRIPT, setOf("*")) }
        }
        // геолокацию сайтам не даём никогда
        @Suppress("DEPRECATION")
        settings.setGeolocationEnabled(false)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }

    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- скачивание файлов — тоже через VDS ----------
    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        when {
            url.startsWith("blob:") -> {
                // blob живёт только внутри страницы — достаём его скриптом
                val js = """
                    (function(){
                      fetch(${quote(url)}).then(function(r){ return r.blob(); }).then(function(b){
                        var fr = new FileReader();
                        fr.onloadend = function(){
                          var s = String(fr.result); var i = s.indexOf(',');
                          RelayDownloads.save(s.substring(i + 1), b.type || ${quote(mimeType ?: "")}, ${quote(name)});
                        };
                        fr.readAsDataURL(b);
                      }).catch(function(e){ RelayDownloads.fail(String(e)); });
                    })();
                """.trimIndent()
                web.evaluateJavascript(js, null)
            }
            url.startsWith("data:") -> {
                val comma = url.indexOf(',')
                val meta = url.substring(5, comma.coerceAtLeast(5))
                val payload = url.substring(comma + 1)
                val bytes = if (meta.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT)
                else Uri.decode(payload).toByteArray()
                saveBytes(bytes, meta.substringBefore(';').ifBlank { mimeType ?: "application/octet-stream" }, name)
            }
            url.startsWith("http") -> {
                toast("Скачиваю через VDS: $name")
                io.execute {
                    try {
                        val c = URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))) as HttpURLConnection
                        c.connectTimeout = 20000
                        c.readTimeout = 60000
                        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent)
                        val code = c.responseCode
                        if (code !in 200..299) throw IllegalStateException("HTTP $code")
                        val bytes = c.inputStream.use { it.readBytes() }
                        c.disconnect()
                        runOnUiThread { saveBytes(bytes, mimeType ?: c.contentType ?: "application/octet-stream", name) }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Не скачалось: ${e.message}") }
                    }
                }
            }
            else -> toast("Этот тип ссылки скачать нельзя")
        }
    }

    private inner class BlobSaver {
        @JavascriptInterface
        fun save(base64: String, mime: String, name: String) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            runOnUiThread {
                if (bytes == null) toast("Не скачалось: битые данные")
                else saveBytes(bytes, mime.ifBlank { "application/octet-stream" }, name)
            }
        }

        @JavascriptInterface
        fun fail(why: String) { runOnUiThread { toast("Не скачалось: $why") } }
    }

    private fun saveBytes(bytes: ByteArray, mime: String, rawName: String) {
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "claude-file" }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Claude")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("нет доступа к «Загрузкам»")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                toast("Сохранено: Загрузки/Claude/$name")
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Claude").apply { mkdirs() }
                File(dir, name).writeBytes(bytes)
                toast("Сохранено: ${dir.absolutePath}/$name")
            }
        } catch (e: Exception) {
            toast("Не сохранилось: ${e.message}")
        }
    }

    private fun quote(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- заставка ----------
    /** Заставка поверх WebView: крутилка, пока Claude грузится через VDS. */
    private fun buildOverlay(): View {
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#262624"))
            isClickable = true
        }
        // звёздочка Claude «дышит», пока страница грузится
        overlaySpinner = LogoView(this)
        overlay.addView(overlaySpinner, LinearLayout.LayoutParams(dp(88), dp(88)))
        overlayText = TextView(this).apply {
            text = "Открываю Claude через VDS…"
            textSize = 20f
            typeface = Palette.SERIF
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FAF9F5"))
            setPadding(dp(32), dp(20), dp(32), 0)
        }
        overlay.addView(overlayText)
        overlay.addView(TextView(this).apply {
            text = "первый вход может занять несколько секунд"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#A6A39A"))
            setPadding(dp(32), dp(6), dp(32), 0)
        })
        retryBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(24), 0, 0)
        }
        fun btn(t: String, fill: String, click: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setTextColor(Color.WHITE)
            stateListAnimator = null
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor(fill))
            }
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { click() }
        }
        retryBox.addView(btn("Повторить", "#C96442") { retry() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { rightMargin = dp(10) })
        retryBox.addView(btn("Назад", "#30302E") { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
        overlay.addView(retryBox)
        return overlay
    }

    private fun hideOverlay() {
        if (overlayHidden || retryBox.visibility == View.VISIBLE) return
        overlayHidden = true
        overlay.animate().alpha(0f).setDuration(350).withEndAction { overlay.visibility = View.GONE }.start()
    }

    private fun showOverlayError(why: String) {
        overlayHidden = false
        overlay.animate().cancel()
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlaySpinner.visibility = View.GONE
        overlayText.text = why
        retryBox.visibility = View.VISIBLE
    }

    private fun retry() {
        retryBox.visibility = View.GONE
        overlaySpinner.visibility = View.VISIBLE
        overlayText.text = "Открываю Claude через VDS…"
        web.loadUrl(web.url?.takeIf { it.startsWith("http") } ?: startUrl())
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun startUrl(): String =
        linkFrom(intent)
            ?: (if (isDebuggable()) intent.getStringExtra("testUrl") else null)
            ?: "https://claude.ai/new"

    /**
     * Принудительный прокси для всех запросов WebView (на весь процесс).
     * Без запасного DIRECT и без снятия при закрытии экрана.
     */
    private fun applyProxy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val cfg = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$proxyPort")
                .build()
            ProxyController.getInstance().setProxyOverride(cfg, executor) {
                runOnUiThread {
                    proxyReady = true
                    web.loadUrl(pendingLink ?: startUrl())
                    pendingLink = null
                }
            }
        } else {
            // Совсем старый WebView — прокси не поддерживает. Не грузим ничего, чтобы не уйти напрямую.
            showOverlayError("Android System WebView слишком старый и не умеет прокси.\nОбновите его в Google Play — иначе Claude пошёл бы напрямую.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val p = popup
        if (p != null) {
            if (p.canGoBack()) p.goBack() else closePopup()
            return
        }
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        // прокси НЕ снимаем: так никакой фоновый запрос страницы не уйдёт мимо VDS
        alive = false
        closePopup()
        executor.shutdown()
        io.shutdown()
        try {
            if (this::web.isInitialized) {
                web.stopLoading()
                web.destroy()
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
