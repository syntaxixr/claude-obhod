package dev.relay.claude

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Вход по почте: Claude присылает письмо со ссылкой claude.ai/magic-link…
 * Без настройки Android откроет её в браузере телефона — то есть мимо VDS,
 * с российским IP, и вход случится не в нашем приложении.
 *
 * Мы объявляем, что умеем открывать ссылки claude.ai (LinkActivity), но подтвердить
 * домен Anthropic за нас не может никто. Поэтому на Android 12+ человек один раз
 * разрешает это сам: «Открывать по умолчанию» → «Добавить ссылку» → claude.ai.
 * На Android 11 и старше система просто спросит «Открыть с помощью…».
 */
object MailLinks {

    /** Ссылки claude.ai уже открываются в нашем приложении. */
    fun approved(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            val m = ctx.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
                ?: return false
            val st = m.getDomainVerificationUserState(ctx.packageName) ?: return false
            if (!st.isLinkHandlingAllowed) return false
            val s = st.hostToStateMap["claude.ai"] ?: return false
            s == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                s == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }.getOrDefault(false)
    }

    fun openSettings(ctx: Context) {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
        for (i in tries) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    fun advise(a: Activity) {
        if (a.isFinishing) return
        AlertDialog.Builder(a)
            .setTitle("Вход по почте")
            .setMessage(
                "Claude пришлёт письмо со ссылкой для входа. Сейчас телефон откроет её в браузере — " +
                    "мимо VDS, с российским IP. Чтобы она открывалась здесь:\n\n" +
                    "1. Нажмите «Настроить».\n" +
                    "2. Включите «Открывать поддерживаемые ссылки».\n" +
                    "3. «Добавить ссылку» → отметьте claude.ai → «Добавить».\n\n" +
                    "Без настройки тоже можно: в письме долго нажмите на кнопку входа → " +
                    "«Копировать ссылку» и вернитесь сюда — ссылка откроется сама.",
            )
            .setPositiveButton("Настроить") { _, _ -> openSettings(a) }
            .setNegativeButton("Потом", null)
            .show()
    }
}
