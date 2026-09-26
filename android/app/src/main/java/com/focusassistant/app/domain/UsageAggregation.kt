package com.focusassistant.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal data class UsageBucketWindow(val label: String, val start: Long, val end: Long)
internal data class UsageBucketTotal(val label: String, val durationMillis: Long, val available: Boolean)

internal class UsageOverviewRange(today: LocalDate, week: Boolean, zone: ZoneId) {
    val todayStart: Long = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val todayEnd: Long = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val buckets: List<UsageBucketWindow> = if (week) {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        WEEK_LABELS.mapIndexed { index, label ->
            val date = monday.plusDays(index.toLong())
            UsageBucketWindow(label, date.atStartOfDay(zone).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }
    } else {
        val boundaries = (0 until HOURS_PER_DAY step HOURS_PER_BUCKET).map { hour ->
            today.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        } + todayEnd
        (0 until HOURS_PER_DAY / HOURS_PER_BUCKET).map { index ->
            UsageBucketWindow((index * HOURS_PER_BUCKET).toString().padStart(2, '0'), boundaries[index], boundaries[index + 1])
        }
    }
    val start: Long = buckets.first().start
    val end: Long = buckets.last().end

    companion object {
        private const val HOURS_PER_DAY = 24
        private const val HOURS_PER_BUCKET = 2
        private val WEEK_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}

internal class UsageAggregation(
    private val start: Long,
    private val end: Long,
    private val overview: UsageOverviewRange? = null
) {
    private val packageTotals = mutableMapOf<String, Long>()
    private val windows = overview?.buckets.orEmpty()
    private val durations = LongArray(windows.size)
    private val available = BooleanArray(windows.size)
    private var rangeHasEvents = false
    private var todayAvailable = false
    private var todayDuration = 0L

    val totals: Map<String, Long> get() = packageTotals.toMap()
    val hasData: Boolean get() = rangeHasEvents || packageTotals.isNotEmpty()
    val todayMillis: Long? get() = todayDuration.takeIf { todayAvailable }
    val buckets: List<UsageBucketTotal> get() = windows.mapIndexed { index, window ->
        UsageBucketTotal(window.label, durations[index], available[index])
    }

    fun recordEvent(time: Long) {
        if (time < start || time >= end) return
        rangeHasEvents = true
        windows.forEachIndexed { index, window ->
            if (time >= window.start && time < window.end) available[index] = true
        }
        if (overview != null && time >= overview.todayStart && time < overview.todayEnd) todayAvailable = true
    }

    fun addSegment(packageName: String, segment: TimeSegment) {
        val from = maxOf(start, segment.startedAt)
        val to = minOf(end, segment.endedAt)
        if (to <= from) return
        packageTotals[packageName] = (packageTotals[packageName] ?: 0L) + to - from
        windows.forEachIndexed { index, window ->
            val duration = overlap(from, to, window.start, window.end)
            if (duration > 0) {
                durations[index] += duration
                available[index] = true
            }
        }
        if (overview != null) {
            val duration = overlap(from, to, overview.todayStart, overview.todayEnd)
            if (duration > 0) {
                todayDuration += duration
                todayAvailable = true
            }
        }
    }

    private fun overlap(from: Long, to: Long, rangeStart: Long, rangeEnd: Long): Long =
        (minOf(to, rangeEnd) - maxOf(from, rangeStart)).coerceAtLeast(0)
}
