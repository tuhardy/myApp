package com.focusassistant.app.data

import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.focusassistant.app.domain.BilibiliMusicEntry
import com.focusassistant.app.domain.BilibiliMusicImport
import com.focusassistant.app.domain.MusicImportCandidate
import com.focusassistant.app.domain.MusicTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class BilibiliMusicSource(
    val candidate: MusicImportCandidate,
    val audioUri: Uri,
    val coverUri: Uri?,
    val metadata: BilibiliMusicEntry
)

/** 只读取用户当次授权的缓存主目录；确认导入后复制音频，不长期保留 SAF 授权。 */
internal object MusicImportScanner {
    private const val ENTRY_LIMIT = 1024 * 1024
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    )

    private data class Document(val id: String, val name: String, val mime: String, val size: Long, val uri: Uri)

    fun scan(context: Context, treeUri: Uri): List<BilibiliMusicSource> {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = queryDocument(resolver, rootUri)?.name ?: rootId.substringAfterLast('/')
        val rootChildren = children(resolver, treeUri, rootId)
        val entryText = rootChildren.firstOrNull { it.name == "entry.json" }?.let { document ->
            runCatching { readLimited(resolver, document.uri).toString(Charsets.UTF_8) }.getOrNull()
        }
        val metadata = BilibiliMusicImport.parseEntry(entryText, rootName)
        val cover = rootChildren.firstOrNull { it.name == "cover.jpg" && it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
        val mediaDirectories = rootChildren.filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
            .sortedBy { it.name }
        val sources = mediaDirectories.mapNotNull { directory ->
            val audio = children(resolver, treeUri, directory.id).firstOrNull { it.name == "audio.m4s" } ?: return@mapNotNull null
            val probe = if (audio.size > 0) runCatching { probeSeconds(resolver, audio.uri) } else null
            val actualSeconds = probe?.getOrNull()
            val duration = actualSeconds ?: metadata.referenceSeconds
            val error = when {
                !metadata.completed -> "缓存尚未下载完成"
                audio.size <= 0 -> "音频文件为空"
                probe?.isFailure == true -> "系统暂时无法识别这段音频"
                duration <= 0 -> "无法读取音频时长"
                else -> ""
            }
            BilibiliMusicSource(
                MusicImportCandidate(directory.id, directory.name,
                    BilibiliMusicImport.candidateTitle(metadata, directory.name, mediaDirectories.size), metadata.artist,
                    duration, audio.size, cover != null, error),
                audio.uri, cover?.uri, metadata
            )
        }
        if (sources.isEmpty()) throw IOException("所选目录的直接子文件夹中没有 audio.m4s")
        return sources
    }

    private fun queryDocument(resolver: android.content.ContentResolver, uri: Uri): Document? =
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.document(uri)
        }

    private fun children(resolver: android.content.ContentResolver, treeUri: Uri, parentId: String): List<Document> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = resolver.query(uri, projection, null, null, null)
            ?: throw IOException("无法读取所选文件夹")
        return cursor.use { result ->
            val documents = mutableListOf<Document>()
            while (result.moveToNext()) {
                val id = result.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                documents += result.document(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
            }
            documents
        }
    }

    private fun Cursor.document(uri: Uri): Document = Document(
        string(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
        string(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        string(DocumentsContract.Document.COLUMN_MIME_TYPE),
        long(DocumentsContract.Document.COLUMN_SIZE), uri
    )

    private fun Cursor.string(column: String): String = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }.orEmpty()
    private fun Cursor.long(column: String): Long = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) } ?: 0

    private fun readLimited(resolver: android.content.ContentResolver, uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取 entry.json")
        return input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (output.size() + count > ENTRY_LIMIT) throw IOException("entry.json 过大")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    /** 容器能由系统识别时才允许导入；时长缺失时才使用 entry.json 的参考值。 */
    private fun probeSeconds(resolver: android.content.ContentResolver, uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        try {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("无法打开音频")
            descriptor.use {
                retriever.setDataSource(it.fileDescriptor)
                val milliseconds = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
                return if (milliseconds > 0) milliseconds / 1000 + if (milliseconds % 1000 >= 500) 1 else 0 else null
            }
        } finally {
            retriever.release()
        }
    }
}

/** 每首歌曲独立目录，数据库只保存相对文件名；半成品不会作为正式曲库文件出现。 */
internal object MusicFiles {
    private fun root(context: Context): File = File(context.filesDir, "music").apply { mkdirs() }
    private fun trackDirectory(context: Context, id: String): File = File(root(context), id)
    fun cover(context: Context, track: MusicTrack): File? =
        track.coverFile.takeIf { it.isNotBlank() }?.let { File(root(context), it).takeIf(File::isFile) }

    fun import(context: Context, source: BilibiliMusicSource, id: String, title: String, artist: String): MusicTrack {
        val staging = File(root(context), ".$id.part")
        val target = trackDirectory(context, id)
        if (staging.exists() || target.exists()) throw IOException("歌曲保存位置冲突")
        if (!staging.mkdir()) throw IOException("无法创建歌曲目录")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val resolver = context.contentResolver
            val input = resolver.openInputStream(source.audioUri) ?: throw IOException("无法读取所选音频")
            val bytes = input.use { sourceStream ->
                File(staging, "audio.m4s").outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        total += count
                    }
                    total
                }
            }
            if (bytes <= 0) throw IOException("音频文件为空")
            source.coverUri?.let { uri ->
                val coverFile = File(staging, "cover.jpg")
                runCatching {
                    resolver.openInputStream(uri)?.use { coverInput ->
                        coverFile.outputStream().use { output -> coverInput.copyTo(output) }
                    }
                }.onFailure { coverFile.delete() }
            }
            if (!staging.renameTo(target)) throw IOException("音频保存失败")
            return MusicTrack(id, title, artist, source.candidate.durationSeconds,
                "$id/audio.m4s", if (File(target, "cover.jpg").isFile) "$id/cover.jpg" else "",
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, bytes,
                System.currentTimeMillis(), source.metadata.bvid, source.metadata.cid)
        } catch (error: Exception) {
            staging.deleteRecursively()
            target.deleteRecursively()
            throw if (error is IOException) error else IOException("音频保存失败，可能是存储空间不足或文件不可读", error)
        }
    }

    fun delete(context: Context, id: String) { trackDirectory(context, id).deleteRecursively() }
    fun cleanOrphans(context: Context, referencedIds: Set<String>) {
        root(context).listFiles()?.forEach { file -> if (file.name !in referencedIds) file.deleteRecursively() }
    }
}
