package com.focusassistant.app.ui

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.focusassistant.app.data.DiaryFiles
import com.focusassistant.app.domain.*
import com.focusassistant.app.platform.DiaryAudioPlayer
import com.focusassistant.app.platform.DiaryImages
import com.focusassistant.app.platform.DiaryRecorder
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class DiaryView { HOME, TRASH, DRAFTS, DETAIL, EDITOR }

internal object DiaryUiLimits {
    const val VISIBLE_PHOTOS = 3
    const val PHOTO_COLUMNS = 3
    const val THUMB_PX = 360
    const val VIEWER_PX = 1600
    const val NAME_LENGTH = 16
    const val TICK_MS = 250L
    const val SWIPE_WIDTH_DP = TodoUiLimits.SWIPE_WIDTH_DP
    const val SWIPE_THRESHOLD_DP = TodoUiLimits.SWIPE_THRESHOLD_DP
    const val SWIPE_ANIMATION_MS = 240
}

/** 日记删除统一淡红，只用于日记，待办删除配色不变。 */
internal val Danger = Color(0xFFC23B33)
internal val DangerSoft = Color(0xFFFDECEB)
internal val DangerLine = Color(0xFFF4C7C3)

internal data class DiaryPhotoViewer(val files: List<String>, val index: Int)

/**
 * 正在编辑的工作副本，放在界面模型里，旋转屏幕不丢；退到后台、返回时有改动就写入本机草稿箱。
 * restoredAt 非空表示是从草稿继续写；orphan 表示原日记已删除，保存时另存为新日记。
 */
internal data class DiaryEditorSession(
    val draft: DiaryDraft, val returnTo: DiaryView, val restoredAt: Long? = null, val orphan: Boolean = false
)

@Stable
internal class DiaryUiState {
    var view by mutableStateOf(DiaryView.HOME)
    var month by mutableStateOf(YearMonth.now())
    var query by mutableStateOf("")
    var detailId by mutableStateOf<String?>(null)
    var editor by mutableStateOf<DiaryEditorSession?>(null)
    var openSwipeId by mutableStateOf<String?>(null)
    var viewer by mutableStateOf<DiaryPhotoViewer?>(null)
    /** 首页列表位置保存在界面模型里，从详情返回时回到原阅读位置。 */
    val homeList = LazyListState()
    val trashList = LazyListState()
    val draftList = LazyListState()

    fun selectMonth(value: YearMonth) {
        if (value > YearMonth.now() || value.year < TodoUiLimits.MIN_YEAR) return
        month = value; query = ""; openSwipeId = null
    }
    fun search(value: String) { if (query != value) { query = value; openSwipeId = null } }
    fun openDetail(id: String) { detailId = id; openSwipeId = null; view = DiaryView.DETAIL }
    /** 点「+」总是空白新日记；编辑已有日记时自动接上它的草稿。 */
    fun openEditor(entry: DiaryEntry?, drafts: List<DiaryDraft>, now: Long = System.currentTimeMillis()) {
        val returnTo = if (view == DiaryView.DETAIL && entry != null) DiaryView.DETAIL else DiaryView.HOME
        val stored = entry?.let { value -> drafts.find { it.key == DiaryRules.editDraftKey(value.id) } }
        editor = if (stored != null) DiaryEditorSession(stored.copy(entryId = entry?.id), returnTo, stored.updatedAt)
            else DiaryEditorSession(DiaryRules.freshDraft(entry?.let { DiaryRules.editDraftKey(it.id) } ?: DiaryRules.newDraftKey(), entry, now), returnTo)
        openSwipeId = null; view = DiaryView.EDITOR
    }
    fun openDraft(item: DiaryDraftItem) {
        editor = DiaryEditorSession(item.draft.copy(entryId = item.entry?.id), DiaryView.DRAFTS, item.draft.updatedAt, item.kind == DiaryDraftKind.ORPHAN)
        view = DiaryView.EDITOR
    }
    fun edit(block: (DiaryDraft) -> DiaryDraft) { editor = editor?.let { it.copy(draft = block(it.draft)) } }
    fun editorFiles(): Set<String> = editor?.draft?.files?.toSet().orEmpty()
    fun closeEditor() { view = editor?.returnTo ?: DiaryView.HOME; editor = null }
    fun showSaved(entry: DiaryEntry, created: Boolean) {
        if (created && query.isBlank()) DiaryRules.localDate(entry)?.let { month = YearMonth.from(it) }
        editor = null; detailId = entry.id; view = DiaryView.DETAIL
    }
    /** 已在日记页时再点「日记」：二级页回首页；已在首页返回 true，由调用方滚回顶部。编辑页没有导航栏，不走这里。 */
    fun reselect(): Boolean {
        if (view == DiaryView.HOME) return true
        if (view != DiaryView.EDITOR) { view = DiaryView.HOME; openSwipeId = null }
        return false
    }
}

internal class DiaryActions(
    val save: (entry: DiaryEntry, existing: Boolean, draftKey: String) -> Unit,
    val trash: (DiaryEntry) -> Unit,
    val restore: (DiaryEntry) -> Unit,
    val purge: (DiaryEntry) -> Unit,
    /** 后台写入草稿箱，不占用忙碌状态。 */
    val storeDraft: (DiaryDraft) -> Unit,
    /** 移出草稿箱（若存在），并清理 extra 中不再使用的附件。 */
    val dropDraft: (key: String, extra: Collection<String>) -> Unit,
    val deleteDraft: (DiaryDraft) -> Unit,
    val pickMonth: () -> Unit,
    val help: () -> Unit,
    val message: (String) -> Unit
)

private fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

internal fun diaryName(entry: DiaryEntry): String {
    val text = (entry.title.ifBlank { entry.text }).trim().replace(Regex("""\s+"""), " ")
    if (text.isNotEmpty()) return if (text.length > DiaryUiLimits.NAME_LENGTH) text.take(DiaryUiLimits.NAME_LENGTH) + "…" else text
    return if (entry.audios.isNotEmpty()) "一段语音" else if (entry.photos.isNotEmpty()) "照片日记" else "日记"
}

internal fun diaryAttachments(photos: Int, audios: Int): String =
    listOfNotNull(photos.takeIf { it > 0 }?.let { "$it 张照片" }, audios.takeIf { it > 0 }?.let { "$it 段语音" }).joinToString(" · ")

/** 同一时间只播放一段；切到后台或离开日记时停止。 */
@Stable
internal class DiaryPlayback(private val player: DiaryAudioPlayer, private val onError: (String) -> Unit) {
    var playing by mutableStateOf<String?>(null)
        private set
    var position by mutableLongStateOf(0L)
        private set
    fun toggle(audio: DiaryAudio) {
        if (playing == audio.file) { stop(); return }
        try {
            player.play(audio.file) { playing = null; position = 0 }
            playing = audio.file
            position = 0
        } catch (error: IOException) { stop(); onError(error.message ?: "无法播放这段录音") }
    }
    fun stop() { player.stop(); playing = null; position = 0 }
    fun tick() { position = player.positionMs() }
}

@Composable
private fun rememberDiaryPlayback(onError: (String) -> Unit): DiaryPlayback {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val playback = remember { DiaryPlayback(DiaryAudioPlayer(context.applicationContext), onError) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) playback.stop() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); playback.stop() }
    }
    LaunchedEffect(playback.playing) {
        while (playback.playing != null) { playback.tick(); delay(DiaryUiLimits.TICK_MS) }
    }
    return playback
}

@Composable
internal fun DiaryScreen(state: AppState, ui: DiaryUiState, today: LocalDate, busy: Boolean, actions: DiaryActions) {
    val playback = rememberDiaryPlayback(actions.message)
    LaunchedEffect(ui.view) { playback.stop() }
    when (ui.view) {
        DiaryView.HOME -> DiaryHome(state, ui, today, busy, playback, actions)
        DiaryView.TRASH -> DiaryTrash(state, ui, busy, actions)
        DiaryView.DRAFTS -> DiaryDraftBox(state, ui, busy, actions)
        DiaryView.DETAIL -> {
            val entry = state.diaries.find { it.id == ui.detailId && it.deletedAt == null }
            LaunchedEffect(entry == null) { if (entry == null) ui.view = DiaryView.HOME }
            if (entry != null) DiaryDetail(entry, ui, state.diaryDrafts, today, busy, playback, actions)
        }
        DiaryView.EDITOR -> {
            val session = ui.editor
            LaunchedEffect(session == null) { if (session == null) ui.view = DiaryView.HOME }
            if (session != null) {
                // 编辑途中原日记被删除时，按「另存为新日记」处理，内容不丢。
                val entry = session.draft.entryId?.let { id -> state.diaries.find { it.id == id && it.deletedAt == null } }
                key(session.draft.key) { DiaryEditor(session, entry, ui, busy, playback, actions) }
            }
        }
    }
    ui.viewer?.let { viewer -> DiaryPhotoViewerDialog(viewer) { ui.viewer = null } }
}

@Composable
private fun DiaryHome(state: AppState, ui: DiaryUiState, today: LocalDate, busy: Boolean, playback: DiaryPlayback, actions: DiaryActions) {
    val current = YearMonth.from(today)
    val month = minOf(ui.month, current)
    LaunchedEffect(month) { if (ui.month > month) ui.selectMonth(month) }
    val selection = DiaryRules.select(state.diaries, month, ui.query, today)
    val searching = ui.query.isNotBlank()
    val trashCount = state.diaries.count { it.deletedAt != null }
    var menu by remember { mutableStateOf(false) }
    LazyColumn(state = ui.homeList, modifier = Modifier.fillMaxSize().background(Color.White),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
        item(key = "diary-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("发生了什么，有什么感受", color = Muted, fontSize = 11.sp)
                    Text(buildAnnotatedString { append("日记"); withStyle(SpanStyle(color = Accent)) { append(".") } },
                        fontSize = 25.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreHoriz, "更多：草稿、回收站与说明") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color.White) {
                        DropdownMenuItem(text = { Text("草稿 · ${state.diaryDrafts.size} 份") }, leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                            onClick = { menu = false; ui.openSwipeId = null; ui.view = DiaryView.DRAFTS })
                        DropdownMenuItem(text = { Text("回收站 · $trashCount 条") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { menu = false; ui.openSwipeId = null; ui.view = DiaryView.TRASH })
                        DropdownMenuItem(text = { Text("关于日记") }, leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick = { menu = false; actions.help() })
                    }
                }
                IconButton(enabled = !busy, onClick = { ui.openEditor(null, state.diaryDrafts) },
                    modifier = Modifier.padding(start = 6.dp).size(48.dp).clip(CircleShape).background(Accent)) {
                    Icon(Icons.Outlined.Add, "记一条", tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
        }
        item(key = "diary-search") {
            OutlinedTextField(value = ui.query, onValueChange = ui::search, singleLine = true,
                placeholder = { Text("搜索文字或日期，如 9月27日", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (ui.query.isNotEmpty()) { { IconButton(onClick = { ui.search("") }) { Icon(Icons.Outlined.Close, "清空搜索", modifier = Modifier.size(18.dp)) } } } else null,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line,
                    focusedContainerColor = Color.White, unfocusedContainerColor = SoftSurface, cursorColor = Accent),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).semantics { contentDescription = "搜索日记文字或日期·全部时间" })
        }
        item(key = "diary-month") {
            Column(Modifier.fillMaxWidth()) {
                if (!searching) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = month > YearMonth.of(TodoUiLimits.MIN_YEAR, 1), onClick = { ui.selectMonth(month.minusMonths(1)) }) { Icon(Icons.Outlined.ChevronLeft, "上一月") }
                    TextButton(onClick = actions.pickMonth, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
                        Text("${month.year} 年 ${month.monthValue} 月", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Outlined.ExpandMore, null, tint = Muted, modifier = Modifier.size(16.dp))
                    }
                    IconButton(enabled = month < current, onClick = { ui.selectMonth(month.plusMonths(1)) }) { Icon(Icons.Outlined.ChevronRight, "下一月") }
                    if (month != current) TextButton(onClick = { ui.selectMonth(current) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("回到本月", fontSize = 11.sp) }
                }
                Text(when {
                    !searching -> "${month.year}年${month.monthValue}月 · ${selection.total} 条日记"
                    selection.dateLabel != null -> "按日期「${selection.dateLabel}」或文字 · 找到 ${selection.total} 条"
                    else -> "全部时间 · 找到 ${selection.total} 条"
                }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (selection.groups.isEmpty()) item(key = "diary-empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val (title, detail) = when {
                    searching -> "没有找到相关日记" to "可以搜索标题、正文或日期（如 9月27日、2026-09-27、昨天），不包含语音与照片内容。"
                    selection.months.isEmpty() -> "还没有日记" to "记下今天发生的一件小事，文字、照片或一段语音都可以。"
                    else -> "这一月还没有日记" to "可以切换月份回看，或搜索全部时间的文字。"
                }
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(detail, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                if (!searching && selection.months.isEmpty()) OutlinedButton(enabled = !busy, onClick = { ui.openEditor(null, state.diaryDrafts) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("记一条", fontSize = 12.sp) }
                val latest = selection.latestMonth
                if (!searching && latest != null && latest != month) OutlinedButton(onClick = { ui.selectMonth(latest) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("查看最近有日记的月份", fontSize = 12.sp)
                }
            }
        }
        selection.groups.forEach { group ->
            item(key = "diary-day:${group.date}") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
                    Text(group.label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${group.entries.size} 条", color = Muted, fontSize = 11.sp)
                }
            }
            group.entries.forEach { entry ->
                item(key = "diary:${entry.id}") { DiaryCard(entry, ui, busy, playback, actions) }
            }
        }
        item(key = "diary-note") {
            Text("日记保存在本机，暂不包含在「导出完整备份」中；卸载应用或清除数据会删除日记。", color = Muted, fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
        }
    }
}

@Composable
private fun DiaryCard(entry: DiaryEntry, ui: DiaryUiState, busy: Boolean, playback: DiaryPlayback, actions: DiaryActions) {
    val density = LocalDensity.current
    val swipeWidth = with(density) { DiaryUiLimits.SWIPE_WIDTH_DP.dp.toPx() }
    val threshold = with(density) { DiaryUiLimits.SWIPE_THRESHOLD_DP.dp.toPx() }
    var dragging by remember(entry.id) { mutableStateOf(false) }
    var dragOffset by remember(entry.id) { mutableFloatStateOf(0f) }
    val opened = ui.openSwipeId == entry.id && !busy
    val currentOpened by rememberUpdatedState(opened)
    val swipe by animateFloatAsState(if (dragging) dragOffset else if (opened) -swipeWidth else 0f,
        animationSpec = if (dragging) snap() else tween(DiaryUiLimits.SWIPE_ANIMATION_MS), label = "diary-swipe")
    val shape = RoundedCornerShape(15.dp)
    val attachments = diaryAttachments(entry.photos.size, entry.audios.size)
    fun act(action: () -> Unit) { if (busy || dragging) return; if (ui.openSwipeId != null) ui.openSwipeId = null else action() }
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).clipToBounds(), propagateMinConstraints = true) {
        if (opened || dragging) {
            Box(Modifier.matchParentSize().background(DangerSoft), contentAlignment = Alignment.CenterEnd) {
                if (opened && !dragging) TextButton(enabled = !busy, onClick = { ui.openSwipeId = null; actions.trash(entry) },
                    modifier = Modifier.width(DiaryUiLimits.SWIPE_WIDTH_DP.dp).fillMaxHeight().heightIn(min = 48.dp)
                        .semantics { contentDescription = "删除日记：${diaryName(entry)}（移入回收站）" }) {
                    Text("删除", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                } else Text("删除", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.width(DiaryUiLimits.SWIPE_WIDTH_DP.dp).clearAndSetSemantics {})
            }
        }
        Surface(color = Color.White, shape = shape, border = BorderStroke(1.dp, Line),
            modifier = Modifier.offset { IntOffset(swipe.roundToInt(), 0) }.fillMaxWidth()
                .pointerInput(entry.id, busy, swipeWidth) {
                    if (!busy) detectHorizontalDragGestures(
                        onDragStart = { dragOffset = if (currentOpened) -swipeWidth else 0f; dragging = true; ui.openSwipeId = entry.id },
                        onHorizontalDrag = { change, amount -> change.consume(); dragOffset = (dragOffset + amount).coerceIn(-swipeWidth, 0f) },
                        onDragEnd = { ui.openSwipeId = if (dragOffset <= -threshold) entry.id else null; dragging = false },
                        onDragCancel = { ui.openSwipeId = null; dragging = false }
                    )
                }
                .semantics {
                    if (!busy) customActions = listOf(
                        CustomAccessibilityAction("查看日记") { ui.openDetail(entry.id); true },
                        CustomAccessibilityAction("删除日记（移入回收站）") { ui.openSwipeId = null; actions.trash(entry); true }
                    )
                }) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().clickable(enabled = !busy, onClickLabel = "查看日记") { act { ui.openDetail(entry.id) } }
                    .semantics { contentDescription = "${clock(entry.occurredAt)} ${diaryName(entry)}${if (attachments.isNotEmpty()) "，$attachments" else ""}" }
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(clock(entry.occurredAt), color = Muted, fontSize = 11.sp)
                    if (entry.title.isNotBlank()) Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (entry.text.isNotBlank()) Text(entry.text.replace(Regex("""\s*\n\s*"""), " "), fontSize = 13.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (entry.photos.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val extra = entry.photos.size - DiaryUiLimits.VISIBLE_PHOTOS
                        entry.photos.take(DiaryUiLimits.VISIBLE_PHOTOS).forEachIndexed { index, photo ->
                            Box(Modifier.weight(1f).aspectRatio(4f / 3f)) {
                                DiaryThumb(photo.file, DiaryUiLimits.THUMB_PX, null, Modifier.fillMaxSize())
                                if (index == DiaryUiLimits.VISIBLE_PHOTOS - 1 && extra > 0) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x66292929)), contentAlignment = Alignment.Center) {
                                    Text("+$extra", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        repeat((DiaryUiLimits.VISIBLE_PHOTOS - entry.photos.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                    }
                }
                entry.audios.forEach { audio ->
                    DiaryVoice(audio, playback, enabled = !busy && ui.openSwipeId == null, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun DiaryThumb(file: String, maxPx: Int, description: String?, modifier: Modifier, crop: Boolean = true) {
    val context = LocalContext.current
    var loaded by remember(file, maxPx) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, file, maxPx) {
        value = try { DiaryImages.load(context.applicationContext, file, maxPx)?.asImageBitmap() } catch (error: Exception) { null }
        loaded = true
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(if (crop) SoftSurface else Color.Transparent), contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> Image(image, description, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
            loaded -> Text("无法显示", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DiaryVoice(audio: DiaryAudio, playback: DiaryPlayback, enabled: Boolean, modifier: Modifier = Modifier) {
    val playing = playback.playing == audio.file
    val total = audio.durationMs / UiLimits.MILLIS_PER_SECOND
    val progress = if (playing && audio.durationMs > 0) (playback.position.toFloat() / audio.durationMs).coerceIn(0f, 1f) else 0f
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(AccentSoft)
        .clickable(enabled = enabled, onClickLabel = if (playing) "停止播放" else "回放原声") { playback.toggle(audio) }
        .semantics { contentDescription = "原声，时长 ${clockText(total)}${if (playing) "，正在播放" else ""}" }
        .padding(start = 6.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text("原声", color = AccentDark, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        LinearProgressIndicator(progress = { progress }, color = Accent, trackColor = Color(0xFFF3D4C3), modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)))
        Text(if (playing) "${clockText(playback.position / UiLimits.MILLIS_PER_SECOND)} / ${clockText(total)}" else clockText(total), color = AccentDark, fontSize = 12.sp)
    }
}

@Composable
private fun PhotoGrid(files: List<String>, onOpen: ((Int) -> Unit)?, onRemove: ((Int) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.withIndex().chunked(DiaryUiLimits.PHOTO_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (index, file) ->
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        DiaryThumb(file, DiaryUiLimits.THUMB_PX, null, Modifier.fillMaxSize()
                            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "查看大图") { onOpen(index) } else Modifier)
                            .semantics { contentDescription = "照片 ${index + 1} / ${files.size}" })
                        if (onRemove != null) IconButton(onClick = { onRemove(index) }, modifier = Modifier.align(Alignment.TopEnd).size(44.dp)) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0x99292929)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Close, "移除第 ${index + 1} 张照片", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                repeat(DiaryUiLimits.PHOTO_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DiaryPhotoViewerDialog(viewer: DiaryPhotoViewer, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = viewer.index) { viewer.files.size }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize()) { page ->
                DiaryThumb(viewer.files[page], DiaryUiLimits.VIEWER_PX, "照片 ${page + 1} / ${viewer.files.size}", Modifier.fillMaxSize(), crop = false)
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭大图", tint = Color.White) }
                Text("${pager.currentPage + 1} / ${viewer.files.size}", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun DiarySubHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回日记") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        actions()
    }
}

@Composable
private fun DangerButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(enabled = enabled, onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, DangerLine), colors = ButtonDefaults.outlinedButtonColors(containerColor = DangerSoft, contentColor = Danger)) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun DiaryDetail(entry: DiaryEntry, ui: DiaryUiState, drafts: List<DiaryDraft>, today: LocalDate, busy: Boolean, playback: DiaryPlayback, actions: DiaryActions) {
    BackHandler { ui.view = DiaryView.HOME }
    val date = DiaryRules.localDate(entry)
    Column(Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        DiarySubHeader(date?.let { DiaryRules.dayLabel(it, today).substringBefore(" · ") } ?: "时间未记录", { ui.view = DiaryView.HOME }) {
            IconButton(enabled = !busy, onClick = { ui.openEditor(entry, drafts) }) { Icon(Icons.Outlined.Edit, "编辑这条日记") }
        }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("${date?.year ?: "—"}年 · ${clock(entry.occurredAt)}", color = Muted, fontSize = 12.sp)
            if (entry.title.isNotBlank()) Text(entry.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, modifier = Modifier.padding(top = 6.dp))
            if (entry.text.isNotBlank()) SelectionContainer { Text(entry.text, fontSize = 15.sp, lineHeight = 29.sp, modifier = Modifier.padding(top = 10.dp)) }
            if (entry.photos.isNotEmpty()) {
                Text("照片 · ${entry.photos.size} 张", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                PhotoGrid(entry.photos.map { it.file }, onOpen = { index -> ui.viewer = DiaryPhotoViewer(entry.photos.map { it.file }, index) })
            }
            if (entry.audios.isNotEmpty()) {
                Text("原声 · ${entry.audios.size} 段", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { entry.audios.forEach { DiaryVoice(it, playback, enabled = true) } }
            }
            HorizontalDivider(color = Line, modifier = Modifier.padding(top = 24.dp))
            Text("写于 ${timestampText(entry.createdAt)}${if (entry.updatedAt != entry.createdAt) " · 最后修改 ${timestampText(entry.updatedAt)}" else ""}",
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
            DangerButton("删除（移入回收站）", enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { actions.trash(entry) }
        }
    }
}

@Composable
private fun DiaryTrash(state: AppState, ui: DiaryUiState, busy: Boolean, actions: DiaryActions) {
    BackHandler { ui.view = DiaryView.HOME }
    val entries = DiaryRules.trashed(state.diaries)
    LazyColumn(state = ui.trashList, modifier = Modifier.fillMaxSize().background(Color.White), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        item(key = "trash-header") {
            DiarySubHeader("回收站", { ui.view = DiaryView.HOME })
            Text("删除的日记先放在这里，不会自动清空。恢复后回到原来的日期；永久删除会同时移除其照片与录音，且无法恢复。",
                color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 14.dp))
        }
        if (entries.isEmpty()) item(key = "trash-empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("回收站是空的", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("删除的日记会先放到这里，需要时可以恢复。", color = Muted, fontSize = 12.sp)
            }
        }
        entries.forEach { entry ->
            item(key = "trash:${entry.id}") {
                val attachments = diaryAttachments(entry.photos.size, entry.audios.size)
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFFFCFCFC))
                    .border(1.dp, Line, RoundedCornerShape(15.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${timestampText(entry.occurredAt)} · ${entry.deletedAt?.let { timestampText(it).substringBefore(' ') } ?: "—"} 删除", color = Muted, fontSize = 11.sp)
                    if (entry.title.isNotBlank()) Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(entry.text.ifBlank { diaryName(entry) }, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (attachments.isNotEmpty()) Text(attachments, color = Muted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { actions.restore(entry) }, modifier = Modifier.heightIn(min = 44.dp),
                            border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentDark)) { Text("恢复", fontSize = 12.sp) }
                        DangerButton("永久删除", enabled = !busy) { actions.purge(entry) }
                    }
                }
            }
        }
    }
}

/** 草稿箱：没写完就离开或退到后台的日记。点卡片或「继续写」接着编辑，删除需确认。 */
@Composable
private fun DiaryDraftBox(state: AppState, ui: DiaryUiState, busy: Boolean, actions: DiaryActions) {
    BackHandler { ui.view = DiaryView.HOME }
    val items = DiaryRules.listDrafts(state.diaryDrafts, state.diaries)
    LazyColumn(state = ui.draftList, modifier = Modifier.fillMaxSize().background(Color.White), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        item(key = "drafts-header") {
            DiarySubHeader("草稿", { ui.view = DiaryView.HOME })
            Text("没写完的日记会自动存到这里：点返回或退到后台时保存，关掉应用、重启手机也还在。保存为正式日记后移出草稿。",
                color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 14.dp))
        }
        if (items.isEmpty()) item(key = "drafts-empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("没有草稿", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("没写完就离开时，日记会自动存到这里。", color = Muted, fontSize = 12.sp)
            }
        }
        items.forEach { item ->
            item(key = "draft:${item.draft.key}") {
                val draft = item.draft
                val attachments = diaryAttachments(draft.photos.size, draft.audios.size)
                val (label, tint, background) = when (item.kind) {
                    DiaryDraftKind.NEW -> Triple("新日记", Muted, SoftSurface)
                    DiaryDraftKind.EDIT -> Triple("修改：${item.entry?.let(::diaryName) ?: "日记"}", AccentDark, AccentSoft)
                    DiaryDraftKind.ORPHAN -> Triple("原日记已删除 · 将另存为新日记", Danger, DangerSoft)
                }
                val summary = draft.title.trim().ifEmpty { draft.text.trim() }.ifEmpty { attachments }.ifEmpty { "空白草稿" }
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(15.dp)).border(1.dp, Line, RoundedCornerShape(15.dp))) {
                    Column(Modifier.fillMaxWidth().clickable(enabled = !busy, onClickLabel = "继续写") { ui.openDraft(item) }
                        .semantics { contentDescription = "草稿：$label，$summary" }
                        .padding(start = 14.dp, end = 14.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(background).padding(horizontal = 8.dp, vertical = 2.dp))
                        Text("最后编辑 ${timestampText(draft.updatedAt)} · 记录时间 ${timestampText(draft.occurredAt)}", color = Muted, fontSize = 11.sp)
                        if (draft.title.isNotBlank()) Text(draft.title.trim(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        val body = draft.text.trim().ifEmpty { if (draft.title.isBlank()) summary else "" }
                        if (body.isNotEmpty()) Text(body, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (attachments.isNotEmpty()) Text(attachments, color = Muted, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { ui.openDraft(item) }, modifier = Modifier.heightIn(min = 44.dp),
                            border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentDark)) { Text("继续写", fontSize = 12.sp) }
                        DangerButton("删除草稿", enabled = !busy) { actions.deleteDraft(draft) }
                    }
                }
            }
        }
    }
}

private fun Activity?.shouldExplainMicrophone(): Boolean = this != null && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)

@Composable
private fun DiaryEditor(session: DiaryEditorSession, entry: DiaryEntry?, ui: DiaryUiState, busy: Boolean, playback: DiaryPlayback, actions: DiaryActions) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val draft = session.draft
    val currentEntry by rememberUpdatedState(entry)
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var micSettings by remember { mutableStateOf(false) }
    var askedMicrophone by rememberSaveable { mutableStateOf(false) }
    val recorder = remember { DiaryRecorder(context.applicationContext) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val savedAudio = remember(entry) { entry?.audios.orEmpty().map { it.id }.toSet() }
    fun edit(block: (DiaryDraft) -> DiaryDraft) {
        ui.edit(block)
        error = null
    }
    /** 有改动就写入本机草稿箱，没改动（或改回原样）就移出。返回是否存为草稿。 */
    fun persistDraft(): Boolean {
        val current = ui.editor?.draft ?: return false
        val changed = DiaryRules.draftChanged(current, currentEntry)
        if (changed) actions.storeDraft(current) else actions.dropDraft(current.key, emptyList())
        return changed
    }
    fun stopRecording(note: String? = null) {
        val result = recorder.stop() ?: return
        recording = false
        val audio = result.audio
        if (audio != null) { edit { it.copy(audios = it.audios + audio) }; actions.message(note ?: "录音已停止，已放入草稿") }
        else actions.message(result.failure ?: "录音未保留")
    }
    fun startRecording() {
        playback.stop()
        try {
            recorder.start { stopRecording("录音被系统中断，已保留录到的内容") }
            recording = true
        } catch (failure: IOException) { actions.message(failure.message ?: "无法开始录音") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedMicrophone = true
        if (granted) startRecording() else actions.message("未获得麦克风权限，文字和照片仍可使用")
    }
    fun toggleRecording() {
        when {
            recording -> stopRecording()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startRecording()
            !askedMicrophone || (context as? Activity).shouldExplainMicrophone() -> permission.launch(Manifest.permission.RECORD_AUDIO)
            else -> micSettings = true
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> ->
        if (uris.isEmpty()) { actions.message("未选择照片，已输入的内容都还在"); return@rememberLauncherForActivityResult }
        importing = true
        scope.launch {
            var failed = 0
            var reason: String? = null
            uris.forEach { uri ->
                try {
                    val photo = withContext(Dispatchers.IO) { DiaryFiles.importPhoto(context.applicationContext, uri) }
                    edit { it.copy(photos = it.photos + photo) }
                } catch (failure: Exception) { failed++; reason = failure.message }
            }
            importing = false
            if (failed > 0) actions.message("有 $failed 张照片未添加：${reason ?: "读取失败"}。已输入的内容都还在。")
        }
    }
    fun pickPhotos() {
        stopRecording("选图前已停止录音，已录内容保留在草稿中")
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun close() {
        stopRecording("返回前已停止录音，已录内容保留在草稿中")
        val kept = persistDraft()
        ui.closeEditor()
        if (kept) actions.message("已存入草稿，可在「更多 · 草稿」继续写")
    }
    fun save() {
        stopRecording("录音已停止")
        val latest = ui.editor?.draft ?: return
        when {
            !latest.hasContent -> error = "写点什么，或添加照片、录音后再保存。"
            latest.occurredAt > System.currentTimeMillis() -> error = "记录时间不能晚于现在。"
            latest.title.trim().length > DiaryRules.MAX_TITLE -> error = "标题最多 ${DiaryRules.MAX_TITLE} 字。"
            // 原日记已删除时另存为新日记，不覆盖回收站里的记录。
            else -> actions.save(latest.toEntry(entry?.id ?: UUID.randomUUID().toString()), entry != null, latest.key)
        }
    }
    fun pickTime() {
        val zone = ZoneId.systemDefault()
        val current = Instant.ofEpochMilli(draft.occurredAt).atZone(zone).toLocalDateTime()
        val dialog = DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                val picked = LocalDateTime.of(year, month + 1, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
                if (picked > System.currentTimeMillis()) actions.message("记录时间不能晚于现在。")
                else edit { it.copy(occurredAt = picked) }
            }, current.hour, current.minute, true).show()
        }, current.year, current.monthValue - 1, current.dayOfMonth)
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.datePicker.minDate = Calendar.getInstance().apply { set(TodoUiLimits.MIN_YEAR, 0, 1, 0, 0, 0) }.timeInMillis
        dialog.show()
    }

    BackHandler { close() }
    LaunchedEffect(recording) { while (recording) { elapsed = recorder.elapsedMs(); delay(DiaryUiLimits.TICK_MS) } }
    // 退到后台、锁屏或被切走：先停录音，再把没写完的内容写入本机草稿箱；工作副本仍留在界面模型里，回来可以接着写。
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                stopRecording("退到后台时已停止录音，已录内容保留在草稿中")
                if (persistDraft()) actions.message("已自动存入草稿")
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            stopRecording("离开编辑页时已停止录音，已录内容保留在草稿中")
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::close, modifier = Modifier.semantics { contentDescription = "返回并保留草稿" }) { Text("返回", color = Muted) }
            Text(if (entry == null) "记一条" else "编辑日记", modifier = Modifier.weight(1f).semantics { heading() }, textAlign = TextAlign.Center, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Button(enabled = !busy && !importing, onClick = ::save, shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                Text(if (busy) "保存中" else "保存")
            }
        }
        HorizontalDivider(color = Line)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            val restoredAt = session.restoredAt
            if (restoredAt != null) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(if (session.orphan) DangerSoft else SoftSurface).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (session.orphan) "原日记已删除，保存时将另存为新日记" else "继续草稿 · 最后编辑 ${timestampText(restoredAt)}",
                    color = if (session.orphan) Danger else Muted, fontSize = 12.sp, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = { confirmDiscard = true }) { Text("放弃草稿", fontSize = 12.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("记录时间", color = Muted, fontSize = 12.sp)
                OutlinedButton(onClick = ::pickTime, modifier = Modifier.padding(start = 10.dp).heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Line), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Text(timestampText(draft.occurredAt), fontSize = 13.sp)
                }
            }
            Text("可补写过去的经历", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            val fieldColors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                focusedIndicatorColor = Accent, unfocusedIndicatorColor = Line, cursorColor = Accent)
            val bodyColors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Accent)
            TextField(value = draft.title, onValueChange = { value -> if (value.length <= DiaryRules.MAX_TITLE) edit { it.copy(title = value) } },
                placeholder = { Text("标题（可选）", fontSize = 18.sp) }, singleLine = true, colors = fieldColors,
                textStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "标题（可选）" })
            TextField(value = draft.text, onValueChange = { value -> if (value.length <= DiaryRules.MAX_TEXT) edit { it.copy(text = value) } },
                placeholder = { Text("今天发生了什么？有什么感受？") },
                colors = bodyColors,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 28.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).semantics { contentDescription = "正文" })
            if (draft.photos.isNotEmpty()) {
                Text("照片 · ${draft.photos.size} 张", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                PhotoGrid(draft.photos.map { it.file }, onOpen = { index -> ui.viewer = DiaryPhotoViewer(draft.photos.map { it.file }, index) },
                    onRemove = { index -> edit { it.copy(photos = it.photos.filterIndexed { position, _ -> position != index }) } })
            }
            if (importing) Text("正在保存所选照片…", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            if (draft.audios.isNotEmpty()) {
                Text("原声 · ${draft.audios.size} 段", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                draft.audios.forEachIndexed { index, audio ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DiaryVoice(audio, playback, enabled = !recording, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (playback.playing == audio.file) playback.stop()
                            edit { it.copy(audios = it.audios.filterIndexed { position, _ -> position != index }) }
                        }, modifier = Modifier.semantics { contentDescription = "移除第 ${index + 1} 段原声" }) { Text("移除", color = Muted, fontSize = 12.sp) }
                    }
                }
                if (draft.audios.any { it.id !in savedAudio }) Text("新录音已停止，目前在草稿中；保存日记后才成为正式记录。", color = AccentDark, fontSize = 11.sp)
            }
            error?.let {
                Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp))
                    .background(DangerSoft).padding(horizontal = 12.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Assertive })
            }
            Text("返回或退到后台时自动存入「更多 · 草稿」，保存为日记后移出草稿。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
        }
        if (recording) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(AccentSoft)
            .border(1.dp, Color(0xFFE8C4B5), RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Accent))
            Text("正在录音", color = AccentDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(clockText(elapsed / UiLimits.MILLIS_PER_SECOND), color = AccentDark, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Button(onClick = { stopRecording() }, shape = RoundedCornerShape(11.dp), modifier = Modifier.heightIn(min = 44.dp)) { Text("停止") }
        }
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && !importing, onClick = ::pickPhotos, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Line), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Icon(Icons.Outlined.Image, null, modifier = Modifier.size(18.dp)); Text("添加照片", fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(enabled = !busy, onClick = ::toggleRecording, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (recording) Accent else Line),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (recording) AccentSoft else Color.White, contentColor = if (recording) AccentDark else MaterialTheme.colorScheme.onSurface)) {
                Icon(Icons.Outlined.Mic, null, modifier = Modifier.size(18.dp)); Text(if (recording) "停止录音" else "录音", fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    if (confirmDiscard) ConfirmDialog("放弃这份草稿？", "未保存的修改会被丢弃，已保存的日记不受影响。", false, { confirmDiscard = false }) {
        confirmDiscard = false
        stopRecording()
        val current = ui.editor ?: return@ConfirmDialog
        val fresh = DiaryRules.freshDraft(current.draft.key, entry, System.currentTimeMillis())
        ui.editor = current.copy(draft = fresh, restoredAt = null, orphan = false)
        actions.dropDraft(current.draft.key, current.draft.files - fresh.files.toSet())
    }
    if (micSettings) AlertDialog(onDismissRequest = { micSettings = false }, title = { Text("麦克风权限未开启") },
        text = { Text("录音需要麦克风权限。可以在系统设置中开启；文字和照片不受影响。") },
        confirmButton = { TextButton(onClick = {
            micSettings = false
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text("去设置") } },
        dismissButton = { TextButton(onClick = { micSettings = false }) { Text("取消") } })
}
