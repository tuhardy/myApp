package com.focusassistant.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.focusassistant.app.data.DiaryFiles
import com.focusassistant.app.domain.DiaryAudio
import com.focusassistant.app.domain.DiaryRules
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 停止录音的结果：成功时给出附件；过短或设备中断时附件为空并说明原因，不伪装成保存成功。 */
data class RecordingResult(val audio: DiaryAudio?, val failure: String?)

/**
 * 原声录音：只在用户点击时开始，调用方在离开日记、切到后台或锁屏时必须 stop()，不在后台继续录音。
 */
class DiaryRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L
    val recording: Boolean get() = recorder != null

    fun start(onInterrupted: () -> Unit) {
        check(recorder == null) { "已在录音" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("未获得麦克风权限，文字和照片仍可使用")
        }
        val file = DiaryFiles.newAudioFile(context)
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioSamplingRate(SAMPLE_RATE)
            next.setAudioEncodingBitRate(BIT_RATE)
            next.setOutputFile(file.path)
            next.setOnErrorListener { _, _, _ -> onInterrupted() }
            next.prepare()
            next.start()
        } catch (error: Exception) {
            next.release()
            file.delete()
            throw IOException("无法开始录音，麦克风可能正被其他应用使用", error)
        }
        recorder = next
        output = file
        startedAt = SystemClock.elapsedRealtime()
    }

    fun elapsedMs(): Long = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    fun stop(): RecordingResult? {
        val current = recorder ?: return null
        val file = requireNotNull(output)
        val elapsed = elapsedMs()
        recorder = null
        output = null
        val stopped = try { current.stop(); true } catch (error: RuntimeException) { false } finally { current.release() }
        val duration = if (stopped) measuredDuration(file) ?: elapsed else 0L
        return when {
            elapsed < DiaryRules.MIN_AUDIO_MS -> { file.delete(); RecordingResult(null, "录音不足 1 秒，未保留") }
            !stopped || duration < DiaryRules.MIN_AUDIO_MS || file.length() == 0L -> { file.delete(); RecordingResult(null, "录音被中断，未能形成有效录音") }
            else -> RecordingResult(DiaryAudio(UUID.randomUUID().toString(), file.name, duration), null)
        }
    }

    private fun measuredDuration(file: File): Long? = try {
        MediaMetadataRetriever().run {
            try { setDataSource(file.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() }
        }
    } catch (error: RuntimeException) { null }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 96_000
    }
}

/** 同一时间只播放一段原声。 */
class DiaryAudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    var file: String? = null
        private set

    fun play(name: String, onDone: () -> Unit) {
        stop()
        val source = DiaryFiles.file(context, name)
        if (!source.isFile) throw IOException("录音文件已不可用")
        val next = MediaPlayer()
        try {
            next.setDataSource(source.path)
            next.setOnCompletionListener { stop(); onDone() }
            next.setOnErrorListener { _, _, _ -> stop(); onDone(); true }
            next.prepare()
            next.start()
        } catch (error: Exception) {
            next.release()
            throw IOException("无法播放这段录音", error)
        }
        player = next
        file = name
    }

    fun positionMs(): Long = player?.currentPosition?.toLong() ?: 0L

    fun stop() {
        player?.release()
        player = null
        file = null
    }
}

/** 按显示尺寸降采样并按 EXIF 方向旋转，避免把原图整张读入内存。 */
object DiaryImages {
    private const val CACHE_BYTES = 24 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(context: Context, name: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$name@$maxPx"
        cache.get(key) ?: decode(DiaryFiles.file(context, name), maxPx)?.also { cache.put(key, it) }
    }

    private fun decode(file: File, maxPx: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (error: IOException) { 0f }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }
}
