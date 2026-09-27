package com.focusassistant.app.domain

import com.focusassistant.app.data.BackupCodec
import com.focusassistant.app.data.JsonCodec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiaryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 28)
    private fun at(month: Int, day: Int, hour: Int, year: Int = 2026) = LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun entry(id: String, occurredAt: Long, text: String = "", title: String = "", deletedAt: Long? = null,
                      photos: List<DiaryPhoto> = emptyList(), audios: List<DiaryAudio> = emptyList()) =
        DiaryEntry(id, title, text, photos, audios, occurredAt, occurredAt, occurredAt, deletedAt)
    private fun select(entries: List<DiaryEntry>, query: String = "", month: YearMonth = YearMonth.of(2026, 9)) =
        DiaryRules.select(entries, month, query, today, zone)
    private fun ids(selection: DiarySelection) = selection.groups.flatMap { group -> group.entries.map { it.id } }

    @Test fun monthViewGroupsSameDayEntriesNewestFirstAndSkipsTrash() {
        val entries = listOf(entry("a", at(9, 27, 8), "早起跑步"), entry("b", at(9, 27, 22), "评审", "第一次"),
            entry("c", at(9, 28, 9), audios = listOf(DiaryAudio("v", "audio-v.m4a", 42_000))),
            entry("d", at(8, 10, 9), "八月"), entry("e", at(9, 20, 9), "删掉的", deletedAt = at(9, 21, 9)))
        val result = select(entries)
        assertEquals(listOf("9月28日 周一 · 今天", "9月27日 周日 · 昨天"), result.groups.map { it.label })
        assertEquals(listOf("c", "b", "a"), ids(result))
        assertEquals(3, result.total)
        assertEquals(listOf(TodoMonthCount(YearMonth.of(2026, 9), 3), TodoMonthCount(YearMonth.of(2026, 8), 1)), result.months)
        assertEquals(YearMonth.of(2026, 9), result.latestMonth)
        assertEquals(0, select(entries, month = YearMonth.of(2026, 7)).total)
    }

    @Test fun searchCoversAllTimeByTextOrDateWithoutReadingAttachments() {
        val entries = listOf(entry("a", at(9, 27, 8), "跑步"), entry("b", at(9, 27, 22), "评审"),
            entry("c", at(9, 27, 9, year = 2025), "去年"), entry("d", at(9, 20, 9), "想起 9.27 那天"),
            entry("p", at(9, 1, 9), photos = listOf(DiaryPhoto("x", "photo-a.jpg"))))
        assertEquals(listOf("b", "a"), ids(select(entries, "2026-09-27")))
        assertEquals(listOf("b", "a"), ids(select(entries, "20260927")))
        assertEquals(listOf("b", "a", "c"), ids(select(entries, "9月27日")))
        assertEquals(listOf("b", "a", "d", "c"), ids(select(entries, "9.27")))
        assertEquals(listOf("b", "a"), ids(select(entries, "昨天")))
        assertEquals(listOf("a"), ids(select(entries, " 跑步 ")))
        assertEquals("9月27日", select(entries, "9月27日").dateLabel)
        assertNull(select(entries, "跑步").dateLabel)
    }

    @Test fun dateQueryAcceptsCommonFormatsAndRejectsImpossibleDates() {
        fun parsed(query: String) = DiaryRules.parseDateQuery(query, today)?.let { Triple(it.year, it.month, it.day) }
        listOf("2026-09-27", "2026/9/27", "2026.9.27", "2026年9月27日", "20260927", " 2026 年 9 月 27 日 ").forEach { assertEquals(it, Triple(2026, 9, 27), parsed(it)) }
        listOf("9-27", "9/27", "9.27", "9月27日", "9月27", "09-27").forEach { assertEquals(it, Triple(null, 9, 27), parsed(it)) }
        assertEquals(Triple(2026, 9, null), parsed("2026年9月"))
        assertEquals(Triple(null, 9, null), parsed("9月"))
        assertEquals(Triple(2026, 9, 26), parsed("前天"))
        assertEquals("2月29日", DiaryRules.parseDateQuery("2月29日", today)?.label)
        listOf("2026-02-29", "13月1日", "9月31日", "0-1", "跑步", "", "2026-9-27-1").forEach { assertNull(it, parsed(it)) }
    }

    @Test fun validationAllowsAttachmentOnlyEntriesButRejectsEmptyFutureOrUnsafeFiles() {
        val now = at(9, 28, 21)
        DiaryRules.validate(entry("a", now, photos = listOf(DiaryPhoto("p", "photo-1.jpg"))), now)
        DiaryRules.validate(entry("b", at(1, 1, 8), audios = listOf(DiaryAudio("v", "audio-1.m4a", 1_000))), now)
        fun rejected(value: DiaryEntry) { try { DiaryRules.validate(value, now); fail("应拒绝 ${value.id}") } catch (error: IllegalArgumentException) { assertNotNull(error.message) } }
        rejected(entry("empty", now, text = " "))
        rejected(entry("empty-text", now).copy(text = ""))
        rejected(entry("future", now + 60_000, "内容"))
        rejected(entry("title", now, title = "字".repeat(DiaryRules.MAX_TITLE + 1)))
        rejected(entry("short", now, audios = listOf(DiaryAudio("v", "audio-1.m4a", 999))))
        listOf("../x.jpg", "/data/x.jpg", "a/b.jpg", ".hidden", "").forEach { rejected(entry("path", now, photos = listOf(DiaryPhoto("p", it)))) }
        rejected(entry("dup", now, photos = listOf(DiaryPhoto("p", "photo-1.jpg"), DiaryPhoto("p", "photo-2.jpg"))))
    }

    @Test fun trashKeepsOriginalDateAndAttachmentsAndRestoreDoesNotRewriteTime() {
        val original = entry("x", at(9, 1, 9), "晚霞", photos = listOf(DiaryPhoto("p", "photo-1.jpg")), audios = listOf(DiaryAudio("v", "audio-1.m4a", 5_000)))
        val trashed = DiaryRules.moveToTrash(original, at(9, 28, 12))
        assertEquals(at(9, 28, 12), trashed.deletedAt)
        assertEquals(trashed, DiaryRules.moveToTrash(trashed, at(9, 29, 12)))
        assertEquals(original, DiaryRules.restore(trashed))
        val older = trashed.copy(id = "y", deletedAt = at(9, 2, 9))
        assertEquals(listOf("x", "y"), DiaryRules.trashed(listOf(older, original, trashed)).map { it.id })
        assertTrue(select(listOf(trashed)).groups.isEmpty())
    }

    @Test fun fileCleanupNeverTouchesReferencedOrDraftFiles() {
        val kept = entry("k", at(9, 1, 9), photos = listOf(DiaryPhoto("p", "photo-kept.jpg")))
        val removable = DiaryRules.unreferenced(listOf("photo-kept.jpg", "photo-old.jpg", "audio-draft.m4a", "../escape"), listOf(kept), setOf("audio-draft.m4a"))
        assertEquals(setOf("photo-old.jpg"), removable)
    }

    private fun draft(key: String, entryId: String?, updatedAt: Long, text: String = "草稿", photos: List<DiaryPhoto> = emptyList()) =
        DiaryDraft(key, entryId, "", text, at(9, 28, 8), photos, emptyList(), updatedAt)

    @Test fun draftListSortsByLastEditAndMarksDeletedOriginalAsOrphan() {
        val alive = entry("a", at(9, 1, 9), "原文")
        val trashed = entry("b", at(9, 2, 9), "删了", deletedAt = at(9, 3, 9))
        val list = DiaryRules.listDrafts(listOf(
            draft("new-1", null, 100), draft(DiaryRules.editDraftKey("a"), "a", 300),
            draft(DiaryRules.editDraftKey("b"), "b", 200), draft(DiaryRules.editDraftKey("c"), "c", 50)), listOf(alive, trashed))
        assertEquals(listOf("edit:a" to DiaryDraftKind.EDIT, "edit:b" to DiaryDraftKind.ORPHAN, "new-1" to DiaryDraftKind.NEW, "edit:c" to DiaryDraftKind.ORPHAN),
            list.map { it.draft.key to it.kind })
        assertEquals(alive, list.first().entry)
        assertNull(list[1].entry)
    }

    @Test fun draftChangeIgnoresNewEntryTimeAndRevertedEdits() {
        val original = entry("a", at(9, 1, 9), "原文", photos = listOf(DiaryPhoto("p", "photo-1.jpg")))
        val fresh = DiaryRules.freshDraft(DiaryRules.editDraftKey("a"), original, at(9, 28, 9))
        assertFalse(DiaryRules.draftChanged(fresh, original))
        assertTrue(DiaryRules.draftChanged(fresh.copy(text = "原文，补一句"), original))
        assertFalse(DiaryRules.draftChanged(fresh.copy(text = "原文，补一句").copy(text = "原文"), original))
        assertTrue(DiaryRules.draftChanged(fresh.copy(occurredAt = at(8, 1, 9)), original))
        assertTrue(DiaryRules.draftChanged(fresh.copy(photos = emptyList()), original))
        val blank = DiaryRules.freshDraft(DiaryRules.newDraftKey(), null, at(9, 28, 9))
        assertTrue(blank.key.startsWith(DiaryRules.NEW_DRAFT_PREFIX))
        assertNull(blank.entryId)
        assertFalse(DiaryRules.draftChanged(blank.copy(occurredAt = at(9, 1, 9)), null))
        assertTrue(DiaryRules.draftChanged(blank.copy(audios = listOf(DiaryAudio("v", "audio-1.m4a", 3_000))), null))
        assertNotEquals(DiaryRules.newDraftKey(), DiaryRules.newDraftKey())
    }

    @Test fun draftValidationAllowsEmptyContentButRejectsBadKeysFilesAndTimes() {
        DiaryRules.validateDraft(draft("new-1", null, 100, text = ""))
        DiaryRules.validateDraft(draft(DiaryRules.editDraftKey("a"), "a", 100))
        fun rejected(value: DiaryDraft) { try { DiaryRules.validateDraft(value); fail("应拒绝 ${value.key}") } catch (error: IllegalArgumentException) { assertNotNull(error.message) } }
        rejected(draft("other-1", null, 100))
        rejected(draft("new-1", "", 100))
        rejected(draft("new-1", null, -1))
        rejected(draft("new-1", null, 100, photos = listOf(DiaryPhoto("p", "../x.jpg"))))
        rejected(draft("new-1", null, 100, text = "字".repeat(DiaryRules.MAX_TEXT + 1)))
    }

    @Test fun draftJsonRoundTripAndFileCleanupProtectsStoredDrafts() {
        val value = DiaryDraft(DiaryRules.editDraftKey("a"), "a", "标题", "正文", at(9, 1, 9),
            listOf(DiaryPhoto("p", "photo-draft.jpg")), listOf(DiaryAudio("v", "audio-draft.m4a", 2_000)), at(9, 28, 9))
        assertEquals(value, JsonCodec.readDiaryDraft(JSONObject(JsonCodec.diaryDraft(value).toString())))
        val newDraft = value.copy(key = "new-x", entryId = null)
        assertEquals(newDraft, JsonCodec.readDiaryDraft(JsonCodec.diaryDraft(newDraft).apply { remove("entryId") }))
        val removable = DiaryRules.unreferenced(listOf("photo-draft.jpg", "audio-draft.m4a", "photo-old.jpg"), emptyList(), drafts = listOf(value))
        assertEquals(setOf("photo-old.jpg"), removable)
        val backup = JSONObject(BackupCodec.encode(AppState(loading = false, diaryDrafts = listOf(value))))
        assertFalse(backup.toString().contains("photo-draft.jpg"))
    }

    @Test fun jsonRoundTripPreservesEntryAndBackupStillExcludesDiaries() {
        val value = entry("x", at(9, 1, 9), "正文", "标题", deletedAt = at(9, 2, 9),
            photos = listOf(DiaryPhoto("p", "photo-1.jpg")), audios = listOf(DiaryAudio("v", "audio-1.m4a", 5_000)))
        assertEquals(value, JsonCodec.readDiary(JSONObject(JsonCodec.diary(value).toString())))
        val alive = value.copy(deletedAt = null)
        assertEquals(alive, JsonCodec.readDiary(JsonCodec.diary(alive).apply { remove("deletedAt") }))
        try { JsonCodec.readDiary(JsonCodec.diary(value).put("text", "").put("title", "").put("photos", org.json.JSONArray()).put("audios", org.json.JSONArray())); fail("空日记不应读入") }
        catch (error: IllegalArgumentException) { assertNotNull(error.message) }
        val backup = JSONObject(BackupCodec.encode(AppState(loading = false, diaries = listOf(value))))
        assertFalse(backup.has("diaries"))
        assertFalse(backup.toString().contains("photo-1.jpg"))
    }
}
