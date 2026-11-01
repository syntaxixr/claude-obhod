package dev.relay.claude

import android.app.Application

/** Держит локальный прокси, пока жив процесс — общий для MainActivity и WebActivity. */
class RelayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applyLocale()
    }

    /**
     * Язык процесса — ru-LV (русский из Латвии): так его увидят сайты в заголовке
     * Accept-Language, а не ru-RU. Ставим до первого запуска WebView.
     */
    fun applyLocale() {
        val list = android.os.LocaleList(
            java.util.Locale("ru", "LV"), java.util.Locale("ru"), java.util.Locale("en", "US"),
        )
        android.os.LocaleList.setDefault(list)
        java.util.Locale.setDefault(list[0])
    }
    var proxy: LocalProxy? = null
        private set

    var proxyPort: Int = 0
        private set

    fun ensureProxy(prefs: Prefs): Boolean {
        val p = proxy
        if (p != null && p.isRunning) return true
        if (!prefs.configured) return false
        return try {
            val np = LocalProxy(prefs.host, prefs.port, prefs.token, prefs.allowSelfSigned, tls = prefs.isRelay, user = prefs.user)
            proxyPort = np.start()
            proxy = np
            true
        } catch (e: Exception) {
            proxy = null
            proxyPort = 0
            false
        }
    }

    fun stopProxy() {
        try { proxy?.stop() } catch (_: Exception) {}
        proxy = null
        proxyPort = 0
    }
}
