package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusassistant.app.domain.*
import kotlin.math.roundToInt

private val PlayerOrange = Color(0xFFEF5B24)
private val PlayerAmbientBase = Color(0xFFEEF1F0)

@Composable
internal fun ImmersiveMusicPlayer(
    track: MusicTrack, state: MusicUiState, onBack: () -> Unit, onEdit: () -> Unit,
    onMode: () -> Unit, onQueue: () -> Unit, onToggle: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit
) {
    val coverBitmap = rememberTrackCoverBitmap(track)
    BoxWithConstraints(Modifier.fillMaxSize().background(PlayerAmbientBase)) {
        val scrollFallback = maxHeight < 560.dp || LocalDensity.current.fontScale > 1.25f
        val scrollCoverHeight = (maxHeight * 0.42f).coerceIn(180.dp, 320.dp)
        PlayerAmbientBackground(track, coverBitmap, Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.66f))
        Column(Modifier.fillMaxSize().then(if (scrollFallback) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(44.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回声音馆") }
                Text("正在播放", Modifier.weight(1f), fontSize = 13.sp, color = MusicMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = onEdit, Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)) { Text("编辑", fontSize = 13.sp) }
            }
            if (scrollFallback) PlayerCover(track, coverBitmap, Modifier.fillMaxWidth().height(scrollCoverHeight).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp))
            else PlayerCover(track, coverBitmap, Modifier.fillMaxWidth().weight(1f).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp))
            PlayerControlPanel(track, state, onMode, onQueue, onToggle, onPrevious, onNext, onSeek)
        }
    }
}

@Composable
private fun PlayerControlPanel(
    track: MusicTrack, state: MusicUiState,
    onMode: () -> Unit, onQueue: () -> Unit, onToggle: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit
) {
    val playback = state.playback
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        color = Color.White, shadowElevation = 14.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 20.dp)) {
            Text(track.title, fontSize = 28.sp, lineHeight = 35.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, Modifier.padding(top = 4.dp), color = MusicMuted, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onMode, Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(if (playback.mode == MusicPlayMode.SHUFFLE) Icons.Outlined.Shuffle else Icons.Outlined.Repeat,
                        null, Modifier.size(18.dp), tint = MusicMuted)
                    Spacer(Modifier.width(7.dp)); Text(playback.mode.label, fontSize = 13.sp)
                }
                TextButton(onClick = onQueue, Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.QueueMusic, null, Modifier.size(19.dp), tint = MusicMuted)
                    Spacer(Modifier.width(7.dp)); Text("播放队列 · ${playback.queueIds.size}", fontSize = 13.sp, maxLines = 1)
                }
            }
            PlaybackProgress(playback.positionMs, playback.durationMs, onSeek, Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(34.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, Modifier.size(52.dp)) { Icon(Icons.Outlined.SkipPrevious, "上一首", Modifier.size(29.dp)) }
                FilledIconButton(onClick = onToggle, Modifier.size(68.dp).shadow(12.dp, CircleShape,
                    ambientColor = PlayerOrange.copy(alpha = 0.28f), spotColor = PlayerOrange.copy(alpha = 0.34f)),
                    shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = PlayerOrange, contentColor = Color.White)) {
                    Icon(if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (playback.playing) "暂停" else "播放", Modifier.size(30.dp))
                }
                IconButton(onClick = onNext, Modifier.size(52.dp)) { Icon(Icons.Outlined.SkipNext, "下一首", Modifier.size(29.dp)) }
            }
        }
    }
}

@Composable
private fun PlayerAmbientBackground(track: MusicTrack, bitmap: Bitmap?, modifier: Modifier = Modifier) {
    val ambientBitmap = remember(bitmap) {
        bitmap?.let { source ->
            val width = 72
            val height = (source.height * width.toFloat() / source.width).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, width, height, true).asImageBitmap()
        }
    }
    Box(modifier) {
        val artModifier = Modifier.matchParentSize().blur(28.dp).graphicsLayer {
            scaleX = 1.12f
            scaleY = 1.12f
            alpha = 0.72f
        }.clearAndSetSemantics { }
        if (ambientBitmap != null) Image(ambientBitmap, null, artModifier, contentScale = ContentScale.Crop)
        else CoverArt(track.id, artModifier, radius = 0.dp)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.08f),
            0.58f to Color.White.copy(alpha = 0.03f),
            1f to Color.White)))
    }
}

@Composable
private fun PlayerCover(track: MusicTrack, bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val ratio = bitmap?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: 1.6f
        val shape = RoundedCornerShape(16.dp)
        val coverModifier = Modifier.aspectRatio(ratio)
            .shadow(20.dp, shape, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.22f))
            .background(Color(0xFFF2F3F3), shape).clip(shape)
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, coverModifier, contentScale = ContentScale.Fit)
        else CoverArt(track.id, coverModifier, radius = 16.dp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackProgress(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat())
    Column(modifier.fillMaxWidth()) {
        Slider(value = value, onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek(it.toLong()) }; dragging = null },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(), enabled = durationMs > 0,
            thumb = {
                Box(Modifier.size(14.dp).shadow(4.dp, CircleShape, spotColor = PlayerOrange.copy(alpha = 0.35f))
                    .background(Color.White, CircleShape).padding(3.dp).background(PlayerOrange, CircleShape))
            },
            track = { sliderState ->
                val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                val progress = if (range > 0f) (sliderState.value - sliderState.valueRange.start) / range else 0f
                Box(Modifier.fillMaxWidth().height(3.dp).background(MusicLine, CircleShape)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(PlayerOrange, CircleShape))
                }
            })
        Row(Modifier.fillMaxWidth().offset(y = (-8).dp)) {
            Text(musicPositionText(dragging?.toLong() ?: positionMs), Modifier.weight(1f), color = MusicMuted, fontSize = 11.sp)
            Text(musicPositionText(durationMs), color = MusicMuted, fontSize = 11.sp)
        }
    }
}

internal fun musicPositionText(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return "${seconds / 60}:${String.format("%02d", seconds % 60)}"
}

@Composable
internal fun MusicModeDialog(selected: MusicPlayMode, onDismiss: () -> Unit, onSelect: (MusicPlayMode) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("播放模式") }, text = {
        Column {
            MusicPlayMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(mode) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(mode.label, Modifier.weight(1f), fontSize = 14.sp)
                    if (mode == selected) Icon(Icons.Outlined.Check, "当前模式", tint = PlayerOrange)
                }
            }
            Text("顺序播放在队列结束后停止；随机播放不会立即重复已播歌曲。", color = MusicMuted, fontSize = 11.sp, lineHeight = 18.sp)
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
internal fun MusicQueueDialog(
    tracks: List<MusicTrack>, playback: MusicPlaybackSnapshot, onDismiss: () -> Unit,
    onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onClear: () -> Unit, onPlay: (Int) -> Unit
) {
    val byId = tracks.associateBy { it.id }
    val sections = MusicQueue.sections(playback.queueIds, playback.trackId)
    var upcomingIds by remember { mutableStateOf(sections.upcomingIds) }
    LaunchedEffect(sections.upcomingIds) { upcomingIds = sections.upcomingIds }
    var playedOpen by remember(playback.trackId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val moveUpcoming: (Int, Int) -> Unit = { from, to ->
        upcomingIds = MusicQueue.move(upcomingIds, from, to)
        onMove(from, to)
    }
    val reorder = rememberMusicReorderState(listState, upcomingIds, moveUpcoming)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), color = Color.White) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 9.dp, bottom = 8.dp)
                        .size(36.dp, 4.dp).background(MusicLine, RoundedCornerShape(2.dp)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("播放队列", Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭播放队列") }
                    }
                    sections.currentId?.let(byId::get)?.let { track ->
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val showPlaybackStatus = maxWidth >= 320.dp
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xFFF5F6F6)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                                    Text("正在播放", color = AccentDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TrackCoverArt(track, Modifier.size(48.dp), radius = 7.dp)
                                        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                                            Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(track.artist, Modifier.padding(top = 3.dp), color = MusicMuted, fontSize = 11.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Icon(Icons.Outlined.GraphicEq, if (playback.playing) "正在播放" else "已暂停",
                                            Modifier.size(18.dp), tint = PlayerOrange)
                                        if (showPlaybackStatus) {
                                            Text(if (playback.playing) "正在播放" else "已暂停", Modifier.padding(start = 5.dp),
                                                color = AccentDark, fontSize = 10.sp)
                                        }
                                        MusicRowMenu(track.title, listOf(MusicRowAction("从队列移除") {
                                            playback.queueIds.indexOf(track.id).takeIf { it >= 0 }?.let(onRemove)
                                        }))
                                    }
                                    val progress = if (playback.durationMs > 0)
                                        (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) else 0f
                                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 10.dp).height(2.dp),
                                        color = PlayerOrange, trackColor = MusicLine)
                                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                        Text(musicPositionText(playback.positionMs), Modifier.weight(1f), color = MusicMuted, fontSize = 10.sp)
                                        Text(musicPositionText(playback.durationMs), color = MusicMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("接下来播放 · ${upcomingIds.size} 首", Modifier.weight(1f),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onClear, enabled = playback.queueIds.size > 1) { Text("清空待播", fontSize = 11.sp) }
                    }
                    HorizontalDivider(color = MusicLine)
                    Box(Modifier.weight(1f).musicReorderContainer(reorder, upcomingIds.size > 1)) {
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            itemsIndexed(upcomingIds, key = { _, id -> id }) { index, id ->
                                val track = byId[id] ?: return@itemsIndexed
                                Column(Modifier.musicReorderItem(reorder, id, index)
                                    .background(Color.White)) {
                                    MusicQueueTrackRow(track, reorder, onClick = { onPlay(playback.queueIds.indexOf(id)) }, actions = listOf(
                                        MusicRowAction("上移", index > 0) { moveUpcoming(index, index - 1) },
                                        MusicRowAction("下移", index < upcomingIds.lastIndex) { moveUpcoming(index, index + 1) },
                                        MusicRowAction("从队列移除") { onRemove(playback.queueIds.indexOf(id)) },
                                    ))
                                    if (index < upcomingIds.lastIndex) HorizontalDivider(color = MusicLine)
                                }
                            }
                            if (upcomingIds.isEmpty()) item {
                                Text(if (sections.currentId != null) "当前歌曲之后没有待播歌曲。" else "队列已清空，从歌曲页选择一首即可重新播放。",
                                    Modifier.fillMaxWidth().padding(vertical = 28.dp), color = MusicMuted, fontSize = 12.sp,
                                    lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            if (upcomingIds.size > 1) item {
                                Text("长按歌曲拖动排序，更多操作在「···」中", Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                                    color = MusicMuted, fontSize = 10.sp, lineHeight = 17.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            if (playback.mode == MusicPlayMode.SHUFFLE) item {
                                Text("随机模式的实际播放顺序由播放器决定，拖动仍会保存队列顺序。",
                                    Modifier.padding(bottom = 8.dp), color = MusicMuted, fontSize = 10.sp, lineHeight = 17.sp)
                            }
                        }
                        MusicReorderDragOverlay(reorder) { id, index ->
                            byId[id]?.let { track ->
                                Column {
                                    MusicQueueTrackRow(track, reorder, onClick = { onPlay(playback.queueIds.indexOf(id)) }, actions = listOf(
                                        MusicRowAction("上移", index > 0) { moveUpcoming(index, index - 1) },
                                        MusicRowAction("下移", index < upcomingIds.lastIndex) { moveUpcoming(index, index + 1) },
                                        MusicRowAction("从队列移除") { onRemove(playback.queueIds.indexOf(id)) },
                                    ))
                                    if (index < upcomingIds.lastIndex) HorizontalDivider(color = MusicLine)
                                }
                            }
                        }
                    }
                    MusicReorderAutoScroll(reorder)
                    if (sections.playedIds.isNotEmpty()) {
                        HorizontalDivider(color = MusicLine)
                        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable { playedOpen = !playedOpen },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("已播放 ${sections.playedIds.size} 首", Modifier.weight(1f), color = MusicMuted, fontSize = 12.sp)
                            Text(if (playedOpen) "收起" else "展开", fontSize = 11.sp)
                        }
                        if (playedOpen) LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            itemsIndexed(sections.playedIds, key = { _, id -> id }) { _, id ->
                                val track = byId[id] ?: return@itemsIndexed
                                MusicQueueTrackRow(track, null, onClick = { onPlay(playback.queueIds.indexOf(id)) },
                                    actions = listOf(MusicRowAction("从队列移除") { onRemove(playback.queueIds.indexOf(id)) }))
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun MusicQueueTrackRow(
    track: MusicTrack, reorder: MusicReorderState?, onClick: () -> Unit, actions: List<MusicRowAction>
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp)
        .clickable { if (reorder?.shouldSuppressClick(track.id) != true) onClick() }
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackCoverArt(track, Modifier.size(40.dp), radius = 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(track.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = MusicMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(musicPositionText(track.durationSeconds * 1000), color = MusicMuted, fontSize = 10.sp)
        MusicRowMenu(track.title, actions)
    }
}

@Composable
internal fun MusicEditDialog(
    track: MusicTrack, coverSelected: Boolean, busy: Boolean, onPickCover: () -> Unit,
    onDismiss: () -> Unit, onSave: (String, String) -> Unit
) {
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    var error by remember(track.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("编辑歌曲信息") }, text = {
        Column {
            OutlinedTextField(title, { title = it; error = "" }, label = { Text("歌名") }, singleLine = true, enabled = !busy)
            OutlinedTextField(artist, { artist = it }, label = { Text("歌手") }, singleLine = true, enabled = !busy,
                modifier = Modifier.padding(top = 10.dp))
            OutlinedButton(onClick = onPickCover, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Text(if (coverSelected) "已选择新封面，点击可重选" else "更换本地封面")
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (title.trim().isEmpty()) error = "请输入歌名"
            else if (title.trim().length > 80 || artist.trim().length > 80) error = "歌名和歌手不可超过 80 个字符"
            else onSave(title, artist)
        }, enabled = !busy) { Text(if (busy) "保存中…" else "保存修改") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}
