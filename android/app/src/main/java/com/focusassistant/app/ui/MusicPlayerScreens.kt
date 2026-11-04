package com.focusassistant.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.focusassistant.app.domain.*

private val PlayerOrange = Color(0xFFEF5B24)

@Composable
internal fun ImmersiveMusicPlayer(
    track: MusicTrack, state: MusicUiState, onBack: () -> Unit, onEdit: () -> Unit,
    onMode: () -> Unit, onQueue: () -> Unit, onToggle: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit
) {
    val playback = state.playback
    Column(Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, Modifier.size(44.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回声音馆") }
            Text("正在播放", Modifier.weight(1f), fontSize = 13.sp, color = MusicMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = onEdit, Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)) { Text("编辑", fontSize = 13.sp) }
        }
        TrackCoverArt(track, Modifier.fillMaxWidth().padding(top = 12.dp).aspectRatio(1f), radius = 14.dp)
        Text(track.title, Modifier.padding(top = 24.dp), fontSize = 28.sp, lineHeight = 36.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(track.artist, Modifier.padding(top = 5.dp), color = MusicMuted, fontSize = 14.sp)
        PlaybackProgress(playback.positionMs, playback.durationMs, onSeek, Modifier.padding(top = 20.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, Modifier.size(52.dp)) { Icon(Icons.Outlined.SkipPrevious, "上一首", Modifier.size(29.dp)) }
            FilledIconButton(onClick = onToggle, Modifier.size(66.dp), shape = RoundedCornerShape(50),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = PlayerOrange, contentColor = Color.White)) {
                Icon(if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (playback.playing) "暂停" else "播放", Modifier.size(30.dp))
            }
            IconButton(onClick = onNext, Modifier.size(52.dp)) { Icon(Icons.Outlined.SkipNext, "下一首", Modifier.size(29.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onMode, Modifier.heightIn(min = 48.dp)) {
                Icon(if (playback.mode == MusicPlayMode.SHUFFLE) Icons.Outlined.Shuffle else Icons.Outlined.Repeat,
                    null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(playback.mode.label, fontSize = 13.sp)
            }
            TextButton(onClick = onQueue, Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.QueueMusic, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp))
                Text("播放队列 · ${playback.queueIds.size}", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PlaybackProgress(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat())
    Column(modifier.fillMaxWidth()) {
        Slider(value = value, onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek(it.toLong()) }; dragging = null },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(), enabled = durationMs > 0,
            colors = SliderDefaults.colors(thumbColor = PlayerOrange, activeTrackColor = PlayerOrange, inactiveTrackColor = MusicLine))
        Row(Modifier.fillMaxWidth().offset(y = (-8).dp)) {
            Text(musicPositionText(positionMs), Modifier.weight(1f), color = MusicMuted, fontSize = 11.sp)
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
    tracks: List<MusicTrack>, queueIds: List<String>, currentId: String?, onDismiss: () -> Unit,
    onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onClear: () -> Unit
) {
    val byId = tracks.associateBy { it.id }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 620.dp), shape = RoundedCornerShape(16.dp), color = Color.White) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("播放队列", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onClear, enabled = queueIds.size > 1) { Text("清空待播") }
                }
                Text("${queueIds.size} 首 · 随机模式由播放器另行打乱顺序", color = MusicMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                if (queueIds.isEmpty()) Text("队列已清空，从歌曲页选择一首即可重新播放。", color = MusicMuted,
                    modifier = Modifier.padding(vertical = 30.dp), fontSize = 13.sp)
                else LazyColumn {
                    items(queueIds.size, key = { queueIds[it] }) { index ->
                        val track = byId[queueIds[index]] ?: return@items
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(track.title, color = if (track.id == currentId) AccentDark else Ink,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (track.id == currentId) "当前播放" else track.artist, color = MusicMuted, fontSize = 10.sp)
                            }
                            IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowUp, "上移 ${track.title}", Modifier.size(19.dp))
                            }
                            IconButton(onClick = { onMove(index, index + 1) }, enabled = index < queueIds.lastIndex, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowDown, "下移 ${track.title}", Modifier.size(19.dp))
                            }
                            IconButton(onClick = { onRemove(index) }, Modifier.size(38.dp)) {
                                Icon(Icons.Outlined.Close, "从队列移除 ${track.title}", Modifier.size(18.dp))
                            }
                        }
                        HorizontalDivider(color = MusicLine)
                    }
                }
                TextButton(onClick = onDismiss, Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("关闭") }
            }
        }
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
