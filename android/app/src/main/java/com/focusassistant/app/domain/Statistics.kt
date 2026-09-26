package com.focusassistant.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class Summary(val seconds: Long, val count: Int, val activeDays: Int, val averageSeconds: Double)
data class AllTimeSummary(val seconds: Long, val count: Int, val activeDays: Int, val calendarDays: Long,
    val calendarAverageSeconds: Double, val activeAverageSeconds: Double, val firstDate: LocalDate?)
data class TrendPoint(val label: String, val seconds: Long)

object Statistics {
    fun range(period: Period, anchor: LocalDate): Pair<LocalDate, LocalDate> {
        val start = when (period) {
            Period.DAY -> anchor
            Period.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Period.MONTH -> anchor.withDayOfMonth(1)
            Period.YEAR -> anchor.withDayOfYear(1)
        }
        return start to when (period) {
            Period.DAY -> start.plusDays(1)
            Period.WEEK -> start.plusWeeks(1)
            Period.MONTH -> start.plusMonths(1)
            Period.YEAR -> start.plusYears(1)
        }
    }
    private fun date(session: FocusSession, zone: ZoneId) = Instant.ofEpochMilli(session.endedAt).atZone(zone).toLocalDate()
    fun select(sessions: List<FocusSession>, period: Period, anchor: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<FocusSession> {
        val (start, end) = range(period, anchor)
        return sessions.filter { date(it, zone).let { day -> !day.isBefore(start) && day.isBefore(end) } }
    }
    fun summary(sessions: List<FocusSession>, zone: ZoneId = ZoneId.systemDefault()): Summary {
        val seconds = sessions.sumOf { it.durationSeconds }
        val days = sessions.map { date(it, zone) }.toSet().size
        return Summary(seconds, sessions.size, days, if (days == 0) 0.0 else seconds.toDouble() / days)
    }
    fun allTime(sessions: List<FocusSession>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): AllTimeSummary {
        val completed = sessions.filter { !date(it, zone).isAfter(today) }
        val summary = summary(completed, zone)
        val first = completed.minOfOrNull { date(it, zone) }
        val days = if (first == null) 0 else ChronoUnit.DAYS.between(first, today) + 1
        return AllTimeSummary(summary.seconds, summary.count, summary.activeDays, days,
            if (days == 0L) 0.0 else summary.seconds.toDouble() / days, summary.averageSeconds, first)
    }
    fun hours(sessions: List<FocusSession>, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val millis = LongArray(24)
        for (session in sessions) for (segment in session.segments) {
            var cursor = Instant.ofEpochMilli(segment.startedAt)
            val end = Instant.ofEpochMilli(segment.endedAt)
            while (cursor < end) {
                val local = cursor.atZone(zone)
                val nextLocal = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS).plusHours(1)
                val nextHour = java.time.ZonedDateTime.ofLocal(nextLocal, zone, local.offset).toInstant()
                val transition = zone.rules.nextTransition(cursor)?.instant
                var next = minOf(nextHour, end)
                if (transition != null && transition > cursor) next = minOf(next, transition)
                millis[local.hour] += next.toEpochMilli() - cursor.toEpochMilli()
                cursor = next
            }
        }
        val seconds = millis.map { it / 1000 }.toMutableList()
        val remainder = (millis.sum() / 1000 - seconds.sum()).toInt()
        millis.indices.sortedByDescending { millis[it] % 1000 }.take(remainder).forEach { seconds[it]++ }
        return seconds
    }
    fun trend(sessions: List<FocusSession>, period: Period, anchor: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<TrendPoint> {
        val selected = select(sessions, period, anchor, zone)
        if (period == Period.DAY) return hours(selected, zone).mapIndexed { hour, seconds -> TrendPoint("%02d".format(hour), seconds) }
        val (start, end) = range(period, anchor)
        val result = mutableListOf<TrendPoint>()
        var cursor = start
        while (cursor < end) {
            val next = if (period == Period.YEAR) cursor.plusMonths(1) else cursor.plusDays(1)
            val seconds = selected.filter { date(it, zone) >= cursor && date(it, zone) < next }.sumOf { it.durationSeconds }
            result += TrendPoint(if (period == Period.YEAR) cursor.monthValue.toString() else cursor.toString(), seconds)
            cursor = next
        }
        return result
    }
    fun latestProgress(progress: List<ProgressEntry>, projectId: String): ProgressEntry? = latest(progress.filter { it.projectId == projectId })
    fun latestForSession(progress: List<ProgressEntry>, sessionId: String): ProgressEntry? = latest(progress.filter { it.sessionId == sessionId })
    private fun latest(progress: List<ProgressEntry>): ProgressEntry? = progress.withIndex().maxWithOrNull(
        compareBy<IndexedValue<ProgressEntry>> { it.value.sessionEndedAt }.thenBy { it.value.updatedAt }.thenBy { it.index })?.value

    fun csv(sessions: List<FocusSession>, progress: List<ProgressEntry>): String {
        val zone = ZoneId.systemDefault()
        val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        fun timestamp(value: Long) = Instant.ofEpochMilli(value).atZone(zone).format(format)
        fun field(value: Any?): String {
            var text = value?.toString() ?: ""
            if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || text.firstOrNull() in listOf('\t', '\r', '\n')) text = "'" + text
            return "\"" + text.replace("\"", "\"\"") + "\""
        }
        val rows = mutableListOf<List<Any?>>(listOf("日期", "任务", "分类", "开始时间", "结束时间", "时长（分钟）", "来源", "计时模式", "专注项", "目标时长（分钟）", "时长（秒）", "学习进度", "完成度（%）", "进度更新时间"))
        sessions.forEach { session ->
            val latest = latestForSession(progress, session.id)
            rows += listOf(date(session, zone).toString(), session.taskTitle, session.category, timestamp(session.startedAt), timestamp(session.endedAt),
                session.durationSeconds / 60.0, "专注计时", if (session.timerMode == TimerMode.COUNTUP) "正计时" else "倒计时", session.projectTitle,
                session.targetMinutes, session.durationSeconds, latest?.note, latest?.percent, latest?.updatedAt?.let { timestamp(it) })
        }
        return "\uFEFF" + rows.joinToString("\r\n") { it.joinToString(",") { value -> field(value) } } + "\r\n"
    }
}
