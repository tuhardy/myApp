package com.focusassistant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageAggregationTest {
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2025-01-08")
    private fun millis(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun UsageAggregation.segment(from: String, to: String, packageName: String = "app") =
        addSegment(packageName, TimeSegment(millis(from), millis(to)))
    private fun aggregate(date: LocalDate = today, week: Boolean = false, end: String = "2025-01-09T00:00:00Z"): UsageAggregation {
        val range = UsageOverviewRange(date, week, utc)
        return UsageAggregation(range.start, minOf(range.end, millis(end)), range)
    }
    private fun assertConsistent(aggregation: UsageAggregation) {
        assertEquals(aggregation.totals.values.sum(), aggregation.buckets.sumOf { it.durationMillis })
    }

    @Test fun todayHasTwelveTwoHourBucketsAndSplitsAcrossBoundary() {
        val result = aggregate()
        result.segment("2025-01-08T01:30:00Z", "2025-01-08T02:30:00Z")
        assertEquals(listOf("00", "02", "04", "06", "08", "10", "12", "14", "16", "18", "20", "22"), result.buckets.map { it.label })
        assertEquals(1_800_000L, result.buckets[0].durationMillis)
        assertEquals(1_800_000L, result.buckets[1].durationMillis)
        assertTrue(result.buckets.take(2).all { it.available })
        assertTrue(result.buckets.drop(2).none { it.available })
        assertEquals(3_600_000L, result.todayMillis)
        assertTrue(result.hasData)
        assertConsistent(result)
    }

    @Test fun dailyClippingExcludesPreviousDayAndAnythingAfterCutoff() {
        val result = aggregate(end = "2025-01-08T00:30:00Z")
        result.segment("2025-01-07T23:30:00Z", "2025-01-08T01:30:00Z")
        assertEquals(1_800_000L, result.todayMillis)
        assertEquals(1_800_000L, result.totals["app"])
        assertConsistent(result)
    }

    @Test fun weekStartsMondayAndSplitsAtMidnight() {
        val result = aggregate(week = true)
        result.segment("2025-01-07T23:30:00Z", "2025-01-08T00:30:00Z")
        assertEquals(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"), result.buckets.map { it.label })
        assertEquals(1_800_000L, result.buckets[1].durationMillis)
        assertEquals(1_800_000L, result.buckets[2].durationMillis)
        assertEquals(1_800_000L, result.todayMillis)
        assertEquals(3_600_000L, result.totals["app"])
        assertConsistent(result)
    }

    @Test fun sundayBelongsToPreviousMondayAcrossYearBoundary() {
        val range = UsageOverviewRange(LocalDate.parse("2025-01-05"), true, utc)
        assertEquals(millis("2024-12-30T00:00:00Z"), range.start)
        assertEquals(millis("2025-01-06T00:00:00Z"), range.end)
        val monday = UsageOverviewRange(LocalDate.parse("2025-01-06"), true, utc)
        assertEquals(millis("2025-01-06T00:00:00Z"), monday.start)
    }

    @Test fun pausedGapsAndEmptyOrReversedSegmentsDoNotProduceUsage() {
        val result = aggregate()
        result.segment("2025-01-08T01:00:00Z", "2025-01-08T01:10:00Z")
        result.segment("2025-01-08T04:50:00Z", "2025-01-08T05:00:00Z")
        result.segment("2025-01-08T03:00:00Z", "2025-01-08T03:00:00Z")
        result.segment("2025-01-08T03:10:00Z", "2025-01-08T03:00:00Z")
        assertEquals(1_200_000L, result.totals["app"])
        assertEquals(0L, result.buckets[1].durationMillis)
        assertFalse(result.buckets[1].available)
        assertConsistent(result)
    }

    @Test fun emptyDataIsUnknownRatherThanConfirmedZero() {
        val result = aggregate()
        result.segment("2025-01-08T03:00:00Z", "2025-01-08T03:00:00Z")
        result.recordEvent(millis("2025-01-07T23:59:59Z"))
        assertFalse(result.hasData)
        assertNull(result.todayMillis)
        assertTrue(result.totals.isEmpty())
        assertTrue(result.buckets.none { it.available })
        assertConsistent(result)
    }

    @Test fun eventsConfirmOnlyTheirOwnBucketWithoutClaimingCompleteCoverage() {
        val result = aggregate()
        result.recordEvent(millis("2025-01-08T02:00:00Z"))
        assertTrue(result.hasData)
        assertEquals(0L, result.todayMillis)
        assertTrue(result.buckets[1].available)
        assertEquals(0L, result.buckets[1].durationMillis)
        assertEquals(1, result.buckets.count { it.available })
        assertTrue(result.totals.isEmpty())
    }

    @Test fun futureBucketsAndCutoffEventsRemainUnavailable() {
        val result = aggregate(end = "2025-01-08T03:00:00Z")
        result.recordEvent(millis("2025-01-08T03:00:00Z"))
        result.recordEvent(millis("2025-01-08T04:00:00Z"))
        result.segment("2025-01-08T04:00:00Z", "2025-01-08T05:00:00Z")
        assertFalse(result.hasData)
        result.segment("2025-01-08T02:30:00Z", "2025-01-08T06:00:00Z")
        assertEquals(1_800_000L, result.buckets[1].durationMillis)
        assertTrue(result.buckets.drop(2).none { it.available })
        assertConsistent(result)
    }

    @Test fun weekDoesNotTreatEarlierDataAsTodayAvailability() {
        val result = aggregate(week = true, end = "2025-01-08T12:00:00Z")
        result.segment("2025-01-06T09:00:00Z", "2025-01-06T10:00:00Z")
        result.recordEvent(millis("2025-01-07T09:00:00Z"))
        assertTrue(result.hasData)
        assertNull(result.todayMillis)
        assertFalse(result.buckets[2].available)
        assertTrue(result.buckets.drop(3).none { it.available })
        result.recordEvent(millis("2025-01-08T09:00:00Z"))
        assertEquals(0L, result.todayMillis)
        assertTrue(result.buckets[2].available)
        assertConsistent(result)
    }

    @Test fun reconstructedCarryoverMakesTodayAvailableWithoutTodayEvents() {
        val result = aggregate(week = true, end = "2025-01-08T01:00:00Z")
        result.recordEvent(millis("2025-01-07T23:00:00Z"))
        result.segment("2025-01-07T23:00:00Z", "2025-01-08T01:00:00Z")
        assertEquals(3_600_000L, result.todayMillis)
        assertTrue(result.buckets[2].available)
        assertEquals(7_200_000L, result.totals["app"])
        assertConsistent(result)
    }

    @Test fun multiWindowPackagesUseTheSameAdditiveTotalsAsBuckets() {
        val result = aggregate()
        result.segment("2025-01-08T01:00:00Z", "2025-01-08T03:00:00Z", "first")
        result.segment("2025-01-08T01:30:00Z", "2025-01-08T02:30:00Z", "second")
        assertEquals(7_200_000L, result.totals["first"])
        assertEquals(3_600_000L, result.totals["second"])
        assertEquals(10_800_000L, result.todayMillis)
        assertConsistent(result)
    }

    @Test fun futureRangeHasNoAvailableBuckets() {
        val result = aggregate(end = "2025-01-07T23:00:00Z")
        result.recordEvent(millis("2025-01-08T00:00:00Z"))
        result.segment("2025-01-07T23:00:00Z", "2025-01-08T01:00:00Z")
        assertFalse(result.hasData)
        assertNull(result.todayMillis)
        assertTrue(result.buckets.none { it.available })
    }

    @Test fun plainRangeStillAggregatesWithoutOverview() {
        val result = UsageAggregation(millis("2025-01-08T01:00:00Z"), millis("2025-01-08T02:00:00Z"))
        result.segment("2025-01-08T00:00:00Z", "2025-01-08T03:00:00Z")
        assertEquals(3_600_000L, result.totals["app"])
        assertTrue(result.hasData)
        assertTrue(result.buckets.isEmpty())
        assertNull(result.todayMillis)
    }

    @Test fun fractionalMillisecondsArePreservedAcrossBucketBoundaries() {
        val result = aggregate()
        result.segment("2025-01-08T01:59:59.500Z", "2025-01-08T02:00:00.500Z")
        assertEquals(500L, result.buckets[0].durationMillis)
        assertEquals(500L, result.buckets[1].durationMillis)
        assertConsistent(result)
    }

    @Test fun springDstKeepsTwelveBucketsAndTwentyThreeActualHours() {
        val range = UsageOverviewRange(LocalDate.parse("2025-03-09"), false, ZoneId.of("America/New_York"))
        val result = UsageAggregation(range.start, range.end, range)
        result.addSegment("app", TimeSegment(range.start, range.end))
        assertEquals(12, result.buckets.size)
        assertEquals(2 * HOUR_MS, result.buckets[0].durationMillis)
        assertEquals(HOUR_MS, result.buckets[1].durationMillis)
        assertEquals(23 * HOUR_MS, result.todayMillis)
        assertConsistent(result)
    }

    @Test fun autumnDstIncludesRepeatedHourWithoutAddingBuckets() {
        val range = UsageOverviewRange(LocalDate.parse("2025-11-02"), false, ZoneId.of("America/New_York"))
        val result = UsageAggregation(range.start, range.end, range)
        result.addSegment("app", TimeSegment(range.start, range.end))
        assertEquals(12, result.buckets.size)
        assertEquals(3 * HOUR_MS, result.buckets[0].durationMillis)
        assertEquals(25 * HOUR_MS, result.todayMillis)
        assertConsistent(result)
    }

    @Test fun weekUsesCalendarDaysAcrossDstAndTodayIsNotWeekTotal() {
        val range = UsageOverviewRange(LocalDate.parse("2025-03-09"), true, ZoneId.of("America/New_York"))
        val result = UsageAggregation(range.start, range.end, range)
        result.addSegment("app", TimeSegment(range.start, range.end))
        assertEquals(7, result.buckets.size)
        assertEquals(23 * HOUR_MS, result.buckets.last().durationMillis)
        assertEquals(23 * HOUR_MS, result.todayMillis)
        assertEquals(167 * HOUR_MS, result.totals["app"])
        assertConsistent(result)
    }

    @Test fun halfHourDstTransitionPreservesActualDuration() {
        val range = UsageOverviewRange(LocalDate.parse("2025-10-05"), false, ZoneId.of("Australia/Lord_Howe"))
        val result = UsageAggregation(range.start, range.end, range)
        result.addSegment("app", TimeSegment(range.start, range.end))
        assertEquals(2 * HOUR_MS, result.buckets[0].durationMillis)
        assertEquals(HOUR_MS + HOUR_MS / 2, result.buckets[1].durationMillis)
        assertEquals(23 * HOUR_MS + HOUR_MS / 2, result.todayMillis)
        assertConsistent(result)
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
    }
}
