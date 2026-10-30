package com.focusassistant.app.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusassistant.app.domain.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

internal enum class MusicTab(val label: String) { RECOMMEND("推荐"), PLAYLISTS("歌单"), SONGS("歌曲"), HISTORY("回顾") }
internal data class MusicPlaylist(val id: String, val name: String, val trackIds: List<String>)

/** 只保存界面状态；真实曲库、歌单和收听记录不由这里伪造或写入。 */
internal class MusicUiState {
    var tab by mutableStateOf(MusicTab.RECOMMEND)
    var query by mutableStateOf("")
    var selectedTrackId by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var playlistId by mutableStateOf<String?>(null)
    var period by mutableStateOf(Period.WEEK)
    var anchor by mutableStateOf(LocalDate.now())
    var rankLimit by mutableIntStateOf(MUSIC_RANK_PAGE)
    fun resetHistory() { rankLimit = MUSIC_RANK_PAGE }
}

private const val MUSIC_RANK_PAGE = 10
private const val MUSIC_PICKER_YEARS = 12
private const val MUSIC_MIN_YEAR = 1900
private const val MUSIC_MAX_YEAR = 2100

// 与浏览器 music-* 样式一致：音乐页使用更明亮的播放橙和独立的灰阶。
private val MusicOrange = Color(0xFFEF5B24)
private val MusicMuted = Color(0xFF686E72)
private val MusicSurface = Color(0xFFF2F3F3)
private val MusicLine = Color(0xFFDDE0E1)
private val MusicSongLine = Color(0xFFE7E9E9)
private val MusicCurrent = Color(0xFFF7F7F6)
private val MusicWaveIdle = Color(0xFFC9CED0)

private fun musicDurationText(seconds: Long): String {
    val minutes = (seconds.coerceAtLeast(0) + 30) / 60
    if (minutes < 60) return "$minutes 分钟"
    val rest = minutes % 60
    return "${minutes / 60} 小时${if (rest > 0) " $rest 分" else ""}"
}

private fun musicPeriodTitle(period: Period) = when (period) {
    Period.DAY -> "单日回顾"; Period.WEEK -> "每周回顾"; Period.MONTH -> "每月回顾"; Period.YEAR -> "年度回顾"
}
private fun musicCurrentName(period: Period) = when (period) {
    Period.DAY -> "今天"; Period.WEEK -> "本周"; Period.MONTH -> "本月"; Period.YEAR -> "今年"
}
private fun musicPeriodShort(period: Period) = when (period) {
    Period.DAY -> "日"; Period.WEEK -> "周"; Period.MONTH -> "月"; Period.YEAR -> "年"
}
private fun musicPreviousName(period: Period) = when (period) {
    Period.DAY -> "昨天"; Period.WEEK -> "上周"; Period.MONTH -> "上月"; Period.YEAR -> "去年"
}
private fun musicBeforeName(period: Period) = when (period) {
    Period.DAY -> "前一天"; Period.WEEK -> "前一周"; Period.MONTH -> "前一个月"; Period.YEAR -> "前一年"
}
private fun musicRangeLabel(period: Period, anchor: LocalDate): String {
    val (start, end) = Statistics.range(period, anchor)
    if (period == Period.DAY) return "${start.year}年${start.monthValue}月${start.dayOfMonth}日"
    if (period == Period.MONTH) return "${start.year}年${start.monthValue}月"
    if (period == Period.YEAR) return "${start.year}年"
    val last = end.minusDays(1)
    val endYear = if (last.year == start.year) "" else "${last.year}年"
    return "${start.year}年${start.monthValue}月${start.dayOfMonth}日–$endYear${last.monthValue}月${last.dayOfMonth}日"
}

@Composable
internal fun MusicScreen(
    tracks: List<MusicTrack>,
    listens: List<MusicListen>,
    playlists: List<MusicPlaylist>,
    state: MusicUiState,
    today: LocalDate,
    onMessage: (String) -> Unit = {}
) {
    val current = tracks.find { it.id == state.selectedTrackId }
    val playlist = playlists.find { it.id == state.playlistId }
    BackHandler(enabled = playlist != null) { state.playlistId = null }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        if (playlist != null) {
            PlaylistDetail(playlist, tracks, state.selectedTrackId, Modifier.weight(1f), onBack = { state.playlistId = null }, onSelect = { track ->
                state.selectedTrackId = track.id; state.playing = true
                onMessage("已切换到《${track.title}》，仅展示界面状态，没有播放音频。")
            })
        } else {
            MusicHeader(state, onMessage)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 23.dp, end = 23.dp, bottom = 20.dp)) {
                when (state.tab) {
                    MusicTab.RECOMMEND -> item(key = "recommend") {
                        RecommendationPanel(tracks, listens, playlists, state, onMessage)
                    }
                    MusicTab.PLAYLISTS -> item(key = "playlists") {
                        MusicSectionHeading("我的歌单", "${playlists.size} 个")
                        if (playlists.isEmpty()) EmptyCard("还没有本机歌单", "新建歌单将在真实曲库接入后开放，不会用示例歌单填充。")
                        else MusicPlaylistGrid(playlists, full = true) { state.playlistId = it.id }
                        OutlinedButton(onClick = { onMessage("新建歌单将在真实曲库接入后开放，本轮仅展示界面。") },
                            Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 52.dp),
                            shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, TickIdle),
                            contentPadding = PaddingValues(12.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
                            Text("新建歌单（界面演示）", fontSize = 12.sp)
                        }
                    }
                    MusicTab.SONGS -> item(key = "songs") {
                        val query = state.query.trim()
                        val filtered = tracks.filter { query.isEmpty() || "${it.title} ${it.artist}".contains(query, ignoreCase = true) }
                        MusicSectionHeading("全部歌曲", if (query.isEmpty()) "${tracks.size} 首" else "${filtered.size} 首匹配")
                        if (filtered.isEmpty()) EmptyCard(if (tracks.isEmpty()) "还没有本机歌曲" else "没有匹配的歌曲",
                            if (tracks.isEmpty()) "当前未接入本地音频导入，不会显示浏览器示例歌曲。" else "换个歌名或歌手试试。")
                        else Column { filtered.forEachIndexed { index, track ->
                            SongRow(track, current?.id == track.id, showDivider = index < filtered.lastIndex,
                                onClick = { selectTrack(track, state, onMessage) })
                        } }
                    }
                    MusicTab.HISTORY -> item(key = "history") {
                        MusicHistoryPanel(tracks, listens, state, today, onMessage)
                    }
                }
                if (state.tab != MusicTab.HISTORY) item(key = "data-note") {
                    Text("只展示本机真实曲库与记录 · 当前未接入音频导入、真实播放或设备音乐读取",
                        color = MusicMuted, fontSize = 12.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            }
        }
        if (current != null) MiniPlayer(current, state.playing) {
            state.playing = !state.playing
            onMessage(if (state.playing) "已切换为播放状态，仅作界面演示，没有播放音频。" else "已暂停界面演示。")
        }
    }
}

@Composable
private fun MusicHeader(state: MusicUiState, onMessage: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 23.dp, end = 23.dp, top = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("流行歌单与日常收听", color = MusicMuted, fontSize = 12.sp, letterSpacing = 0.4.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
                Text("声音馆.", fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = (-0.9).sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { state.tab = MusicTab.HISTORY },
                Modifier.padding(end = 7.dp).size(44.dp).border(1.dp, Line, RoundedCornerShape(10.dp))) {
                Icon(Icons.Outlined.BarChart, "查看收听回顾", Modifier.size(18.dp), tint = Ink)
            }
            IconButton(onClick = { onMessage("添加歌曲将在确认本地音频导入方案后接入；当前不会读取或复制文件。") },
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Ink)) {
                Icon(Icons.Outlined.Add, "添加歌曲，界面演示", Modifier.size(18.dp), tint = Color.White)
            }
        }
        BasicTextField(state.query, { state.query = it },
            Modifier.fillMaxWidth().height(42.dp).background(MusicSurface, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
            singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 13.sp), cursorBrush = SolidColor(Accent),
            decorationBox = { innerTextField ->
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = MusicMuted)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (state.query.isEmpty()) Text("搜索歌名或歌手", color = MusicMuted, fontSize = 13.sp)
                        innerTextField()
                    }
                }
            })
        MusicTabs(state)
    }
}

@Composable
private fun MusicTabs(state: MusicUiState) {
    Box(Modifier.fillMaxWidth().padding(top = 7.dp)) {
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = MusicLine)
        Row(horizontalArrangement = Arrangement.spacedBy(25.dp)) {
            MusicTab.entries.forEach { tab ->
                val selected = state.tab == tab
                Column(Modifier.height(44.dp).clickable { state.tab = tab; state.playlistId = null },
                    verticalArrangement = Arrangement.Bottom) {
                    Text(tab.label, color = if (selected) Ink else MusicMuted, fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.width(26.dp).height(2.dp).background(if (selected) MusicOrange else Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun RecommendationPanel(tracks: List<MusicTrack>, listens: List<MusicListen>, playlists: List<MusicPlaylist>, state: MusicUiState, onMessage: (String) -> Unit) {
    val current = tracks.find { it.id == state.selectedTrackId }
    if (current == null) EmptyCard("还没有本机歌曲", "导入方案确认后，可在这里选择真实音频；当前播放器不会发出声音。", Modifier.padding(top = 19.dp))
    else NowPlayingCard(current, tracks, state, onMessage)
    MusicSectionHeading("我的歌单", action = "全部") { state.tab = MusicTab.PLAYLISTS }
    if (playlists.isEmpty()) EmptyCard("还没有本机歌单", "不会用示例歌单替代尚未创建的记录。")
    else MusicPlaylistGrid(playlists.take(3)) { state.playlistId = it.id }
    Spacer(Modifier.height(2.dp))
    MusicSectionHeading("最近播放", action = "全部歌曲") { state.tab = MusicTab.SONGS }
    val byId = tracks.associateBy { it.id }
    val recent = listens.sortedByDescending { it.playedAt }.mapNotNull { byId[it.trackId] }.distinctBy { it.id }.take(2)
    if (recent.isEmpty()) EmptyCard("还没有收听记录", "真实播放接入后才会在这里生成记录。")
    else Column { recent.forEachIndexed { index, track ->
        SongRow(track, current?.id == track.id, showDivider = index < recent.lastIndex) { selectTrack(track, state, onMessage) }
    } }
}

private fun selectTrack(track: MusicTrack, state: MusicUiState, onMessage: (String) -> Unit) {
    state.selectedTrackId = track.id; state.playing = true
    onMessage("已切换到《${track.title}》，仅展示界面状态，没有播放音频。")
}

@Composable
private fun NowPlayingCard(track: MusicTrack, tracks: List<MusicTrack>, state: MusicUiState, onMessage: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 19.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(MusicOrange, RoundedCornerShape(50)))
                Text(if (state.playing) "正在播放" else "已暂停", color = MusicMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 7.dp))
            }
            IconButton(onClick = { onMessage("更多操作将在真实播放接入后开放，本轮仅展示界面。") }, Modifier.size(44.dp)) {
                Icon(Icons.Outlined.MoreHoriz, "当前歌曲更多操作，界面演示", Modifier.size(19.dp), tint = MusicMuted)
            }
        }
        Text(track.title, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.7).sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
        Text("${track.artist} · ${clockText(track.durationSeconds)}", color = MusicMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp))
        WavePanel(track.id, Modifier.padding(top = 16.dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(34.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { stepTrack(tracks, state, -1, onMessage) }, Modifier.size(44.dp)) {
                Icon(Icons.Outlined.SkipPrevious, "上一首，界面演示", tint = Color(0xFF565C60))
            }
            FilledIconButton(onClick = {
                state.playing = !state.playing
                onMessage(if (state.playing) "已切换为播放状态，仅作界面演示，没有播放音频。" else "已暂停界面演示。")
            }, modifier = Modifier.size(50.dp), shape = RoundedCornerShape(50),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MusicOrange, contentColor = Color(0xFF252729))) {
                Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (state.playing) "暂停，界面演示" else "播放，界面演示", Modifier.size(20.dp))
            }
            IconButton(onClick = { stepTrack(tracks, state, 1, onMessage) }, Modifier.size(44.dp)) {
                Icon(Icons.Outlined.SkipNext, "下一首，界面演示", tint = Color(0xFF565C60))
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MusicLine)
    }
}

private fun stepTrack(tracks: List<MusicTrack>, state: MusicUiState, direction: Int, onMessage: (String) -> Unit) {
    if (tracks.isEmpty()) return
    val index = tracks.indexOfFirst { it.id == state.selectedTrackId }.coerceAtLeast(0)
    selectTrack(tracks[(index + direction + tracks.size) % tracks.size], state, onMessage)
}

@Composable
private fun WavePanel(seed: String, modifier: Modifier = Modifier) {
    val levels = remember(seed) {
        val base = listOf(18, 32, 48, 26, 66, 88, 56, 36, 74, 46, 24, 60, 82, 44,
            28, 64, 94, 50, 30, 72, 40, 20, 54, 78, 42, 88, 60, 32, 68, 48, 22, 58, 80, 38)
        val shift = seed.hashCode().and(3)
        base.mapIndexed { index, level -> if (index < 14) level else base[(index + shift) % base.size] }
    }
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(76.dp).semantics { contentDescription = "歌曲波形示意，不代表真实播放进度" }) {
            val gap = 3.dp.toPx()
            val cell = (size.width - gap * (levels.size - 1)) / levels.size
            val waveTop = 4.dp.toPx()
            val waveHeight = size.height - waveTop
            levels.forEachIndexed { index, level ->
                val height = waveHeight * level / 100f
                drawRoundRect(if (index < 14) MusicOrange else MusicWaveIdle,
                    Offset(index * (cell + gap), waveTop + (waveHeight - height) / 2),
                    Size(cell, height), CornerRadius(2.dp.toPx()))
            }
            val playheadX = size.width * 0.39f
            drawLine(AccentDark, Offset(playheadX, 2.dp.toPx()), Offset(playheadX, size.height - 20.dp.toPx()), 1.dp.toPx())
            drawCircle(AccentDark, 2.5.dp.toPx(), Offset(playheadX, 1.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text("00:00", color = Ink, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("未播放", color = MusicMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun MusicSectionHeading(title: String, detail: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        if (detail != null) Text(detail, color = MusicMuted, fontSize = 11.sp)
        if (action != null && onAction != null) TextButton(onClick = onAction, Modifier.height(36.dp),
            contentPadding = PaddingValues(0.dp)) { Text(action, color = MusicMuted, fontSize = 11.sp) }
    }
}

@Composable
private fun MusicPlaylistGrid(playlists: List<MusicPlaylist>, full: Boolean = false, onOpen: (MusicPlaylist) -> Unit) {
    Column(Modifier.padding(top = if (full) 6.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(if (full) 22.dp else 12.dp)) {
        playlists.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { playlist ->
                    Column(Modifier.weight(1f).clickable { onOpen(playlist) }.semantics { contentDescription = "歌单 ${playlist.name}，${playlist.trackIds.size} 首" }) {
                        CoverArt(playlist.id, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(playlist.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 7.dp))
                        Text("${playlist.trackIds.size} 首", color = MusicMuted, fontSize = 10.sp)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(playlist: MusicPlaylist, tracks: List<MusicTrack>, selectedTrackId: String?, modifier: Modifier, onBack: () -> Unit, onSelect: (MusicTrack) -> Unit) {
    val byId = tracks.associateBy { it.id }
    val songs = playlist.trackIds.mapNotNull { byId[it] }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 23.dp, end = 23.dp, top = 14.dp, bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(44.dp).border(1.dp, Line, RoundedCornerShape(50))) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(18.dp))
                }
                Text("歌单", fontSize = 18.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).weight(1f))
                Text("本机", color = MusicMuted, fontSize = 11.sp,
                    modifier = Modifier.border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
        item {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(playlist.id, Modifier.size(108.dp), radius = 9.dp)
                    Column(Modifier.padding(start = 18.dp).weight(1f)) {
                        Text(playlist.name, fontSize = 23.sp, lineHeight = 31.sp, letterSpacing = (-0.5).sp, fontWeight = FontWeight.SemiBold)
                        Text("${songs.size} 首 · 本机歌单", color = MusicMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        Button(onClick = { songs.firstOrNull()?.let(onSelect) }, enabled = songs.isNotEmpty(),
                            modifier = Modifier.padding(top = 13.dp).height(40.dp), shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                            Text("播放全部", fontSize = 12.sp)
                        }
                    }
                }
                HorizontalDivider(color = MusicLine)
            }
        }
        item { MusicSectionHeading("歌单歌曲", if (songs.isEmpty()) "暂无歌曲" else "${songs.size} 首") }
        if (songs.isEmpty()) item { EmptyCard("这个歌单还没有歌曲", "不会从全部歌曲或浏览器示例中自动补入。") }
        else item { Column { songs.forEachIndexed { index, track ->
            SongRow(track, track.id == selectedTrackId, showDivider = index < songs.lastIndex) { onSelect(track) }
        } } }
    }
}

@Composable
private fun SongRow(track: MusicTrack, selected: Boolean, rank: Int? = null, stats: Pair<Int, Long>? = null,
    showDivider: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (rank == null) 60.dp else 68.dp)
            .background(if (selected) MusicCurrent else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (rank != null) {
                Text("$rank", color = if (rank == 1) Accent else Color(0xFF8B9093), fontSize = if (rank == 1) 16.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.width(20.dp))
                Spacer(Modifier.width(9.dp))
            }
            CoverArt(track.id, Modifier.size(40.dp), radius = 6.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(track.title, color = if (selected) AccentDark else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = MusicMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (stats == null) Text(clockText(track.durationSeconds), color = MusicMuted, fontSize = 10.sp)
            else Column(Modifier.widthIn(min = 62.dp), horizontalAlignment = Alignment.End) {
                Text("${stats.first} 次", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(musicDurationText(stats.second), color = MusicMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (showDivider) HorizontalDivider(color = MusicSongLine)
    }
}

@Composable
private fun CoverArt(seed: String, modifier: Modifier = Modifier, radius: Dp = 7.dp) {
    val tones = listOf(
        Color(0xFFE4E6E6) to Color(0xFF777E81), Color(0xFF35393B) to Color(0xFFD8DCDD),
        Color(0xFFD7DADB) to Color(0xFF666D70), Color(0xFFECECEA) to Color(0xFF85898A),
        Color(0xFFC9CED0) to Color(0xFF555D60), Color(0xFFE0E1E1) to Color(0xFF727779))
    val hash = seed.hashCode()
    val (base, foreground) = tones[(hash and Int.MAX_VALUE) % tones.size]
    Canvas(modifier.clip(RoundedCornerShape(radius)).background(base).clearAndSetSemantics { }) {
        val count = 12
        val cell = size.width / count
        repeat(count) { index ->
            val level = 0.18f + ((hash ushr (index % 20)) xor index * 19).and(7) / 10f
            val height = size.height * level
            drawRoundRect(foreground.copy(alpha = 0.55f + (index % 3) * 0.15f), Offset(index * cell + cell * 0.2f, (size.height - height) / 2),
                Size(cell * 0.55f, height), CornerRadius(2.dp.toPx()))
        }
    }
}

@Composable
private fun EmptyCard(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SoftSurface).padding(16.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MusicMuted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MiniPlayer(track: MusicTrack, playing: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 7.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MusicSurface)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(start = 10.dp, end = 8.dp, top = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CoverArt(track.id, Modifier.size(36.dp), radius = 6.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(track.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = MusicMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledIconButton(onClick = onToggle, Modifier.size(44.dp), shape = RoundedCornerShape(50),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Ink, contentColor = Color.White)) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (playing) "暂停，界面演示" else "播放，界面演示", Modifier.size(17.dp))
                }
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 9.dp)) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(MusicLine))
                Box(Modifier.fillMaxWidth(0.39f).height(2.dp).background(if (playing) MusicOrange else Color(0xFFA8ADB0)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicHistoryPanel(tracks: List<MusicTrack>, listens: List<MusicListen>, state: MusicUiState, today: LocalDate, onMessage: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    val period = state.period
    val anchor = state.anchor
    val summary = remember(listens, tracks, period, anchor) { MusicListening.summary(listens, tracks, period, anchor, zone) }
    val trend = remember(listens, tracks, period, anchor) { MusicListening.trend(listens, tracks, period, anchor, zone) }
    val comparison = remember(listens, tracks, period, anchor) { MusicListening.compare(listens, tracks, period, anchor, zone) }
    val daily = remember(listens, tracks) { MusicListening.dailySeconds(listens, tracks, zone) }
    val current = Statistics.range(period, anchor).first == Statistics.range(period, today).first
    var pickerOpen by remember { mutableStateOf(false) }
    var restorePickerFocus by remember { mutableStateOf(false) }
    val pickerFocus = remember { FocusRequester() }
    LaunchedEffect(pickerOpen) {
        if (!pickerOpen && restorePickerFocus) {
            pickerFocus.requestFocus()
            restorePickerFocus = false
        }
    }
    fun selectAnchor(date: LocalDate) {
        if (Statistics.range(period, date).first > Statistics.range(period, today).first) {
            onMessage("不能查看未来周期，请选择今天或更早的日期。"); return
        }
        state.anchor = date; state.resetHistory(); pickerOpen = false
    }
    MusicSectionHeading(musicPeriodTitle(period), "本机记录")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SoftSurface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Period.entries.forEach { value ->
            val selected = period == value
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                .background(if (selected) AccentSoft else Color.Transparent)
                .clickable { state.period = value; state.resetHistory() }, contentAlignment = Alignment.Center) {
                Text(musicPeriodShort(value), color = if (selected) AccentDark else MusicMuted, fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { state.anchor = Statistics.shift(period, anchor, -1); state.resetHistory() },
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MusicSurface)) {
            Icon(Icons.Outlined.ChevronLeft, "上一期", Modifier.size(18.dp))
        }
        TextButton(onClick = { restorePickerFocus = true; pickerOpen = true },
            Modifier.weight(1f).height(44.dp).focusRequester(pickerFocus), shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(6.dp)) {
            Text(musicRangeLabel(period, anchor), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Icon(Icons.Outlined.ExpandMore, "选择回顾周期", tint = MusicMuted, modifier = Modifier.size(13.dp))
        }
        IconButton(onClick = { state.anchor = Statistics.shift(period, anchor, 1); state.resetHistory() }, enabled = !current,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MusicSurface).alpha(if (current) 0.3f else 1f)) {
            Icon(Icons.Outlined.ChevronRight, "下一期", Modifier.size(18.dp))
        }
    }
    if (!current) TextButton(onClick = { state.anchor = today; state.resetHistory() },
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).height(34.dp)) { Text("回到${musicCurrentName(period)}", fontSize = 12.sp) }
    MusicOverview(summary, comparison, period, current)
    MusicSectionHeading("播放趋势", "按收听时长")
    val title = when (period) { Period.DAY -> "逐小时播放时长"; Period.WEEK, Period.MONTH -> "每日播放时长"; Period.YEAR -> "每月播放时长" }
    fun trendLabel(index: Int): String {
        val point = trend[index]
        return when (period) {
            Period.DAY -> point.label
            Period.YEAR -> "${point.label.toInt()}月"
            Period.MONTH -> "${point.label.toInt()}日"
            Period.WEEK -> point.label.substringBefore("-").toInt().toString() + "月" + point.label.substringAfter("-").toInt() + "日"
        }
    }
    val positions = if (trend.size <= 7) trend.indices.toList() else List(5) { it * (trend.lastIndex) / 4 }.distinct()
    key(period, anchor) {
        ScrubBars(trend.map { it.seconds }, ::trendLabel, "最多", "这个周期还没有播放记录", title, 4.dp, valueLabel = ::musicDurationText) {
            PositionedLabels(positions.map { (it + 0.5f) / trend.size to trend[it].label }, Modifier.padding(top = 6.dp))
        }
    }
    Text(if (summary.seconds > 0) "按开始播放时刻汇总；点按、滑动或用方向键查看。" else "这个周期还没有本机播放记录。",
        color = MusicMuted, fontSize = 12.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
    val count = minOf(state.rankLimit, summary.ranking.size)
    MusicSectionHeading("常听歌曲排行", if (summary.ranking.isEmpty()) "按播放次数 · 所选周期" else "按播放次数 · 已显示 $count / ${summary.ranking.size} 首")
    HorizontalDivider(color = MusicSongLine)
    if (summary.ranking.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("这个周期还没有播放记录", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("可切换日期，或回到当前周期看看；不会用其他周期的数据填充。", color = MusicMuted, fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
        }
    } else {
        Column { summary.ranking.take(count).forEachIndexed { index, ranked ->
            SongRow(ranked.track, ranked.track.id == state.selectedTrackId, index + 1, ranked.plays to ranked.seconds,
                showDivider = index < count - 1) { selectTrack(ranked.track, state, onMessage) }
        } }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            if (count < summary.ranking.size) OutlinedButton(onClick = { state.rankLimit += MUSIC_RANK_PAGE },
                Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, TickIdle)) { Text("查看更多（还有 ${summary.ranking.size - count} 首）", fontSize = 12.sp) }
            if (count > MUSIC_RANK_PAGE) TextButton(onClick = { state.rankLimit = MUSIC_RANK_PAGE }) { Text("收起排行", fontSize = 12.sp) }
        }
    }
    Text("统计仅来自本机真实收听事件；当前未接入音频播放，因此不会自动生成记录。",
        color = MusicMuted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 20.dp))
    if (pickerOpen) MusicPeriodPickerSheet(period, anchor, today, daily, { pickerOpen = false }, ::selectAnchor)
}

@Composable
private fun MusicOverview(summary: MusicSummary, comparison: Comparison, period: Period, current: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(14.dp)).background(AccentSoft)
        .padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text("${if (current) musicCurrentName(period) else "所选周期"}收听", color = AccentDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text("${(summary.seconds + 30) / 60}", fontSize = 48.sp, lineHeight = 53.sp, letterSpacing = (-0.96).sp,
                fontWeight = FontWeight.Light, color = Ink, style = NumeralStyle)
            Text(" 分钟", color = AccentDark, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (comparison.current != 0L || comparison.previous != 0L) {
            val name = if (current) musicPreviousName(period) else musicBeforeName(period)
            val text = when {
                comparison.previous == 0L -> "${name}没有记录"
                comparison.difference == 0L -> "与${name}持平"
                else -> "比$name${if (comparison.difference > 0) "多" else "少"} ${musicDurationText(abs(comparison.difference))}"
            }
            val mark = if (comparison.previous > 0 && comparison.difference != 0L) {
                if (comparison.difference > 0) "▲ " else "▼ "
            } else ""
            Text(mark + text, color = if (comparison.previous > 0 && comparison.difference > 0) AccentDark else MusicMuted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp).background(Color.White, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp))
        }
        HorizontalDivider(Modifier.padding(top = 14.dp), color = Color(0xFFF1D3C4))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MusicMetric("${summary.plays}", "播放次数", Modifier.weight(1f))
            MusicMetric("${summary.activeDays}", "收听天数", Modifier.weight(1f))
            MusicMetric("${(summary.averageSeconds + 30) / 60}", "活跃日均 / 分钟", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MusicMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Medium, style = NumeralStyle)
        Text(label, color = MusicMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicPeriodPickerSheet(period: Period, anchor: LocalDate, today: LocalDate, daily: Map<LocalDate, Long>, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val initialYear = if (today.year - anchor.year in 0 until MUSIC_PICKER_YEARS) today.year else anchor.year
    var cursor by remember(period, anchor) {
        mutableStateOf(when (period) {
            Period.MONTH -> anchor.withDayOfYear(1)
            Period.YEAR -> LocalDate.of(initialYear.coerceIn(MUSIC_MIN_YEAR, MUSIC_MAX_YEAR), 1, 1)
            else -> anchor.withDayOfMonth(1)
        })
    }
    fun shift(direction: Long) {
        val next = when (period) {
            Period.MONTH -> cursor.plusYears(direction)
            Period.YEAR -> cursor.plusYears(direction * MUSIC_PICKER_YEARS)
            else -> cursor.plusMonths(direction)
        }
        if (next.year in MUSIC_MIN_YEAR..MUSIC_MAX_YEAR) cursor = next
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shift(-1) }) { Icon(Icons.Outlined.ChevronLeft, if (period == Period.YEAR) "更早的年份" else if (period == Period.MONTH) "上一年" else "上一月") }
                Text(when (period) {
                    Period.YEAR -> "${(cursor.year - MUSIC_PICKER_YEARS + 1).coerceAtLeast(MUSIC_MIN_YEAR)}–${cursor.year} 年"
                    Period.MONTH -> "${cursor.year} 年"
                    else -> "${cursor.year} 年 ${cursor.monthValue} 月"
                }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { shift(1) }) { Icon(Icons.Outlined.ChevronRight, if (period == Period.YEAR) "更晚的年份" else if (period == Period.MONTH) "下一年" else "下一月") }
            }
            val future = { date: LocalDate -> Statistics.range(period, date).first > Statistics.range(period, today).first }
            when (period) {
                Period.DAY, Period.WEEK -> MusicPickerCalendar(period, cursor, anchor, today, daily, future, onPick)
                Period.MONTH -> {
                    val totals = (1..12).map { month -> daily.filterKeys { it.year == cursor.year && it.monthValue == month }.values.sum() }
                    val peak = totals.maxOrNull() ?: 0L
                    val chosen = Statistics.range(Period.MONTH, anchor).first
                    MusicPickerGrid((1..12).map { month ->
                        val date = LocalDate.of(cursor.year, month, 1)
                        MusicPickerCell("${month}月", if (totals[month - 1] > 0) musicDurationText(totals[month - 1]) else "—",
                            heatLevel(totals[month - 1], peak), date == chosen, !future(date)) { onPick(date) }
                    })
                }
                Period.YEAR -> {
                    val years = ((cursor.year - MUSIC_PICKER_YEARS + 1)..cursor.year).filter { it in MUSIC_MIN_YEAR..MUSIC_MAX_YEAR }
                    val totals = years.map { year -> daily.filterKeys { it.year == year }.values.sum() }
                    val peak = totals.maxOrNull() ?: 0L
                    MusicPickerGrid(years.mapIndexed { index, year ->
                        val date = LocalDate.of(year, 1, 1)
                        MusicPickerCell("$year", if (totals[index] > 0) musicDurationText(totals[index]) else "—",
                            heatLevel(totals[index], peak), year == anchor.year, !future(date)) { onPick(date) }
                    })
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = Line)
            TextButton(onClick = {
                val dialog = DatePickerDialog(context, { _, year, month, day -> onDismiss(); onPick(LocalDate.of(year, month + 1, day)) }, anchor.year, anchor.monthValue - 1, anchor.dayOfMonth)
                val zone = ZoneId.systemDefault()
                dialog.datePicker.minDate = LocalDate.of(MUSIC_MIN_YEAR, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
                dialog.datePicker.maxDate = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                dialog.show()
            }, Modifier.fillMaxWidth()) { Text("精确跳到日期…") }
        }
    }
}

private data class MusicPickerCell(val title: String, val detail: String, val level: Int, val selected: Boolean, val enabled: Boolean, val onClick: () -> Unit)

@Composable
private fun MusicPickerGrid(cells: List<MusicPickerCell>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    val (background, foreground) = heatColors(cell.level)
                    Column(Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).background(background)
                        .border(2.dp, if (cell.selected) Ink else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable(enabled = cell.enabled, onClick = cell.onClick)
                        .alpha(if (cell.enabled) 1f else 0.4f)
                        .semantics { contentDescription = "${cell.title}，收听 ${cell.detail}"; stateDescription = if (cell.selected) "已选择" else "" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(cell.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = foreground)
                        Text(cell.detail, fontSize = 11.sp, color = foreground.copy(alpha = 0.85f))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MusicPickerCalendar(period: Period, cursor: LocalDate, anchor: LocalDate, today: LocalDate, daily: Map<LocalDate, Long>, future: (LocalDate) -> Boolean, onPick: (LocalDate) -> Unit) {
    val first = cursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val monthEnd = cursor.plusMonths(1)
    val weeks = generateSequence(first) { it.plusWeeks(1) }.takeWhile { it < monthEnd }.toList()
    val peak = weeks.maxOfOrNull { week -> (0L..6L).maxOf { daily[week.plusDays(it)] ?: 0L } } ?: 0L
    val selectedWeek = Statistics.range(Period.WEEK, anchor).first
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
            Text(day, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        } }
        weeks.forEach { week ->
            val weekMode = period == Period.WEEK
            val weekSeconds = (0L..6L).sumOf { daily[week.plusDays(it)] ?: 0L }
            val rowModifier = if (weekMode) Modifier.clip(RoundedCornerShape(13.dp))
                .border(2.dp, if (week == selectedWeek) Ink else Color.Transparent, RoundedCornerShape(13.dp))
                .clickable(enabled = !future(week)) { onPick(week) }
                .semantics(mergeDescendants = true) { contentDescription = "$week 至 ${week.plusDays(6)}，收听 ${musicDurationText(weekSeconds)}" }
                .padding(3.dp) else Modifier
            Row(Modifier.fillMaxWidth().then(rowModifier).alpha(if (weekMode && future(week)) 0.4f else 1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0L..6L).forEach { offset ->
                    val date = week.plusDays(offset)
                    val seconds = daily[date] ?: 0L
                    val (background, foreground) = heatColors(heatLevel(seconds, peak))
                    val dayModifier = if (weekMode) Modifier else Modifier
                        .border(2.dp, if (date == anchor) Ink else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(enabled = !future(date)) { onPick(date) }
                        .semantics { contentDescription = "$date，收听 ${musicDurationText(seconds)}" }
                    Box(Modifier.weight(1f).height(if (weekMode) 34.dp else 40.dp).clip(RoundedCornerShape(10.dp)).background(background)
                        .then(dayModifier)
                        .border(1.dp, if (date == today) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                        .alpha(if (date.monthValue == cursor.monthValue) 1f else 0.45f), contentAlignment = Alignment.Center) {
                        Text(date.dayOfMonth.toString(), fontSize = 13.sp, color = foreground)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("颜色表示收听时长", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("少", color = Muted, fontSize = 11.sp)
            (0..4).forEach { level -> Box(Modifier.padding(2.dp).size(12.dp).background(heatColors(level).first, RoundedCornerShape(2.dp))) }
            Text("多", color = Muted, fontSize = 11.sp)
        }
    }
}
