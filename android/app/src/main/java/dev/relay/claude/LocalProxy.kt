package dev.relay.claude

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Локальный HTTP-прокси для WebView (и для приложения Claude под «защитой»).
 *
 * Два режима выхода в интернет:
 *  • [tls] = true  — своя VDS с сервером claude-relay: TLS до VDS, внутри SOCKS5
 *    (логин relay, пароль = токен). Снаружи выглядит как обычный HTTPS.
 *  • [tls] = false — любой обычный SOCKS5-прокси (купленный, свой dante/3proxy и т.п.):
 *    голый SOCKS5, вход по логину/паролю или без него. Не шифруется — провайдер
 *    видит, куда идут соединения, и может такой прокси заблокировать.
 *
 * WebView умеет только HTTP-прокси без авторизации, поэтому всё это живёт здесь.
 */
class LocalProxy(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val allowSelfSigned: Boolean,
    private val tls: Boolean = true,
    private val user: String = "relay",
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var threads: Thread? = null
    var boundPort: Int = 0
        private set
    val isRunning: Boolean get() = running

    private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })

    fun start(): Int {
        if (running) return boundPort
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        boundPort = s.localPort
        server = s
        running = true
        threads = Thread {
            while (running) {
                try {
                    val c = s.accept()
                    Thread { handle(c) }.apply { isDaemon = true }.start()
                } catch (_: IOException) {
                    if (!running) break
                }
            }
        }.apply { isDaemon = true; start() }
        return boundPort
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        threads = null
    }

    /**
     * Туннель до VDS: TLS до VDS, внутри — SOCKS5 (логин relay, пароль = токен).
     * TLS снаружи нужен, чтобы токен не шёл открытым текстом и соединение
     * выглядело как обычный HTTPS на 443 (голый SOCKS легко режется DPI).
     */
    fun openTunnel(targetHost: String, targetPort: Int): Socket {
        if (!tls) return openPlainTunnel(targetHost, targetPort)
        val ctx = if (allowSelfSigned) {
            SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        } else {
            SSLContext.getInstance("TLS").apply { init(null, null, null) }
        }
        val raw = Socket()
        raw.soTimeout = 20000
        raw.connect(java.net.InetSocketAddress(host, port), 20000)
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.soTimeout = 20000
        ssl.startHandshake()

        try {
            socks5Handshake(ssl, targetHost, targetPort)
        } catch (e: Exception) {
            try { ssl.close() } catch (_: Exception) {}
            throw e
        }
        ssl.soTimeout = 0
        return ssl
    }

    /** Обычный SOCKS5-прокси: TCP без TLS, дальше то же рукопожатие. */
    private fun openPlainTunnel(targetHost: String, targetPort: Int): Socket {
        val raw = Socket()
        raw.soTimeout = 20000
        raw.tcpNoDelay = true
        raw.connect(java.net.InetSocketAddress(host, port), 20000)
        try {
            socks5Handshake(raw, targetHost, targetPort)
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
        raw.soTimeout = 0
        return raw
    }

    private fun socks5Handshake(sock: Socket, targetHost: String, targetPort: Int) {
        val out = sock.getOutputStream()
        val input = sock.getInputStream()
        val who = if (tls) "VDS" else "Прокси"

        // 1. приветствие. Своя VDS — только логин/пароль (0x02).
        //    Обычный SOCKS5 — логин/пароль, если он задан, и «без входа» (0x00).
        val methods = when {
            tls -> byteArrayOf(0x02)
            token.isNotEmpty() -> byteArrayOf(0x02, 0x00)
            else -> byteArrayOf(0x00)
        }
        out.write(byteArrayOf(0x05, methods.size.toByte()) + methods); out.flush()
        val g = readExact(input, 2)
        if (g[0] != 0x05.toByte()) {
            throw IOException(if (tls) "VDS ответил не по SOCKS5 — обновите сервер Claude Обход" else "по этому адресу не SOCKS5-прокси")
        }
        when (g[1].toInt() and 0xff) {
            0x00 -> Unit // вход не нужен
            0x02 -> {
                // 2. логин/пароль (у своей VDS логин любой, пароль = токен)
                val u = user.ifEmpty { "relay" }.toByteArray(Charsets.UTF_8)
                val pass = token.toByteArray(Charsets.UTF_8)
                if (u.size > 255 || pass.size > 255) throw IOException("логин или пароль длиннее 255 байт")
                out.write(byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(pass.size.toByte()) + pass)
                out.flush()
                val a = readExact(input, 2)
                if (a[1] != 0x00.toByte()) throw IOException(if (tls) "VDS отклонил токен" else "прокси не принял логин/пароль")
            }
            0xff -> throw IOException(if (tls || token.isNotEmpty()) "$who не принял способ входа" else "прокси требует логин и пароль")
            else -> throw IOException("$who: неизвестный способ входа")
        }

        // 3. CONNECT по имени — резолвит сам VDS, DNS оператора не участвует
        val h = targetHost.removePrefix("[").removeSuffix("]").toByteArray(Charsets.UTF_8)
        if (h.isEmpty() || h.size > 255) throw IOException("плохое имя хоста")
        out.write(
            byteArrayOf(0x05, 0x01, 0x00, 0x03, h.size.toByte()) + h +
                byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte())
        )
        out.flush()

        // 4. ответ: VER REP RSV ATYP BND.ADDR BND.PORT
        val r = readExact(input, 4)
        val rep = r[1].toInt() and 0xff
        if (rep != 0) throw IOException(socksError(rep))
        val addrLen = when (r[3].toInt()) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> readExact(input, 1)[0].toInt() and 0xff
            else -> throw IOException("VDS: странный ответ SOCKS5")
        }
        readExact(input, addrLen + 2)
    }

    private fun socksError(rep: Int): String = when (rep) {
        0x02 -> "сервер запретил адрес (локальная сеть)"
        0x03 -> "сервер: сеть недоступна"
        0x04 -> "сервер: хост не найден/недоступен"
        0x05 -> "сервер: соединение отклонено"
        0x07 -> "сервер: команда не поддерживается"
        else -> "сервер: ошибка SOCKS5 ($rep)"
    }

    private fun readExact(input: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val k = input.read(b, got, n - got)
            if (k < 0) throw IOException("VDS закрыл соединение")
            got += k
        }
        return b
    }

    private fun handle(client: Socket) {
        var tunnel = false
        try {
            client.soTimeout = 30000
            val input = BufferedInputStream(client.getInputStream())
            val out = BufferedOutputStream(client.getOutputStream())

            val first = readLine(input) ?: return
            val parts = first.split(' ')
            if (parts.size < 2) return
            if (!parts[0].equals("CONNECT", true)) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray()); out.flush()
                return
            }
            val hp = parts[1]
            val idx = hp.lastIndexOf(':')
            val th = if (idx > 0) hp.substring(0, idx) else hp
            val tp = if (idx > 0) hp.substring(idx + 1).toIntOrNull() ?: 443 else 443

            var line: String?
            do {
                line = readLine(input)
            } while (!line.isNullOrEmpty())

            // мобильные сети иногда рвут первое соединение — одна повторная попытка
            val remote = try {
                try { openTunnel(th, tp) } catch (e: IOException) {
                    if (e.message?.startsWith("VDS") == true && e.message?.contains("закрыл") != true) throw e
                    Thread.sleep(300)
                    openTunnel(th, tp)
                }
            } catch (e: Exception) {
                out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); out.flush()
                return
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); out.flush()
            tunnel = true
            // дальше туннель может долго молчать в одну сторону (Claude печатает длинный ответ,
            // а мы ничего не шлём) — таймаут 30 с на чтении от клиента рвал бы такие соединения
            client.soTimeout = 0

            // сокеты закроются сами, когда одна из сторон закончит
            pump(input, remote.getOutputStream(), remote)
            pump(BufferedInputStream(remote.getInputStream()), out, client)
        } catch (_: Exception) {
        } finally {
            if (!tunnel) {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun pump(
        from: java.io.InputStream,
        to: java.io.OutputStream,
        closeOnEnd: Socket,
    ): Thread = Thread {
        try {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { closeOnEnd.close() } catch (_: Exception) {}
        }
    }.apply { isDaemon = true; start() }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }
}
