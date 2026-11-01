package dev.relay.claude

import android.content.Context
import android.content.res.Resources

/**
 * Язык интерфейса: русский или английский.
 * По умолчанию — как в системе (русский для ru/uk/be/kk/…, иначе английский),
 * дальше человек переключает сам кнопкой «🌐 English / Русский».
 */
object Lang {
    private const val KEY = "lang"
    private val RU_LIKE = setOf("ru", "uk", "be", "kk", "ky", "uz", "tg")

    @Volatile
    var en: Boolean = false
        private set

    fun init(ctx: Context) {
        val v = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE).getString(KEY, "auto") ?: "auto"
        en = when (v) {
            "en" -> true
            "ru" -> false
            else -> systemLang() !in RU_LIKE
        }
    }

    fun set(ctx: Context, english: Boolean) {
        ctx.getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putString(KEY, if (english) "en" else "ru").apply()
        en = english
    }

    private fun systemLang(): String =
        runCatching { Resources.getSystem().configuration.locales[0].language }.getOrNull() ?: "ru"

    /** Подпись кнопки переключения — на том языке, на который переключаем. */
    val switchLabel: String get() = if (en) "🌐 Русский" else "🌐 English"
}

/** Строка на текущем языке. */
fun tr(ru: String, en: String): String = if (Lang.en) en else ru
