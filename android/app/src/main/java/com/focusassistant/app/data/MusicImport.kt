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

internal data class BilibiliMusicScan(val sources: List<BilibiliMusicSource>, val skipped: Int)

internal data class BoundMusicDirectory(val uri: Uri, val label: String)

/** 只持久化用户可访问的共享目录授权；不针对其他应用的 Android/data。 */
internal object MusicCacheBinding {
    private const val PREFERENCES = "music-import-directory"
    private const val TREE = "tree"

    fun bind(context: Context, uri: Uri): BoundMusicDirectory {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(TREE, null)?.takeIf { it != uri.toString() }?.let { previous ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        preferences.edit().putString(TREE, uri.toString()).apply()
        return BoundMusicDirectory(uri, BilibiliMusicImport.treeLabel(DocumentsContract.getTreeDocumentId(uri)))
    }

    fun current(context: Context): BoundMusicDirectory? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val value = preferences.getString(TREE, null) ?: return null
        val uri = Uri.parse(value)
        val readable = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!readable) {
            clear(context)
            return null
        }
        return BoundMusicDirectory(uri, BilibiliMusicImport.treeLabel(DocumentsContract.getTreeDocumentId(uri)))
    }

    fun clear(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(TREE, null)?.let { value ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(value), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        preferences.edit().remove(TREE).apply()
    }
}

/** 支持单个/并列 c_*、av号/c_*，以及整个复制来的 download/av号/c_*。 */
internal object MusicImportScanner {
    private const val ENTRY_LIMIT = 1024 * 1024
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    )

    private data class Document(val id: String, val name: String, val mime: String, val size: Long, val uri: Uri)

    private data class CacheRoot(val document: Document, val children: List<Document>)

    fun scan(context: Context, treeUri: Uri, importedCids: Set<Long> = emptySet()): BilibiliMusicScan {
        val resolver = context.contentResolver
        val sources = mutableListOf<BilibiliMusicSource>()
        var skipped = 0
        cacheRoots(resolver, treeUri).forEach cache@{ root ->
            // 规范的 c_<cid> 目录无需打开音频即可排除；entry.json 中的 cid 作为另一种缓存命名的补充。
            val directoryCid = BilibiliMusicImport.cacheCid(root.document.name)
            if (directoryCid > 0 && directoryCid in importedCids) {
                skipped++
                return@cache
            }
            val entryText = root.children.firstOrNull { it.name == "entry.json" }?.let { document ->
                runCatching { readLimited(resolver, document.uri).toString(Charsets.UTF_8) }.getOrNull()
            }
            val parsed = BilibiliMusicImport.parseEntry(entryText, root.document.name)
            val cid = BilibiliMusicImport.sourceCid(parsed, root.document.name)
            // 把目录名回退也写入 sourceCid，缺失 entry.json 的缓存下次仍能提前跳过。
            val metadata = parsed.copy(cid = cid)
            if (cid > 0 && cid in importedCids) {
                skipped++
                return@cache
            }
            val cover = root.children.firstOrNull { it.name == "cover.jpg" && it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
            val mediaDirectories = root.children.filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
                .sortedBy { it.name }
            mediaDirectories.forEach audioDirectory@{ directory ->
                val audio = children(resolver, treeUri, directory.id).firstOrNull { it.name == "audio.m4s" } ?: return@audioDirectory
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
                sources += BilibiliMusicSource(
                    MusicImportCandidate(directory.id, directory.name,
                        BilibiliMusicImport.candidateTitle(metadata, directory.name, mediaDirectories.size), metadata.artist,
                        duration, audio.size, cover != null, error),
                    audio.uri, cover?.uri, metadata
                )
            }
        }
        return BilibiliMusicScan(sources, skipped)
    }

    private fun cacheRoots(resolver: android.content.ContentResolver, treeUri: Uri): List<CacheRoot> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val root = queryDocument(resolver, rootUri) ?: throw IOException("无法读取所选文件夹")
        val result = mutableListOf<CacheRoot>()
        fun visit(document: Document, documentChildren: List<Document>, depth: Int) {
            if (isCache(document, documentChildren)) {
                result += CacheRoot(document, documentChildren)
                return
            }
            if (depth >= BilibiliMusicImport.CACHE_DEPTH) return
            documentChildren.filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }.sortedBy { it.name }.forEach { child ->
                visit(child, children(resolver, treeUri, child.id), depth + 1)
            }
        }
        visit(root, children(resolver, treeUri, rootId), 0)
        return result
    }

    private fun isCache(document: Document, children: List<Document>): Boolean =
        BilibiliMusicImport.cacheCid(document.name) > 0 || children.any { it.name == "entry.json" }

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
    fun audio(context: Context, track: MusicTrack): File? =
        track.audioFile.takeIf { it.isNotBlank() }?.let { File(root(context), it).takeIf(File::isFile) }

    fun cover(context: Context, track: MusicTrack): File? =
        track.coverFile.takeIf { it.isNotBlank() }?.let { File(root(context), it).takeIf(File::isFile) }

    fun replaceCover(context: Context, track: MusicTrack, source: Uri): String {
        val directory = trackDirectory(context, track.id)
        if (!directory.isDirectory) throw IOException("歌曲保存目录已不可用")
        val name = "cover-${System.currentTimeMillis()}.jpg"
        val staging = File(directory, ".$name.part")
        val target = File(directory, name)
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("无法读取所选封面")
            input.use { coverInput -> staging.outputStream().use { coverInput.copyTo(it) } }
            if (staging.length() <= 0) throw IOException("封面文件为空")
            if (!staging.renameTo(target)) throw IOException("无法保存新封面")
            return "${track.id}/$name"
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
    }

    fun deleteCover(context: Context, relativePath: String) {
        if (relativePath.isNotBlank()) File(root(context), relativePath).delete()
    }

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
