package com.focusassistant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageInsightsTest {
    private val utc = ZoneId.of("UTC")
    private fun millis(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun span(from: String, to: String, packageName: String = "app") = UsageSpan(packageName, millis(from), millis(to))

    @Test fun nightWindowRunsFromPreviousElevenPmToFiveAm() {
        val (from, to) = UsageInsights.nightWindow(LocalDate.parse("2025-01-08"), utc)
        assertEquals(millis("2025-01-07T23:00:00Z"), from)
        assertEquals(millis("2025-01-08T05:00:00Z"), to)
    }

    @Test fun nightWindowFollowsLocalCalendarAcrossDaylightSaving() {
        val berlin = ZoneId.of("Europe/Berlin")
        val (from, to) = UsageInsights.nightWindow(LocalDate.parse("2025-03-30"), berlin)
        // 夏令时当晚少一小时：23:00 CET 到 05:00 CEST 只有 5 小时。
        assertEquals(5 * 3_600_000L, to - from)
    }

    @Test fun nightClipsSpansAndCountsOverlappingWindowsOnce() {
        val spans = listOf(
            span("2025-01-07T22:50:00Z", "2025-01-07T23:20:00Z", "chat"),
            span("2025-01-07T23:10:00Z", "2025-01-07T23:30:00Z", "browser"),
            span("2025-01-08T04:50:00Z", "2025-01-08T05:10:00Z"),
            span("2025-01-08T12:00:00Z", "2025-01-08T13:00:00Z")
        )
        val night = UsageInsights.night(spans, listOf(LocalDate.parse("2025-01-08")), utc)
        assertEquals((30 + 10) * 60_000L, night.millis)
        assertEquals(millis("2025-01-08T05:00:00Z"), night.lastUse)
    }

    @Test fun weekNightSumsEachDayAndEmptyIsZero() {
        val spans = listOf(span("2025-01-06T00:00:00Z", "2025-01-06T00:20:00Z"), span("2025-01-07T01:00:00Z", "2025-01-07T01:15:00Z"))
        val dates = listOf(LocalDate.parse("2025-01-06"), LocalDate.parse("2025-01-07"))
        assertEquals(35 * 60_000L, UsageInsights.night(spans, dates, utc).millis)
        assertEquals(NightSummary(0L, null), UsageInsights.night(emptyList(), dates, utc))
    }

    @Test fun compactMergesSameAppWithinOneMinuteOnly() {
        val spans = listOf(
            span("2025-01-08T09:00:00Z", "2025-01-08T09:05:00Z", "chat"),
            span("2025-01-08T09:05:40Z", "2025-01-08T09:08:00Z", "chat"),
            span("2025-01-08T09:10:00Z", "2025-01-08T09:12:00Z", "chat"),
            span("2025-01-08T09:05:10Z", "2025-01-08T09:05:30Z", "browser")
        )
        val compacted = UsageInsights.compact(spans)
        assertEquals(3, compacted.size)
        assertEquals(span("2025-01-08T09:00:00Z", "2025-01-08T09:08:00Z", "chat"), compacted[0])
        assertEquals("browser", compacted[1].packageName)
    }

    @Test fun focusSpansUseRealSegmentsClippedToDay() {
        val from = millis("2025-01-08T00:00:00Z")
        val to = millis("2025-01-09T00:00:00Z")
        val session = FocusSession("s", "p", "阅读", "学习", TimerMode.COUNTDOWN, 25, millis("2025-01-07T23:50:00Z"), millis("2025-01-08T09:20:00Z"), 1800,
            listOf(TimeSegment(millis("2025-01-07T23:50:00Z"), millis("2025-01-08T00:10:00Z")), TimeSegment(millis("2025-01-08T09:00:00Z"), millis("2025-01-08T09:20:00Z"))))
        assertEquals(listOf(from to millis("2025-01-08T00:10:00Z"), millis("2025-01-08T09:00:00Z") to millis("2025-01-08T09:20:00Z")),
            UsageInsights.focusSpans(listOf(session), from, to))
    }

    @Test fun overlapCountsMergedScreenSpansThatTouchFocus() {
        val focus = listOf(millis("2025-01-08T09:00:00Z") to millis("2025-01-08T09:20:00Z"), millis("2025-01-08T09:40:00Z") to millis("2025-01-08T10:00:00Z"))
        val spans = listOf(
            span("2025-01-08T09:10:00Z", "2025-01-08T09:12:00Z", "chat"),
            span("2025-01-08T09:11:00Z", "2025-01-08T09:13:00Z", "browser"),
            span("2025-01-08T09:25:00Z", "2025-01-08T09:35:00Z"),
            span("2025-01-08T09:20:00Z", "2025-01-08T09:22:00Z"),
            span("2025-01-08T09:55:00Z", "2025-01-08T10:05:00Z")
        )
        assertEquals(2, UsageInsights.overlapCount(spans, focus))
        assertTrue(UsageInsights.overlaps(spans[0], focus))
        assertFalse(UsageInsights.overlaps(spans[3], focus))
    }

    @Test fun aggregationKeepsEarlySpansForNightButNotInTotals() {
        val range = UsageOverviewRange(LocalDate.parse("2025-01-08"), false, utc)
        val nightStart = UsageInsights.nightWindow(LocalDate.parse("2025-01-08"), utc).first
        val aggregation = UsageAggregation(range.start, range.end, range, nightStart)
        aggregation.addSegment("chat", TimeSegment(millis("2025-01-07T22:30:00Z"), millis("2025-01-08T00:30:00Z")))
        assertEquals(30 * 60_000L, aggregation.totals["chat"])
        assertEquals(30 * 60_000L, aggregation.todayMillis)
        assertEquals(listOf(UsageSpan("chat", nightStart, millis("2025-01-08T00:30:00Z"))), aggregation.spans)
    }
}
