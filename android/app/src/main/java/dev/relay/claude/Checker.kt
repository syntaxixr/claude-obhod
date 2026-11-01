package dev.relay.claude

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class Status { WAIT, RUN, OK, BAD }

data class CheckItem(
    val id: String,
    val label: String,
    var status: Status = Status.WAIT,
    var detail: String = "",
    var ms: Long = 0,
)

/**
 * Проверки «галочек». Всё, что касается claude.ai, идёт через локальный прокси,
 * то есть реально через VDS — как будет ходить и сам WebView.
 */
class Checker(private val ctx: Context, private val prefs: Prefs, private val proxyPort: Int) {

    private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })

    private fun sslCtx(): SSLContext =
        if (prefs.allowSelfSigned) SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        else SSLContext.getInstance("TLS").apply { init(null, null, null) }

    /** Прямой HTTPS к VDS (не через туннель) — проверяем сам сервер и токен. */
    private fun directGet(path: String, timeout: Int = 12000): Pair<Int, String> {
        val url = URL(prefs.baseUrl + path)
        // напрямую к VDS: под «защитой» Android выдаёт нам системный прокси — его обходим
        val c = url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        (c as HttpsURLConnection).sslSocketFactory = sslCtx().socketFactory
        c.connectTimeout = timeout
        c.readTimeout = timeout
        if (prefs.allowSelfSigned) c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        c.setRequestProperty("Authorization", "Bearer ${prefs.token}")
        c.setRequestProperty("Accept", "application/json")
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            c.disconnect()
        }
    }

    private fun proxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))

    private data class Resp(val code: Int, val body: String, val location: String, val cfMitigated: String)

    /** HTTPS-запрос через локальный прокси (→ VDS). Возвращает код, тело, Location и Cf-Mitigated. */
    private fun viaProxyResp(urlStr: String, method: String = "GET", timeout: Int = 15000): Resp {
        val url = URL(urlStr)
        val c = url.openConnection(proxy()) as HttpsURLConnection
        c.sslSocketFactory = sslCtx().socketFactory
        c.connectTimeout = timeout
        c.readTimeout = timeout
        if (prefs.allowSelfSigned) c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
        return try {
            val code = c.responseCode
            val body = if (method == "HEAD") "" else (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            Resp(
                code,
                body,
                c.getHeaderField("Location") ?: "",
                c.getHeaderField("Cf-Mitigated") ?: "",
            )
        } finally {
            c.disconnect()
        }
    }

    private fun viaProxy(urlStr: String, method: String = "GET", timeout: Int = 15000): Pair<Int, String> {
        val r = viaProxyResp(urlStr, method, timeout)
        return r.code to r.body
    }

    /** TLS-хендшейк через туннель до указанного хоста. */
    private fun tlsThroughProxy(host: String, port: Int): String {
        val raw = java.net.Socket()
        raw.connect(InetSocketAddress("127.0.0.1", proxyPort), 15000)
        raw.soTimeout = 15000
        val out = raw.getOutputStream()
        out.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray())
        out.flush()
        val inp = BufferedInputStream(raw.getInputStream())
        val status = readLine(inp) ?: throw java.io.IOException(tr("нет ответа прокси", "no reply from proxy"))
        if (!status.contains(" 200")) throw java.io.IOException(tr("прокси: $status", "proxy: $status"))
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
        }
        val ssl = sslCtx().socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.soTimeout = 15000
        ssl.startHandshake()
        val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
        val info = cert?.subjectDN?.name ?: host
        ssl.close()
        return info
    }

    private fun readLine(i: java.io.InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = i.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar()); prev = b
        }
    }

    /** Есть ли у телефона вообще интернет (до любых запросов к VDS). */
    private fun phoneOnline(): Pair<Boolean, String> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false to tr("нет сети — включите Wi-Fi или мобильный интернет", "no network — turn on Wi-Fi or mobile data")
        val caps = cm.getNetworkCapabilities(net) ?: return false to tr("нет сети", "no network")
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false to tr("сеть без интернета", "network has no internet")
        val kind = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> tr("мобильный интернет", "mobile data")
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> tr("через VPN", "via VPN")
            else -> tr("есть", "connected")
        }
        return true to kind
    }

    suspend fun runAll(items: List<CheckItem>, onUpdate: (CheckItem) -> Unit) = withContext(Dispatchers.IO) {
        fun item(id: String) = items.first { it.id == id }
        fun start(id: String) { item(id).apply { status = Status.RUN; detail = tr("проверяю…", "checking…") }.also(onUpdate) }
        fun set(id: String, ok: Boolean, detail: String, ms: Long) {
            item(id).apply { status = if (ok) Status.OK else Status.BAD; this.detail = detail; this.ms = ms }.also(onUpdate)
        }
        fun skipRest(why: String) {
            items.filter { it.status == Status.WAIT || it.status == Status.RUN }
                .forEach { it.status = Status.BAD; it.detail = why; it.ms = 0; onUpdate(it) }
        }
        fun now() = System.currentTimeMillis()
        // проверки быстрые — чуть притормаживаем, чтобы глаз успевал увидеть каждую
        fun breathe(t: Long) { val left = 450 - (now() - t); if (left > 0) Thread.sleep(left) }

        // 1. Интернет на телефоне
        start("net")
        run {
            val t = now()
            val (ok, d) = phoneOnline()
            breathe(t)
            set("net", ok, d, now() - t)
        }
        if (item("net").status != Status.OK) return@withContext skipRest(tr("нет интернета", "no internet"))

        // 2. Сервер: своя VDS (claude-relay) — /healthz и токен; обычный SOCKS5 — рукопожатие с прокси
        start("vds")
        if (!prefs.isRelay) run {
            val t = now()
            try {
                val probe = LocalProxy(prefs.host, prefs.port, prefs.token, false, tls = false, user = prefs.user)
                probe.openTunnel("api.country.is", 443).close()
                breathe(t)
                set("vds", true, tr("SOCKS5 ${prefs.host}:${prefs.port} · вход принят", "SOCKS5 ${prefs.host}:${prefs.port} · login accepted"), now() - t)
            } catch (e: java.net.SocketTimeoutException) {
                set("vds", false, tr("прокси не отвечает (таймаут) — проверьте адрес и порт", "proxy not responding (timeout) — check address and port"), now() - t)
            } catch (e: java.net.ConnectException) {
                set("vds", false, tr("прокси отказал в соединении — неверный порт или он выключен", "proxy refused the connection — wrong port or it's off"), now() - t)
            } catch (e: Exception) {
                set("vds", false, e.message ?: tr("нет связи с прокси", "can't reach the proxy"), now() - t)
            }
        } else run {
            val t = now()
            try {
                val (code, body) = directGet("/healthz")
                val isRelay = code == 200 && runCatching { JSONObject(body).has("build") }.getOrDefault(false)
                if (!isRelay) {
                    // на порту живёт что-то чужое (часто VPN, который маскируется под сайт)
                    set("vds", false, tr("на порту ${prefs.port} не сервер Claude Обход (HTTP $code) — сервер не установлен или занят другой программой", "port ${prefs.port} is not a Claude Obhod server (HTTP $code) — server not installed or port used by another program"), now() - t)
                } else {
                    val (wc, _) = directGet("/relay/api/whoami")
                    val build = runCatching { JSONObject(body).optInt("build") }.getOrDefault(0)
                    breathe(t)
                    when (wc) {
                        200 -> set("vds", true, tr("${prefs.host} · на связи", "${prefs.host} · online"), now() - t)
                        401 -> set("vds", false, tr("сервер не принял токен — проверьте ссылку", "server rejected the token — check the link"), now() - t)
                        else -> set("vds", false, tr("токен: HTTP $wc (build $build)", "token: HTTP $wc (build $build)"), now() - t)
                    }
                }
            } catch (e: javax.net.ssl.SSLException) {
                set("vds", false, if (prefs.allowSelfSigned) tr("ошибка TLS: ${e.message}", "TLS error: ${e.message}") else tr("сертификат не доверенный — включите «самоподписанный» в настройках", "untrusted certificate — turn on \"self-signed\" in settings"), now() - t)
            } catch (e: java.net.SocketTimeoutException) {
                set("vds", false, tr("VDS не отвечает (таймаут) — сервер не запущен или порт закрыт", "VDS not responding (timeout) — server not running or port closed"), now() - t)
            } catch (e: java.net.ConnectException) {
                set("vds", false, tr("VDS отказал в соединении — сервер не запущен на порту ${prefs.port}", "VDS refused the connection — server isn't running on port ${prefs.port}"), now() - t)
            } catch (e: Exception) {
                set("vds", false, e.message ?: tr("нет связи с VDS", "can't reach the VDS"), now() - t)
            }
        }
        if (item("vds").status != Status.OK) return@withContext skipRest(tr("ждёт сервер", "waiting for server"))

        // 3. Туннель: SOCKS5 внутри TLS до VDS и дальше в интернет
        start("tunnel")
        run {
            val t = now()
            try {
                tlsThroughProxy("api.country.is", 443)
                breathe(t)
                set("tunnel", true, if (prefs.isRelay) tr("трафик идёт через VDS", "traffic goes through VDS") else tr("трафик идёт через прокси", "traffic goes through proxy"), now() - t)
            } catch (e: Exception) {
                set("tunnel", false, e.message ?: tr("туннель не поднялся", "tunnel didn't come up"), now() - t)
            }
        }
        if (item("tunnel").status != Status.OK) return@withContext skipRest(tr("ждёт туннель", "waiting for tunnel"))

        // 4. Выход: какая страна видна снаружи
        start("exit")
        val blocked = setOf("RU", "BY", "IR", "KP", "CU", "SY", "VE", "CN", "HK")
        var country = ""
        run {
            val t = now()
            try {
                val (code, body) = viaProxy("https://api.country.is/")
                if (code == 200) {
                    val j = JSONObject(body)
                    val ip = j.optString("ip")
                    country = j.optString("country")
                    breathe(t)
                    val ok = ip.isNotBlank() && country.isNotBlank() && country !in blocked
                    set("exit", ok, if (ok) "${countryName(country)} · $ip" else tr("$country — тут Claude закрыт, нужен другой VDS", "$country — Claude is blocked here, you need another VDS"), now() - t)
                } else set("exit", false, "HTTP $code", now() - t)
            } catch (e: Exception) {
                set("exit", false, e.message ?: "", now() - t)
            }
        }

        // 5. Claude: сайт и API без блокировки по региону
        start("claude")
        run {
            val t = now()
            try {
                val r = viaProxyResp("https://claude.ai/", "GET")
                val regionBlock = r.location.contains("unavailable-in-region") || r.body.contains("app-unavailable-in-region")
                // Cloudflare может показать JS-проверку — это не блок, WebView проходит её сам
                val challenge = r.cfMitigated.isNotEmpty() || r.body.contains("challenges.cloudflare.com")
                val siteOk = !regionBlock && (r.code in 200..399 || (r.code == 403 && challenge))
                val (apiCode, _) = viaProxy("https://api.anthropic.com/v1/messages", "POST")
                val apiOk = apiCode != 403
                breathe(t)
                set(
                    "claude", siteOk && apiOk,
                    when {
                        regionBlock -> tr("регион закрыт", "region blocked")
                        !siteOk -> "claude.ai: HTTP ${r.code}"
                        !apiOk -> tr("API: доступ запрещён (403)", "API: access denied (403)")
                        else -> tr("claude.ai открывается", "claude.ai opens")
                    },
                    now() - t,
                )
            } catch (e: Exception) {
                set("claude", false, e.message ?: "", now() - t)
            }
        }
    }

    private fun countryName(c: String): String = if (Lang.en) {
        runCatching { java.util.Locale("", c).getDisplayCountry(java.util.Locale.ENGLISH) }.getOrNull()?.ifBlank { null } ?: c
    } else when (c) {
        "LV" -> "Латвия"; "LT" -> "Литва"; "EE" -> "Эстония"; "DE" -> "Германия"; "NL" -> "Нидерланды"
        "FI" -> "Финляндия"; "PL" -> "Польша"; "FR" -> "Франция"; "GB" -> "Великобритания"; "US" -> "США"
        "SE" -> "Швеция"; "CZ" -> "Чехия"; "KZ" -> "Казахстан"; "TR" -> "Турция"; "AM" -> "Армения"; "GE" -> "Грузия"
        else -> c
    }

    companion object {
        fun items(): List<CheckItem> = listOf(
            CheckItem("net", tr("Интернет на телефоне", "Internet on the phone")),
            CheckItem("vds", tr("Сервер (VDS или прокси)", "Server (VDS or proxy)")),
            CheckItem("tunnel", tr("Туннель", "Tunnel")),
            CheckItem("exit", tr("Выход в интернет", "Internet exit")),
            CheckItem("claude", tr("Claude доступен", "Claude available")),
        )
    }
}
