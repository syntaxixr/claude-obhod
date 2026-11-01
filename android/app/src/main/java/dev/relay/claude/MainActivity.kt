package dev.relay.claude

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Intent
import android.net.VpnService
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var logo: LogoView
    private lateinit var aurora: AuroraView
    private lateinit var badge: TextView
    private lateinit var enterShine: ShineView
    private lateinit var progressShine: ShineView
    private lateinit var header: TextView
    private lateinit var subheader: TextView
    private lateinit var progress: View
    private lateinit var progressBar: FrameLayout
    private lateinit var enter: Button
    private lateinit var enterSpinner: ProgressBar
    private lateinit var hint: TextView
    private lateinit var webLink: TextView
    private lateinit var vpnOff: TextView
    private lateinit var mailLink: TextView
    /** Ссылка claude.ai снаружи (вход из письма) — откроем, как только всё зелёное. */
    private var pendingLink: String? = null
    private lateinit var items: List<CheckItem>
    private val rowViews = mutableMapOf<String, RowViews>()

    private var runId = 0
    private var checking = false
    private var allOk = false
    private var webWarm = false
    private var enterPulse: ValueAnimator? = null
    private var progressAnim: ValueAnimator? = null

    private val app: RelayApp get() = application as RelayApp

    /** Что сделать, когда защита поднимется (открыть Claude здесь или приложение Claude). */
    private var afterShield: (() -> Unit)? = null
    private var shieldOptional = false

    /** Системное «Разрешить VPN?» — показывается один раз. */
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) startShield() else shieldDeclined()
    }

    /** Android 13+: разрешение на уведомление защиты (сама защита работает и без него). */
    private val notifConsent = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askVpn() }

    private fun claudeInstalled(): Boolean = packageManager.getLaunchIntentForPackage(BuildConfig.VPN_APP) != null

    /** Открываем родное приложение Claude через туннель (иначе — веб-версию внутри). */
    private fun useApp(): Boolean = RelayVpnService.appModeSupported && claudeInstalled()

    private class RowViews(
        val root: View,
        val mark: MarkView,
        val label: TextView,
        val detail: TextView,
        val ms: TextView,
    ) { var shown: Status = Status.WAIT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pendingLink = intent.getStringExtra(WebActivity.EXTRA_LINK)
        items = Checker.items()
        buildUi()
        // личная сборка: ссылка вшита — подключаемся сразу, без вставки.
        // Применяем один раз (и при обновлении поверх старой версии), дальше решают настройки.
        if (BuildConfig.DEFAULT_LINK.isNotBlank() && prefs.appliedDefaultLink != BuildConfig.DEFAULT_LINK) {
            prefs.appliedDefaultLink = BuildConfig.DEFAULT_LINK
            runCatching { RelayLink.parse(BuildConfig.DEFAULT_LINK) }.getOrNull()?.let {
                prefs.host = it.host
                prefs.port = it.port
                prefs.token = it.token
                prefs.allowSelfSigned = BuildConfig.DEFAULT_SELF_SIGNED
            }
        }
        subheader.text = serverLabel()
        val askSetup = {
            if (!prefs.configured) {
                header.text = "Claude Обход"
                hint.text = "Вставь ссылку от своей VDS или адрес SOCKS5 — дальше всё само."
                setEnter(EnterState.SETUP)
                showSettings(firstRun = true)
            }
        }
        if (!prefs.rulesAccepted) showRules(firstRun = true) { askSetup() } else askSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(WebActivity.EXTRA_LINK)?.let { pendingLink = it }
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.configured) return
        // debug-хук для автотеста WebView: adb ... --ez autoenter true --es testUrl https://…
        if (isDebuggable() && intent.getBooleanExtra("autoenter", false)) {
            intent.removeExtra("autoenter")
            openClaude()
            return
        }
        runChecks()
    }

    override fun onPause() {
        super.onPause()
        enterPulse?.cancel()
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun serverLabel(): String = when {
        prefs.host.isBlank() -> "не настроено"
        prefs.isRelay -> "VDS ${prefs.host}${if (prefs.port == 443) "" else ":" + prefs.port}"
        else -> "SOCKS5 ${prefs.host}:${prefs.port}"
    }

    // ---------- UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun round(radius: Int, fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            clipChildren = false // «дышащая» кнопка не обрезается по краям
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        scroll.addView(root)
        aurora = AuroraView(this)
        val stage = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.bg))
            addView(aurora, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        // логотип: огненное кольцо, пока идёт проверка
        logo = LogoView(this)
        root.addView(logo, LinearLayout.LayoutParams(dp(104), dp(104)).apply { bottomMargin = dp(10) })

        // красная плашка — главное, что надо знать
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(99, Palette.alpha(Palette.ACCENT, 0x1A), Palette.alpha(Palette.ACCENT, 0x66))
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.ACCENT) } }
        pill.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(8) })
        badge = TextView(this).apply {
            text = "ТОЛЬКО ЧЕРЕЗ VDS"
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.06f
            setTextColor(Palette.ACCENT)
        }
        pill.addView(badge)
        root.addView(pill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        // точка мигает, как индикатор «в эфире»
        ValueAnimator.ofFloat(1f, 0.2f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }

        header = TextView(this).apply {
            text = "Проверяю подключение"
            textSize = 28f
            typeface = Palette.SERIF
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text))
        }
        root.addView(header)
        subheader = TextView(this).apply {
            text = "не настроено"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.dim))
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(subheader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(22) })

        // карточка с проверками
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(16, Palette.CARD, Palette.LINE)
            setPadding(0, dp(6), 0, dp(6))
        }
        for ((i, it) in items.withIndex()) {
            val rv = makeRow(it)
            rowViews[it.id] = rv
            card.addView(rv.root)
            if (i != items.lastIndex) {
                card.addView(View(this).apply { setBackgroundColor(color(R.color.line)) },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { leftMargin = dp(54); rightMargin = dp(16) })
            }
            // строки выезжают по очереди
            rv.root.alpha = 0f
            rv.root.translationY = dp(12).toFloat()
            rv.root.animate().alpha(1f).translationY(0f).setStartDelay(120L + i * 70L)
                .setDuration(320).setInterpolator(DecelerateInterpolator()).start()
        }
        root.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })

        // полоса прогресса
        progressBar = FrameLayout(this).apply { background = round(99, Palette.LINE) }
        progress = FrameLayout(this).apply { background = bar(Palette.ACCENT_DEEP, Palette.ACCENT) }
        progressShine = ShineView(this, 1400, dp(99).toFloat()).apply { running = true }
        (progress as FrameLayout).addView(progressShine, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        progressBar.addView(progress, FrameLayout.LayoutParams(0, MATCH_PARENT))
        root.addView(progressBar, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).apply { bottomMargin = dp(18) })

        // главная кнопка + крутилка внутри неё
        val enterBox = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        enter = Button(this).apply {
            text = "Проверяю…"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
            stateListAnimator = null
            elevation = 0f
            setOnClickListener { onEnterClick() }
        }
        enterBox.addView(enter, FrameLayout.LayoutParams(MATCH_PARENT, dp(58)))
        enterShine = ShineView(this, 3200, dp(14).toFloat()).apply { elevation = dp(20).toFloat() }
        enterBox.addView(enterShine, FrameLayout.LayoutParams(MATCH_PARENT, dp(58)))
        enter.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
        enterSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT)
            visibility = View.GONE
            elevation = dp(2).toFloat()
        }
        enterBox.addView(enterSpinner, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(20) })
        root.addView(enterBox, LinearLayout.LayoutParams(MATCH_PARENT, dp(58)))

        fun link(t: String, click: () -> Unit) = TextView(this).apply {
            text = t
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.accent))
            setPadding(dp(8), dp(12), dp(8), 0)
            visibility = View.GONE
            setOnClickListener { click() }
        }
        webLink = link("Открыть в приложении Claude (тоже только через VDS)") { if (allOk) confirmClaudeApp() }
        root.addView(webLink, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        vpnOff = link("Выключить защиту") { stopVpn(); vpnOff.postDelayed({ refreshLinks() }, 500) }
        root.addView(vpnOff, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mailLink = link("Вход по почте: открывать ссылку из письма здесь") { MailLinks.advise(this) }
        root.addView(mailLink, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        hint = MarkedText(this).apply {
            text = "Проверяю интернет и связь с VDS…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.dim))
            setPadding(dp(8), dp(12), dp(8), 0)
        }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        fun small(t: String, click: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            textSize = 13f
            setTextColor(Palette.TEXT)
            stateListAnimator = null
            background = round(12, Palette.CARD, Palette.LINE)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { click() }
        }
        tools.addView(small("Проверить") { runChecks() }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { rightMargin = dp(8) })
        tools.addView(small("Настройки") { showSettings(false) }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { rightMargin = dp(8) })
        tools.addView(small("Инструкция") { showRules(false) }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)))
        root.addView(tools)

        // распорка, чтобы подпись прижалась к низу
        root.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply {
            text = "build ${BuildConfig.VERSION_NAME} · весь трафик Claude идёт через ваш сервер"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Palette.FAINT)
            setPadding(0, dp(20), 0, 0)
        })
        // автор: ссылки открываются в браузере телефона
        val author = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        fun authorLink(t: String, url: String) = TextView(this).apply {
            text = t
            textSize = 11f
            setTextColor(Palette.DIM)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { openUrl(url) }
        }
        author.addView(authorLink("Telegram @synaps_ss", AUTHOR_TG))
        author.addView(TextView(this).apply { text = "·"; textSize = 11f; setTextColor(Palette.FAINT) })
        author.addView(authorLink("GitHub syntaxixr", AUTHOR_GITHUB))
        root.addView(author)

        setContentView(stage)
        setEnter(EnterState.CHECKING)
    }

    private fun makeRow(item: CheckItem): RowViews {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        // значок статуса: точка → дуга → рисующаяся ✓ / ✕
        val mark = MarkView(this)
        row.addView(mark, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            text = item.label
            textSize = 15f
            setTextColor(color(R.color.text))
        }
        texts.addView(label)
        val detail = TextView(this).apply {
            text = "ожидание"
            textSize = 12f
            setTextColor(color(R.color.dim))
        }
        texts.addView(detail)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val ms = TextView(this).apply {
            textSize = 11f
            setTextColor(Palette.FAINT)
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(ms)
        return RowViews(row, mark, label, detail, ms)
    }

    /** Показывает состояние строки с анимацией смены статуса. */
    private fun renderRow(item: CheckItem) {
        val rv = rowViews[item.id] ?: return
        rv.detail.text = item.detail.ifBlank { if (item.status == Status.WAIT) "ожидание" else "" }
        rv.detail.setTextColor(color(if (item.status == Status.BAD) R.color.red else R.color.dim))
        rv.ms.text = if (item.ms > 0 && item.status != Status.RUN) "${item.ms} мс" else ""
        if (rv.shown == item.status) return
        rv.shown = item.status
        when (item.status) {
            Status.WAIT -> {
                rv.mark.setState(MarkView.State.WAIT)
                setRowBg(rv.root, null)
                rv.label.typeface = Typeface.DEFAULT
            }
            Status.RUN -> {
                rv.mark.setState(MarkView.State.RUN)
                // текущая проверка подсвечена красным
                val bg = rowGlow(0x16)
                bg.alpha = 0
                setRowBg(rv.root, bg)
                ValueAnimator.ofInt(0, 255).apply {
                    duration = 260
                    addUpdateListener { v -> bg.alpha = v.animatedValue as Int }
                    start()
                }
                rv.label.typeface = Typeface.DEFAULT_BOLD
            }
            Status.OK, Status.BAD -> {
                val ok = item.status == Status.OK
                rv.mark.setState(if (ok) MarkView.State.OK else MarkView.State.BAD)
                rv.label.typeface = Typeface.DEFAULT
                val bg = rv.root.background
                if (ok && bg != null) {
                    ValueAnimator.ofInt(bg.alpha, 0).apply {
                        duration = 420
                        addUpdateListener { v -> bg.alpha = v.animatedValue as Int }
                        withEnd { if (rv.root.background === bg) setRowBg(rv.root, null) }
                        start()
                    }
                } else if (!ok) {
                    setRowBg(rv.root, rowGlow(0x1E, Palette.ERR))
                    rv.root.animate().translationX(dp(6).toFloat()).setDuration(360)
                        .setInterpolator(CycleInterpolator(2f))
                        .withEndAction { rv.root.translationX = 0f }.start()
                }
            }
        }
    }

    private fun ValueAnimator.withEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = f()
    })

    /** Смена фона строки без потери её отступов (фон с insets иначе подменяет padding). */
    private fun setRowBg(v: View, d: android.graphics.drawable.Drawable?) {
        val l = v.paddingLeft; val t = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        v.background = d
        v.setPadding(l, t, r, b)
    }

    /** Подсветка строки внутри карточки — с отступом, чтобы не вылезать за скругление. */
    private fun rowGlow(a: Int, c: Int = Palette.ACCENT) = android.graphics.drawable.InsetDrawable(round(12, Palette.alpha(c, a)), dp(6), dp(2), dp(6), dp(2))

    private fun bar(from: Int, to: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(from, to)).apply {
        cornerRadius = dp(99).toFloat()
    }

    private fun setBadge(t: String) {
        badge.text = t
    }

    private fun animateProgress(percent: Int, ok: Boolean? = null) {
        val target = progressBar.width * percent.coerceIn(0, 100) / 100
        val from = progress.layoutParams.width.coerceAtLeast(0)
        progressAnim?.cancel()
        if (ok != null) {
            progress.background = when {
                ok -> bar(Palette.ACCENT_DEEP, Palette.ACCENT)
                else -> bar(Palette.ERR, Palette.ERR)
            }
        }
        progressAnim = ValueAnimator.ofInt(from, target).apply {
            duration = 420
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress.layoutParams = progress.layoutParams.apply { width = it.animatedValue as Int }
            }
            start()
        }
    }

    private fun startLogoPulse() {
        logo.setState(LogoView.State.CHECKING)
        aurora.setMood(Palette.ACCENT)
        setBadge("ТОЛЬКО ЧЕРЕЗ VDS")
    }

    private fun stopLogoPulse(ok: Boolean) {
        logo.setState(if (ok) LogoView.State.OK else LogoView.State.FAIL)
        aurora.setMood(if (ok) Palette.ACCENT else Palette.ERR)
        setBadge(if (ok) "ГОТОВО · ТОЛЬКО ЧЕРЕЗ VDS" else "НЕТ ПОДКЛЮЧЕНИЯ")
    }

    private enum class EnterState { SETUP, CHECKING, READY, FAILED, CONNECTING }

    private fun glowButton() = round(14, Palette.ACCENT_DEEP)

    private fun setEnter(state: EnterState) {
        enterPulse?.cancel()
        enter.scaleX = 1f; enter.scaleY = 1f
        enter.elevation = 0f
        enter.setTextColor(Palette.TEXT)
        enterShine.running = state == EnterState.READY || state == EnterState.SETUP
        if (android.os.Build.VERSION.SDK_INT >= 28) { enter.outlineSpotShadowColor = Palette.ACCENT; enter.outlineAmbientShadowColor = Palette.ACCENT }
        enterSpinner.visibility = if (state == EnterState.CHECKING || state == EnterState.CONNECTING) View.VISIBLE else View.GONE
        when (state) {
            EnterState.SETUP -> {
                enter.text = "Вставить ссылку"
                enter.isEnabled = true
                enter.background = glowButton()
            }
            EnterState.CHECKING -> {
                enter.text = "Проверяю…"
                enter.isEnabled = false
                enter.background = round(14, Palette.CARD, Palette.LINE)
                enter.setTextColor(Palette.DIM)
            }
            EnterState.READY -> {
                enter.text = "Открыть Claude"
                enter.isEnabled = true
                enter.background = glowButton()
                // кнопка «дышит» красным свечением, приглашая нажать
                enterPulse = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1100
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        val v = it.animatedValue as Float
                        enter.elevation = dp(2) + dp(8) * v
                        if (!enter.isPressed) { enter.scaleX = 1f + 0.015f * v; enter.scaleY = 1f + 0.015f * v }
                    }
                    start()
                }
            }
            EnterState.FAILED -> {
                enter.text = "Повторить проверку"
                enter.isEnabled = true
                enter.background = round(14, Palette.alpha(Palette.ERR, 0x1A), Palette.ERR)
                enter.setTextColor(Palette.ERR)
            }
            EnterState.CONNECTING -> {
                enter.text = "Подключаюсь…"
                enter.isEnabled = false
                enter.background = glowButton()
            }
        }
    }

    private fun onEnterClick() {
        when {
            !prefs.configured -> showSettings(firstRun = true)
            checking -> Unit
            allOk -> openClaude()
            else -> runChecks()
        }
    }

    // ---------- проверки ----------
    private fun runChecks() {
        if (!prefs.configured) return
        if (!app.ensureProxy(prefs)) {
            hint.text = "Не удалось поднять локальный прокси"
            setEnter(EnterState.FAILED)
            return
        }
        val id = ++runId
        checking = true
        allOk = false
        subheader.text = serverLabel()
        header.text = "Проверяю подключение"
        hint.text = "Проверяю интернет и связь с сервером…"
        setEnter(EnterState.CHECKING)
        webLink.visibility = View.GONE
        startLogoPulse()
        items.forEach { it.status = Status.WAIT; it.detail = ""; it.ms = 0; renderRow(it) }
        progress.post { animateProgress(0, ok = true) }

        lifecycleScope.launch {
            val checker = Checker(this@MainActivity, prefs, app.proxyPort)
            checker.runAll(items) { item ->
                val snapshot = item.copy()
                runOnUiThread {
                    if (id != runId) return@runOnUiThread // устаревший прогон
                    renderRow(snapshot)
                    val done = items.count { it.status == Status.OK || it.status == Status.BAD }
                    animateProgress(done * 100 / items.size)
                }
            }
            runOnUiThread { if (id == runId) finishChecks() }
        }
    }

    private fun finishChecks() {
        checking = false
        items.forEach { renderRow(it) }
        val bad = items.filter { it.status != Status.OK }
        allOk = bad.isEmpty()
        stopLogoPulse(allOk)
        if (allOk) {
            val exit = items.first { it.id == "exit" }.detail.substringBefore(" · ")
            header.text = "Всё готово"
            subheader.text = "${serverLabel()} · выход: $exit"
            hint.text = "Claude откроется прямо здесь — только через VDS. Российский IP не засветится."
            animateProgress(100, ok = true)
            setEnter(EnterState.READY)
            refreshLinks()
            warmUpWebView()
            if (pendingLink != null) {
                hint.text = "Открываю ссылку для входа…"
                openClaude()
            }
        } else {
            val first = bad.first()
            header.text = "Нет подключения"
            hint.text = "${first.label}: ${first.detail}"
            animateProgress(100 - bad.size * 100 / items.size, ok = false)
            setEnter(EnterState.FAILED)
        }
    }

    /**
     * Движок WebView (Chromium) при первом создании стартует на главном потоке —
     * на слабых телефонах это заметная пауза. Прогреваем его, пока человек смотрит
     * на зелёные галочки, чтобы нажатие «Подключиться» срабатывало сразу.
     */
    private fun warmUpWebView() {
        if (webWarm) return
        webWarm = true
        enter.postDelayed({
            runCatching { android.webkit.WebView(applicationContext).destroy() }
        }, 700)
    }

    private fun refreshLinks() {
        webLink.visibility = if (allOk && useApp()) View.VISIBLE else View.GONE
        vpnOff.visibility = if (RelayVpnService.running) View.VISIBLE else View.GONE
        mailLink.visibility = if (allOk && !MailLinks.approved(this)) View.VISIBLE else View.GONE
    }

    // ---------- защита: наружу только VDS ----------
    /**
     * Поднимает «защиту» (VPN только для Claude Обход и приложения Claude) и затем
     * выполняет [next]. Если защита уже стоит на текущем прокси — сразу [next].
     * [optional] — можно ли продолжить без защиты, если человек откажет (только для
     * Claude внутри приложения: прокси там всё равно принудительный).
     */
    private fun withShield(optional: Boolean, next: () -> Unit) {
        if (!app.ensureProxy(prefs)) {
            hint.text = "Не удалось поднять локальный прокси"
            return
        }
        if (RelayVpnService.running && RelayVpnService.port == app.proxyPort) {
            next()
            return
        }
        afterShield = next
        shieldOptional = optional
        setEnter(EnterState.CONNECTING)
        hint.text = "Включаю защиту — наружу пойдёт только VDS…"
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifConsent.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        askVpn()
    }

    private fun askVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else startShield()
    }

    private fun startShield() {
        RelayVpnService.lastError = null
        startService(
            Intent(this, RelayVpnService::class.java)
                .setAction(RelayVpnService.ACTION_START)
                .putExtra(RelayVpnService.EXTRA_PORT, app.proxyPort)
        )
        // ждём, пока служба поднимет туннель (до 5 с)
        val started = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                when {
                    RelayVpnService.running -> {
                        refreshLinks()
                        val next = afterShield
                        afterShield = null
                        next?.invoke()
                    }
                    RelayVpnService.lastError != null || System.currentTimeMillis() - started > 5000 -> {
                        hint.text = "Защита не включилась: ${RelayVpnService.lastError ?: "нет ответа"}"
                        setEnter(EnterState.READY)
                        refreshLinks()
                        afterShield = null
                    }
                    else -> enter.postDelayed(this, 150)
                }
            }
        }
        enter.postDelayed(poll, 150)
    }

    private fun shieldDeclined() {
        val next = afterShield
        afterShield = null
        setEnter(EnterState.READY)
        if (!shieldOptional || next == null) {
            hint.text = "Без разрешения на VPN приложение Claude пошло бы напрямую — не открываю."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Открыть без защиты?")
            .setMessage(
                "Claude всё равно пойдёт через VDS, но без защиты страница теоретически может " +
                    "узнать ваш настоящий IP через WebRTC, а DNS-запросы увидит оператор.\n\n" +
                    "Рекомендуем нажать «Открыть Claude» ещё раз и разрешить VPN."
            )
            .setPositiveButton("Открыть без защиты") { _, _ -> next() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun stopVpn() {
        if (!RelayVpnService.running) return
        startService(Intent(this, RelayVpnService::class.java).setAction(RelayVpnService.ACTION_STOP))
        hint.text = "Защита выключена. Приложение Claude теперь ходит напрямую — не открывайте его без защиты."
    }

    // ---------- Claude здесь (главный способ) ----------
    private fun openClaude() = withShield(optional = true) { launchWeb() }

    private fun launchWeb() {
        setEnter(EnterState.CONNECTING)
        hint.text = "Открываю Claude через VDS…"
        val i = Intent(this, WebActivity::class.java).putExtra("port", app.proxyPort)
        pendingLink?.let { i.putExtra(WebActivity.EXTRA_LINK, it) }
        pendingLink = null
        if (isDebuggable()) intent.getStringExtra("testUrl")?.let { i.putExtra("testUrl", it) }
        enter.postDelayed({
            startActivity(i)
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            setEnter(EnterState.READY)
        }, 350)
    }

    // ---------- родное приложение Claude через защиту ----------
    /**
     * Родное приложение Claude видит то, что Claude Обход спрятать не может:
     * страну SIM-карты, часовой пояс и язык телефона. IP при этом скрыт, но
     * по этим признакам можно понять, что телефон в России. Честно предупреждаем.
     */
    private fun confirmClaudeApp() {
        AlertDialog.Builder(this)
            .setTitle("Открыть приложение Claude?")
            .setMessage(
                "IP будет только латвийский — защита не выпустит приложение напрямую.\n\n" +
                    "Но само приложение Claude видит страну SIM-карты и язык телефона — " +
                    "по ним можно понять, что вы в России. Спрятать это мы не можем.\n\n" +
                    "Внутри Claude Обход (кнопка «Открыть Claude») SIM-карта не видна, а язык — " +
                    "«русский, Латвия». Это самый незаметный вариант."
            )
            .setPositiveButton("Открыть всё равно") { _, _ -> openClaudeApp() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openClaudeApp() = withShield(optional = false) { launchClaudeApp() }

    private fun launchClaudeApp() {
        if (!RelayVpnService.claudeAppIncluded) {
            hint.text = "Приложение Claude не найдено — открываю Claude здесь"
            launchWeb()
            return
        }
        // если Claude уже висел в фоне со старыми (прямыми) соединениями — перезапускаем его
        runCatching {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(BuildConfig.VPN_APP)
        }
        val launch = packageManager.getLaunchIntentForPackage(BuildConfig.VPN_APP)
        if (launch == null) {
            hint.text = "Приложение Claude не найдено — открываю Claude здесь"
            launchWeb()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        setEnter(EnterState.READY)
        hint.text = "Защита включена — приложение Claude ходит только через VDS."
        refreshLinks()
    }

    // ---------- правила (показываются при первом запуске) ----------
    /**
     * Короткие правила, как пользоваться, чтобы не засветиться и не словить проблем.
     * При первом запуске их нельзя пропустить — только «Понятно».
     */
    private fun showRules(firstRun: Boolean, then: (() -> Unit)? = null) {
        Guide.show(this, firstRun) {
            prefs.rulesAccepted = true
            then?.invoke()
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    // ---------- настройки ----------
    /**
     * Подключение: либо своя VDS с сервером claude-relay (ссылка из install.sh),
     * либо любой обычный SOCKS5-прокси (адрес от провайдера).
     */
    private fun showSettings(firstRun: Boolean, prefill: String? = null, prefillMode: String? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        var mode = prefillMode ?: prefs.mode

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 12f
            setTextColor(color(R.color.dim))
            setPadding(0, dp(10), 0, dp(4))
        }

        // выбор режима
        val group = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        fun radio(id: Int, t: String) = android.widget.RadioButton(this).apply {
            this.id = id
            text = t
            textSize = 14f
            setTextColor(color(R.color.text))
            buttonTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
        }
        val rRelay = radio(1001, "Своя VDS (сервер Claude Обход) — рекомендуется")
        val rSocks = radio(1002, "Обычный SOCKS5-прокси")
        group.addView(rRelay)
        group.addView(rSocks)
        box.addView(label("Через что подключаться"))
        box.addView(group)

        val explain = TextView(this).apply {
            textSize = 12.5f
            setTextColor(color(R.color.dim))
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.1f)
        }
        box.addView(explain)

        val linkLabel = label("")
        box.addView(linkLabel)
        val linkField = EditText(this).apply {
            textSize = 14f
            setTextColor(color(R.color.text))
            setHintTextColor(Palette.FAINT)
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1F1E1D"))
                setStroke(dp(1), color(R.color.line))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(linkField)

        // кнопка «Вставить из буфера» — чтобы не целиться пальцем в поле
        box.addView(Button(this).apply {
            text = "Вставить из буфера"
            isAllCaps = false
            textSize = 13f
            setTextColor(color(R.color.text))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), color(R.color.line))
            }
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    ?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
                if (clip.isEmpty()) Toast.makeText(this@MainActivity, "Буфер пуст", Toast.LENGTH_SHORT).show()
                else linkField.setText(clip)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        val self = android.widget.CheckBox(this).apply {
            text = "Разрешить самоподписанный сертификат (сервер ставили по IP)"
            textSize = 12f
            setTextColor(color(R.color.dim))
            isChecked = prefs.allowSelfSigned
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(self)

        val guide = TextView(this).apply {
            text = "Как поставить свой сервер — инструкция на GitHub"
            textSize = 12.5f
            setTextColor(color(R.color.accent))
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { openUrl(GITHUB_REPO) }
        }
        box.addView(guide)

        fun currentFor(m: String): String = when {
            prefill != null && (prefillMode ?: prefs.mode) == m -> prefill
            !prefs.configured || prefs.mode != m -> ""
            m == Prefs.MODE_SOCKS -> SocksLink(prefs.host, prefs.port, prefs.user, prefs.token).toString()
            else -> RelayLink(prefs.host, prefs.port, prefs.token).toString()
        }

        fun applyMode(m: String) {
            mode = m
            val relay = m != Prefs.MODE_SOCKS
            explain.text = if (relay) {
                "Ссылку socks5://relay:ТОКЕН@адрес печатает сервер Claude Обход при установке на вашу VDS. " +
                    "Соединение шифруется и снаружи выглядит как обычный HTTPS."
            } else {
                "Подойдёт адрес от любого провайдера SOCKS5. Внимание: обычный SOCKS5 не шифруется — " +
                    "провайдер видит, к каким сайтам вы подключаетесь, и может такой прокси блокировать. " +
                    "Надёжнее своя VDS."
            }
            linkLabel.text = if (relay) "Ссылка от вашего сервера" else "Адрес SOCKS5-прокси"
            linkField.hint = if (relay) "socks5://relay:ТОКЕН@relay.example.com" else "socks5://логин:пароль@хост:порт  или  хост:порт:логин:пароль"
            linkField.setText(currentFor(m))
            self.visibility = if (relay) View.VISIBLE else View.GONE
        }
        group.setOnCheckedChangeListener { _, id -> applyMode(if (id == 1002) Prefs.MODE_SOCKS else Prefs.MODE_RELAY) }
        group.check(if (mode == Prefs.MODE_SOCKS) 1002 else 1001)
        applyMode(mode)

        val scroll = ScrollView(this).apply { addView(box) }

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (firstRun) "Подключение" else "Настройки")
            .setView(scroll)
            .setPositiveButton("Сохранить") { _, _ ->
                val raw = linkField.text.toString()
                try {
                    if (mode == Prefs.MODE_SOCKS) {
                        val l = SocksLink.parse(raw)
                        prefs.host = l.host
                        prefs.port = l.port
                        prefs.user = l.user
                        prefs.token = l.pass
                        prefs.allowSelfSigned = false
                    } else {
                        val l = RelayLink.parse(raw)
                        prefs.host = l.host
                        prefs.port = l.port
                        prefs.user = ""
                        prefs.token = l.token
                        prefs.allowSelfSigned = self.isChecked
                    }
                    prefs.mode = mode
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    showSettings(firstRun, raw, mode)
                    return@setPositiveButton
                }
                stopVpn()
                app.stopProxy()
                subheader.text = serverLabel()
                runChecks()
            }
            .setNegativeButton("Отмена", null)
            .create()
        dlg.show()
    }

    companion object {
        const val GITHUB_REPO = "https://github.com/syntaxixr/claude-obhod"
        const val AUTHOR_TG = "https://t.me/synaps_ss"
        const val AUTHOR_GITHUB = "https://github.com/syntaxixr"
    }
}
