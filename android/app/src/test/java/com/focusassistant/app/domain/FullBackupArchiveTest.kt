package com.focusassistant.app.data

import com.focusassistant.app.domain.AppState
import com.focusassistant.app.domain.DiaryEntry
import com.focusassistant.app.domain.DiaryPhoto
import com.focusassistant.app.domain.FullBackupFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FullBackupArchiveTest {
    private val photo = "diary photo".toByteArray()
    private val state = AppState(diaries = listOf(DiaryEntry("d", "标题", "正文",
        listOf(DiaryPhoto("p", "photo.jpg")), emptyList(), 1000, 1000, 1000)), loading = false)
    private val descriptor = FullBackupFile("diary/photo.jpg", photo.size.toLong(), sha256(photo))

    @Test fun validZipRoundTripStagesOnlyVerifiedFiles() = runBlocking {
        val manifest = FullBackupCodec.encodeManifest(state, listOf(descriptor))
        val archive = archive(manifest, listOf("files/diary/photo.jpg" to photo))
        assertEquals(state, FullBackupArchive.read(ByteArrayInputStream(archive), null).state)

        val staging = temporaryDirectory()
        try {
            val restored = FullBackupArchive.read(ByteArrayInputStream(archive), staging)
            assertEquals(listOf(descriptor), restored.files)
            assertTrue(File(staging, "diary/photo.jpg").readBytes().contentEquals(photo))
            assertTrue(File(staging, "music").isDirectory)
        } finally { staging.deleteRecursively() }
    }

    @Test fun missingTamperedAndUnexpectedEntriesFailBeforeInspectionCompletes() {
        val manifest = FullBackupCodec.encodeManifest(state, listOf(descriptor))
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            FullBackupArchive.read(ByteArrayInputStream(archive(manifest, emptyList())), null)
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            FullBackupArchive.read(ByteArrayInputStream(archive(manifest, listOf("files/diary/photo.jpg" to "changed".toByteArray()))), null)
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            FullBackupArchive.read(ByteArrayInputStream(archive(FullBackupCodec.encodeManifest(AppState(loading = false), emptyList()),
                listOf("unexpected.txt" to photo))), null)
        } }
    }

    @Test fun traversalAbsoluteAndBrokenArchivesAreRejected() {
        val manifest = FullBackupCodec.encodeManifest(AppState(loading = false), emptyList())
        listOf("files/../outside", "files//absolute", "files/diary\\escape").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                FullBackupArchive.read(ByteArrayInputStream(archive(manifest, listOf(path to photo))), null)
            } }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            FullBackupArchive.read(ByteArrayInputStream("not a zip".toByteArray()), null)
        } }
    }

    private fun archive(manifest: String, files: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toByteArray()); zip.closeEntry()
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun temporaryDirectory(): File = File.createTempFile("full-backup", "test").let {
        require(it.delete() && it.mkdirs()); it
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
