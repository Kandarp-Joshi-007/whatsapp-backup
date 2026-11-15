package com.kandarp.chatarchive

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/** Shows the WhatsApp-style archive (the bundled web viewer) on top of local files + Google Drive. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!File(Paths.indexDir(this), "manifest.json").isFile) {
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/data/", DataHandler())
            .build()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(r.url)
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (r.url.host == "appassets.androidplatform.net") return false
                    startActivity(Intent(Intent.ACTION_VIEW, r.url)); return true // links open in the browser
                }
            }
        }
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        setContentView(android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor(if (dark) "#202C33" else "#008069")) // matches the top bar
            addView(web)
            // Android 15 draws apps edge-to-edge: keep the viewer clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        })
        web.loadUrl("https://appassets.androidplatform.net/assets/viewer/index.html?app=1")
    }

    override fun onResume() {
        super.onResume()
        // reload when a sync finished while we were away
        val ok = Status.last(this, "last_ok")
        if (::web.isInitialized && ok != null && ok != shownVersion) { if (shownVersion != null) web.reload(); shownVersion = ok }
    }
    private var shownVersion: String? = null

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** Local file if it's still on the phone, otherwise download from Drive into the cache. */
    private suspend fun mediaFile(path: String): File? {
        val wa = Paths.whatsAppRoot()
        val local = File(wa, path)
        if (local.isFile && local.canonicalPath.startsWith(wa.canonicalPath)) return local
        val id = Store.get(this).uploaded(path)?.second ?: return null
        val cached = File(cacheDir, "media/" + id + "-" + path.substringAfterLast('/')).apply { parentFile?.mkdirs() }
        if (!cached.isFile) Drive(this).download(id, cached)
        trimCache()
        return cached
    }

    private fun trimCache() {
        val files = File(cacheDir, "media").listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < 500L shl 20) break; total -= f.length(); f.delete() }
    }

    private inner class DataHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? = try {
            when {
                path.startsWith("index/") -> {
                    val dir = Paths.indexDir(this@MainActivity)
                    val f = File(dir, path.removePrefix("index/"))
                    if (f.isFile && f.canonicalPath.startsWith(dir.canonicalPath))
                        WebResourceResponse("application/json", "utf-8", FileInputStream(f)) else notFound()
                }
                path.startsWith("raw/") -> {
                    val f = runBlocking { mediaFile(path.removePrefix("raw/")) }
                    if (f != null) WebResourceResponse(SyncWorker.mime(f), null, FileInputStream(f).buffered(1 shl 16))
                    else notFound()
                }
                else -> notFound()
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 502, "Error", null, (e.message ?: "error").byteInputStream())
        }
        private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, "".byteInputStream())
    }

    private inner class Bridge {
        @JavascriptInterface fun openSetup() = runOnUiThread { startActivity(Intent(this@MainActivity, SetupActivity::class.java)) }

        @JavascriptInterface fun status(): String = Status.get(this@MainActivity).toString()

        /** Saves a file into Downloads/WhatsApp Archive. */
        @JavascriptInterface fun save(path: String, name: String) {
            scope.launch {
                toastJs("Saving $name…")
                val msg = try {
                    withContext(Dispatchers.IO) {
                        val src = mediaFile(path) ?: throw IllegalStateException("This file was never backed up.")
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, SyncWorker.mime(src))
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WhatsApp Archive")
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("Could not create file in Downloads")
                        contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
                    }
                    "Saved to Downloads/WhatsApp Archive/$name"
                } catch (e: Exception) { e.message ?: "Download failed" }
                toastJs(msg)
            }
        }

        private fun toastJs(text: String) = runOnUiThread {
            web.evaluateJavascript("window.nativeToast && window.nativeToast(${JSONObject.quote(text)})", null)
        }
    }
}
