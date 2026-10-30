package com.focusassistant.app.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTest {
    private val utc = ZoneId.of("UTC")
    private val tracks = listOf(
        MusicTrack("sunny", "晴天", "周杰伦", 269),
        MusicTrack("if", "如果可以", "韦礼安", 274),
        MusicTrack("lucky", "小幸运", "田馥甄", 266)
    )
    private fun at(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()
    private fun listen(id: String, track: String, at: String, seconds: Long? = 100) = MusicListen(id, track, at(at), seconds)

    @Test fun summaryFiltersPeriodsAndRanksByPlaysThenSecondsThenTitle() {
        val records = listOf(
            listen("1", "sunny", "2026-10-30T08:00:00Z"),
            listen("2", "sunny", "2026-10-30T09:00:00Z", 120),
            listen("3", "if", "2026-10-29T20:00:00Z", 274),
            listen("4", "lucky", "2026-09-30T20:00:00Z", 266),
            listen("5", "sunny", "2025-10-30T20:00:00Z", 259)
        )
        val day = MusicListening.summary(records, tracks, Period.DAY, LocalDate.parse("2026-10-30"), utc)
        assertEquals(MusicSummary(2, 220, 1, 220, listOf(MusicRankedTrack(tracks[0], 2, 220, at("2026-10-30T09:00:00Z")))), day)
        assertEquals("sunny", MusicListening.summary(records, tracks, Period.WEEK, LocalDate.parse("2026-10-30"), utc).ranking.first().track.id)
        assertEquals(3, MusicListening.summary(records, tracks, Period.MONTH, LocalDate.parse("2026-10-30"), utc).plays)
        assertEquals(4, MusicListening.summary(records, tracks, Period.YEAR, LocalDate.parse("2026-10-30"), utc).plays)
        assertTrue(MusicListening.summary(records, tracks, Period.YEAR, LocalDate.parse("2024-01-01"), utc).ranking.isEmpty())
    }

    @Test fun equalPlaysUseListenedSecondsThenTitle() {
        val records = listOf(
            listen("1", "lucky", "2026-10-30T08:00:00Z", 100),
            listen("2", "if", "2026-10-30T09:00:00Z", 100),
            listen("3", "sunny", "2026-10-30T10:00:00Z", 200)
        )
        assertEquals(listOf("sunny", "if", "lucky"), MusicListening.summary(records, tracks, Period.DAY,
            LocalDate.parse("2026-10-30"), utc).ranking.map { it.track.id })
    }

    @Test fun invalidDurationFallsBackAndUnknownTracksAreSkipped() {
        val records = listOf(
            listen("1", "sunny", "2026-10-30T08:00:00Z", -1),
            listen("2", "missing", "2026-10-30T09:00:00Z", 999)
        )
        val summary = MusicListening.summary(records, tracks, Period.DAY, LocalDate.parse("2026-10-30"), utc)
        assertEquals(1, summary.plays)
        assertEquals(269L, summary.seconds)
    }

    @Test fun rangeUsesLocalWeekAndExclusiveEndBoundary() {
        val records = listOf(
            listen("before", "sunny", "2026-10-25T23:59:59Z"),
            listen("first", "sunny", "2026-10-26T00:00:00Z"),
            listen("last", "sunny", "2026-11-01T23:59:59Z"),
            listen("after", "sunny", "2026-11-02T00:00:00Z")
        )
        val summary = MusicListening.summary(records, tracks, Period.WEEK, LocalDate.parse("2026-10-30"), utc)
        assertEquals(2, summary.plays)
        assertEquals(200L, summary.seconds)
        assertEquals(at("2026-11-01T23:59:59Z"), summary.ranking.single().lastPlayedAt)
    }

    @Test fun trendFillsBinsAndMatchesSummaryAcrossPeriods() {
        val records = listOf(
            listen("1", "sunny", "2024-02-01T08:00:00Z", 100),
            listen("2", "if", "2024-02-29T23:59:59Z", 200),
            listen("3", "lucky", "2024-03-01T00:00:00Z", 300)
        )
        val anchor = LocalDate.parse("2024-02-20")
        Period.entries.forEach { period ->
            val trend = MusicListening.trend(records, tracks, period, anchor, utc)
            val summary = MusicListening.summary(records, tracks, period, anchor, utc)
            assertEquals(summary.seconds, trend.sumOf { it.seconds })
        }
        assertEquals(24, MusicListening.trend(records, tracks, Period.DAY, anchor, utc).size)
        assertEquals(29, MusicListening.trend(records, tracks, Period.MONTH, anchor, utc).size)
        assertEquals(12, MusicListening.trend(records, tracks, Period.YEAR, anchor, utc).size)
        assertEquals(200L, MusicListening.trend(records, tracks, Period.MONTH, anchor, utc).last().seconds)
    }

    @Test fun activeDayAverageRoundsAndComparisonUsesPreviousWholePeriod() {
        val records = listOf(
            listen("1", "sunny", "2026-10-30T08:00:00Z", 100),
            listen("2", "if", "2026-10-30T09:00:00Z", 101),
            listen("3", "lucky", "2026-10-29T09:00:00Z", 50),
            listen("4", "sunny", "2026-10-23T09:00:00Z", 80)
        )
        val anchor = LocalDate.parse("2026-10-30")
        val summary = MusicListening.summary(records, tracks, Period.WEEK, anchor, utc)
        assertEquals(2, summary.activeDays)
        assertEquals(126L, summary.averageSeconds)
        assertEquals(Comparison(251, 80), MusicListening.compare(records, tracks, Period.WEEK, anchor, utc))
    }

    @Test fun dailySecondsUsesLocalDatesIncludingDst() {
        val zone = ZoneId.of("America/New_York")
        val records = listOf(
            listen("1", "sunny", "2026-11-01T05:30:00Z", 60),
            listen("2", "if", "2026-11-01T06:30:00Z", 90)
        )
        assertEquals(mapOf(LocalDate.parse("2026-11-01") to 150L), MusicListening.dailySeconds(records, tracks, zone))
    }

    @Test fun emptyInputProducesZeroSummaryAndZeroFilledTrend() {
        val summary = MusicListening.summary(emptyList(), tracks, Period.DAY, LocalDate.parse("2026-10-30"), utc)
        assertEquals(MusicSummary(0, 0, 0, 0, emptyList()), summary)
        assertTrue(MusicListening.trend(emptyList(), tracks, Period.DAY, LocalDate.parse("2026-10-30"), utc).all { it.seconds == 0L })
    }
}
