package dev.relay.claude

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Палитра как у Claude (тёмная тема claude.ai): тёплый графит, кремовый текст,
 * терракотовый акцент. Красным (ERR) — только ошибки.
 */
object Palette {
    val BG = Color.parseColor("#262624")
    val CARD = Color.parseColor("#30302E")
    val LINE = Color.parseColor("#3E3E38")
    val TEXT = Color.parseColor("#FAF9F5")
    val DIM = Color.parseColor("#A6A39A")
    val FAINT = Color.parseColor("#75736C")
    val ACCENT = Color.parseColor("#D97757")
    val ACCENT_DEEP = Color.parseColor("#C96442")
    val ERR = Color.parseColor("#E5534B")
    val GRAY = Color.parseColor("#5A5852")

    /** Шрифт заголовков с засечками — как приветствие в Claude. */
    val SERIF: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

    fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** Бесконечный цикл 0..1, перерисовывает View на каждом кадре. */
private fun loop(view: View, ms: Long, onTick: (Float) -> Unit) = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = ms
    repeatCount = ValueAnimator.INFINITE
    interpolator = LinearInterpolator()
    addUpdateListener { onTick(it.animatedValue as Float); view.invalidate() }
}

/** Фон: ровный графит Claude и едва заметное тёплое свечение сверху, которое медленно плывёт. */
class AuroraView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var mood = Palette.ACCENT
    private var moodAnim: ValueAnimator? = null
    private val anim = loop(this, 20000) { t = it }

    fun setMood(target: Int) {
        moodAnim?.cancel()
        moodAnim = ValueAnimator.ofObject(ArgbEvaluator(), mood, target).apply {
            duration = 900
            addUpdateListener { mood = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); moodAnim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val a = (2 * PI * t).toFloat()
        val cx = w * (0.5f + 0.12f * sin(a)); val cy = h * (0.08f + 0.03f * cos(a)); val r = w * 0.9f
        p.shader = RadialGradient(cx, cy, r, Palette.alpha(mood, 0x2A), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
    }
}

/**
 * Звёздочка Claude: 12 лучей разной длины.
 * Проверка — лучи «дышат» волной и звезда медленно вращается (как Claude, когда думает).
 * Готово — звезда раскрывается с пружинкой. Ошибка — краснеет, сжимается и вздрагивает.
 */
class LogoView(ctx: Context) : View(ctx) {
    enum class State { CHECKING, OK, FAIL }

    var state = State.CHECKING
        private set
    private val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var wave = 0f
    private var turn = 0f
    private var calm = 0f          // 0 — «дышит», 1 — спокойная полная звезда
    private var shrink = 0f        // ошибка: лучи короче
    private var color = Palette.ACCENT
    private val waveAnim = loop(this, 1600) { wave = it }
    private val turnAnim = loop(this, 9000) { turn = it }

    private val lengths = floatArrayOf(1f, .78f, .93f, .70f, .97f, .82f, 1f, .74f, .90f, .72f, .95f, .80f)
    private val jitter = floatArrayOf(0f, 4f, -3f, 2f, -4f, 3f, -2f, 4f, -3f, 2f, -4f, 1f)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); waveAnim.start(); turnAnim.start() }
    override fun onDetachedFromWindow() { waveAnim.cancel(); turnAnim.cancel(); super.onDetachedFromWindow() }

    private fun animate(from: Float, to: Float, ms: Long, set: (Float) -> Unit) =
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms; interpolator = DecelerateInterpolator()
            addUpdateListener { set(it.animatedValue as Float); invalidate() }
            start()
        }

    fun setState(s: State) {
        if (s == state && s == State.CHECKING) return
        state = s
        val target = if (s == State.FAIL) Palette.ERR else Palette.ACCENT
        ValueAnimator.ofObject(ArgbEvaluator(), color, target).apply {
            duration = 400
            addUpdateListener { color = it.animatedValue as Int; invalidate() }
            start()
        }
        when (s) {
            State.CHECKING -> { animate(calm, 0f, 400) { calm = it }; animate(shrink, 0f, 400) { shrink = it } }
            State.OK -> {
                animate(calm, 1f, 500) { calm = it }
                animate(shrink, 0f, 300) { shrink = it }
                scaleX = 0.7f; scaleY = 0.7f
                animate().scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(OvershootInterpolator(2.2f)).start()
            }
            State.FAIL -> {
                animate(calm, 1f, 400) { calm = it }
                animate(shrink, 1f, 400) { shrink = it }
                animate().translationX(context.dpf(7f)).setDuration(420).setInterpolator(CycleInterpolator(3f))
                    .withEndAction { translationX = 0f }.start()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val R = min(width, height) * 0.40f

        // мягкое тёплое свечение за звездой
        val gr = min(width, height) / 2f   // не больше самой View — иначе виден квадратный край
        glow.shader = RadialGradient(cx, cy, gr, Palette.alpha(color, 0x38), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, gr, glow)

        ray.color = color
        ray.strokeWidth = R * 0.15f
        val rot = turn * 360f * (1f - calm)
        val inner = R * 0.10f
        for (i in 0 until 12) {
            val breathe = 0.5f + 0.5f * sin((2 * PI * wave - i * 0.55).toFloat())
            val k = (1f - calm) * (0.70f + 0.30f * breathe) + calm * 1f
            val len = R * lengths[i] * k * (1f - 0.35f * shrink)
            val ang = Math.toRadians((i * 30.0 + jitter[i] + rot - 90.0))
            val dx = cos(ang).toFloat(); val dy = sin(ang).toFloat()
            c.drawLine(cx + dx * inner, cy + dy * inner, cx + dx * len, cy + dy * len, ray)
        }
    }
}

/** Значок строки проверки: точка → бегущая дуга → рисующаяся ✓ (терракота) или ✕ (красный). */
class MarkView(ctx: Context) : View(ctx) {
    enum class State { WAIT, RUN, OK, BAD }

    private var state = State.WAIT
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var spin = 0f
    private var grow = 1f
    private var draw = 1f
    private val spinAnim = loop(this, 900) { spin = it }
    private val a = Path(); private val b = Path(); private val part = Path()

    fun setState(s: State) {
        if (s == state) return
        state = s
        if (s == State.RUN) { if (!spinAnim.isStarted) spinAnim.start() } else spinAnim.cancel()
        if (s == State.OK || s == State.BAD) {
            grow = 0f; draw = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380; interpolator = OvershootInterpolator(2.4f)
                addUpdateListener { grow = it.animatedValue as Float; invalidate() }
                start()
            }
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 340; startDelay = 160; interpolator = DecelerateInterpolator()
                addUpdateListener { draw = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { spinAnim.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cx = w / 2f; val cy = h / 2f; val r = min(w, h) / 2f
        a.reset(); a.moveTo(cx - r * 0.40f, cy + r * 0.02f); a.lineTo(cx - r * 0.10f, cy + r * 0.32f); a.lineTo(cx + r * 0.42f, cy - r * 0.28f)
        b.reset(); b.moveTo(cx - r * 0.30f, cy - r * 0.30f); b.lineTo(cx + r * 0.30f, cy + r * 0.30f)
        b.moveTo(cx + r * 0.30f, cy - r * 0.30f); b.lineTo(cx - r * 0.30f, cy + r * 0.30f)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f
        when (state) {
            State.WAIT -> { fill.color = Palette.GRAY; c.drawCircle(cx, cy, r * 0.20f, fill) }
            State.RUN -> {
                stroke.strokeWidth = r * 0.16f
                stroke.color = Palette.alpha(Palette.ACCENT, 0x40)
                c.drawCircle(cx, cy, r * 0.70f, stroke)
                stroke.color = Palette.ACCENT
                c.drawArc(RectF(cx - r * 0.70f, cy - r * 0.70f, cx + r * 0.70f, cy + r * 0.70f), spin * 360f, 100f, false, stroke)
            }
            State.OK, State.BAD -> {
                val ok = state == State.OK
                val col = if (ok) Palette.ACCENT else Palette.ERR
                fill.color = col
                c.drawCircle(cx, cy, r * 0.84f * grow, fill)
                stroke.strokeWidth = r * 0.19f
                stroke.color = Palette.BG
                part.reset()
                val pm = PathMeasure(if (ok) a else b, false)
                if (ok) {
                    pm.getSegment(0f, pm.length * draw, part, true)
                } else {
                    pm.getSegment(0f, pm.length * min(1f, draw * 2), part, true)
                    if (pm.nextContour() && draw > 0.5f) pm.getSegment(0f, pm.length * (draw - 0.5f) * 2, part, true)
                }
                c.drawPath(part, stroke)
            }
        }
    }
}

/** Мягкий блик, который изредка пробегает по кнопке. Не ловит нажатия. */
class ShineView(ctx: Context, periodMs: Long = 3200, private val radius: Float = 0f) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val anim = loop(this, periodMs) { t = it }
    private val clip = Path()
    var running = false
        set(v) { field = v; if (v && isAttachedToWindow) anim.start() else anim.cancel(); invalidate() }

    init { isClickable = false; isFocusable = false }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (running) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        if (!running) return
        val w = width.toFloat(); val h = height.toFloat()
        val k = t / 0.40f
        if (k > 1f) return
        val band = w * 0.25f
        val x = -band + (w + band * 2) * k
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        p.shader = LinearGradient(x - band, 0f, x + band, h,
            intArrayOf(Color.TRANSPARENT, Color.argb(0x30, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        c.restore()
    }
}

/** Текст, в котором важные слова сами выделяются цветом Claude. */
class MarkedText(ctx: Context) : TextView(ctx) {
    companion object {
        private val PHRASES = listOf(
            "российский IP не засветится", "российский IP", "только через VDS", "только VDS",
            "мимо VDS", "напрямую", "не засветится",
        )

        fun mark(src: CharSequence?): CharSequence {
            if (src.isNullOrEmpty() || src is Spanned) return src ?: ""
            val s = SpannableString(src)
            val taken = BooleanArray(src.length)
            for (ph in PHRASES) {
                var i = src.indexOf(ph)
                while (i >= 0) {
                    if ((i until i + ph.length).none { taken[it] }) {
                        s.setSpan(ForegroundColorSpan(Palette.ACCENT), i, i + ph.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        s.setSpan(StyleSpan(Typeface.BOLD), i, i + ph.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        for (j in i until i + ph.length) taken[j] = true
                    }
                    i = src.indexOf(ph, i + ph.length)
                }
            }
            return s
        }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(mark(text), type)
    }
}
