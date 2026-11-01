package dev.relay.claude

import java.net.URLDecoder

/**
 * Socks5-ссылка от VDS: socks5://relay:ТОКЕН@домен[:порт]
 * Её печатает install.sh (и `node src/index.mjs link`) — человек просто вставляет её в приложение.
 * Логин в ссылке любой, пароль = токен relay. Порт по умолчанию 443.
 */
data class RelayLink(val host: String, val port: Int, val token: String) {

    override fun toString(): String =
        "socks5://relay:$token@${if (host.contains(':')) "[$host]" else host}${if (port == 443) "" else ":$port"}"

    companion object {
        /** Бросает IllegalArgumentException с понятным текстом, если ссылка не распознана. */
        fun parse(raw: String): RelayLink {
            var s = raw.trim().replace(Regex("\\s+"), "")
            if (s.isEmpty()) throw IllegalArgumentException("Вставьте socks5-ссылку")
            s = s.replace(Regex("^(socks5h?|socks)://", RegexOption.IGNORE_CASE), "")
            if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
                throw IllegalArgumentException("Это не socks5-ссылка. Нужна вида socks5://relay:ТОКЕН@адрес")
            }

            val at = s.lastIndexOf('@')
            if (at < 0) throw IllegalArgumentException("В ссылке нет токена. Нужна вида socks5://relay:ТОКЕН@адрес")
            val userInfo = s.substring(0, at)
            val hostPart = s.substring(at + 1).substringBefore('/').substringBefore('?').substringBefore('#')

            val pass = if (userInfo.contains(':')) userInfo.substringAfter(':') else userInfo
            val token = URLDecoder.decode(pass.replace("+", "%2B"), "UTF-8").trim()
            if (token.length < 16) throw IllegalArgumentException("Токен в ссылке слишком короткий (нужно от 16 символов)")

            val host: String
            val portStr: String?
            if (hostPart.startsWith("[")) {                       // [IPv6]:порт
                val end = hostPart.indexOf(']')
                if (end < 0) throw IllegalArgumentException("Адрес VDS записан неверно")
                host = hostPart.substring(1, end)
                portStr = hostPart.substring(end + 1).removePrefix(":").ifEmpty { null }
            } else {
                val c = hostPart.lastIndexOf(':')
                host = if (c >= 0) hostPart.substring(0, c) else hostPart
                portStr = if (c >= 0) hostPart.substring(c + 1) else null
            }
            if (host.isBlank()) throw IllegalArgumentException("В ссылке нет адреса VDS")
            val port = if (portStr == null) 443 else portStr.toIntOrNull()
                ?: throw IllegalArgumentException("Порт в ссылке не число: $portStr")
            if (port !in 1..65535) throw IllegalArgumentException("Порт вне диапазона: $port")

            return RelayLink(host.lowercase(), port, token)
        }
    }
}

/**
 * Адрес обычного SOCKS5-прокси. Понимает все частые форматы, в которых его выдают:
 *   socks5://логин:пароль@хост:порт   socks5h://…   socks://…
 *   логин:пароль@хост:порт
 *   хост:порт:логин:пароль
 *   хост:порт            (без входа)
 * Порт по умолчанию 1080.
 */
data class SocksLink(val host: String, val port: Int, val user: String, val pass: String) {

    override fun toString(): String {
        val auth = if (user.isNotEmpty() || pass.isNotEmpty()) "$user:$pass@" else ""
        return "socks5://$auth${if (host.contains(':')) "[$host]" else host}:$port"
    }

    companion object {
        fun parse(raw: String): SocksLink {
            var s = raw.trim().replace(Regex("\\s+"), "")
            if (s.isEmpty()) throw IllegalArgumentException("Вставьте адрес SOCKS5-прокси")
            s = s.replace(Regex("^(socks5h?|socks)://", RegexOption.IGNORE_CASE), "")
            if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
                throw IllegalArgumentException("Нужен адрес SOCKS5, а не ссылка на сайт")
            }
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')

            var user = ""
            var pass = ""
            val hostPart: String
            val at = s.lastIndexOf('@')
            if (at >= 0) {
                val info = s.substring(0, at)
                user = java.net.URLDecoder.decode(info.substringBefore(':').replace("+", "%2B"), "UTF-8")
                pass = if (info.contains(':')) java.net.URLDecoder.decode(info.substringAfter(':').replace("+", "%2B"), "UTF-8") else ""
                hostPart = s.substring(at + 1)
            } else {
                val parts = s.split(':')
                if (parts.size == 4 && !s.startsWith("[")) {          // хост:порт:логин:пароль
                    user = parts[2]
                    pass = parts[3]
                    hostPart = parts[0] + ":" + parts[1]
                } else {
                    hostPart = s
                }
            }

            val host: String
            val portStr: String?
            if (hostPart.startsWith("[")) {
                val end = hostPart.indexOf(']')
                if (end < 0) throw IllegalArgumentException("Адрес прокси записан неверно")
                host = hostPart.substring(1, end)
                portStr = hostPart.substring(end + 1).removePrefix(":").ifEmpty { null }
            } else {
                val c = hostPart.lastIndexOf(':')
                host = if (c >= 0) hostPart.substring(0, c) else hostPart
                portStr = if (c >= 0) hostPart.substring(c + 1) else null
            }
            if (host.isBlank()) throw IllegalArgumentException("В адресе нет хоста прокси")
            val port = if (portStr == null) 1080 else portStr.toIntOrNull()
                ?: throw IllegalArgumentException("Порт прокси не число: $portStr")
            if (port !in 1..65535) throw IllegalArgumentException("Порт вне диапазона: $port")
            return SocksLink(host.lowercase(), port, user, pass)
        }
    }
}
