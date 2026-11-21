package uz.personal.ymusic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val pool = Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.init(applicationContext)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B0B0F"))
        web.overScrollMode = View.OVER_SCROLL_NEVER
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.textZoom = 100
        s.mediaPlaybackRequiresUserGesture = false
        WebView.setWebContentsDebuggingEnabled(true)
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)

        Player.onEvent = { ev ->
            runOnUiThread { js("window.__player&&window.__player(" + JSONObject.quote(ev) + ")") }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        web.loadUrl("file:///android_asset/index.html")
    }

    private fun js(code: String) {
        web.evaluateJavascript(code, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            var token = ""
            if (resultCode == RESULT_OK) {
                token = data?.getStringExtra("token") ?: ""
            }
            js("window.__token(" + JSONObject.quote(token) + ")")
        }
    }

    override fun onBackPressed() {
        web.evaluateJavascript("(window.__back&&window.__back())?1:0") { r ->
            if (r != "1") moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        Player.onEvent = null
        if (isFinishing) Player.shutdown()
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {

        @JavascriptInterface
        fun http(id: String, method: String, url: String, headers: String, body: String) {
            pool.execute {
                var status = -1
                var text = ""
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.requestMethod = method
                    c.connectTimeout = 15000
                    c.readTimeout = 25000
                    val h = JSONObject(headers)
                    val keys = h.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        c.setRequestProperty(k, h.getString(k))
                    }
                    if (body.isNotEmpty()) {
                        c.doOutput = true
                        c.outputStream.use { o -> o.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    status = c.responseCode
                    val stream = if (status >= 400) c.errorStream else c.inputStream
                    text = stream?.bufferedReader(Charsets.UTF_8)?.use { r -> r.readText() } ?: ""
                } catch (e: Exception) {
                    status = -1
                    text = e.message ?: e.toString()
                }
                val code = "window.__http(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(text) + ")"
                runOnUiThread { js(code) }
            }
        }

        @JavascriptInterface
        fun login() {
            runOnUiThread {
                startActivityForResult(Intent(this@MainActivity, LoginActivity::class.java), 42)
            }
        }

        @JavascriptInterface
        fun play(url: String, title: String, artist: String, cover: String) {
            Player.play(url, title, artist, cover)
        }

        @JavascriptInterface
        fun toggle() {
            Player.toggle()
        }

        @JavascriptInterface
        fun pause() {
            Player.pause()
        }

        @JavascriptInterface
        fun resume() {
            Player.resume()
        }

        @JavascriptInterface
        fun seek(ms: Int) {
            Player.seek(ms)
        }

        @JavascriptInterface
        fun stop() {
            Player.stop()
        }

        @JavascriptInterface
        fun state(): String {
            val playing = if (Player.isPlaying) 1 else 0
            return "" + Player.position + "|" + Player.duration + "|" + playing
        }

        @JavascriptInterface
        fun md5(s: String): String {
            val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in d) sb.append(String.format("%02x", b))
            return sb.toString()
        }

        @JavascriptInterface
        fun hmac(key: String, msg: String): String {
            val m = Mac.getInstance("HmacSHA256")
            m.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return Base64.encodeToString(m.doFinal(msg.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun getPref(k: String): String {
            return getSharedPreferences("ymusic", Context.MODE_PRIVATE).getString(k, "") ?: ""
        }

        @JavascriptInterface
        fun setPref(k: String, v: String) {
            getSharedPreferences("ymusic", Context.MODE_PRIVATE).edit().putString(k, v).apply()
        }

        @JavascriptInterface
        fun toast(m: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, m, Toast.LENGTH_SHORT).show() }
        }
    }
}
