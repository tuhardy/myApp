package com.focusassistant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatisticsTest {
    private val utc = ZoneId.of("UTC")
    private fun session(start: String, end: String, id: String = "s"): FocusSession {
        val from = Instant.parse(start).toEpochMilli()
        val to = Instant.parse(end).toEpochMilli()
        return FocusSession(id, "p", "项目", "学习", TimerMode.COUNTUP, 25, startedAt = from, endedAt = to,
            durationSeconds = (to - from) / 1000, segments = listOf(TimeSegment(from, to)))
    }
    @Test fun selectionUsesCompletionDateAndDoesNotClipHourlySegments() {
        val session = session("2025-01-01T23:30:00Z", "2025-01-02T00:30:00Z")
        val selected = Statistics.select(listOf(session), Period.DAY, LocalDate.parse("2025-01-02"), utc)
        assertEquals(1, selected.size)
        val hours = Statistics.hours(selected, utc)
        assertEquals(1800L, hours[23]); assertEquals(1800L, hours[0])
        assertTrue(Statistics.select(listOf(session), Period.DAY, LocalDate.parse("2025-01-01"), utc).isEmpty())
    }
    @Test fun weekStartsMonday() {
        val range = Statistics.range(Period.WEEK, LocalDate.parse("2025-01-05"))
        assertEquals(LocalDate.parse("2024-12-30"), range.first)
        assertEquals(LocalDate.parse("2025-01-06"), range.second)
    }
    @Test fun calendarAverageIncludesGapsAndUsesCalendarDaysAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val session = session("2025-03-08T15:00:00Z", "2025-03-08T16:00:00Z")
        val summary = Statistics.allTime(listOf(session), LocalDate.parse("2025-03-10"), zone)
        assertEquals(3L, summary.calendarDays)
        assertEquals(1200.0, summary.calendarAverageSeconds, 0.0)
        assertEquals(3600.0, summary.activeAverageSeconds, 0.0)
    }
    @Test fun futureCompletionsDoNotAffectAllTimeToToday() {
        val session = session("2025-01-05T10:00:00Z", "2025-01-05T11:00:00Z")
        assertEquals(0, Statistics.allTime(listOf(session), LocalDate.parse("2025-01-04"), utc).count)
    }
    @Test fun emptySummariesAreZero() {
        assertEquals(Summary(0, 0, 0, 0.0), Statistics.summary(emptyList(), utc))
        val all = Statistics.allTime(emptyList())
        assertEquals(0L, all.calendarDays); assertEquals(0.0, all.calendarAverageSeconds, 0.0); assertNull(all.firstDate)
        assertEquals(List(24) { 0L }, Statistics.hours(emptyList(), utc))
    }
    @Test fun springDstSkipsMissingHour() {
        val session = session("2025-03-09T06:30:00Z", "2025-03-09T07:30:00Z")
        val hours = Statistics.hours(listOf(session), ZoneId.of("America/New_York"))
        assertEquals(1800L, hours[1]); assertEquals(0L, hours[2]); assertEquals(1800L, hours[3])
        assertEquals(3600L, hours.sum())
    }
    @Test fun autumnDstCombinesRepeatedHour() {
        val session = session("2025-11-02T05:00:00Z", "2025-11-02T07:00:00Z")
        val hours = Statistics.hours(listOf(session), ZoneId.of("America/New_York"))
        assertEquals(7200L, hours[1]); assertEquals(7200L, hours.sum())
    }
    @Test fun halfHourDstTransitionPreservesDistribution() {
        val session = session("2025-10-04T15:00:00Z", "2025-10-04T16:30:00Z")
        val hours = Statistics.hours(listOf(session), ZoneId.of("Australia/Lord_Howe"))
        assertEquals(1800L, hours[1]); assertEquals(1800L, hours[2]); assertEquals(1800L, hours[3]); assertEquals(5400L, hours.sum())
    }
    @Test fun hourlyDistributionExcludesPausedTime() {
        val session = session("2025-01-01T10:00:00Z", "2025-01-01T13:00:00Z")
        val segments = listOf(TimeSegment(session.startedAt, session.startedAt + 600_000), TimeSegment(session.endedAt - 600_000, session.endedAt))
        val hours = Statistics.hours(listOf(session.copy(durationSeconds = 1200, segments = segments)), utc)
        assertEquals(600L, hours[10]); assertEquals(0L, hours[11]); assertEquals(600L, hours[12])
    }
    @Test fun fractionalHourBoundaryDoesNotLoseRecordedSeconds() {
        val session = session("2025-01-01T10:59:59.500Z", "2025-01-01T11:00:00.500Z")
        assertEquals(1L, Statistics.hours(listOf(session), utc).sum())
    }
    @Test fun leapMonthTrendIncludesAllDays() {
        assertEquals(29, Statistics.trend(emptyList(), Period.MONTH, LocalDate.parse("2024-02-20"), utc).size)
        assertEquals(12, Statistics.trend(emptyList(), Period.YEAR, LocalDate.parse("2024-02-20"), utc).size)
    }
    @Test fun backfillingOlderSessionNeverOverridesNewerSessionProgress() {
        val newer = ProgressEntry("n", "new", "p", 2000, "新专注", 20, 3000)
        val older = ProgressEntry("o", "old", "p", 1000, "晚补旧专注", 90, 9000)
        assertEquals(newer, Statistics.latestProgress(listOf(newer, older), "p"))
    }
    @Test fun progressRevisionTieUsesAppendOrder() {
        val first = ProgressEntry("1", "s", "p", 1000, "第一版", null, 2000)
        val second = first.copy(id = "2", note = "第二版")
        assertEquals(second, Statistics.latestForSession(listOf(first, second), "s"))
        assertEquals(second, Statistics.latestProgress(listOf(first, second), "p"))
    }
    @Test fun csvIncludesBomCrlfThirteenColumnsAndEscapesAllText() {
        val session = session("2025-01-01T10:00:00Z", "2025-01-01T10:01:00Z").copy(projectTitle = "=SUM(A1)", category = "+分类")
        val note = ProgressEntry("n", "s", "p", session.endedAt, "  -公式,\"引号\"\n换行", null, session.endedAt)
        val csv = Statistics.csv(listOf(session), listOf(note))
        assertTrue(csv.startsWith("\uFEFF\"日期\"")); assertTrue(csv.endsWith("\r\n"))
        assertEquals(13, csv.substringBefore("\r\n").split(',').size)
        assertFalse(csv.substringBefore("\r\n").contains("\"任务\""))
        assertTrue(csv.contains("\"'=SUM(A1)\"")); assertTrue(csv.contains("\"'+分类\""))
        assertTrue(csv.contains("\"'  -公式,\"\"引号\"\"\n换行\""))
        val atPrefix = Statistics.csv(listOf(session.copy(projectTitle = "@项目")), emptyList())
        assertTrue(atPrefix.contains("\"'@项目\""))
    }
    @Test fun csvHeaderAndRowsStayAlignedWithoutTaskColumn() {
        val session = session("2025-01-01T10:00:00Z", "2025-01-01T10:01:00Z").copy(targetMinutes = null)
        val note = ProgressEntry("n", "s", "p", session.endedAt, "完成一章", 50, session.endedAt)
        val lines = Statistics.csv(listOf(session), listOf(note)).removePrefix("\uFEFF").trimEnd('\r', '\n').split("\r\n")
        val header = lines[0].split(',').map { it.removeSurrounding("\"") }
        val row = lines[1].split(',').map { it.removeSurrounding("\"") }
        assertEquals(listOf("日期", "分类", "开始时间", "结束时间", "时长（分钟）", "来源", "计时模式", "专注项", "目标时长（分钟）", "时长（秒）", "学习进度", "完成度（%）", "进度更新时间"), header)
        assertEquals(header.size, row.size)
        val values = header.zip(row).toMap()
        assertEquals(session.category, values["分类"])
        assertEquals(session.projectTitle, values["专注项"])
        assertEquals("1.0", values["时长（分钟）"])
        assertEquals("不限时", values["目标时长（分钟）"])
        assertEquals("60", values["时长（秒）"])
        assertEquals(note.note, values["学习进度"])
        assertEquals("50", values["完成度（%）"])
    }
}
