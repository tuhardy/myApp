package com.focusassistant.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import com.focusassistant.app.domain.DiaryPhoto
import com.focusassistant.app.domain.DiaryRules
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 日记附件保存在应用私有目录。照片复制一份，不依赖相册原图或临时授权；
 * 数据库里只记录文件名，日志和界面都不输出绝对路径。
 */
object DiaryFiles {
    private const val DIRECTORY = "diary"
    private const val PHOTO_PREFIX = "photo-"
    private const val AUDIO_PREFIX = "audio-"
    private const val AUDIO_EXTENSION = "m4a"
    private const val PARTIAL_SUFFIX = ".part"
    private const val DEFAULT_PHOTO_EXTENSION = "img"
    private val EXTENSION = Regex("""^[a-z0-9]{1,5}$""")

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    fun file(context: Context, name: String): File {
        require(DiaryRules.isSafeFileName(name)) { "附件文件名无效" }
        return File(directory(context), name)
    }

    fun exists(context: Context, name: String): Boolean = DiaryRules.isSafeFileName(name) && file(context, name).let { it.isFile && it.length() > 0 }

    fun newAudioFile(context: Context): File = File(directory(context), "$AUDIO_PREFIX${UUID.randomUUID()}.$AUDIO_EXTENSION")

    /** 复制用户选中的照片；无法读取或系统无法解码时删除半成品并说明原因，不影响已输入内容。 */
    fun importPhoto(context: Context, uri: Uri): DiaryPhoto {
        val mime = context.contentResolver.getType(uri)
        val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.lowercase()?.takeIf(EXTENSION::matches) ?: DEFAULT_PHOTO_EXTENSION
        val name = "$PHOTO_PREFIX${UUID.randomUUID()}.$extension"
        val target = File(directory(context), name)
        val partial = File(directory(context), name + PARTIAL_SUFFIX)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选照片")
            input.use { source -> partial.outputStream().use { source.copyTo(it) } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(partial.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "这张图片的格式当前系统无法显示" }
            if (!partial.renameTo(target)) throw IOException("照片保存失败")
            return DiaryPhoto(UUID.randomUUID().toString(), name)
        } catch (error: Exception) {
            partial.delete()
            target.delete()
            throw if (error is IllegalArgumentException) error else IOException("照片保存失败，可能是存储空间不足或文件不可读", error)
        }
    }

    fun delete(context: Context, names: Collection<String>) {
        names.filter(DiaryRules::isSafeFileName).forEach { file(context, it).delete() }
    }

    /** 清理未被任何正式日记引用的文件和中断留下的半成品。 */
    fun cleanOrphans(context: Context, referenced: Set<String>) {
        directory(context).listFiles()?.forEach { file ->
            if (file.isFile && file.name !in referenced) file.delete()
        }
    }
}
