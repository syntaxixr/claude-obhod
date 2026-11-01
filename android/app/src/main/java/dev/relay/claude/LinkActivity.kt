package dev.relay.claude

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Принимает ссылки claude.ai снаружи (кнопка «Войти» в письме, ссылка из мессенджера)
 * и открывает их в нашем Claude через VDS, а не в браузере телефона.
 * Невидимая: ничего не рисует, сразу передаёт ссылку дальше.
 */
class LinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val link = uri?.takeIf { it.scheme == "https" && WebActivity.isInternal(it.host ?: "") }?.toString()
        val app = application as RelayApp
        val next = if (link != null && WebActivity.alive && app.proxyPort != 0) {
            // Claude уже открыт и стоит на прокси — просто открываем ссылку в нём
            Intent(this, WebActivity::class.java)
                .putExtra("port", app.proxyPort)
                .putExtra(WebActivity.EXTRA_LINK, link)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            // сначала проверки и защита, ссылка откроется после них
            Intent(this, MainActivity::class.java)
                .putExtra(WebActivity.EXTRA_LINK, link)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(next) }
        finish()
    }
}
