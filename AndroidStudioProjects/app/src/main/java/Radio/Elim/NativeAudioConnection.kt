package Radio.Elim

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** UI-side controller only. Releasing this connection must not stop the service. */
@androidx.annotation.OptIn(UnstableApi::class)
class NativeAudioConnection(
    private val view: WebView,
    private val onPageReady: (String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    companion object {
        fun isSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }

    private val context = view.context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var ready = false
    private var pageId: String? = null
    private var foreground = true
    private var released = false
    private val pending = ArrayDeque<JSONObject>()
    private var bridgeError: String? = null
    val hasActivePlayback: Boolean get() = controller == null || controller?.playWhenReady == true
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish() }
    }
    private val ticker = object : Runnable {
        override fun run() {
            publish()
            if (foreground && !released) handler.postDelayed(this, 500)
        }
    }
    private val future = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()

    init {
        check(isSupported())
        WebViewCompat.addWebMessageListener(view, "ElimNative", setOf(PlaybackRules.ORIGIN)) {
            sourceView, message, sourceOrigin, isMainFrame, _ ->
            if (!released && isMainFrame && sourceOrigin.toString().trimEnd('/') == PlaybackRules.ORIGIN &&
                PlaybackRules.isDocument(sourceView.url.orEmpty())) {
                runCatching {
                    val raw = message.data.orEmpty()
                    require(raw.length <= 1_000_000)
                    val data = JSONObject(raw)
                    val activePage = pageId ?: return@addWebMessageListener
                    if (data.optString("pageId") != activePage ||
                        Uri.parse(sourceView.url).getQueryParameter("_app_reload") != activePage) {
                        return@addWebMessageListener
                    }
                    if (data.optString("type") == "ready") {
                        ready = true
                        onPageReady(activePage)
                        publish()
                    } else if (data.optString("type") == "sync") {
                        if (ready) publish()
                    } else if (!ready) {
                        return@addWebMessageListener
                    } else if (controller == null) {
                        require(pending.size < 20)
                        pending.addLast(data)
                    } else handle(data)
                }.onFailure {
                    bridgeError = "재생 요청을 처리하지 못했습니다. 다시 재생해 주세요."
                    publish()
                }
            }
        }
        val script = context.assets.open("native-audio-bridge.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(view, script, setOf(PlaybackRules.ORIGIN))
        future.addListener({
            if (!released) {
                runCatching {
                    controller = future.get()
                    controller?.addListener(listener)
                    while (pending.isNotEmpty()) handle(pending.removeFirst())
                    publish()
                }.onFailure { onFailure("재생 서비스에 연결하지 못했습니다. 앱을 다시 열어 주세요.") }
            }
        }, ContextCompat.getMainExecutor(context))
        handler.post(ticker)
    }

    fun beginPage(id: String) {
        pageId = id
        ready = false
        pending.clear()
        bridgeError = null
    }

    fun stopPlayback() {
        pending.clear()
        controller?.let { player ->
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        handler.removeCallbacks(ticker)
        if (value && !released) handler.post(ticker)
    }

    private fun handle(data: JSONObject) {
        val player = controller ?: return
        bridgeError = null
        when (data.getString("type")) {
            "play" -> {
                val items = PlaybackItems.queue(data.getJSONArray("queue"))
                val index = items.indexOfFirst { it.mediaId == data.getString("id") }
                require(index >= 0)
                val selected = items[index]
                val same = player.currentMediaItem?.let {
                    it.mediaId == selected.mediaId && PlaybackItems.toJson(it).optString("src") ==
                        PlaybackItems.toJson(selected).optString("src")
                } == true
                val queueChanged = player.mediaItemCount != items.size || items.indices.any { i ->
                    val old = PlaybackItems.toJson(player.getMediaItemAt(i))
                    val fresh = PlaybackItems.toJson(items[i])
                    listOf("id", "src", "title", "date", "tab", "youtubeId").any { key ->
                        old.optString(key) != fresh.optString(key)
                    }
                }
                if (!same || queueChanged) {
                    val prefs = context.getSharedPreferences("elim_native_playback", Context.MODE_PRIVATE)
                    val requested = data.optDouble("position", 0.0)
                        .takeIf { it.isFinite() && it >= 0 } ?: 0.0
                    val start = if (same && player.playbackState == Player.STATE_ENDED) 0L
                        else if (same) player.currentPosition else
                        prefs.getLong("position_${selected.mediaId}", (requested * 1000).toLong())
                    player.setMediaItems(items, index, start.coerceAtLeast(0))
                } else if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                }
                player.setPlaybackSpeed(PlaybackRules.speed(data.optDouble("speed", 1.0).toFloat()))
                if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
                player.play()
            }
            "pause" -> player.pause()
            "seek" -> {
                val seconds = data.getDouble("position")
                require(seconds.isFinite() && seconds >= 0)
                val max = if (player.duration > 0) player.duration else Long.MAX_VALUE
                player.seekTo((seconds * 1000).toLong().coerceIn(0, max))
            }
            "speed" -> player.setPlaybackSpeed(PlaybackRules.speed(data.getDouble("speed").toFloat()))
            "stop" -> { player.pause(); player.stop(); player.clearMediaItems() }
            else -> error("Unknown playback command")
        }
        publish()
    }

    private fun publish() {
        if (!ready || !foreground || released) return
        val player = controller ?: return
        val item = player.currentMediaItem
        val state = JSONObject()
            .put("pageId", pageId)
            .put("item", item?.let(PlaybackItems::toJson) ?: JSONObject.NULL)
            .put("position", player.currentPosition.coerceAtLeast(0) / 1000.0)
            .put("duration", player.duration.coerceAtLeast(0) / 1000.0)
            .put("paused", !player.playWhenReady || player.playbackState == Player.STATE_ENDED || player.playerError != null)
            .put("isPlaying", player.isPlaying)
            .put("buffering", player.playbackState == Player.STATE_BUFFERING)
            .put("ended", player.playbackState == Player.STATE_ENDED)
            .put("speed", player.playbackParameters.speed.toDouble())
            .put("error", bridgeError ?: player.playerError?.let { "음원을 불러오지 못했습니다. 인터넷 연결을 확인한 뒤 다시 재생해 주세요." } ?: JSONObject.NULL)
        view.evaluateJavascript("window.__elimNativeApply && window.__elimNativeApply($state);", null)
    }

    fun release() {
        if (released) return
        released = true
        pageId = null
        handler.removeCallbacksAndMessages(null)
        controller?.removeListener(listener)
        controller = null
        pending.clear()
        MediaController.releaseFuture(future)
    }
}
