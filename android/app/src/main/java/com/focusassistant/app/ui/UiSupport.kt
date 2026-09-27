package com.focusassistant.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusassistant.app.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal object UiLimits {
    const val MINUTES_PER_HOUR = 60L
    const val SECONDS_PER_MINUTE = 60L
    const val MILLIS_PER_SECOND = 1000L
    const val MIN_TIMER = Validation.MIN_MINUTES
    const val MAX_TIMER = Validation.MAX_MINUTES
    const val MAX_HOURS = 23
    const val MAX_TITLE = 80
    const val MAX_NOTE = 2000
    const val MAX_PERCENT = 100
    const val RECORD_PAGE = 20
    const val TODO_GUIDE_HEIGHT_FRACTION = 0.85f
    /** 与待办分组共用一份分类顺序，避免编辑器与分组各自漂移。 */
    val CATEGORIES = TodoGrouping.CATEGORIES
    val PRESETS = listOf(15, 25, 45, 60)
    const val MIN_PRESET = 25
}

/** 待办页签：放下的事只在「放下的」里出现，可以找回。 */
internal enum class TodoFilter { PENDING, DONE, ARCHIVED }

internal val Accent = Color(0xFFC44E22)
internal val AccentDark = Color(0xFFA33D18)
internal val AccentSoft = Color(0xFFFFF1E9)
internal val SoftSurface = Color(0xFFF5F5F5)
internal val Muted = Color(0xFF626262)
internal val Line = Color(0xFFE5E5E5)

@Composable
internal fun FocusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent, onPrimary = Color.White, primaryContainer = Color(0xFFFFF0E8),
            onPrimaryContainer = Color(0xFF873B20), background = Color.White, surface = Color.White,
            onBackground = Color(0xFF292929), onSurface = Color(0xFF292929),
            surfaceVariant = SoftSurface, onSurfaceVariant = Muted, outlineVariant = Color(0xFFE5E5E5)
        ),
        content = content
    )
}

internal fun modeName(mode: TimerMode) = if (mode == TimerMode.COUNTUP) "正计时" else "倒计时"
/** 把分钟数写成「1 小时 30 分钟」；null 表示不限时的正计时目标。 */
internal fun targetText(minutes: Int?): String {
    if (minutes == null) return "不限时"
    val hours = minutes / UiLimits.MINUTES_PER_HOUR.toInt()
    val remainder = minutes % UiLimits.MINUTES_PER_HOUR.toInt()
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { "$it 小时" },
        remainder.takeIf { it > 0 }?.let { "$it 分钟" }
    ).joinToString(" ").ifEmpty { "0 分钟" }
}
internal fun projectSubtitle(project: Project): String {
    val target = project.targetMinutes
    val detail = if (target == null) "不限时" else "${if (project.timerMode == TimerMode.COUNTUP) "目标 " else ""}${targetText(target)}"
    return "${modeName(project.timerMode)} · $detail"
}
/** 超过一小时显示 HH:MM:SS，否则 MM:SS。 */
internal fun clockText(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / (UiLimits.MINUTES_PER_HOUR * UiLimits.SECONDS_PER_MINUTE)
    val minutes = safe / UiLimits.SECONDS_PER_MINUTE % UiLimits.MINUTES_PER_HOUR
    val remainder = safe % UiLimits.SECONDS_PER_MINUTE
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, remainder)
    else "%02d:%02d".format(Locale.ROOT, minutes, remainder)
}
internal fun durationText(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / (UiLimits.MINUTES_PER_HOUR * UiLimits.SECONDS_PER_MINUTE)
    val minutes = safe / UiLimits.SECONDS_PER_MINUTE % UiLimits.MINUTES_PER_HOUR
    val remainder = safe % UiLimits.SECONDS_PER_MINUTE
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { "$it 小时" },
        minutes.takeIf { it > 0 }?.let { "$it 分钟" },
        remainder.takeIf { it > 0 }?.let { "$it 秒" }
    ).joinToString(" ").ifEmpty { "0 分钟" }
}
internal fun minuteText(seconds: Double): String = String.format(Locale.ROOT, "%.2f", seconds / UiLimits.SECONDS_PER_MINUTE).trimEnd('0').trimEnd('.')
internal fun timestampText(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@Composable
internal fun PageTitle(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
internal fun HelpButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "累计统计说明" }) {
        Text("?", fontSize = 19.sp, color = Muted)
    }
}

@Composable
internal fun InformationDialog(title: String, lines: List<String>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            lines.forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}

@Composable
internal fun ConfirmDialog(title: String, message: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(enabled = !busy, onClick = onConfirm) { Text(if (busy) "处理中" else "确认") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

/** 解析小时与分钟输入。countup 且未启用目标时返回 null 值表示不限时。 */
internal fun parseTarget(hours: String, minutes: String, mode: TimerMode, enabled: Boolean): Result<Int?> {
    if (mode == TimerMode.COUNTUP && !enabled) return Result.success(null)
    val hourText = hours.trim()
    val minuteText = minutes.trim()
    val hourValue = if (hourText.isEmpty()) 0 else hourText.toIntOrNull()
    val minuteValue = if (minuteText.isEmpty()) 0 else minuteText.toIntOrNull()
    if (hourValue == null || minuteValue == null || hourValue !in 0..UiLimits.MAX_HOURS || minuteValue !in 0 until UiLimits.MINUTES_PER_HOUR.toInt()) {
        return Result.failure(IllegalArgumentException("小时须为 0–${UiLimits.MAX_HOURS}，分钟须为 0–${UiLimits.MINUTES_PER_HOUR - 1}，且均为整数。"))
    }
    val total = hourValue * UiLimits.MINUTES_PER_HOUR.toInt() + minuteValue
    if (total < UiLimits.MIN_TIMER) {
        return Result.failure(IllegalArgumentException(
            if (mode == TimerMode.COUNTUP) "目标时长至少为 1 分钟；不想设目标请点「取消目标」。" else "专注时长至少为 1 分钟。"))
    }
    return Result.success(total)
}

/**
 * 自绘滚轮选择器：LazyColumn + 吸附滚动，上下各留一行空白，居中行即为当前值。
 * 只能选出 0 until count 的整数，不存在非法文本输入。
 */
@Composable
private fun WheelPicker(count: Int, value: Int, unit: String, description: String, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val rowHeight = 44.dp
    val density = LocalDensity.current
    val rowPx = with(density) { rowHeight.toPx() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value.coerceIn(0, count - 1))
    val centered by remember {
        derivedStateOf {
            (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0).coerceIn(0, count - 1)
        }
    }
    // 居中行一变就回报，不等滚动停止，避免滑动未停时保存到旧值；外部改值（如快捷时长）时把滚轮滚到对应行。
    LaunchedEffect(state) {
        snapshotFlow { centered }.collect(onChange)
    }
    LaunchedEffect(value) {
        val target = value.coerceIn(0, count - 1)
        if (!state.isScrollInProgress && target != centered) state.animateScrollToItem(target)
    }
    Box(modifier.height(rowHeight * 3), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(rowHeight).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF3EC)))
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = rowHeight),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$description：$value $unit" }
        ) {
            items(count) { index ->
                val selected = index == centered
                Box(Modifier.fillMaxWidth().height(rowHeight), contentAlignment = Alignment.Center) {
                    Text(
                        index.toString().padStart(2, '0'),
                        fontSize = if (selected) 26.sp else 20.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Accent else Color(0xFFB0A8A2)
                    )
                }
            }
        }
    }
}

@Composable
private fun DurationWheels(hours: Int, minutes: Int, onHours: (Int) -> Unit, onMinutes: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WheelPicker(UiLimits.MAX_HOURS + 1, hours, "小时", "小时", onHours, Modifier.weight(1f))
        Text("小时", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        WheelPicker(UiLimits.MINUTES_PER_HOUR.toInt(), minutes, "分钟", "分钟", onMinutes, Modifier.weight(1f))
        Text("分钟", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ModeSelector(mode: TimerMode, onSelect: (TimerMode) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        TimerMode.entries.forEach { value ->
            val selected = mode == value
            Column(
                Modifier.weight(1f)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) onSelect(value) })
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(modeName(value), color = if (selected) Accent else Muted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.height(3.dp).width(40.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (selected) Accent else Color.Transparent))
            }
        }
    }
}

/** 新建/编辑项目的草稿，按项目（新建为 null）分别保留；仅驻留内存，进程重启即丢弃。 */
internal data class ProjectDraft(
    val title: String,
    val category: String,
    val mode: TimerMode,
    val countdownHours: Int,
    val countdownMinutes: Int,
    val countupHours: Int,
    val countupMinutes: Int,
    val targetEnabled: Boolean
)

internal object ProjectDrafts {
    private val drafts = mutableMapOf<String, ProjectDraft>()
    private fun key(id: String?) = id ?: "__new__"
    fun get(id: String?): ProjectDraft? = drafts[key(id)]
    fun put(id: String?, draft: ProjectDraft) { drafts[key(id)] = draft }
    fun remove(id: String?) { drafts.remove(key(id)) }
    fun clear() = drafts.clear()
}

private fun initialDraft(project: Project?): ProjectDraft {
    val target = project?.targetMinutes
    val hours = target?.let { it / UiLimits.MINUTES_PER_HOUR.toInt() } ?: 0
    val minutes = target?.let { it % UiLimits.MINUTES_PER_HOUR.toInt() } ?: UiLimits.MIN_PRESET
    val countdown = project?.timerMode == TimerMode.COUNTDOWN && target != null
    val countup = project?.timerMode == TimerMode.COUNTUP && target != null
    return ProjectDraft(
        title = project?.title.orEmpty(),
        category = project?.category ?: UiLimits.CATEGORIES.first(),
        mode = project?.timerMode ?: TimerMode.COUNTDOWN,
        countdownHours = if (countdown) hours else 0,
        countdownMinutes = if (countdown) minutes else UiLimits.MIN_PRESET,
        countupHours = if (countup) hours else 0,
        countupMinutes = if (countup) minutes else UiLimits.MIN_PRESET,
        // 新建正计时默认不设目标；编辑时沿用项目已有设置。
        targetEnabled = countup
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ProjectEditorDialog(
    project: Project?,
    busy: Boolean,
    /** 只调整时长：从计时页「调整」进入时隐藏名称、分类与模式，避免多余步骤。 */
    durationOnly: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Project) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(project?.id) { mutableStateOf(ProjectDrafts.get(project?.id) ?: initialDraft(project)) }
    var error by remember { mutableStateOf<String?>(null) }
    // 关闭、Esc、遮罩、下拖都不丢草稿；保存成功后由调用方清除。
    fun edit(block: (ProjectDraft) -> ProjectDraft) {
        draft = block(draft)
        ProjectDrafts.put(project?.id, draft)
        error = null
    }
    val countup = draft.mode == TimerMode.COUNTUP
    val hours = if (countup) draft.countupHours else draft.countdownHours
    val minutes = if (countup) draft.countupMinutes else draft.countdownMinutes
    val unlimited = countup && !draft.targetEnabled

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState, containerColor = Color.White) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                when { durationOnly -> "调整时长"; project == null -> "新建学习项目"; else -> "项目设置" },
                fontSize = 19.sp, fontWeight = FontWeight.SemiBold
            )
            if (durationOnly) Text(draft.title, color = Muted, fontSize = 13.sp)
            else {
                OutlinedTextField(
                    value = draft.title, onValueChange = { if (it.length <= UiLimits.MAX_TITLE) edit { d -> d.copy(title = it) } },
                    label = { Text("项目名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("分类", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiLimits.CATEGORIES.forEach { value ->
                        FilterChip(selected = draft.category == value, onClick = { edit { d -> d.copy(category = value) } }, label = { Text(value, fontSize = 12.sp) })
                    }
                }
                // 模式切换保留各模式的时长设置。
                ModeSelector(draft.mode) { value -> edit { d -> d.copy(mode = value) } }
            }
            if (unlimited) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("不限时", fontSize = 30.sp, fontWeight = FontWeight.Light)
                    Text("按自己的节奏，手动结束", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { edit { d -> d.copy(targetEnabled = true) } }) { Text("设置目标时长") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (countup) "目标时长" else "专注时长", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(targetText(hours * UiLimits.MINUTES_PER_HOUR.toInt() + minutes), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (countup) TextButton(onClick = { edit { d -> d.copy(targetEnabled = false) } }) { Text("取消目标") }
                }
                DurationWheels(
                    hours, minutes,
                    onHours = { value -> edit { d -> if (countup) d.copy(countupHours = value) else d.copy(countdownHours = value) } },
                    onMinutes = { value -> edit { d -> if (countup) d.copy(countupMinutes = value) else d.copy(countdownMinutes = value) } }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiLimits.PRESETS.forEach { value ->
                        OutlinedButton(
                            onClick = {
                                edit { d ->
                                    val h = value / UiLimits.MINUTES_PER_HOUR.toInt()
                                    val m = value % UiLimits.MINUTES_PER_HOUR.toInt()
                                    if (countup) d.copy(countupHours = h, countupMinutes = m) else d.copy(countdownHours = h, countdownMinutes = m)
                                }
                            },
                            contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f)
                        ) { Text("$value", fontSize = 12.sp) }
                    }
                }
                Text("合计至少 1 分钟，最多 ${UiLimits.MAX_HOURS} 小时 ${UiLimits.MINUTES_PER_HOUR - 1} 分钟。", color = Muted, fontSize = 11.sp)
            }
            Text(if (countup) "达到目标只提示，计时继续；手动结束后保存实际时长。" else "到零自动完成；暂停和未完成计时不计入统计。", color = Muted, fontSize = 12.sp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    enabled = !busy, modifier = Modifier.weight(1f),
                    onClick = {
                        if (draft.title.isBlank()) { error = "请输入项目名称。"; return@Button }
                        parseTarget(hours.toString(), minutes.toString(), draft.mode, draft.targetEnabled)
                            .onFailure { error = it.message }
                            .onSuccess { onSave(Project(project?.id ?: UUID.randomUUID().toString(), draft.title.trim(), draft.category, draft.mode, it)) }
                    }
                ) { Text(if (busy) "保存中" else if (durationOnly) "保存时长" else "保存项目") }
            }
        }
    }
}

private val TODO_GUIDE_SECTIONS = listOf(
    "拆成小步" to "点进度展开，每一步整行都能勾选。",
    "删除与撤销" to "左滑后点删除，可在底部撤销；关闭撤销提示后失效。",
    "回看与重做" to "已完成按月查看，搜索覆盖全部时间；重做请到详情中「重新打开」。",
    "暂时放下" to "放下不是删除，需要时可以找回。"
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun TodoGuideSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maximumHeight = with(LocalDensity.current) { windowHeight.toDp() } * UiLimits.TODO_GUIDE_HEIGHT_FRACTION
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), dragHandle = null) {
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("待办怎么用", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭使用指南", tint = Muted, modifier = Modifier.size(20.dp)) }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                TODO_GUIDE_SECTIONS.forEach { (title, detail) ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("知道了")
            }
        }
    }
}

internal data class TodoDraft(val value: Todo, val stepsOpen: Boolean)

internal object TodoDrafts {
    private val drafts = mutableMapOf<String?, TodoDraft>()
    fun open(todo: Todo?): TodoDraft {
        val draft = drafts[todo?.id] ?: TodoDraft(
            todo ?: Todo(UUID.randomUUID().toString(), "", UiLimits.CATEGORIES.first()),
            todo?.steps?.isNotEmpty() == true
        )
        return sync(draft, todo).also { put(todo?.id, it) }
    }
    fun sync(draft: TodoDraft, current: Todo?): TodoDraft {
        if (current == null) return draft
        val titles = draft.value.steps.associateBy { it.id }
        val stored = current.steps.associateBy { it.id }
        val steps = if (current.done) current.steps.map { it.copy(title = titles[it.id]?.title ?: it.title) }
        else draft.value.steps.map { it.copy(done = stored[it.id]?.done ?: false) }
        return draft.copy(value = current.copy(
            title = draft.value.title, category = draft.value.category, important = draft.value.important, steps = steps
        ))
    }
    fun put(id: String?, draft: TodoDraft) { drafts[id] = draft }
    fun remove(id: String?) { drafts.remove(id) }
    fun clear() = drafts.clear()
    fun removeMissing(ids: Set<String>) { drafts.keys.removeAll { it != null && it !in ids } }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
internal fun TodoEditorDialog(
    todo: Todo?,
    busy: Boolean,
    onDismiss: () -> Unit,
    /** 删除与找回保留在编辑弹层，删除直接回调，由清单提供撤销。 */
    onDelete: (Todo) -> Unit,
    onRestore: (Todo) -> Unit,
    onSave: (Todo) -> Unit
) {
    // 草稿按待办保留在进程内存，所有关闭方式均保留；保存成功后由调用方清除。
    var draft by remember(todo?.id) { mutableStateOf(TodoDrafts.open(todo)) }
    var error by remember(todo?.id) { mutableStateOf<String?>(null) }
    var focusStep by remember(todo?.id) { mutableStateOf<String?>(null) }
    val titleFocus = remember(todo?.id) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentBusy }
    )
    val locked = todo?.done == true
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    fun edit(block: (TodoDraft) -> TodoDraft) {
        if (busy) return
        draft = block(draft)
        TodoDrafts.put(todo?.id, draft)
        error = null
    }
    fun addStep() {
        if (busy || locked || draft.value.steps.size >= Validation.MAX_STEPS) return
        val step = TodoStep(UUID.randomUUID().toString(), "")
        edit { it.copy(value = it.value.copy(steps = it.value.steps + step), stepsOpen = true) }
        focusStep = step.id
    }
    LaunchedEffect(todo) {
        draft = TodoDrafts.sync(draft, todo)
        TodoDrafts.put(todo?.id, draft)
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState,
        containerColor = Color.White, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), dragHandle = null
    ) {
        LaunchedEffect(titleFocus) { titleFocus.requestFocus() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消", color = Muted) }
                Text(
                    if (todo == null) "记下一件事" else "编辑待办",
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Button(enabled = !busy, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), onClick = {
                    val value = TodoDrafts.sync(draft, todo).value
                    val trimmed = value.steps.map { it.copy(title = it.title.trim()) }
                    when {
                        value.title.isBlank() -> {
                            error = "请输入待办名称。"
                            titleFocus.requestFocus()
                        }
                        trimmed.any { it.title.isBlank() } -> {
                            edit { it.copy(stepsOpen = true) }
                            focusStep = trimmed.first { it.title.isBlank() }.id
                            error = if (locked) "每个小步都要有名字。" else "每个小步都要有名字，或删掉它。"
                        }
                        else -> {
                            // 提交编辑字段，完成状态与时间由真实待办及领域保存规则决定。
                            onSave(value.copy(title = value.title.trim(), steps = trimmed))
                        }
                    }
                }) { Text(if (busy) "保存中" else if (todo == null) "添加待办" else "保存修改") }
            }
            HorizontalDivider(color = Line)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = draft.value.title,
                    onValueChange = { value -> if (value.length <= UiLimits.MAX_TITLE) edit { it.copy(value = it.value.copy(title = value)) } },
                    enabled = !busy, label = { Text("想做什么？") }, placeholder = { Text("从一件具体的小事开始") },
                    singleLine = true, isError = error != null && draft.value.title.isBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line,
                        focusedLabelColor = AccentDark, unfocusedLabelColor = Muted, cursorColor = Accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiLimits.CATEGORIES.forEach { category ->
                        FilterChip(
                            selected = draft.value.category == category, enabled = !busy,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(11.dp),
                            colors = FilterChipDefaults.filterChipColors(containerColor = Color.White, labelColor = Muted,
                                selectedContainerColor = AccentSoft, selectedLabelColor = AccentDark),
                            border = FilterChipDefaults.filterChipBorder(enabled = !busy, selected = draft.value.category == category,
                                borderColor = Line, selectedBorderColor = Accent),
                            onClick = { edit { it.copy(value = it.value.copy(category = category)) } },
                            label = { Text(category, fontSize = 12.sp) }
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = draft.value.important, enabled = !busy, role = Role.Checkbox,
                        onValueChange = { value -> edit { it.copy(value = it.value.copy(important = value)) } }
                    ), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Checkbox(checked = draft.value.important, onCheckedChange = null, enabled = !busy, modifier = Modifier.size(24.dp))
                    Text("标为重要，放在最前面", modifier = Modifier.weight(1f), fontSize = 13.sp)
                }
                HorizontalDivider(color = Line)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) {
                        edit { it.copy(stepsOpen = !it.stepsOpen) }
                    }, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "拆成小步 · ${if (draft.value.steps.isEmpty()) "可选" else "${draft.value.steps.size} 步"}",
                        modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium
                    )
                    Text(if (draft.stepsOpen) "收起" else "展开", color = Muted, fontSize = 12.sp)
                }
                if (draft.stepsOpen) {
                    draft.value.steps.forEachIndexed { index, step ->
                        key(step.id) {
                            val requester = remember { FocusRequester() }
                            val bringIntoView = remember { BringIntoViewRequester() }
                            var focused by remember { mutableStateOf(false) }
                            LaunchedEffect(focusStep) {
                                if (focusStep == step.id) {
                                    withFrameNanos { }
                                    requester.requestFocus()
                                    focusStep = null
                                }
                            }
                            LaunchedEffect(focused, imeBottom) {
                                if (focused) bringIntoView.bringIntoView()
                            }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).bringIntoViewRequester(bringIntoView),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("${index + 1}", color = Muted, fontSize = 13.sp)
                                BasicTextField(
                                    value = step.title, enabled = !busy, singleLine = true,
                                    onValueChange = { value ->
                                        if (value.length <= Validation.MAX_STEP_TITLE) edit { d ->
                                            d.copy(value = d.value.copy(steps = d.value.steps.map { if (it.id == step.id) it.copy(title = value) else it }))
                                        }
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(requester)
                                        .onFocusChanged { focused = it.isFocused }
                                        .semantics { contentDescription = "第 ${index + 1} 步" },
                                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                                    cursorBrush = SolidColor(Accent),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = {
                                        val next = draft.value.steps.getOrNull(index + 1)
                                        if (next != null) focusStep = next.id
                                        else if (!locked && draft.value.steps.size < Validation.MAX_STEPS) addStep()
                                        else focusManager.clearFocus()
                                    }),
                                    decorationBox = { input ->
                                        Box(
                                            Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, Line, RoundedCornerShape(10.dp))
                                                .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (step.title.isEmpty()) Text("写下一个小步", color = Muted, fontSize = 15.sp)
                                            input()
                                        }
                                    }
                                )
                                if (!locked) IconButton(
                                    enabled = !busy, modifier = Modifier.size(48.dp),
                                    onClick = { edit { d -> d.copy(value = d.value.copy(steps = d.value.steps.filterNot { it.id == step.id })) } }
                                ) { Icon(Icons.Outlined.DeleteOutline, "删除第 ${index + 1} 步", tint = Muted) }
                            }
                        }
                    }
                    if (locked) Text("已完成的步骤仅可改名。需要重做时，请在详情中选择「重新打开」。", color = Muted, fontSize = 12.sp)
                    else TextButton(
                        enabled = !busy && draft.value.steps.size < Validation.MAX_STEPS,
                        onClick = ::addStep, modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(if (draft.value.steps.size >= Validation.MAX_STEPS) "最多 ${Validation.MAX_STEPS} 步" else "加一步") }
                }
                if (todo != null) {
                    HorizontalDivider(color = Line)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (todo.archived) TextButton(enabled = !busy, onClick = { onRestore(todo) }) { Text("找回") }
                        TextButton(enabled = !busy, onClick = { onDelete(todo) }) { Text("删除待办", color = AccentDark) }
                    }
                }
            }
            HorizontalDivider(color = Line)
            Text("关闭保留草稿，保存后清除。", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TodoDetailsDialog(
    todo: Todo,
    busy: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onReopen: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onImportant: () -> Unit,
    onArchive: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text(todo.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${todo.category} · ${when { todo.archived -> "已放下"; todo.done -> "已完成"; else -> "待完成" }}", color = Muted)
                if (todo.done) {
                    Text(
                        if (TodoHistory.completedDate(todo) == null) "这条历史没有记录完成时间。"
                        else "完成于 ${timestampText(requireNotNull(todo.completedAt))}", color = Muted, fontSize = 13.sp
                    )
                }
                todo.steps.forEach { step ->
                    Text("${if (step.done) "已走过" else "还没走"} · ${step.title}", modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !busy, onClick = onEdit) { Text("编辑待办") }
                    when {
                        todo.archived -> TextButton(enabled = !busy, onClick = onRestore) { Text("找回") }
                        todo.done -> TextButton(enabled = !busy, onClick = onReopen) { Text("重新打开") }
                        else -> {
                            TextButton(enabled = !busy, onClick = onImportant) { Text(if (todo.important) "取消重要" else "标为重要") }
                            TextButton(enabled = !busy, onClick = onArchive) { Text("放下") }
                        }
                    }
                    TextButton(enabled = !busy, onClick = onDelete) { Text("删除待办", color = AccentDark) }
                }
                Text("也可以左滑卡片，再点击删除。删除后可在底部撤销；关闭提示或应用进程结束后，撤销记录不再保留。", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
internal fun TodoReopenDialog(todo: Todo, busy: Boolean, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    var selected by remember(todo.id) { mutableStateOf(emptySet<String>()) }
    val validSelection = selected.intersect(todo.steps.map { it.id }.toSet())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text("重新打开") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(todo.title, fontWeight = FontWeight.Medium)
                Text(if (todo.steps.isEmpty()) "确认后，这件事会回到待完成清单。" else "选择要重做的小步，其余已走过的进度会保留。", color = Muted)
                todo.steps.forEach { step ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = step.id in validSelection, enabled = !busy, role = Role.Checkbox,
                            onValueChange = { checked -> selected = if (checked) selected + step.id else selected - step.id }
                        ).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(checked = step.id in validSelection, onCheckedChange = null, enabled = !busy)
                        Text(step.title, modifier = Modifier.weight(1f))
                    }
                }
                if (todo.steps.isNotEmpty() && validSelection.isEmpty()) Text("请至少选择一个需要重做的小步。", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (todo.steps.isEmpty() || validSelection.isNotEmpty()), onClick = { onConfirm(validSelection) }) {
                Text(if (busy) "处理中" else "确认重新打开")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun TodoMonthDialog(
    month: YearMonth,
    months: List<TodoMonthCount>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (YearMonth) -> Unit,
    /** 待办历史与日记共用：计数单位与读屏描述可替换。 */
    unit: String = "件",
    describe: (Int) -> String = { "完成 $it 件" }
) {
    var year by remember(month, today.year) { mutableIntStateOf(month.year.coerceIn(TodoUiLimits.MIN_YEAR, today.year)) }
    var choosingYear by remember { mutableStateOf(false) }
    val counts = remember(months) { months.associate { it.month to it.count } }
    val currentMonth = YearMonth.from(today)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color.White,
        title = { Text("翻到哪一月？") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { choosingYear = true }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("选择年份 · $year 年") }
                    DropdownMenu(expanded = choosingYear, onDismissRequest = { choosingYear = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        (today.year downTo TodoUiLimits.MIN_YEAR).forEach { value ->
                            DropdownMenuItem(text = { Text("$value 年", color = if (value == year) Accent else MaterialTheme.colorScheme.onSurface) },
                                onClick = { year = value; choosingYear = false })
                        }
                    }
                }
                java.time.Month.values().toList().chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { value ->
                            val target = YearMonth.of(year, value)
                            val future = target > currentMonth
                            val selected = target == month
                            val count = counts[target] ?: 0
                            OutlinedButton(
                                enabled = !future, onClick = { onSelect(target) },
                                modifier = Modifier.weight(1f).heightIn(min = 64.dp).semantics {
                                    contentDescription = "${year}年${value.value}月，${if (future) "尚未到来" else describe(count)}"
                                },
                                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp),
                                border = BorderStroke(1.dp, if (selected) Accent else Line),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) AccentSoft else Color.White,
                                    contentColor = if (selected) AccentDark else MaterialTheme.colorScheme.onSurface)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${value.value} 月", fontSize = 14.sp)
                                    Text(if (future) "—" else if (count > 0) "$count $unit" else "暂无记录", fontSize = 10.sp,
                                        color = if (future) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else Muted)
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { onSelect(currentMonth) }, modifier = Modifier.align(Alignment.End).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line)) { Text("回到本月", fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

internal object ProgressDrafts {
    private const val FILE = "progress-drafts"
    fun note(context: Context, id: String): String? = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("$id:note", null)
    fun percent(context: Context, id: String): String? = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("$id:percent", null)
    fun put(context: Context, id: String, note: String, percent: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("$id:note", note).putString("$id:percent", percent).apply()
    }
    fun remove(context: Context, id: String) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove("$id:note").remove("$id:percent").apply() }
    fun clear(context: Context) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply() }
}

@Composable
internal fun ProgressEditorDialog(context: Context, session: FocusSession, previous: ProgressEntry?, busy: Boolean, onDismiss: () -> Unit, onSave: (String, Int?) -> Unit) {
    var note by rememberSaveable(session.id) { mutableStateOf(ProgressDrafts.note(context, session.id) ?: previous?.note.orEmpty()) }
    var percent by rememberSaveable(session.id) { mutableStateOf(ProgressDrafts.percent(context, session.id) ?: previous?.percent?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("这一段，推进了什么？") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${session.projectTitle}\n有效专注 ${durationText(session.durationSeconds)}，时长已保存。", color = Muted, fontSize = 13.sp)
            OutlinedTextField(value = note, onValueChange = {
                if (it.length <= UiLimits.MAX_NOTE) { note = it; ProgressDrafts.put(context, session.id, note, percent) }
            }, label = { Text("目前学到哪里了？") }, minLines = 4, maxLines = 7, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = percent, onValueChange = { percent = it; ProgressDrafts.put(context, session.id, note, percent) }, label = { Text("完成度 0–100%（可选）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Text("可稍后补写，草稿保留在本机。旧记录补写不会覆盖新一次的当前进度。", color = Muted, fontSize = 12.sp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val value = percent.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        when {
            note.isBlank() -> error = "请填写学习进度。"
            percent.isNotBlank() && (value == null || value !in 0..UiLimits.MAX_PERCENT) -> error = "完成度应为 0–100 的整数。"
            else -> onSave(note.trim(), value)
        }
    }) { Text(if (busy) "保存中" else "保存进度") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("稍后再写") } })
}

@Composable
internal fun BarChart(values: List<Long>, labels: List<String>, description: String) {
    val color = MaterialTheme.colorScheme.primary
    val maximum = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column {
        Canvas(Modifier.fillMaxWidth().height(125.dp).semantics { contentDescription = description }) {
            val line = Color(0xFFEEEEEE)
            repeat(4) { index -> val y = size.height * index / 3f; drawLine(line, Offset(0f, y), Offset(size.width, y)) }
            if (values.isNotEmpty()) {
                val cell = size.width / values.size
                values.forEachIndexed { index, value ->
                    val height = (size.height - 8.dp.toPx()) * value.toFloat() / maximum
                    drawRoundRect(if (value == maximum) color else Color(0xFFBBBBBB), Offset(index * cell + cell * 0.16f, size.height - height.coerceAtLeast(1f)), Size(cell * 0.68f, height.coerceAtLeast(1f)), CornerRadius(3.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) { labels.forEach { Text(it, fontSize = 10.sp, color = Muted) } }
    }
}
