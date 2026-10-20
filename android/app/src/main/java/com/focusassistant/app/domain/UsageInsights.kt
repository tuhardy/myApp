package com.focusassistant.app.domain

import java.time.LocalDate
import java.time.ZoneId

/** 一段应用前台片段，已按查询范围裁剪；同一时刻多窗口的不同应用可能重叠。 */
data class UsageSpan(val packageName: String, val startedAt: Long, val endedAt: Long)

data class NightSummary(val millis: Long, val lastUse: Long?)

/** 用时页的派生规则：夜间足迹、片段并集、专注期间打开手机的次数。与浏览器原型 usageDaySummary 同口径。 */
object UsageInsights {
    const val NIGHT_START_HOUR = 23
    const val NIGHT_END_HOUR = 5
    /** 同一应用相隔不足 1 分钟的片段在展示时合并，避免真机上的短暂切换刷出大量碎片。 */
    const val COMPACT_GAP_MS = 60_000L

    /** date 的夜间窗口：前一天 23:00 至当天 05:00，按本地日历计算，夏令时当天自动伸缩。 */
    fun nightWindow(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
        date.minusDays(1).atTime(NIGHT_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli() to
            date.atTime(NIGHT_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    fun clip(spans: List<UsageSpan>, from: Long, to: Long): List<UsageSpan> = spans
        .map { it.copy(startedAt = maxOf(from, it.startedAt), endedAt = minOf(to, it.endedAt)) }
        .filter { it.endedAt > it.startedAt }
        .sortedWith(compareBy<UsageSpan> { it.startedAt }.thenBy { it.packageName })

    /** 不分应用的并集，多窗口重叠只算一次。 */
    fun union(spans: List<UsageSpan>): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        spans.sortedBy { it.startedAt }.forEach { span ->
            val last = result.lastOrNull()
            if (last != null && span.startedAt <= last.second) result[result.lastIndex] = last.first to maxOf(last.second, span.endedAt)
            else result += span.startedAt to span.endedAt
        }
        return result
    }

    /** 同一应用相隔不足 gapMs 的片段合并为一段，仅用于展示。 */
    fun compact(spans: List<UsageSpan>, gapMs: Long = COMPACT_GAP_MS): List<UsageSpan> =
        spans.groupBy { it.packageName }.flatMap { (_, group) ->
            val merged = mutableListOf<UsageSpan>()
            group.sortedBy { it.startedAt }.forEach { span ->
                val last = merged.lastOrNull()
                if (last != null && span.startedAt - last.endedAt <= gapMs) merged[merged.lastIndex] = last.copy(endedAt = maxOf(last.endedAt, span.endedAt))
                else merged += span
            }
            merged
        }.sortedWith(compareBy<UsageSpan> { it.startedAt }.thenBy { it.packageName })

    /** 多个日期的夜间足迹合计（周视图传周一至今天），最晚使用时刻取所有窗口中最晚的片段结束时间。 */
    fun night(spans: List<UsageSpan>, dates: List<LocalDate>, zone: ZoneId): NightSummary {
        val windows = dates.map { union(clip(spans, nightWindow(it, zone).first, nightWindow(it, zone).second)) }
        return NightSummary(windows.sumOf { window -> window.sumOf { it.second - it.first } }, windows.flatten().maxOfOrNull { it.second })
    }

    /** 专注记录的真实活动片段裁剪到 [from, to)，按开始时间排序，与首页刻度尺共用。 */
    fun focusSpans(sessions: List<FocusSession>, from: Long, to: Long): List<Pair<Long, Long>> =
        sessions.flatMap { it.segments }.map { maxOf(from, it.startedAt) to minOf(to, it.endedAt) }
            .filter { it.second > it.first }.sortedBy { it.first }

    /** 专注期间打开手机的次数：用屏并集中与任一专注片段相交的段数。 */
    fun overlapCount(spans: List<UsageSpan>, focus: List<Pair<Long, Long>>): Int =
        union(spans).count { (from, to) -> focus.any { from < it.second && it.first < to } }

    fun overlaps(span: UsageSpan, focus: List<Pair<Long, Long>>): Boolean =
        focus.any { span.startedAt < it.second && it.first < span.endedAt }
}
