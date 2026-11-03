package com.focusassistant.app.domain

import com.focusassistant.app.data.BackupCodec
import com.focusassistant.app.data.FullBackupCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FullBackupCodecTest {
    private val hash = "ab".repeat(32)
    private val project = Project("p", "学习", "分类", TimerMode.COUNTUP, 25)
    private val session = FocusSession("s", "p", "学习快照", "分类", TimerMode.COUNTUP, 25,
        startedAt = 100_000, endedAt = 102_000, durationSeconds = 2, segments = listOf(TimeSegment(100_000, 102_000)), progressPrompted = true)
    private val progress = ProgressEntry("r", "s", "p", 102_000, "已完成第一步", 50, 103_000)
    private val diary = DiaryEntry("d", "标题", "正文", listOf(DiaryPhoto("photo", "photo-d.jpg")),
        listOf(DiaryAudio("audio", "audio-d.m4a", 1000)), 1000, 1000, 1000)
    private val draft = DiaryDraft("new-draft", null, "草稿", "", 2000,
        listOf(DiaryPhoto("shared", "photo-d.jpg")), emptyList(), 2000)
    private val assets = AssetBook(listOf(AssetAccount("a", "现金", AssetKind.CASH)),
        listOf(AssetSnapshot("balance", "a", "2026-01-01", 1000)), listOf(Wish("w", "旅行", 5000, null, null, scope = WishScope.ALL)))
    private val job = JobOpportunity("j", "公司", "岗位", "", "", createdAt = 1000, updatedAt = 1000)
    private val auto = AutoApplyBook(records = listOf(AutoApplyRecord("record", "公司|岗位|", "公司", "岗位", "", 1000, AutoApplyResult.CONTACTED)))
    private val track = MusicTrack("m", "歌曲", "歌手", 60, "m/audio.m4s", "m/cover.jpg", hash, 3, 1000, "bvid", 1)

    private fun state() = AppState(listOf(project), listOf(Todo("t", "待办", "分类")), listOf(session), listOf(progress),
        diaries = listOf(diary), diaryDrafts = listOf(draft), assets = assets, jobOpportunities = listOf(job), autoApply = auto,
        musicTracks = listOf(track), loading = false)

    private fun files() = listOf(
        FullBackupFile("diary/photo-d.jpg", 4, "01".repeat(32)),
        FullBackupFile("diary/audio-d.m4a", 5, "02".repeat(32)),
        FullBackupFile("music/m/audio.m4s", 3, hash),
        FullBackupFile("music/m/cover.jpg", 6, "03".repeat(32)))

    @Test fun fullManifestRoundTripCoversEveryModuleAndSharedAttachments() {
        val expected = state()
        val encoded = FullBackupCodec.encodeManifest(expected, files())
        val restored = FullBackupCodec.decodeManifest(encoded)
        assertEquals(expected, restored.state)
        assertEquals(files(), restored.files)
        assertEquals(4, FullBackupCodec.expectedPaths(expected).size)
        assertFalse(JSONObject(encoded).has("timer"))
    }

    @Test fun activeTimerIsExcludedRatherThanSerialized() {
        val active = TimerEngine.start(project, "active", 1000, 1000, 1)
        val encoded = FullBackupCodec.encodeManifest(state().copy(timer = active), files())
        assertNull(FullBackupCodec.decodeManifest(encoded).state.timer)
    }

    @Test fun missingUnexpectedAndDuplicateFilesAreRejected() {
        val missing = JSONObject(FullBackupCodec.encodeManifest(state(), files()))
        missing.put("files", org.json.JSONArray(files().drop(1).map {
            JSONObject().put("path", it.path).put("bytes", it.bytes).put("sha256", it.sha256)
        }))
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decodeManifest(missing.toString()) }

        val root = JSONObject(FullBackupCodec.encodeManifest(state(), files()))
        root.getJSONArray("files").put(JSONObject(root.getJSONArray("files").getJSONObject(0).toString()))
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decodeManifest(root.toString()) }
    }

    @Test fun invalidHashesSizesAndMusicPathsAreRejected() {
        val root = JSONObject(FullBackupCodec.encodeManifest(state(), files()))
        root.getJSONArray("files").getJSONObject(0).put("sha256", "bad")
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decodeManifest(root.toString()) }
        root.getJSONArray("files").getJSONObject(0).put("sha256", "01".repeat(32)).put("bytes", 0)
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decodeManifest(root.toString()) }

        val traversal = state().musicTracks.single().copy(audioFile = "../audio.m4s")
        assertThrows(IllegalArgumentException::class.java) {
            FullBackupCodec.encodeManifest(state().copy(musicTracks = listOf(traversal)), files())
        }
    }

    @Test fun legacyRestoreKeepsModulesOutsideTheOldFormat() {
        val current = state()
        val restored = BackupCodec.restoreState(current, BackupData(emptyList(), emptyList(), emptyList(), emptyList(), AppSettings(90)))
        assertEquals(emptyList<Project>(), restored.projects)
        assertEquals(90, restored.settings.dailyGoalMinutes)
        assertEquals(current.diaries, restored.diaries)
        assertEquals(current.diaryDrafts, restored.diaryDrafts)
        assertEquals(current.assets, restored.assets)
        assertEquals(current.jobOpportunities, restored.jobOpportunities)
        assertEquals(current.autoApply, restored.autoApply)
        assertEquals(current.musicTracks, restored.musicTracks)
        val active = TimerEngine.start(project, "active", 1000, 1000, 1)
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.restoreState(current.copy(timer = active), BackupData(emptyList(), emptyList(), emptyList(), emptyList(), AppSettings()))
        }
    }

    @Test fun unsupportedVersionAndInvalidModuleReferencesAreRejected() {
        val version = JSONObject(FullBackupCodec.encodeManifest(state(), files())).put("version", 1)
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decodeManifest(version.toString()) }
        val reference = JSONObject(FullBackupCodec.encodeManifest(state(), files()))
        reference.getJSONArray("assetSnapshots").getJSONObject(0).put("accountId", "missing")
        assertThrows(IllegalArgumentException::class.java) { FullBackupCodec.decodeManifest(reference.toString()) }
        assertTrue(FullBackupCodec.decodeManifest(FullBackupCodec.encodeManifest(AppState(loading = false), emptyList())).files.isEmpty())
    }
}
