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
            if (s.isEmpty()) throw IllegalArgumentException(tr("Вставьте socks5-ссылку", "Paste a socks5 link"))
            s = s.replace(Regex("^(socks5h?|socks)://", RegexOption.IGNORE_CASE), "")
            if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
                throw IllegalArgumentException(tr("Это не socks5-ссылка. Нужна вида socks5://relay:ТОКЕН@адрес", "This isn't a socks5 link. Expected socks5://relay:TOKEN@address"))
            }

            val at = s.lastIndexOf('@')
            if (at < 0) throw IllegalArgumentException(tr("В ссылке нет токена. Нужна вида socks5://relay:ТОКЕН@адрес", "The link has no token. Expected socks5://relay:TOKEN@address"))
            val userInfo = s.substring(0, at)
            val hostPart = s.substring(at + 1).substringBefore('/').substringBefore('?').substringBefore('#')

            val pass = if (userInfo.contains(':')) userInfo.substringAfter(':') else userInfo
            val token = URLDecoder.decode(pass.replace("+", "%2B"), "UTF-8").trim()
            if (token.length < 16) throw IllegalArgumentException(tr("Токен в ссылке слишком короткий (нужно от 16 символов)", "The token in the link is too short (16+ characters needed)"))

            val host: String
            val portStr: String?
            if (hostPart.startsWith("[")) {                       // [IPv6]:порт
                val end = hostPart.indexOf(']')
                if (end < 0) throw IllegalArgumentException(tr("Адрес VDS записан неверно", "The VDS address is malformed"))
                host = hostPart.substring(1, end)
                portStr = hostPart.substring(end + 1).removePrefix(":").ifEmpty { null }
            } else {
                val c = hostPart.lastIndexOf(':')
                host = if (c >= 0) hostPart.substring(0, c) else hostPart
                portStr = if (c >= 0) hostPart.substring(c + 1) else null
            }
            if (host.isBlank()) throw IllegalArgumentException(tr("В ссылке нет адреса VDS", "The link has no VDS address"))
            val port = if (portStr == null) 443 else portStr.toIntOrNull()
                ?: throw IllegalArgumentException(tr("Порт в ссылке не число: $portStr", "Port in the link is not a number: $portStr"))
            if (port !in 1..65535) throw IllegalArgumentException(tr("Порт вне диапазона: $port", "Port out of range: $port"))

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
            if (s.isEmpty()) throw IllegalArgumentException(tr("Вставьте адрес SOCKS5-прокси", "Paste a SOCKS5 proxy address"))
            s = s.replace(Regex("^(socks5h?|socks)://", RegexOption.IGNORE_CASE), "")
            if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
                throw IllegalArgumentException(tr("Нужен адрес SOCKS5, а не ссылка на сайт", "Need a SOCKS5 address, not a website link"))
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
                if (end < 0) throw IllegalArgumentException(tr("Адрес прокси записан неверно", "The proxy address is malformed"))
                host = hostPart.substring(1, end)
                portStr = hostPart.substring(end + 1).removePrefix(":").ifEmpty { null }
            } else {
                val c = hostPart.lastIndexOf(':')
                host = if (c >= 0) hostPart.substring(0, c) else hostPart
                portStr = if (c >= 0) hostPart.substring(c + 1) else null
            }
            if (host.isBlank()) throw IllegalArgumentException(tr("В адресе нет хоста прокси", "The address has no proxy host"))
            val port = if (portStr == null) 1080 else portStr.toIntOrNull()
                ?: throw IllegalArgumentException(tr("Порт прокси не число: $portStr", "Proxy port is not a number: $portStr"))
            if (port !in 1..65535) throw IllegalArgumentException(tr("Порт вне диапазона: $port", "Port out of range: $port"))
            return SocksLink(host.lowercase(), port, user, pass)
        }
    }
}
