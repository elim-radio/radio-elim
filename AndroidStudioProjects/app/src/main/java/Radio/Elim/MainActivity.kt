package Radio.Elim

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity() {
    companion object {
        // iOS and Android use the same GitHub Pages site.
        private const val APP_URL = "https://elim-radio.github.io/radio-elim/"
    }

    private var appWebView: WebView? = null
    private var playbackConnection: NativeAudioConnection? = null
    private var loading by mutableStateOf(true)
    private var pageError by mutableStateOf<String?>(null)
    private val pageLoad = PageLoadState()
    private var backgroundSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var loadTimeout: Runnable? = null
    private var readinessTimeout: Runnable? = null

    private fun isAppPage(uri: Uri): Boolean = PlaybackRules.isDocument(uri.toString())

    private fun exitApp() {
        playbackConnection?.stopPlayback()
        finishAndRemoveTask()
    }

    private fun cancelLoadTimeout() {
        loadTimeout?.let(handler::removeCallbacks)
        readinessTimeout?.let(handler::removeCallbacks)
        loadTimeout = null
        readinessTimeout = null
    }

    private fun updatePageUi() {
        loading = pageLoad.phase == PageLoadState.Phase.LOADING
        pageError = pageLoad.message
    }

    private fun isActivePage(view: WebView, id: String): Boolean =
        appWebView === view && pageLoad.isCurrent(id)

    private fun pageIdFor(view: WebView, url: String): String? {
        val uri = Uri.parse(url)
        if (!isAppPage(uri)) return null
        val id = uri.getQueryParameter("_app_reload") ?: return null
        return id.takeIf { isActivePage(view, it) }
    }

    private fun failPage(id: String, message: String) {
        if (!pageLoad.fail(id, message)) return
        cancelLoadTimeout()
        updatePageUi()
    }

    private fun checkPageReady(view: WebView, id: String, onNotReady: () -> Unit = {}) {
        if (!isActivePage(view, id) || !pageLoad.awaitingReady(id)) return
        view.evaluateJavascript(
            """
            (() => {
                if (window.__elimPageId !== ${JSONObject.quote(id)} ||
                    !window.__elimNativeReady ||
                    typeof window.__elimIsPageReady !== 'function' ||
                    !window.__elimIsPageReady()) return false;
                let style = document.getElementById('elim-android-viewport');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'elim-android-viewport';
                    style.textContent = 'html,body{height:100vh!important;height:100dvh!important;min-height:100vh;min-height:100dvh}#app{height:100%;min-height:100%}';
                    document.head.appendChild(style);
                }
                if (!window.__elimAndroidLinks) {
                    document.addEventListener('click', function(event) {
                        const anchor = event.target.closest && event.target.closest('a[target="_blank"]');
                        if (anchor) anchor.target = '_self';
                    }, true);
                    window.__elimAndroidLinks = true;
                }
                return true;
            })();
            """.trimIndent()
        ) { ready ->
            // Results from a replaced WebView/document must never change this screen.
            if (isActivePage(view, id) && pageLoad.awaitingReady(id)) {
                if (ready == "true") {
                    pageLoad.markReady(id)
                    cancelLoadTimeout()
                    updatePageUi()
                } else {
                    onNotReady()
                }
            }
        }
    }

    private fun scheduleLoadTimeout(view: WebView, id: String) {
        cancelLoadTimeout()
        loadTimeout = Runnable {
            if (!isActivePage(view, id) || !pageLoad.awaitingReady(id)) return@Runnable
            val showDelay = Runnable {
                if (isActivePage(view, id) && pageLoad.markDelayed(
                        id, "연결이 지연되고 있습니다. 잠시 기다리거나 다시 시도해 주세요."
                    )) updatePageUi()
            }
            // Bound the probe too, in case the renderer cannot answer JavaScript.
            readinessTimeout = showDelay
            handler.postDelayed(showDelay, 1_000)
            checkPageReady(view, id) {
                handler.removeCallbacks(showDelay)
                showDelay.run()
            }
            // Do not stopLoading(): the same document can still become ready.
        }.also { handler.postDelayed(it, 25_000) }
    }

    private fun loadLatestPage(target: Uri = Uri.parse(APP_URL)) {
        val view = appWebView ?: return
        val id = UUID.randomUUID().toString()
        cancelLoadTimeout()
        pageLoad.begin(id)
        updatePageUi()
        if (playbackConnection == null) {
            failPage(id, "Google Play에서 Android 시스템 WebView와 Chrome을 업데이트한 뒤 앱을 다시 열어 주세요.")
            return
        }
        playbackConnection?.beginPage(id)
        // Invalidate the previous document before stopLoading can deliver callbacks.
        view.stopLoading()
        // A unique URL binds native callbacks and bridge messages to one navigation.
        // Preserve the site's own query parameters and same-origin localStorage.
        val builder = target.buildUpon().clearQuery()
        target.queryParameterNames.filter { it != "_app_reload" }.forEach { name ->
            target.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        val latest = builder.appendQueryParameter("_app_reload", id).build().toString()
        scheduleLoadTimeout(view, id)
        view.loadUrl(latest, mapOf("Cache-Control" to "no-cache"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                BackHandler {
                    val view = appWebView
                    if (view == null || pageError != null) {
                        exitApp()
                    } else {
                        view.evaluateJavascript(
                            """
                            (() => {
                                if (typeof currentRoute === 'undefined') return false;
                                if (currentRoute === 'player' && typeof backToList === 'function') {
                                    backToList(); return true;
                                }
                                if (currentRoute === 'favorites' && typeof navigate === 'function') {
                                    navigate('home'); return true;
                                }
                                return false;
                            })();
                            """.trimIndent()
                        ) { handled ->
                            if (handled != "true") {
                                // Single-page app: do not reopen stale refresh URLs.
                                exitApp()
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                appWebView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView, request: WebResourceRequest
                                    ): Boolean {
                                        if (isAppPage(request.url)) {
                                            if (!request.isForMainFrame) return false
                                            // App-owned navigations also receive a fresh page ID.
                                            if (appWebView === view) loadLatestPage(request.url)
                                            return true
                                        }
                                        if (request.isForMainFrame && request.hasGesture() &&
                                            request.url.scheme in listOf("https", "http", "mailto", "tel")) {
                                            try {
                                                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                            } catch (_: ActivityNotFoundException) {
                                                Toast.makeText(context, "링크를 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        return true
                                    }

                                    override fun onReceivedError(
                                        view: WebView, request: WebResourceRequest, error: WebResourceError
                                    ) {
                                        if (request.isForMainFrame) {
                                            val id = pageIdFor(view, request.url.toString()) ?: return
                                            failPage(id, "인터넷 연결을 확인한 뒤 다시 시도해 주세요. (오류 ${error.errorCode})")
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView, request: WebResourceRequest, response: WebResourceResponse
                                    ) {
                                        if (request.isForMainFrame) {
                                            val id = pageIdFor(view, request.url.toString()) ?: return
                                            failPage(id, "웹페이지에 연결할 수 없습니다. (HTTP ${response.statusCode})")
                                        }
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView, sslHandler: SslErrorHandler, error: SslError
                                    ) {
                                        sslHandler.cancel()
                                        if (error.url == view.url) {
                                            val id = pageIdFor(view, error.url) ?: return
                                            failPage(id, "보안 연결을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.")
                                        }
                                    }

                                    override fun onRenderProcessGone(
                                        view: WebView, detail: RenderProcessGoneDetail
                                    ): Boolean {
                                        (view.parent as? ViewGroup)?.removeView(view)
                                        if (appWebView === view) {
                                            pageLoad.pageId?.let { failPage(it, "웹 화면이 종료되었습니다. 다시 시도해 주세요.") }
                                            playbackConnection?.release()
                                            playbackConnection = null
                                            appWebView = null
                                        }
                                        view.destroy()
                                        return true
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        val id = pageIdFor(view, url) ?: return
                                        // Fallback only: ready messages do not wait for external assets.
                                        checkPageReady(view, id)
                                    }
                                }
                                if (NativeAudioConnection.isSupported()) {
                                    val view = this
                                    playbackConnection = NativeAudioConnection(
                                        view,
                                        onPageReady = { id -> checkPageReady(view, id) },
                                        onFailure = { message ->
                                            if (appWebView === view) pageLoad.pageId?.let { failPage(it, message) }
                                        }
                                    )
                                }
                                loadLatestPage()
                            }
                        },
                        onRelease = { view ->
                            if (appWebView === view) {
                                cancelLoadTimeout()
                                pageLoad.clear()
                                playbackConnection?.release()
                                playbackConnection = null
                                appWebView = null
                                view.stopLoading()
                                view.destroy()
                            }
                        }
                    )

                    if (loading || pageError != null) {
                        Column(
                            modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("라디오 엘림", style = MaterialTheme.typography.titleLarge)
                            if (pageError == null) {
                                CircularProgressIndicator()
                                Text("최신 방송 목록을 불러오고 있습니다.", textAlign = TextAlign.Center)
                            } else {
                                Text(pageError.orEmpty(), textAlign = TextAlign.Center)
                                Button(onClick = {
                                    if (appWebView == null) recreate() else loadLatestPage()
                                }) { Text("다시 시도") }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appWebView?.onResume()
        playbackConnection?.setForeground(true)
        // Refresh after a longer break, never during active use.
        if (backgroundSince > 0 && SystemClock.elapsedRealtime() - backgroundSince > 60_000 &&
            playbackConnection?.hasActivePlayback == false) {
            loadLatestPage()
        }
        backgroundSince = 0L
    }

    override fun onStop() {
        // The service owns playback; only suspend UI synchronization here.
        playbackConnection?.setForeground(false)
        appWebView?.onPause()
        backgroundSince = SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onDestroy() {
        cancelLoadTimeout()
        pageLoad.clear()
        playbackConnection?.release()
        super.onDestroy()
    }
}
