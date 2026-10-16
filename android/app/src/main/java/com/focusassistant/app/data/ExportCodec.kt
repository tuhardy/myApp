package com.focusassistant.app.data

import com.focusassistant.app.domain.FocusSession
import com.focusassistant.app.domain.Period
import com.focusassistant.app.domain.ProgressEntry
import com.focusassistant.app.domain.Statistics
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object ExportCodec {
    /** allPeriod 为 true 时导出「全部」周期：范围从首个完成日到明天零点，趋势按月。 */
    fun statisticsJson(sessions: List<FocusSession>, progress: List<ProgressEntry>, allSessions: List<FocusSession>, period: Period, anchor: LocalDate, allPeriod: Boolean = false): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val (start, end) = if (allPeriod) Statistics.allTimeRange(sessions, today, zone) ?: (today to today.plusDays(1)) else Statistics.range(period, anchor)
        val summary = Statistics.summary(sessions, zone)
        val all = Statistics.allTime(allSessions, today, zone)
        val ids = sessions.map { it.id }.toSet()
        val trend = if (allPeriod) Statistics.monthlyTrend(sessions, today, zone) else Statistics.trend(sessions, period, anchor, zone)
        return JSONObject().put("kind", "focus-statistics").put("schemaVersion", 1)
            .put("exportedAt", Instant.now().toString()).put("period", if (allPeriod) "ALL" else period.name).put("anchor", anchor.toString())
            .put("range", JSONObject().put("start", start.atStartOfDay(zone).toInstant().toString()).put("endExclusive", end.atStartOfDay(zone).toInstant().toString()))
            .put("timeZone", zone.id).put("grouping", "local-completion-date").put("activeDayAverage", true)
            .put("includesSamples", false).put("source", "native-focus-sessions").put("durationUnit", "seconds")
            .put("hourDistributionPolicy", "selected-sessions-active-segments-without-date-clipping")
            .put("summary", JSONObject().put("seconds", summary.seconds).put("count", summary.count).put("activeDays", summary.activeDays).put("averageSeconds", summary.averageSeconds))
            .put("allTimeSummary", JSONObject().put("seconds", all.seconds).put("count", all.count).put("activeDays", all.activeDays).put("calendarDays", all.calendarDays)
                .put("calendarAverageSeconds", all.calendarAverageSeconds).put("activeAverageSeconds", all.activeAverageSeconds).put("firstDate", all.firstDate?.toString() ?: JSONObject.NULL))
            .put("hours", JSONArray(Statistics.hours(sessions, zone)))
            .put("trend", JSONArray(trend.map { JSONObject().put("label", it.label).put("seconds", it.seconds) }))
            .put("sessions", JSONArray(sessions.map { session -> JsonCodec.session(session).put("latestProgress", Statistics.latestForSession(progress, session.id)?.let(JsonCodec::progress) ?: JSONObject.NULL) }))
            .put("progress", JSONArray(progress.filter { it.sessionId in ids }.map(JsonCodec::progress))).toString(2)
    }
}
