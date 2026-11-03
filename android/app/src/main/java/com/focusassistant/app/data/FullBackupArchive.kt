package com.focusassistant.app.data

import android.content.Context
import android.net.Uri
import com.focusassistant.app.domain.AppState
import com.focusassistant.app.domain.FullBackupData
import com.focusassistant.app.domain.FullBackupFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** 流式 ZIP 读写。所有附件先校验并落入暂存目录，元数据提交失败时恢复旧目录。 */
object FullBackupArchive {
    private const val MANIFEST = "manifest.json"
    private const val FILE_PREFIX = "files/"
    private val MODULES = listOf("diary", "music")
    private const val STAGING_NAME = ".full-backup-new"
    private const val BACKUP_NAME = ".full-backup-old"
    private const val JOURNAL_NAME = ".full-backup-restore.json"

    suspend fun export(context: Context, uri: Uri, state: AppState) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统文件选择器提供的文档" }
        val sources = sources(context, state)
        val descriptors = sources.map { (path, file) ->
            coroutineContext.ensureActive()
            require(file.isFile && file.length() > 0) { "备份引用的附件不存在：$path" }
            FullBackupFile(path, file.length(), file.inputStream().buffered().use(::sha256))
        }
        validateSourceHashes(state, descriptors)
        val manifest = FullBackupCodec.encodeManifest(state.copy(timer = null, loading = false), descriptors).toByteArray(Charsets.UTF_8)
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开所选文档进行写入")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest)
            zip.closeEntry()
            sources.forEach { (path, file) ->
                coroutineContext.ensureActive()
                val descriptor = descriptors.first { it.path == path }
                zip.putNextEntry(ZipEntry(FILE_PREFIX + path))
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= FullBackupCodec.MAX_FILE_BYTES) { "附件超过大小限制" }
                        digest.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                    }
                }
                require(total == descriptor.bytes && digest.hex() == descriptor.sha256) { "导出期间附件发生变化，请重试" }
                zip.closeEntry()
            }
        }
    }

    suspend fun inspect(context: Context, uri: Uri): FullBackupData = read(context, uri, null)

    /** 第二次读取并重新校验，防止预览后原文件被其他应用修改。 */
    suspend fun stage(context: Context, uri: Uri, preview: FullBackupData): FullBackupData = withContext(Dispatchers.IO) {
        val staging = staging(context)
        require(!journal(context).exists() && !staging.exists()) { "存在未完成的恢复，请重启应用后再试" }
        try {
            val decoded = read(context, uri, staging)
            require(decoded == preview) { "备份文件在预览后发生变化，请重新选择" }
            decoded
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private suspend fun read(context: Context, uri: Uri, destination: File?): FullBackupData = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统文件选择器提供的备份文档" }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选备份")
        input.use { read(it, destination) }
    }

    /** 独立于 SAF 的流读取，便于对 ZIP 攻击与损坏场景做 JVM 测试。 */
    internal suspend fun read(input: InputStream, destination: File?): FullBackupData {
        var manifest: String? = null
        val actual = mutableMapOf<String, FullBackupFile>()
        val names = mutableSetOf<String>()
        var entries = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "备份包含不支持的目录条目" }
                require(++entries <= FullBackupCodec.MAX_FILES + 1) { "备份条目过多" }
                val name = entry.name
                require(name == MANIFEST || (name.startsWith(FILE_PREFIX) && safeRelative(name.removePrefix(FILE_PREFIX)))) { "备份包含非法路径" }
                require(names.add(name)) { if (name == MANIFEST) "备份清单重复" else "备份附件路径重复" }
                if (name == MANIFEST) manifest = readManifest(zip)
                else {
                    val path = name.removePrefix(FILE_PREFIX)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    val output = destination?.let {
                        val target = safeChild(it, path)
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered()
                    }
                    try {
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= FullBackupCodec.MAX_FILE_BYTES) { "附件超过大小限制" }
                            digest.update(buffer, 0, count)
                            output?.write(buffer, 0, count)
                        }
                    } finally { output?.close() }
                    require(bytes > 0) { "备份包含空附件" }
                    total = try { Math.addExact(total, bytes) } catch (_: ArithmeticException) { throw IllegalArgumentException("备份附件总大小无效") }
                    require(total <= FullBackupCodec.MAX_TOTAL_BYTES) { "备份附件总大小不可超过 4 GiB" }
                    actual[path] = FullBackupFile(path, bytes, digest.hex())
                }
                zip.closeEntry()
            }
        }
        val data = FullBackupCodec.decodeManifest(requireNotNull(manifest) { "备份缺少 manifest.json" })
        require(actual.values.toSet() == data.files.toSet()) { "附件内容、大小或校验值与清单不一致" }
        if (destination != null) MODULES.forEach { File(destination, it).mkdirs() }
        data
    }

    private fun readManifest(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= BackupCodec.MAX_BYTES) { "备份清单不可超过 20 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun sources(context: Context, state: AppState): List<Pair<String, File>> = FullBackupCodec.expectedPaths(state).map { path ->
        val root = when (path.substringBefore('/')) {
            "diary" -> DiaryFiles.directory(context)
            "music" -> File(context.filesDir, "music")
            else -> error("不支持的附件类型")
        }
        path to safeChild(root, path.substringAfter('/'))
    }

    private fun validateSourceHashes(state: AppState, files: List<FullBackupFile>) {
        val byPath = files.associateBy { it.path }
        state.musicTracks.forEach { track ->
            val audio = byPath["music/${track.audioFile}"] ?: error("音乐音频缺失")
            require(audio.bytes == track.fileBytes && audio.sha256 == track.contentHash) { "音乐文件与曲库记录不一致：${track.title}" }
        }
    }

    private fun safeRelative(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun safeChild(root: File, relative: String): File {
        require(safeRelative(relative)) { "附件路径无效" }
        val base = root.canonicalFile
        val target = File(base, relative).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "附件路径越过私有目录" }
        return target
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= FullBackupCodec.MAX_FILE_BYTES) { "附件超过大小限制" }
            digest.update(buffer, 0, count)
        }
        require(total > 0) { "附件不能为空" }
        return digest.hex()
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun staging(context: Context) = File(context.filesDir, STAGING_NAME)
    private fun backup(context: Context) = File(context.filesDir, BACKUP_NAME)
    private fun journal(context: Context) = File(context.filesDir, JOURNAL_NAME)

    fun discardStaging(context: Context) {
        if (!journal(context).exists()) staging(context).deleteRecursively()
    }

    /** 在数据库事务前换入已校验文件；旧目录保留到事务成功后。 */
    fun beginReplacement(context: Context, oldRevision: Long?): FullBackupReplacement {
        val journal = journal(context)
        val staging = staging(context)
        val backup = backup(context)
        require(!journal.exists() && !backup.exists() && staging.isDirectory) { "恢复暂存状态无效" }
        MODULES.forEach { File(context.filesDir, it).mkdirs() }
        writeJournal(journal, oldRevision)
        if (!backup.mkdirs()) throw IOException("无法创建附件回滚目录")
        val replacement = FullBackupReplacement(context, oldRevision)
        try {
            MODULES.forEach { module ->
                val target = File(context.filesDir, module)
                if (!target.renameTo(File(backup, module))) throw IOException("无法暂存现有附件")
            }
            MODULES.forEach { module ->
                if (!File(staging, module).renameTo(File(context.filesDir, module))) throw IOException("无法写入恢复附件")
            }
            return replacement
        } catch (error: Exception) {
            replacement.rollback()
            throw error
        }
    }

    /** 启动加载数据库后调用：revision 未前进说明事务未提交，回滚文件；否则保留新文件。 */
    fun recoverPending(context: Context, currentRevision: Long?) {
        val journal = journal(context)
        if (!journal.exists()) {
            staging(context).deleteRecursively()
            return
        }
        val root = JSONObject(journal.readText())
        val oldRevision = if (root.isNull("revision")) null else root.getLong("revision")
        val replacement = FullBackupReplacement(context, oldRevision)
        if (currentRevision == oldRevision) replacement.rollback() else replacement.commit()
    }

    private fun writeJournal(file: File, revision: Long?) {
        val content = JSONObject().put("revision", revision ?: JSONObject.NULL).toString()
        file.outputStream().bufferedWriter().use { it.write(content) }
    }
}

class FullBackupReplacement internal constructor(private val context: Context, private val oldRevision: Long?) {
    private val staging = File(context.filesDir, ".full-backup-new")
    private val backup = File(context.filesDir, ".full-backup-old")
    private val journal = File(context.filesDir, ".full-backup-restore.json")
    private val modules = listOf("diary", "music")

    fun rollback() {
        modules.forEach { module ->
            val old = File(backup, module)
            if (old.exists()) {
                File(context.filesDir, module).deleteRecursively()
                if (!old.renameTo(File(context.filesDir, module))) throw IOException("无法回滚附件，请重启应用恢复")
            }
        }
        staging.deleteRecursively()
        backup.deleteRecursively()
        journal.delete()
    }

    fun commit() {
        staging.deleteRecursively()
        backup.deleteRecursively()
        journal.delete()
    }

    override fun toString(): String = "FullBackupReplacement(oldRevision=$oldRevision)"
}
