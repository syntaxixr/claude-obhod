package dev.relay.claude

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Гайд «Как пользоваться» — полноэкранный, в стиле Claude.
 * Три шага, правила «можно / нельзя», честно о рисках. При первом запуске пропустить нельзя.
 */
object Guide {

    fun show(a: Activity, firstRun: Boolean, onDone: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun round(r: Int, fill: Int, stroke: Int? = null) = GradientDrawable().apply {
            cornerRadius = dp(r).toFloat(); setColor(fill); if (stroke != null) setStroke(dp(1), stroke)
        }

        /** **жирное** в тексте → терракотовое и жирное. */
        fun rich(src: String, base: Int = Palette.DIM): CharSequence {
            val out = SpannableStringBuilder()
            val parts = src.split("**")
            for ((i, p) in parts.withIndex()) {
                val start = out.length
                out.append(p)
                if (i % 2 == 1) {
                    out.setSpan(ForegroundColorSpan(Palette.TEXT), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            return out
        }

        fun text(t: CharSequence, size: Float, color: Int, face: Typeface = Typeface.DEFAULT) = TextView(a).apply {
            text = t; textSize = size; setTextColor(color); typeface = face
            setLineSpacing(0f, 1.18f)
        }

        val dialog = Dialog(a, android.R.style.Theme_Material_NoActionBar)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(120))
        }
        val animated = mutableListOf<View>()

        // шапка
        col.addView(LogoView(a), LinearLayout.LayoutParams(dp(76), dp(76)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(text(tr("Как пользоваться", "How to use"), 32f, Palette.TEXT, Palette.SERIF).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
        })
        col.addView(text(tr("Три шага — и Claude работает через твой сервер.\nЗаймёт минуту.", "Three steps and Claude runs through your server.\nTakes a minute."), 15f, Palette.DIM).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(4))
        })

        fun section(t: String) = text(t, 11.5f, Palette.ACCENT, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.14f
            setPadding(dp(4), dp(26), 0, dp(10))
        }

        // ---- три шага ----
        col.addView(section(tr("ТРИ ШАГА", "THREE STEPS")))
        val steps = listOf(
            Triple("1", tr("Подключись", "Connect"),
                tr("Вставь ссылку от своей VDS или адрес SOCKS5. Приложение само проверит связь — нужно **5 галочек**.", "Paste the link from your VDS or a SOCKS5 address. The app checks the connection itself — you need **5 checkmarks**.")),
            Triple("2", tr("Открой Claude", "Open Claude"),
                tr("Жми **«Открыть Claude»**. Включится защита: наружу пойдёт **только IP сервера**, твой настоящий не засветится.", "Tap **\"Open Claude\"**. Protection turns on: **only the server's IP** goes out, your real one stays hidden.")),
            Triple("3", tr("Войди в аккаунт", "Sign in"),
                tr("Через Google или по почте — всё откроется **внутри приложения**. Для почты один раз разреши ссылки claude.ai — приложение подскажет.", "With Google or by email — everything opens **inside the app**. For email, allow claude.ai links once — the app will show you how.")),
        )
        for ((n, title, body) in steps) {
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                background = round(18, Palette.CARD, Palette.LINE)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            val num = text(n, 17f, Palette.BG, Palette.SERIF).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create(Palette.SERIF, Typeface.BOLD)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.ACCENT) }
            }
            card.addView(num, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(14) })
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(title, 17f, Palette.TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
            texts.addView(text(rich(body), 14.5f, Palette.DIM).apply { setPadding(0, dp(4), 0, 0) })
            card.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            col.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            animated += card
        }

        // ---- правила ----
        col.addView(section(tr("ЧТОБЫ НЕ ЗАБАНИЛИ", "DON'T GET BANNED")))
        val rules = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = round(18, Palette.CARD, Palette.LINE)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        val list = listOf(
            true to tr("**Всегда один сервер.** Один аккаунт — один IP, без прыжков между VPN.", "**Always the same server.** One account, one IP, no hopping between VPNs."),
            true to tr("**Сервер в стране, где есть Claude** — ЕС, США, Британия.", "**A server in a country where Claude works** — EU, US, UK."),
            false to tr("**Не заходи в этот аккаунт без приложения** — ни с телефона, ни с компа напрямую.", "**Don't use this account outside the app** — not on the phone, not on a computer directly."),
            false to tr("**Не раздавай свою ссылку** и не бери бесплатные прокси: чужие действия лягут на твой аккаунт.", "**Don't share your link** and don't use free proxies: what others do gets tied to your account."),
        )
        for ((i, pair) in list.withIndex()) {
            val (ok, body) = pair
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, dp(12))
            }
            val c = if (ok) Palette.ACCENT else Palette.ERR
            row.addView(text(if (ok) "✓" else "✕", 13f, c).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.alpha(c, 0x2A)) }
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) })
            row.addView(text(rich(body), 14.5f, Palette.DIM), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            rules.addView(row)
            if (i != list.lastIndex) rules.addView(View(a).apply { setBackgroundColor(Palette.LINE) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { leftMargin = dp(40) })
        }
        col.addView(rules, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        animated += rules

        // ---- риски ----
        col.addView(section(tr("ЧЕСТНО", "HONESTLY")))
        val risk = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(18, Palette.alpha(Palette.ERR, 0x14), Palette.alpha(Palette.ERR, 0x55))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        risk.addView(text("!", 15f, Palette.ERR).apply {
            gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.alpha(Palette.ERR, 0x2A)) }
        }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) })
        risk.addView(text(rich(
            tr("Claude официально не работает в России. Заходить через прокси — **против правил Anthropic**, аккаунт могут заблокировать. " +
                "Приложение прячет IP, но решение за тобой. Проект не связан с Anthropic.",
                "Claude isn't officially available in your country. Using it through a proxy is **against Anthropic's rules**, and the account can be blocked. " +
                "The app hides your IP, but the decision is yours. This project isn't affiliated with Anthropic.")), 14f, Palette.DIM),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(risk, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        animated += risk

        // автор
        val author = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) }
        fun link(t: String, url: String) = text(t, 13f, Palette.DIM).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        }
        author.addView(text(tr("автор", "author"), 13f, Palette.FAINT))
        author.addView(link("Telegram @synaps_ss", MainActivity.AUTHOR_TG))
        author.addView(text("·", 13f, Palette.FAINT))
        author.addView(link("GitHub", MainActivity.AUTHOR_GITHUB))
        col.addView(author)

        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(col) }

        // кнопка внизу, поверх лёгкого затемнения
        val bottom = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Palette.BG, Palette.BG, Color.TRANSPARENT))
        }
        val go = Button(a).apply {
            text = if (firstRun) tr("Понятно, поехали", "Got it, let's go") else tr("Понятно", "Got it")
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
            stateListAnimator = null
            background = round(14, Palette.ACCENT_DEEP)
            setOnClickListener { dialog.dismiss(); onDone() }
        }
        bottom.addView(go, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))

        val root = FrameLayout(a).apply {
            setBackgroundColor(Palette.BG)
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            // переключатель языка в правом верхнем углу
            addView(text(Lang.switchLabel, 13f, Palette.DIM).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = round(99, Palette.CARD, Palette.LINE)
                setOnClickListener {
                    dialog.dismiss()
                    if (a is MainActivity) a.switchLanguage() else { Lang.set(a, !Lang.en); a.recreate() }
                }
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(18); rightMargin = dp(16)
            })
        }
        dialog.setContentView(root)
        dialog.setCancelable(!firstRun)
        dialog.window?.apply {
            setLayout(MATCH_PARENT, MATCH_PARENT)
            statusBarColor = Palette.BG
            navigationBarColor = Palette.BG
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }

        // карточки выезжают по очереди — когда окно уже на экране
        for (v in animated) { v.alpha = 0f; v.translationY = dp(18).toFloat() }
        go.alpha = 0f; go.scaleX = 0.9f; go.scaleY = 0.9f
        dialog.setOnShowListener {
            // ValueAnimator сам выставляет свойства и перерисовывает — работает в любом окне
            fun rise(v: View, delay: Long, overshoot: Boolean = false) = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = delay
                duration = 420
                interpolator = if (overshoot) OvershootInterpolator(2f) else DecelerateInterpolator()
                addUpdateListener {
                    val k = it.animatedValue as Float
                    v.alpha = k.coerceIn(0f, 1f)
                    if (overshoot) { v.scaleX = 0.9f + 0.1f * k; v.scaleY = 0.9f + 0.1f * k }
                    else v.translationY = dp(18) * (1f - k)
                    v.invalidate(); root.invalidate()
                }
                start()
            }
            for ((i, v) in animated.withIndex()) rise(v, 120L + i * 90L)
            rise(go, 520, overshoot = true)
        }

        dialog.show()
    }
}
