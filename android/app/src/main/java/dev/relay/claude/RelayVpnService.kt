package dev.relay.claude

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.net.Inet4Address
import java.net.InetAddress

/**
 * «Защита»: системный VPN, который накрывает ТОЛЬКО Claude Обход и приложение Claude.
 *
 * Принцип — «наружу только VDS» (fail-closed):
 *  • в туннель заворачивается ВСЁ, кроме адреса VDS. Туннель никто не читает,
 *    поэтому всё, что в него попало, просто исчезает;
 *  • наружу из этих двух приложений физически может выйти только соединение к VDS
 *    (TLS → SOCKS5 → VDS → claude.ai);
 *  • WebRTC/STUN по UDP, DNS-подсказки страницы, любые прямые попытки — в туннель и в никуда.
 *    Так российский IP не увидит ни claude.ai, ни скрипты на странице;
 *  • DNS — мёртвый адрес внутри туннеля (если VDS задан IP-адресом). Имена резолвит VDS;
 *  • приложению Claude Android выдаёт HTTP-прокси 127.0.0.1:<порт LocalProxy> (Android 10+);
 *  • foreground с уведомлением, чтобы Android не убил защиту в фоне.
 *
 * Остальные приложения телефона VPN не видят и работают как обычно.
 */
class RelayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "dev.relay.claude.VPN_START"
        const val ACTION_STOP = "dev.relay.claude.VPN_STOP"
        const val EXTRA_PORT = "port"
        private const val CHANNEL = "tunnel"
        private const val NOTIF_ID = 7

        @Volatile var running = false
            private set
        @Volatile var port = 0
            private set
        /** Последняя ошибка запуска; MainActivity сбрасывает её перед новым запуском. */
        @Volatile var lastError: String? = null
        @Volatile var claudeAppIncluded = false
            private set

        /** Родное приложение Claude через туннель — нужен Android 10+ (прокси для VPN). */
        val appModeSupported: Boolean get() = Build.VERSION.SDK_INT >= 29

        /** IPv4 → число. */
        private fun ipToLong(a: Inet4Address): Long =
            a.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

        private fun longToIp(v: Long): String =
            "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

        /**
         * Маршруты «весь IPv4, кроме этих адресов». Каждый исключённый /32
         * раскалывает покрывающий его префикс пополам до самого адреса.
         */
        fun ipv4Complement(excluded: Collection<Long>): List<Pair<String, Int>> {
            var ranges = listOf(0L to 0)
            for (ip in excluded.toSet()) {
                val out = ArrayList<Pair<Long, Int>>()
                for ((net, len) in ranges) {
                    val mask = if (len == 0) 0L else (0xFFFFFFFFL shl (32 - len)) and 0xFFFFFFFFL
                    if ((ip and mask) != net) {
                        out += net to len
                        continue
                    }
                    var n = net
                    var l = len
                    while (l < 32) {
                        val half = 1L shl (31 - l)
                        if ((ip and half) != 0L) {
                            out += n to (l + 1)          // левая половина — без адреса
                            n = n or half                // идём в правую
                        } else {
                            out += (n or half) to (l + 1) // правая половина — без адреса
                        }
                        l++
                    }
                }
                ranges = out
            }
            return ranges.map { (n, l) -> longToIp(n) to l }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var userStopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            userStopped = true
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        userStopped = false
        goForeground()
        val wantPort = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        // адрес VDS резолвим в фоне — до того, как туннель отрежет DNS
        Thread {
            val err = try {
                establish(wantPort)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (err != null) {
                lastError = err
                shutdown()
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun establish(wantPort: Int) {
        val app = application as RelayApp
        val prefs = Prefs(this)
        // запуск системой («Постоянный VPN» / после перезагрузки) — поднимаем прокси сами
        var p = wantPort
        if (p == 0 && app.ensureProxy(prefs)) p = app.proxyPort
        if (p == 0) throw IllegalStateException("локальный прокси не запущен")
        if (prefs.host.isBlank()) throw IllegalStateException("не настроен адрес VDS")

        // куда пускаем напрямую: только VDS
        val vdsIsLiteral = prefs.host.all { it.isDigit() || it == '.' }
        val vdsV4 = InetAddress.getAllByName(prefs.host).filterIsInstance<Inet4Address>()
        if (vdsV4.isEmpty()) throw IllegalStateException("у VDS нет IPv4-адреса")
        val excluded = vdsV4.map { ipToLong(it) }.toMutableList()

        // DNS: если VDS задан IP — DNS не нужен вовсе, глушим. Если доменом —
        // пропускаем системный DNS (иначе не найдём VDS при смене адреса).
        val dnsServers = ArrayList<String>()
        if (!vdsIsLiteral) {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers
                ?.filterIsInstance<Inet4Address>()
                ?.forEach { dnsServers += it.hostAddress!!; excluded += ipToLong(it) }
        }

        val b = Builder()
            .setSession("Claude Обход — защита")
            .setMtu(1500)
            .addAddress("10.111.0.2", 32)
            .addAddress("fd11:1:1::2", 128)
            .addRoute("::", 0) // весь IPv6 — в никуда (VDS только IPv4)
            .setConfigureIntent(mainIntent())
        for ((net, len) in ipv4Complement(excluded)) b.addRoute(net, len)
        if (dnsServers.isEmpty()) b.addDnsServer("10.111.0.1") // мёртвый адрес внутри туннеля
        else dnsServers.forEach { b.addDnsServer(it) }

        // накрываем себя (WebView с Claude) и приложение Claude, если оно установлено
        b.addAllowedApplication(packageName)
        claudeAppIncluded = runCatching { b.addAllowedApplication(BuildConfig.VPN_APP); true }.getOrDefault(false)
        if (Build.VERSION.SDK_INT >= 29) {
            b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", p))
            b.setMetered(false)
        }

        val fd = b.establish() ?: throw IllegalStateException("нет разрешения на VPN")
        try { tun?.close() } catch (_: Exception) {}
        tun = fd
        port = p
        running = true
        lastError = null
    }

    override fun onRevoke() {
        // выключили VPN в системе или включили другой VPN
        if (running) warnTunnelGone()
        shutdown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (running && !userStopped) warnTunnelGone() // остановили не кнопкой — предупреждаем
        shutdown()
        super.onDestroy()
    }

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Защита Claude", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывается, пока Claude идёт только через VDS"
                setShowBadge(false)
            }
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Claude идёт только через VDS")
            .setContentText("Защита включена: Claude Обход и приложение Claude не выходят напрямую")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(mainIntent())
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun warnTunnelGone() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("tunnel-alert", "Защита Claude отключена", NotificationManager.IMPORTANCE_HIGH)
        )
        val n = Notification.Builder(this, "tunnel-alert")
            .setContentTitle("Защита Claude отключена")
            .setContentText("Приложение Claude сейчас может пойти напрямую. Включите защиту в Claude Обход.")
            .setStyle(Notification.BigTextStyle().bigText("Приложение Claude сейчас может пойти напрямую (без VDS). Не открывайте его — зайдите в Claude Обход и нажмите «Открыть Claude», защита включится снова."))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(mainIntent())
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(8, n) }
    }

    private fun shutdown() {
        running = false
        port = 0
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }
}
