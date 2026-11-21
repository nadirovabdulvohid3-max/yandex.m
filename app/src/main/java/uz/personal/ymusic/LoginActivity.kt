package uz.personal.ymusic

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Yandex OAuth sahifasini ochadi. Kirgandan keyin Yandex token bilan
 * music.yandex.ru manziliga yo'naltiradi, biz tokenni manzildan olib qaytamiz.
 */
class LoginActivity : Activity() {

    private val clientId = "23cabbbdc6cd418abb4b39c32c41195d"
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = WebView(this)
        setContentView(w)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return grab(request?.url?.toString() ?: "")
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                grab(url ?: "")
            }
        }
        w.loadUrl("https://oauth.yandex.ru/authorize?response_type=token&client_id=" + clientId)
    }

    private fun grab(url: String): Boolean {
        if (done) return true
        val m = Regex("access_token=([^&]+)").find(url) ?: return false
        done = true
        setResult(RESULT_OK, Intent().putExtra("token", m.groupValues[1]))
        finish()
        return true
    }
}
