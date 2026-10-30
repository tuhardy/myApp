package com.focusassistant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 曲库与收听事件只描述真实数据；Android 不提供浏览器里的示例歌曲和示例记录。 */
data class MusicTrack(val id: String, val title: String, val artist: String, val durationSeconds: Long)
data class MusicListen(val id: String, val trackId: String, val playedAt: Long, val seconds: Long? = null)
data class MusicRankedTrack(val track: MusicTrack, val plays: Int, val seconds: Long, val lastPlayedAt: Long)
data class MusicSummary(
    val plays: Int,
    val seconds: Long,
    val activeDays: Int,
    val averageSeconds: Long,
    val ranking: List<MusicRankedTrack>
)
data class MusicTrendPoint(val key: String, val label: String, val seconds: Long)

object MusicListening {
    private data class ValidListen(val at: ZonedDateTime, val track: MusicTrack, val seconds: Long)

    private fun valid(record: MusicListen, tracks: Map<String, MusicTrack>, zone: ZoneId): ValidListen? {
        val track = tracks[record.trackId] ?: return null
        return ValidListen(Instant.ofEpochMilli(record.playedAt).atZone(zone), track,
            record.seconds?.takeIf { it >= 0 } ?: track.durationSeconds)
    }

    private fun selected(records: List<MusicListen>, tracks: List<MusicTrack>, period: Period, anchor: LocalDate, zone: ZoneId): List<ValidListen> {
        val (start, end) = Statistics.range(period, anchor)
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = end.atStartOfDay(zone).toInstant().toEpochMilli()
        val byId = tracks.associateBy { it.id }
        return records.mapNotNull { record ->
            if (record.playedAt < from || record.playedAt >= until) null else valid(record, byId, zone)
        }
    }

    /** 名次先看播放次数，再看累计秒数与歌名；活跃日均只计有收听的自然日。 */
    fun summary(records: List<MusicListen>, tracks: List<MusicTrack>, period: Period, anchor: LocalDate, zone: ZoneId = ZoneId.systemDefault()): MusicSummary {
        val selected = selected(records, tracks, period, anchor, zone)
        val byTrack = linkedMapOf<String, MusicRankedTrack>()
        selected.forEach { listen ->
            val current = byTrack[listen.track.id]
            byTrack[listen.track.id] = if (current == null) MusicRankedTrack(listen.track, 1, listen.seconds, listen.at.toInstant().toEpochMilli())
            else current.copy(plays = current.plays + 1, seconds = current.seconds + listen.seconds,
                lastPlayedAt = maxOf(current.lastPlayedAt, listen.at.toInstant().toEpochMilli()))
        }
        val days = selected.mapTo(mutableSetOf()) { it.at.toLocalDate() }.size
        val seconds = selected.sumOf { it.seconds }
        val ranking = byTrack.values.sortedWith(compareByDescending<MusicRankedTrack> { it.plays }
            .thenByDescending { it.seconds }.thenBy { it.track.title }.thenBy { it.track.artist })
        return MusicSummary(selected.size, seconds, days, if (days == 0) 0 else (seconds + days / 2) / days, ranking)
    }

    /** 日按小时、周与月按自然日、年按月补零；跨午夜记录只归入开始播放的时间桶。 */
    fun trend(records: List<MusicListen>, tracks: List<MusicTrack>, period: Period, anchor: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<MusicTrendPoint> {
        val (start, end) = Statistics.range(period, anchor)
        val bins = when (period) {
            Period.DAY -> (0 until 24).map { hour -> MusicTrendPoint("%02d".format(hour), "%02d:00".format(hour), 0) }
            Period.YEAR -> (0 until 12).map { offset ->
                val month = start.plusMonths(offset.toLong())
                MusicTrendPoint(month.toString().substring(0, 7), "%02d".format(month.monthValue), 0)
            }
            else -> generateSequence(start) { it.plusDays(1) }.takeWhile { it < end }.map { date ->
                MusicTrendPoint(date.toString(), if (period == Period.WEEK) date.toString().substring(5) else "%02d".format(date.dayOfMonth), 0)
            }.toList()
        }
        val indexes = bins.mapIndexed { index, point -> point.key to index }.toMap()
        val totals = LongArray(bins.size)
        selected(records, tracks, period, anchor, zone).forEach { listen ->
            val key = when (period) {
                Period.DAY -> "%02d".format(listen.at.hour)
                Period.YEAR -> listen.at.toLocalDate().toString().substring(0, 7)
                else -> listen.at.toLocalDate().toString()
            }
            indexes[key]?.let { totals[it] += listen.seconds }
        }
        return bins.mapIndexed { index, point -> point.copy(seconds = totals[index]) }
    }

    fun compare(records: List<MusicListen>, tracks: List<MusicTrack>, period: Period, anchor: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Comparison =
        Comparison(summary(records, tracks, period, anchor, zone).seconds,
            summary(records, tracks, period, Statistics.shift(period, anchor, -1), zone).seconds)

    fun dailySeconds(records: List<MusicListen>, tracks: List<MusicTrack>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Long> {
        val byId = tracks.associateBy { it.id }
        val result = linkedMapOf<LocalDate, Long>()
        records.forEach { record ->
            val listen = valid(record, byId, zone) ?: return@forEach
            val date = listen.at.toLocalDate()
            result[date] = (result[date] ?: 0L) + listen.seconds
        }
        return result
    }
}
