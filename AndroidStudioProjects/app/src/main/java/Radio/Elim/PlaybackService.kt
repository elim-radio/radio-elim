package Radio.Elim

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("elim_native_playback", MODE_PRIVATE) }
    private val saveTask = object : Runnable {
        override fun run() {
            if (player.isPlaying) savePosition()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val openApp = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>
                ): ListenableFuture<List<MediaItem>> {
                    val safe = mediaItems.size <= PlaybackRules.MAX_QUEUE && mediaItems.all {
                        PlaybackRules.isAudio(it.localConfiguration?.uri?.toString().orEmpty())
                    }
                    return if (safe) Futures.immediateFuture(mediaItems)
                    else Futures.immediateFailedFuture(IllegalArgumentException("지원하지 않는 음원 주소입니다."))
                }
            }).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_stat_elim)
        })
        restorePausedQueue()
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { saveQueue() }
            override fun onEvents(player: Player, events: Player.Events) { savePosition() }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) {
                oldPosition.mediaItem?.mediaId?.let { id ->
                    val position = if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) 0L
                        else oldPosition.positionMs.coerceAtLeast(0)
                    prefs.edit().putLong("position_$id", position).apply()
                }
            }
        })
        handler.postDelayed(saveTask, 5000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.uid == Process.myUid() || controllerInfo.isTrusted) session else null

    private fun savePosition() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
        prefs.edit().putLong("position_${item.mediaId}", position)
            .putString("last_id", item.mediaId)
            .putFloat("speed", player.playbackParameters.speed).apply()
    }

    private fun saveQueue() {
        val queue = JSONArray()
        for (index in 0 until player.mediaItemCount) queue.put(PlaybackItems.toJson(player.getMediaItemAt(index)))
        prefs.edit().putString("queue", queue.toString()).apply()
        if (player.mediaItemCount == 0) prefs.edit().remove("last_id").apply()
    }

    private fun restorePausedQueue() {
        runCatching {
            val array = JSONArray(prefs.getString("queue", "[]"))
            if (array.length() == 0) return
            val items = PlaybackItems.queue(array)
            val index = items.indexOfFirst { it.mediaId == prefs.getString("last_id", "") }.coerceAtLeast(0)
            player.setMediaItems(items, index, prefs.getLong("position_${items[index].mediaId}", 0))
            player.setPlaybackSpeed(PlaybackRules.speed(prefs.getFloat("speed", 1f)))
            // Never start audio without an explicit play action after process death.
            player.playWhenReady = false
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Home / screen-off keep playing. Dismissing this task is an explicit exit.
        savePosition()
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
        session?.release()
        session = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
