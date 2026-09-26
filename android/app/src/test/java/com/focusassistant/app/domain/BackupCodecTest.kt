package com.focusassistant.app.domain

import com.focusassistant.app.data.BackupCodec
import com.focusassistant.app.data.ExportCodec
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val project = Project("p", "学习", "分类", TimerMode.COUNTUP, 25)
    private val session = FocusSession("s", "p", "学习快照", "分类", TimerMode.COUNTUP, 25,
        startedAt = 100_000, endedAt = 102_000, durationSeconds = 2, segments = listOf(TimeSegment(100_000, 102_000)))
    private val progress = ProgressEntry("r", "s", "p", 102_000, "已完成第一步", null, 103_000)
    private fun state() = AppState(listOf(project), listOf(Todo("t", "待办", "分类")), listOf(session), listOf(progress), loading = false)
    private fun root() = JSONObject(BackupCodec.encode(state()))
    private fun rejected(root: JSONObject) {
        try { BackupCodec.decode(root.toString()); fail("应拒绝无效备份") } catch (error: Exception) { assertNotNull(error.message) }
    }

    @Test fun completeBackupRoundTripPreservesImmutableHistoryAndSettings() {
        val expected = state()
        val restored = BackupCodec.decode(BackupCodec.encode(expected))
        assertEquals(expected.projects, restored.projects); assertEquals(expected.todos, restored.todos)
        assertEquals(expected.sessions, restored.sessions); assertEquals(expected.progress, restored.progress)
        assertEquals(expected.settings, restored.settings)
    }
    @Test fun todoAgingAndStepsSurviveRoundTrip() {
        val rich = Todo("t2", "梳理想法", "工作", important = true, createdAt = "2026-03-11",
            steps = listOf(TodoStep("a", "写下问题", true), TodoStep("b", "画草图")), archived = true)
        val restored = BackupCodec.decode(BackupCodec.encode(state().copy(todos = listOf(rich))))
        assertEquals(rich, restored.todos.single())
    }
    @Test fun olderBackupsWithoutAgingFieldsLoadWithoutInventingHistory() {
        val root = root()
        val todo = root.getJSONArray("todos").getJSONObject(0)
        todo.remove("createdAt"); todo.remove("steps"); todo.remove("archived")
        val restored = BackupCodec.decode(root.toString()).todos.single()
        // 旧备份没有这些字段，不臆造放入日期、小步或放下状态。
        assertEquals("", restored.createdAt)
        assertTrue(restored.steps.isEmpty())
        assertFalse(restored.archived)
    }
    @Test fun brokenTodoAgingFieldsAreRejected() {
        rejected(root().also { it.getJSONArray("todos").getJSONObject(0).put("createdAt", "2026-3-1") })
        rejected(root().also { it.getJSONArray("todos").getJSONObject(0).put("archived", "yes") })
        rejected(root().also { root ->
            val steps = org.json.JSONArray().put(JSONObject().put("id", "a").put("title", "").put("done", false))
            root.getJSONArray("todos").getJSONObject(0).put("steps", steps)
        })
        rejected(root().also { root ->
            val duplicate = org.json.JSONArray()
                .put(JSONObject().put("id", "a").put("title", "一").put("done", false))
                .put(JSONObject().put("id", "a").put("title", "二").put("done", false))
            root.getJSONArray("todos").getJSONObject(0).put("steps", duplicate)
        })
    }
    @Test fun emptyBackupIsValidAndContainsNoSampleData() {
        val backup = BackupCodec.decode(BackupCodec.encode(AppState()))
        assertTrue(backup.projects.isEmpty() && backup.sessions.isEmpty() && backup.progress.isEmpty() && backup.todos.isEmpty())
    }
    @Test fun activeTimerIsNeverExported() {
        val active = TimerEngine.start(project, "active", 1000, 1000, 1)
        val root = JSONObject(BackupCodec.encode(state().copy(timer = active)))
        assertFalse(root.has("timer")); assertFalse(root.has("activeTimer"))
    }
    @Test fun deletedProjectsDoNotInvalidateHistoricalReferences() {
        val backup = BackupCodec.decode(BackupCodec.encode(state().copy(projects = emptyList())))
        assertEquals(1, backup.sessions.size); assertEquals(1, backup.progress.size)
    }
    @Test fun wrongKindAndUnsupportedVersionAreRejected() {
        rejected(root().put("kind", "focus-statistics"))
        rejected(root().put("version", 2))
    }
    @Test fun missingMandatoryFieldsAreRejected() {
        val root = root(); root.getJSONArray("projects").getJSONObject(0).remove("title"); rejected(root)
    }
    @Test fun unlimitedCountupTargetRoundTripsAsNull() {
        val unlimited = state().let { base ->
            base.copy(projects = listOf(project.copy(targetMinutes = null)),
                sessions = listOf(session.copy(targetMinutes = null)))
        }
        val encoded = BackupCodec.encode(unlimited)
        assertTrue(JSONObject(encoded).getJSONArray("projects").getJSONObject(0).isNull("targetMinutes"))
        val restored = BackupCodec.decode(encoded)
        assertNull(restored.projects.single().targetMinutes)
        assertNull(restored.sessions.single().targetMinutes)
    }
    @Test fun missingTargetKeyIsRejectedRatherThanTreatedAsUnlimited() {
        val root = root(); root.getJSONArray("projects").getJSONObject(0).remove("targetMinutes"); rejected(root)
    }
    @Test fun countdownWithoutTargetIsRejected() {
        val root = root()
        root.getJSONArray("projects").getJSONObject(0).put("timerMode", TimerMode.COUNTDOWN.name).put("targetMinutes", JSONObject.NULL)
        rejected(root)
    }
    @Test fun numericStringsAndFractionalIntegersAreRejected() {
        val text = root(); text.getJSONArray("projects").getJSONObject(0).put("targetMinutes", "25"); rejected(text)
        val fraction = root(); fraction.getJSONArray("projects").getJSONObject(0).put("targetMinutes", 25.5); rejected(fraction)
    }
    @Test fun wrongEnumTypeAndValueAreRejected() {
        val value = root(); value.getJSONArray("projects").getJSONObject(0).put("timerMode", "SOMETHING"); rejected(value)
        val type = root(); type.getJSONArray("projects").getJSONObject(0).put("timerMode", 1); rejected(type)
    }
    @Test fun duplicateIdsAreRejected() {
        val root = root(); root.getJSONArray("projects").put(root.getJSONArray("projects").getJSONObject(0)); rejected(root)
    }
    @Test fun danglingProgressReferenceIsRejected() {
        val root = root(); root.getJSONArray("progress").getJSONObject(0).put("sessionId", "missing"); rejected(root)
    }
    @Test fun mismatchedProgressSnapshotIsRejected() {
        val root = root(); root.getJSONArray("progress").getJSONObject(0).put("sessionEndedAt", 99_000); rejected(root)
    }
    @Test fun overlappingSegmentsAreRejected() {
        val root = root(); val segments = root.getJSONArray("sessions").getJSONObject(0).getJSONArray("segments")
        segments.put(JSONObject().put("startedAt", 101_000).put("endedAt", 102_000)); rejected(root)
    }
    @Test fun DurationMismatchAndInvalidDatesAreRejected() {
        val duration = root(); duration.getJSONArray("sessions").getJSONObject(0).put("durationSeconds", 999); rejected(duration)
        val date = root(); date.getJSONArray("sessions").getJSONObject(0).put("startedAt", -1); rejected(date)
    }
    @Test fun invalidSettingsAndPercentAreRejected() {
        val settings = root(); settings.getJSONObject("settings").put("dailyGoalMinutes", 0); rejected(settings)
        val percent = root(); percent.getJSONArray("progress").getJSONObject(0).put("percent", 101); rejected(percent)
    }
    @Test(expected = IllegalArgumentException::class) fun trailingContentIsRejected() { BackupCodec.decode(BackupCodec.encode(state()) + " []") }
    @Test(expected = IllegalArgumentException::class) fun oversizedBackupRejectedBeforeParsing() { BackupCodec.decode(" ".repeat(BackupCodec.MAX_BYTES + 1)) }
    @Test(expected = IllegalArgumentException::class) fun excessRecordsRejected() {
        BackupCodec.validate(BackupData(List(BackupCodec.MAX_PROJECTS + 1) { project.copy(id = "p$it") }, emptyList(), emptyList(), emptyList(), AppSettings()))
    }
    @Test fun statisticsJsonContainsMetadataHistoryDistributionAndAllTime() {
        val extra = progress.copy(id = "outside", sessionId = "outside")
        val root = JSONObject(ExportCodec.statisticsJson(listOf(session), listOf(progress, extra), listOf(session), Period.DAY, LocalDate.of(1970, 1, 1)))
        assertEquals("focus-statistics", root.getString("kind"))
        assertEquals(24, root.getJSONArray("hours").length())
        assertEquals(1, root.getJSONArray("progress").length())
        assertEquals(2L, root.getJSONObject("allTimeSummary").getLong("seconds"))
        assertEquals("已完成第一步", root.getJSONArray("sessions").getJSONObject(0).getJSONObject("latestProgress").getString("note"))
        assertTrue(root.has("range") && root.has("timeZone") && root.has("summary"))
        rejected(root)
    }
}
