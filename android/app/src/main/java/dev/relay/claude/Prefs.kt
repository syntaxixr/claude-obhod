package dev.relay.claude

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки подключения. Хранятся локально на телефоне.
 * Никаких паролей Anthropic — только адрес вашего VDS и токен relay.
 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString(
            "host",
            v.trim()
                .removePrefix("https://").removePrefix("http://")
                .removeSuffix("/")
                .substringBefore("/")
                .trim(),
        ).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Порт HTTPS-relay. 443 по умолчанию. */
    var port: Int
        get() = sp.getInt("port", 443)
        set(v) = sp.edit().putInt("port", v).apply()

    /** Пропускать проверку сертификата (только для самоподписанного на старте). */
    var allowSelfSigned: Boolean
        get() = sp.getBoolean("selfSigned", false)
        set(v) = sp.edit().putBoolean("selfSigned", v).apply()

    /** Какую вшитую в сборку ссылку уже применили (чтобы не затирать ручные настройки). */
    var appliedDefaultLink: String
        get() = sp.getString("appliedDefaultLink", "") ?: ""
        set(v) = sp.edit().putString("appliedDefaultLink", v).apply()

    /** Режим: "relay" — своя VDS с сервером claude-relay; "socks" — обычный SOCKS5-прокси. */
    var mode: String
        get() = sp.getString("mode", MODE_RELAY) ?: MODE_RELAY
        set(v) = sp.edit().putString("mode", v).apply()

    val isRelay: Boolean get() = mode != MODE_SOCKS

    /** Логин обычного SOCKS5 (пароль хранится в [token]). Для своей VDS не нужен. */
    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v.trim()).apply()

    /** Человек прочитал правила при первом запуске. */
    var rulesAccepted: Boolean
        get() = sp.getBoolean("rulesAccepted", false)
        set(v) = sp.edit().putBoolean("rulesAccepted", v).apply()

    val configured: Boolean get() = host.isNotBlank() && (token.isNotBlank() || !isRelay)

    companion object {
        const val MODE_RELAY = "relay"
        const val MODE_SOCKS = "socks"
    }

    /** https://host[:port] */
    val baseUrl: String
        get() = if (port == 443) "https://$host" else "https://$host:$port"

    fun webAuthHost(): String = if (port == 443) host else "$host:$port"
}
