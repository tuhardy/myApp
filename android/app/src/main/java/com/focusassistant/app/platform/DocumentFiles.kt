package com.focusassistant.app.platform

import android.content.Context
import android.net.Uri
import com.focusassistant.app.data.BackupCodec
import com.focusassistant.app.domain.BackupData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext

object DocumentFiles {
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val BUFFER_BYTES = 8192

    suspend fun write(context: Context, uri: Uri, content: String) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统文件选择器提供的文档" }
        require(content.length <= MAX_BYTES) { "导出文件不能超过 20 MiB" }
        val encoded = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(content))
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("导出内容包含无效字符", error)
        }
        require(encoded.remaining() <= MAX_BYTES) { "导出文件不能超过 20 MiB" }
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开所选文档进行写入")
        output.use {
            val buffer = ByteArray(BUFFER_BYTES)
            while (encoded.hasRemaining()) {
                coroutineContext.ensureActive()
                val count = minOf(encoded.remaining(), buffer.size)
                encoded.get(buffer, 0, count)
                it.write(buffer, 0, count)
            }
            it.flush()
        }
    }

    suspend fun readBackup(context: Context, uri: Uri): BackupData = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统文件选择器提供的备份文档" }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选备份文档")
        val bytes = input.use { stream ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "备份文件不能超过 20 MiB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val content = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("备份不是有效的 UTF-8 文件", error)
        }
        BackupCodec.decode(content)
    }
}
