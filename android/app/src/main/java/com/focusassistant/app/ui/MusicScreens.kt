package com.focusassistant.app.ui

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.window.Dialog
import com.focusassistant.app.data.MusicFiles
import com.focusassistant.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

internal enum class MusicTab(val label: String) { RECOMMEND("推荐"), PLAYLISTS("歌单"), SONGS("歌曲"), HISTORY("回顾") }

/** 只保存界面状态；真实曲库、歌单和收听记录不由这里伪造或写入。 */
internal class MusicUiState {
    var tab by mutableStateOf(MusicTab.RECOMMEND)
    var query by mutableStateOf("")
    var selectedTrackId by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var playback by mutableStateOf(MusicPlaybackSnapshot())
    var playerOpen by mutableStateOf(false)
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
internal val MusicMuted = Color(0xFF686E72)
private val MusicSurface = Color(0xFFF2F3F3)
internal val MusicLine = Color(0xFFDDE0E1)
private val MusicSongLine = Color(0xFFE7E9E9)
private val MusicCurrent = Color(0xFFF7F7F6)
private val MusicWaveIdle = Color(0xFFC9CED0)

internal data class MusicPlaylistActions(
    val create: () -> Unit = {},
    val edit: (MusicPlaylist) -> Unit = {},
    val delete: (MusicPlaylist) -> Unit = {},
    val save: (MusicPlaylist) -> Unit = {},
    val play: (List<MusicTrack>, MusicTrack) -> Unit = { _, _ -> },
    val addTrack: (MusicTrack) -> Unit = {}
)

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
    onMessage: (String) -> Unit = {},
    onAdd: () -> Unit = {},
    onPlay: (MusicTrack) -> Unit = {},
    onToggle: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onMode: () -> Unit = {},
    onQueue: () -> Unit = {},
    onEdit: () -> Unit = {},
    playlistActions: MusicPlaylistActions = MusicPlaylistActions()
) {
    val current = tracks.find { it.id == state.selectedTrackId }
    if (state.playerOpen && current != null) {
        BackHandler { state.playerOpen = false }
        ImmersiveMusicPlayer(current, state, onBack = { state.playerOpen = false }, onEdit = onEdit,
            onMode = onMode, onQueue = onQueue, onToggle = onToggle, onPrevious = onPrevious, onNext = onNext, onSeek = onSeek)
        return
    }
    val playlist = playlists.find { it.id == state.playlistId }
    BackHandler(enabled = playlist != null) { state.playlistId = null }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        if (playlist != null) {
            PlaylistDetail(playlist, tracks, state.selectedTrackId, Modifier.weight(1f), playlistActions,
                onBack = { state.playlistId = null })
        } else {
            MusicHeader(state, onAdd)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 23.dp, end = 23.dp, bottom = 20.dp)) {
                when (state.tab) {
                    MusicTab.RECOMMEND -> item(key = "recommend") {
                        RecommendationPanel(tracks, listens, playlists, state, onPlay, onToggle, onOpenPlayer)
                    }
                    MusicTab.PLAYLISTS -> item(key = "playlists") {
                        MusicSectionHeading("我的歌单", "${playlists.size} 个")
                        if (playlists.isEmpty()) EmptyCard("还没有本机歌单", "点击下面新建歌单，可从本机曲库选择歌曲，也可以先创建空歌单。")
                        else MusicPlaylistGrid(playlists, tracks, full = true) { state.playlistId = it.id }
                        OutlinedButton(onClick = playlistActions.create,
                            Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 52.dp),
                            shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, TickIdle),
                            contentPadding = PaddingValues(12.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
                            Text("新建歌单", fontSize = 12.sp)
                        }
                    }
                    MusicTab.SONGS -> item(key = "songs") {
                        val query = state.query.trim()
                        val filtered = tracks.filter { query.isEmpty() || "${it.title} ${it.artist}".contains(query, ignoreCase = true) }
                        MusicSectionHeading("全部歌曲", if (query.isEmpty()) "${tracks.size} 首" else "${filtered.size} 首匹配")
                        if (filtered.isEmpty()) EmptyCard(if (tracks.isEmpty()) "还没有本机歌曲" else "没有匹配的歌曲",
                            if (tracks.isEmpty()) "选择右上角「＋」，从共享目录批量导入 B 站缓存音频。" else "换个歌名或歌手试试。")
                        else Column { filtered.forEachIndexed { index, track ->
                            SongRow(track, current?.id == track.id, showDivider = index < filtered.lastIndex,
                                onMore = { playlistActions.addTrack(track) }, onClick = { onPlay(track) })
                        } }
                    }
                    MusicTab.HISTORY -> item(key = "history") {
                        MusicHistoryPanel(tracks, listens, state, today, onMessage, onPlay)
                    }
                }
                if (state.tab != MusicTab.HISTORY) item(key = "data-note") {
                    Text("本机曲库与真实收听记录 · 支持后台播放、进度拖动、队列编辑和歌曲信息修改",
                        color = MusicMuted, fontSize = 12.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            }
        }
        if (current != null) MiniPlayer(current, state.playback, onToggle, onOpenPlayer)
    }
}

@Composable
private fun MusicHeader(state: MusicUiState, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 23.dp, end = 23.dp, top = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("流行歌单与日常收听", color = MusicMuted, fontSize = 12.sp, letterSpacing = 0.4.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
                Text("声音馆.", fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = (-0.9).sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onAdd,
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Ink)) {
                Icon(Icons.Outlined.Add, "导入 B 站音频", Modifier.size(18.dp), tint = Color.White)
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
internal fun MusicSourceDialog(onDismiss: () -> Unit, onBind: () -> Unit, onOnce: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(20.dp)) {
                Text("手动复制，自动去重", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("1. 用手机文件管理器把 B 站整个 download 文件夹复制到共享目录，例如：\n内部存储/Music/专注助手/B站导入\n\n2. 在这里选择该共享目录。也支持直接放多个 c_数字 文件夹，或 av号/c_数字 结构。",
                    color = MusicMuted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
                Text("选择“记住共享目录”后，下次点击「＋」会重新扫描并跳过已导入缓存；导入时还会按音频 SHA-256 去重。无需 Shizuku，不读取其他应用的 Android/data。",
                    color = MusicMuted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
                Button(onClick = onBind, Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) {
                    Text("选择并记住共享目录")
                }
                OutlinedButton(onClick = onOnce, Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, Line)) { Text("仅本次选择") }
                TextButton(onClick = onDismiss, Modifier.align(Alignment.End).padding(top = 4.dp)) { Text("取消") }
            }
        }
    }
}

private class MusicImportDraft(candidate: MusicImportCandidate) {
    val sourceId = candidate.sourceId
    val directoryName = candidate.directoryName
    val durationSeconds = candidate.durationSeconds
    val fileBytes = candidate.fileBytes
    val hasCover = candidate.hasCover
    val error = candidate.error
    var selected by mutableStateOf(error.isEmpty())
    var title by mutableStateOf(candidate.title)
    var artist by mutableStateOf(candidate.artist)
}

@Composable
internal fun MusicImportDialog(candidates: List<MusicImportCandidate>, busy: Boolean,
    directoryLabel: String = "", skipped: Int = 0, onReselect: () -> Unit = {},
    onUnbind: (() -> Unit)? = null, onDismiss: () -> Unit,
    onConfirm: (List<MusicImportRequest>) -> Unit) {
    val drafts = remember(candidates) { candidates.map(::MusicImportDraft) }
    val selected = drafts.filter { it.selected && it.error.isEmpty() }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(20.dp)) {
                Text("导入 B 站音频", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("只复制 audio.m4s 和本地 cover.jpg；不会读取视频、弹幕或远程资源。",
                    color = MusicMuted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (directoryLabel.isEmpty()) "本次选择的目录不会记住" else directoryLabel,
                        Modifier.weight(1f), color = MusicMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onReselect, enabled = !busy, contentPadding = PaddingValues(horizontal = 7.dp)) {
                        Text(if (directoryLabel.isEmpty()) "重选" else "更换", fontSize = 11.sp)
                    }
                    if (onUnbind != null) TextButton(onClick = onUnbind, enabled = !busy, contentPadding = PaddingValues(horizontal = 7.dp)) {
                        Text("解绑", fontSize = 11.sp)
                    }
                }
                if (skipped > 0) Text("已自动跳过 $skipped 个已导入缓存；写入前仍会校验音频 SHA-256。",
                    color = AccentDark, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 10.dp))
                else Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (drafts.isEmpty()) item(key = "empty") {
                        EmptyCard("没有可导入的新音频", if (skipped > 0) "所选目录中的缓存均已在曲库中，可以直接取消。"
                            else "请确认整个 download 或 c_数字 缓存文件夹已复制到所选共享目录。")
                    }
                    items(drafts.size, key = { drafts[it].sourceId }) { index ->
                        val draft = drafts[index]
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(MusicSurface).padding(11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(draft.selected, { draft.selected = it }, enabled = !busy && draft.error.isEmpty())
                                Column(Modifier.weight(1f)) {
                                    Text("目录 ${draft.directoryName} · ${clockText(draft.durationSeconds)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${musicFileSize(draft.fileBytes)}${if (draft.hasCover) " · 含本地封面" else ""}",
                                        color = MusicMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                                }
                            }
                            if (draft.error.isNotEmpty()) Text(draft.error, color = AccentDark, fontSize = 11.sp,
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                            else {
                                MusicImportField("歌名", draft.title, !busy) { draft.title = it }
                                MusicImportField("歌手", draft.artist, !busy, "未知歌手") { draft.artist = it }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { onConfirm(selected.map { MusicImportRequest(it.sourceId, it.title, it.artist) }) },
                        enabled = !busy && selected.isNotEmpty(), shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) {
                        Text(if (busy) "正在导入…" else "导入 ${selected.size} 首")
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicImportField(label: String, value: String, enabled: Boolean, placeholder: String = "", modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Text(label, color = MusicMuted, fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp))
    BasicTextField(value, onChange, modifier.padding(horizontal = 12.dp).fillMaxWidth().height(40.dp)
        .background(Color.White, RoundedCornerShape(7.dp)).border(1.dp, MusicLine, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp),
        enabled = enabled, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 12.sp), cursorBrush = SolidColor(Accent),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = MusicMuted, fontSize = 12.sp)
                inner()
            }
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicPlaylistEditorDialog(existing: MusicPlaylist?, tracks: List<MusicTrack>, initialTrackId: String? = null,
    busy: Boolean = false, onDismiss: () -> Unit, onConfirm: (String, List<String>) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var query by remember(existing?.id) { mutableStateOf("") }
    val selected = remember(existing?.id) {
        mutableStateListOf<String>().also { ids ->
            existing?.trackIds?.let(ids::addAll)
            if (existing == null && initialTrackId != null && tracks.any { it.id == initialTrackId }) ids += initialTrackId
        }
    }
    val nameFocus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        LaunchedEffect(Unit) { if (existing == null) nameFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            Text(if (existing == null) "创建歌单" else "编辑歌单", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("可只创建空歌单；歌曲按点选顺序加入，重复点选会取消。", color = MusicMuted, fontSize = 11.sp,
                lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp))
            MusicImportField("歌单名称", name, !busy, "1–30 个字符", Modifier.focusRequester(nameFocus)) { if (it.length <= MusicPlaylists.MAX_NAME) name = it }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("选择歌曲", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("已选 ${selected.size} 首", color = AccentDark, fontSize = 11.sp)
            }
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth().height(42.dp)
                .background(MusicSurface, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                singleLine = true, enabled = !busy, textStyle = TextStyle(color = Ink, fontSize = 13.sp), cursorBrush = SolidColor(Accent),
                decorationBox = { inner ->
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = MusicMuted)
                        Spacer(Modifier.width(8.dp)); Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("搜索歌名或歌手", color = MusicMuted, fontSize = 13.sp)
                            inner()
                        }
                    }
                })
            val filtered = tracks.filter { query.trim().isEmpty() || "${it.title} ${it.artist}".contains(query.trim(), ignoreCase = true) }
            LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 7.dp)) {
                if (filtered.isEmpty()) item { EmptyCard(if (tracks.isEmpty()) "曲库还没有歌曲" else "没有匹配的歌曲",
                    if (tracks.isEmpty()) "仍可先创建空歌单，导入歌曲后再添加。" else "换个关键词试试；当前选择会保留。") }
                filtered.forEach { track ->
                    item(key = track.id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !busy) {
                            if (!selected.remove(track.id)) selected += track.id
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(track.id in selected, { checked ->
                                if (checked) { if (track.id !in selected) selected += track.id } else selected.remove(track.id)
                            }, enabled = !busy)
                            TrackCoverArt(track, Modifier.size(36.dp), radius = 5.dp)
                            Column(Modifier.padding(start = 9.dp)) {
                                Text(track.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, color = MusicMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(9.dp)) { Text("取消") }
                Button(onClick = { onConfirm(name, selected.toList()) }, enabled = !busy && name.isNotBlank(),
                    modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(9.dp)) {
                    Text(if (existing == null) "创建歌单" else "保存修改")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicPlaylistPickerDialog(track: MusicTrack, playlists: List<MusicPlaylist>, onDismiss: () -> Unit,
    onCreate: () -> Unit, onSelect: (MusicPlaylist) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("添加到歌单", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(track.title, color = MusicMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            if (playlists.isEmpty()) EmptyCard("还没有歌单", "先新建一个歌单，这首歌曲会自动加入。")
            else LazyColumn(Modifier.heightIn(max = 330.dp)) {
                playlists.forEach { playlist ->
                    val added = track.id in playlist.trackIds
                    item(key = playlist.id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(enabled = !added) { onSelect(playlist) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("${playlist.trackIds.size} 首", color = MusicMuted, fontSize = 11.sp)
                            }
                            Text(if (added) "已添加" else "添加", color = if (added) MusicMuted else AccentDark, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = MusicSongLine)
                    }
                }
            }
            OutlinedButton(onClick = onCreate, Modifier.fillMaxWidth().padding(top = 14.dp).height(46.dp), shape = RoundedCornerShape(9.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("新建歌单并加入")
            }
        }
    }
}

private fun musicFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    bytes > 0 -> "${(bytes + 1023) / 1024} KB"
    else -> "大小未知"
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
private fun RecommendationPanel(tracks: List<MusicTrack>, listens: List<MusicListen>, playlists: List<MusicPlaylist>, state: MusicUiState,
    onPlay: (MusicTrack) -> Unit, onToggle: () -> Unit, onOpenPlayer: () -> Unit) {
    val current = tracks.find { it.id == state.selectedTrackId }
    if (current == null) EmptyCard("还没有本机歌曲", "导入后可在这里播放应用私有目录中的真实音频。", Modifier.padding(top = 19.dp))
    else CurrentPlaybackCard(current, state.playing, onOpenPlayer, onToggle)
    MusicSectionHeading("我的歌单", action = "全部") { state.tab = MusicTab.PLAYLISTS }
    if (playlists.isEmpty()) EmptyCard("还没有本机歌单", "在「歌单」页新建后，会显示在这里。")
    else MusicPlaylistGrid(playlists.take(3), tracks) { state.playlistId = it.id }
    Spacer(Modifier.height(2.dp))
    MusicSectionHeading("最近播放", action = "全部歌曲") { state.tab = MusicTab.SONGS }
    val byId = tracks.associateBy { it.id }
    val recent = listens.sortedByDescending { it.playedAt }.mapNotNull { byId[it.trackId] }.distinctBy { it.id }.take(2)
    if (recent.isEmpty()) EmptyCard("还没有收听记录", "实际播放累计满 10 秒后才会生成记录。")
    else Column { recent.forEachIndexed { index, track ->
        SongRow(track, current?.id == track.id, showDivider = index < recent.lastIndex) { onPlay(track) }
    } }
}

@Composable
private fun CurrentPlaybackCard(track: MusicTrack, playing: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 19.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackCoverArt(track, Modifier.size(68.dp), radius = 9.dp)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(if (playing) "正在播放" else "继续播放", color = MusicMuted, fontSize = 11.sp)
                Text(track.title, Modifier.padding(top = 4.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = MusicMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onToggle, Modifier.size(48.dp)) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "暂停" else "播放", tint = MusicOrange)
            }
        }
        HorizontalDivider(color = MusicLine, modifier = Modifier.padding(top = 11.dp))
    }
}

@Composable
private fun MusicSectionHeading(title: String, detail: String? = null, action: String? = null, modifier: Modifier = Modifier.fillMaxWidth(), onAction: (() -> Unit)? = null) {
    Row(modifier.padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        if (detail != null) Text(detail, color = MusicMuted, fontSize = 11.sp)
        if (action != null && onAction != null) TextButton(onClick = onAction, Modifier.height(36.dp),
            contentPadding = PaddingValues(0.dp)) { Text(action, color = MusicMuted, fontSize = 11.sp) }
    }
}

@Composable
private fun MusicPlaylistGrid(playlists: List<MusicPlaylist>, tracks: List<MusicTrack>, full: Boolean = false, onOpen: (MusicPlaylist) -> Unit) {
    val byId = tracks.associateBy { it.id }
    Column(Modifier.padding(top = if (full) 6.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(if (full) 22.dp else 12.dp)) {
        playlists.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { playlist ->
                    Column(Modifier.weight(1f).clickable { onOpen(playlist) }.semantics { contentDescription = "歌单 ${playlist.name}，${playlist.trackIds.size} 首" }) {
                        PlaylistCoverArt(playlist, playlist.trackIds.mapNotNull(byId::get), Modifier.fillMaxWidth().aspectRatio(1f))
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
private fun PlaylistDetail(playlist: MusicPlaylist, tracks: List<MusicTrack>, selectedTrackId: String?, modifier: Modifier,
    actions: MusicPlaylistActions, onBack: () -> Unit) {
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
                TextButton(onClick = { actions.edit(playlist) }) { Text("编辑", fontSize = 12.sp) }
                IconButton(onClick = { actions.delete(playlist) }, Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, "删除歌单", tint = MusicMuted, modifier = Modifier.size(19.dp))
                }
            }
        }
        item {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlaylistCoverArt(playlist, songs, Modifier.size(108.dp), radius = 9.dp)
                    Column(Modifier.padding(start = 18.dp).weight(1f)) {
                        Text(playlist.name, fontSize = 23.sp, lineHeight = 31.sp, letterSpacing = (-0.5).sp, fontWeight = FontWeight.SemiBold)
                        Text("${songs.size} 首 · 本机歌单", color = MusicMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        Button(onClick = { songs.firstOrNull()?.let { actions.play(songs, it) } }, enabled = songs.isNotEmpty(),
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
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MusicSectionHeading("歌单歌曲", if (songs.isEmpty()) "暂无歌曲" else "${songs.size} 首", modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.edit(playlist) }, Modifier.height(36.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(15.dp)); Spacer(Modifier.width(3.dp)); Text("添加歌曲", fontSize = 11.sp)
                }
            }
        }
        if (songs.isEmpty()) item { EmptyCard("这个歌单还没有歌曲", "点击「添加歌曲」从本机曲库选择，不会自动补入其他歌曲。") }
        else item {
            Column {
                songs.forEachIndexed { index, track ->
                    PlaylistTrackRow(track, track.id == selectedTrackId, index, songs.size,
                        onMove = { target -> actions.save(playlist.copy(trackIds = MusicQueue.move(playlist.trackIds, index, target))) },
                        onRemove = { actions.save(playlist.copy(trackIds = MusicQueue.remove(playlist.trackIds, index))) },
                        onClick = { actions.play(songs, track) })
                    if (index < songs.lastIndex) HorizontalDivider(color = MusicSongLine)
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(track: MusicTrack, selected: Boolean, index: Int, count: Int,
    onMove: (Int) -> Unit, onRemove: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).background(if (selected) MusicCurrent else Color.Transparent)
        .clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackCoverArt(track, Modifier.size(40.dp), radius = 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(track.title, color = if (selected) AccentDark else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = MusicMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onMove(index - 1) }, enabled = index > 0, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.KeyboardArrowUp, "上移 ${track.title}", Modifier.size(18.dp))
        }
        IconButton(onClick = { onMove(index + 1) }, enabled = index < count - 1, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.KeyboardArrowDown, "下移 ${track.title}", Modifier.size(18.dp))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Close, "从歌单移除 ${track.title}", Modifier.size(17.dp), tint = MusicMuted)
        }
    }
}

@Composable
private fun PlaylistCoverArt(playlist: MusicPlaylist, songs: List<MusicTrack>, modifier: Modifier, radius: Dp = 7.dp) {
    val covers = songs.take(4)
    if (covers.isEmpty()) { CoverArt(playlist.id, modifier, radius); return }
    if (covers.size == 1) { TrackCoverArt(covers.first(), modifier, radius); return }
    Column(modifier.clip(RoundedCornerShape(radius)).background(MusicSurface).clearAndSetSemantics { }) {
        covers.chunked(2).forEach { row ->
            Row(Modifier.weight(1f)) {
                row.forEach { track -> TrackCoverArt(track, Modifier.weight(1f).fillMaxHeight(), radius = 0.dp) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SongRow(track: MusicTrack, selected: Boolean, rank: Int? = null, stats: Pair<Int, Long>? = null,
    showDivider: Boolean = true, onMore: (() -> Unit)? = null, onClick: () -> Unit) {
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
            TrackCoverArt(track, Modifier.size(40.dp), radius = 6.dp)
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
            if (onMore != null) IconButton(onClick = onMore, Modifier.size(36.dp)) {
                Icon(Icons.Outlined.MoreVert, "添加到歌单 ${track.title}", Modifier.size(18.dp), tint = MusicMuted)
            }
        }
        if (showDivider) HorizontalDivider(color = MusicSongLine)
    }
}

@Composable
internal fun TrackCoverArt(track: MusicTrack, modifier: Modifier = Modifier, radius: Dp = 7.dp,
    contentScale: ContentScale = ContentScale.Crop, naturalAspectRatio: Boolean = false) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, track.id, track.coverFile) {
        value = withContext(Dispatchers.IO) {
            val file = MusicFiles.cover(context.applicationContext, track) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    bitmap?.let {
        val imageModifier = if (naturalAspectRatio) Modifier.aspectRatio(it.width.toFloat() / it.height)
            .background(Color(0xFFF2F3F3), RoundedCornerShape(radius)).clip(RoundedCornerShape(radius))
        else modifier.clip(RoundedCornerShape(radius))
        Image(it.asImageBitmap(), null, imageModifier, contentScale = contentScale)
    } ?: CoverArt(track.id, modifier, radius)
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
private fun MiniPlayer(track: MusicTrack, playback: MusicPlaybackSnapshot, onToggle: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 7.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MusicSurface)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(onClick = onOpen).padding(start = 10.dp, end = 8.dp, top = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TrackCoverArt(track, Modifier.size(36.dp), radius = 6.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(track.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = MusicMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledIconButton(onClick = onToggle, Modifier.size(44.dp), shape = RoundedCornerShape(50),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Ink, contentColor = Color.White)) {
                    Icon(if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (playback.playing) "暂停" else "播放", Modifier.size(17.dp))
                }
            }
            val progress = if (playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 9.dp)) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(MusicLine))
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(MusicOrange))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicHistoryPanel(tracks: List<MusicTrack>, listens: List<MusicListen>, state: MusicUiState, today: LocalDate,
    onMessage: (String) -> Unit, onPlay: (MusicTrack) -> Unit) {
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
                showDivider = index < count - 1) { onPlay(ranked.track) }
        } }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            if (count < summary.ranking.size) OutlinedButton(onClick = { state.rankLimit += MUSIC_RANK_PAGE },
                Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, TickIdle)) { Text("查看更多（还有 ${summary.ranking.size - count} 首）", fontSize = 12.sp) }
            if (count > MUSIC_RANK_PAGE) TextButton(onClick = { state.rankLimit = MUSIC_RANK_PAGE }) { Text("收起排行", fontSize = 12.sp) }
        }
    }
    Text("统计来自真实播放：累计满 10 秒后记录，暂停、缓冲和拖动进度不计入收听时长。",
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
