package com.focusassistant.app.platform

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.focusassistant.app.FocusApplication
import com.focusassistant.app.data.MusicFiles
import com.focusassistant.app.domain.MusicListen
import com.focusassistant.app.domain.MusicListenSession
import com.focusassistant.app.domain.MusicPlayMode
import com.focusassistant.app.domain.MusicPlaybackSnapshot
import com.focusassistant.app.domain.MusicTrack
import kotlinx.coroutines.launch
import java.util.UUID

private const val MUSIC_SAVE_INTERVAL_MS = 15_000L

/** 本机音频的媒体会话与前台服务；只有用户开始播放后才进入后台播放状态。 */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var recorder: ListenRecorder

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val activity = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        session = MediaSession.Builder(this, player).apply {
            if (activity != null) setSessionActivity(activity)
        }.build()
        recorder = ListenRecorder(player)
        player.addListener(recorder)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        recorder.finish()
        player.removeListener(recorder)
        session.release()
        player.release()
        super.onDestroy()
    }

    /** 墙钟只累计 isPlaying 的真实时间；拖动、缓冲和暂停不计入收听秒数。 */
    private inner class ListenRecorder(private val player: Player) : Player.Listener {
        private val app = application as FocusApplication
        private val handler = Handler(Looper.getMainLooper())
        private var sessionId: String? = null
        private var trackId: String? = null
        private var playedAt = 0L
        private var accumulatedMs = 0L
        private var playingSince: Long? = null
        private var savedSeconds = 0L
        private val periodicSave = object : Runnable {
            override fun run() {
                if (!player.isPlaying) return
                persist()
                handler.postDelayed(this, MUSIC_SAVE_INTERVAL_MS)
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val now = SystemClock.elapsedRealtime()
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                finish(now)
                begin(now)
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) {
                    if (playingSince == null) playingSince = now
                    handler.removeCallbacks(periodicSave)
                    handler.postDelayed(periodicSave, MUSIC_SAVE_INTERVAL_MS)
                } else {
                    accumulate(now)
                    persist()
                    handler.removeCallbacks(periodicSave)
                }
            }
        }

        private fun begin(now: Long) {
            trackId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
            sessionId = UUID.randomUUID().toString()
            playedAt = System.currentTimeMillis()
            accumulatedMs = 0
            savedSeconds = 0
            playingSince = if (player.isPlaying) now else null
        }

        private fun accumulate(now: Long) {
            playingSince?.let { accumulatedMs += (now - it).coerceAtLeast(0) }
            playingSince = null
        }

        private fun persist() {
            val id = sessionId ?: return
            val track = trackId ?: return
            val seconds = MusicListenSession.seconds(accumulatedMs, playingSince, SystemClock.elapsedRealtime())
            if (!MusicListenSession.recordable(seconds) || seconds <= savedSeconds) return
            savedSeconds = seconds
            app.appScope.launch { runCatching { app.repository.recordMusicListen(MusicListen(id, track, playedAt, seconds)) } }
        }

        fun finish(now: Long = SystemClock.elapsedRealtime()) {
            handler.removeCallbacks(periodicSave)
            accumulate(now)
            persist()
            sessionId = null
            trackId = null
            playingSince = null
        }
    }
}

/** UI 只连接控制器；释放界面连接不会停止 MediaSessionService 中的播放。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MusicPlaybackClient(
    context: Context,
    private val onUpdate: (MusicPlaybackSnapshot) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private var controller: MediaController? = null
    private var released = false
    private val future = MediaController.Builder(appContext, SessionToken(appContext,
        android.content.ComponentName(appContext, MusicPlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { emit() }
        override fun onPlayerError(error: PlaybackException) {
            onError("无法播放当前音频，系统可能不支持其编码或分片格式；曲库文件仍保留。")
            emit()
        }
    }

    init {
        future.addListener({
            if (released) return@addListener
            try {
                controller = future.get().also { it.addListener(listener) }
                emit()
            } catch (error: Exception) {
                if (!released) onError("无法连接本机播放服务，请重试。")
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun play(tracks: List<MusicTrack>, trackId: String) {
        val current = controller
        if (current == null) {
            onError("播放服务正在连接，请稍候再试。")
            return
        }
        if (current.currentMediaItem?.mediaId == trackId) {
            toggle()
            return
        }
        val items = tracks.mapNotNull { track -> mediaItem(track)?.let { track.id to it } }
        val index = items.indexOfFirst { it.first == trackId }
        if (index < 0) {
            onError("这首歌曲的音频文件已不可用，请重新导入。")
            return
        }
        current.setMediaItems(items.map { it.second }, index, 0)
        current.prepare()
        current.play()
        emit()
    }

    fun toggle() {
        val current = controller ?: return
        if (current.isPlaying) current.pause() else {
            if (current.playbackState == Player.STATE_ENDED) current.seekTo(0)
            current.play()
        }
        emit()
    }

    fun next() { controller?.seekToNext(); emit() }
    fun previous() { controller?.seekToPrevious(); emit() }
    fun seekTo(positionMs: Long) {
        val current = controller ?: return
        val duration = current.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
        current.seekTo(positionMs.coerceIn(0, duration))
        emit()
    }

    fun setMode(mode: MusicPlayMode) {
        val current = controller ?: return
        when (mode) {
            MusicPlayMode.SEQUENTIAL -> { current.shuffleModeEnabled = false; current.repeatMode = Player.REPEAT_MODE_OFF }
            MusicPlayMode.REPEAT_ALL -> { current.shuffleModeEnabled = false; current.repeatMode = Player.REPEAT_MODE_ALL }
            MusicPlayMode.REPEAT_ONE -> { current.shuffleModeEnabled = false; current.repeatMode = Player.REPEAT_MODE_ONE }
            MusicPlayMode.SHUFFLE -> { current.repeatMode = Player.REPEAT_MODE_ALL; current.shuffleModeEnabled = true }
        }
        emit()
    }

    fun move(from: Int, to: Int) {
        val current = controller ?: return
        if (from in 0 until current.mediaItemCount && to in 0 until current.mediaItemCount) current.moveMediaItem(from, to)
        emit()
    }

    fun remove(index: Int) {
        val current = controller ?: return
        if (index in 0 until current.mediaItemCount) current.removeMediaItem(index)
        emit()
    }

    fun clearUpcoming() {
        val current = controller ?: return
        val selected = current.currentMediaItemIndex
        for (index in current.mediaItemCount - 1 downTo 0) {
            if (selected < 0 || index != selected) current.removeMediaItem(index)
        }
        emit()
    }

    fun tick() = emit()

    fun release() {
        released = true
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        controller = null
    }

    private fun mediaItem(track: MusicTrack): MediaItem? {
        val audio = MusicFiles.audio(appContext, track) ?: return null
        val metadata = MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setArtworkUri(MusicFiles.cover(appContext, track)?.let(Uri::fromFile)).build()
        return MediaItem.Builder().setMediaId(track.id).setUri(Uri.fromFile(audio)).setMediaMetadata(metadata).build()
    }

    private fun emit() {
        val current = controller
        if (current == null) {
            onUpdate(MusicPlaybackSnapshot())
            return
        }
        val duration = current.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        val mode = when {
            current.shuffleModeEnabled -> MusicPlayMode.SHUFFLE
            current.repeatMode == Player.REPEAT_MODE_ONE -> MusicPlayMode.REPEAT_ONE
            current.repeatMode == Player.REPEAT_MODE_ALL -> MusicPlayMode.REPEAT_ALL
            else -> MusicPlayMode.SEQUENTIAL
        }
        onUpdate(MusicPlaybackSnapshot(current.currentMediaItem?.mediaId, current.isPlaying,
            current.currentPosition.coerceAtLeast(0), duration,
            List(current.mediaItemCount) { index -> current.getMediaItemAt(index).mediaId }, mode, connected = true))
    }
}
