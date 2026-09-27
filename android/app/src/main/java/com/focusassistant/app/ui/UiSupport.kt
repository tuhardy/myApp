package com.focusassistant.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusassistant.app.domain.*
import java.time.Instant
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
    /** 与待办分组共用一份分类顺序，避免编辑器与分组各自漂移。 */
    val CATEGORIES = TodoGrouping.CATEGORIES
    val PRESETS = listOf(15, 25, 45, 60)
    const val MIN_PRESET = 25
}

/** 待办页签：放下的事只在「放下的」里出现，可以找回。 */
internal enum class TodoFilter { ALL, PENDING, DONE, ARCHIVED }

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

@Composable
internal fun TodoEditorDialog(
    todo: Todo?,
    busy: Boolean,
    onDismiss: () -> Unit,
    /** 删除与找回和原型一样放在编辑弹窗里，列表卡片不再堆按钮。 */
    onDelete: (Todo) -> Unit,
    onRestore: (Todo) -> Unit,
    onSave: (Todo) -> Unit
) {
    var title by rememberSaveable(todo?.id) { mutableStateOf(todo?.title.orEmpty()) }
    var category by rememberSaveable(todo?.id) { mutableStateOf(todo?.category ?: UiLimits.CATEGORIES.first()) }
    var important by rememberSaveable(todo?.id) { mutableStateOf(todo?.important ?: false) }
    // 小步草稿只活在弹窗里，取消即丢弃。保留原有的走过状态。
    val steps = remember(todo?.id) { mutableStateListOf<TodoStep>().apply { addAll(todo?.steps.orEmpty()) } }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (todo == null) "添加待办" else "编辑待办") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = title, onValueChange = { if (it.length <= UiLimits.MAX_TITLE) title = it }, label = { Text("想完成什么？") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                UiLimits.CATEGORIES.forEach { value -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value, fontSize = 11.sp) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = important, onCheckedChange = { important = it }); Text("重要事项") }
            Text("拆成小步（可选，最多 ${Validation.MAX_STEPS} 步）", color = Muted, fontSize = 12.sp)
            steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = step.title,
                        onValueChange = { if (it.length <= Validation.MAX_STEP_TITLE) { steps[index] = step.copy(title = it); error = null } },
                        label = { Text("第 ${index + 1} 步") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { steps.removeAt(index); error = null }) { Icon(Icons.Outlined.DeleteOutline, "删除第 ${index + 1} 步", tint = Muted) }
                }
            }
            if (steps.size < Validation.MAX_STEPS) {
                TextButton(onClick = { steps.add(TodoStep(UUID.randomUUID().toString(), "")); error = null }) { Text("加一步") }
            }
            if (todo != null) {
                HorizontalDivider(color = Line)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (todo.archived) OutlinedButton(enabled = !busy, onClick = { onRestore(todo) }) { Text("找回", fontSize = 12.sp) }
                    OutlinedButton(enabled = !busy, onClick = { onDelete(todo) }) { Text("删除", fontSize = 12.sp, color = AccentDark) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val trimmed = steps.map { it.copy(title = it.title.trim()) }
        if (title.isBlank()) error = "请输入待办名称。"
        else if (trimmed.any { it.title.isBlank() }) error = "每个小步都要有名字，或删掉它。"
        else onSave(Todo(todo?.id ?: UUID.randomUUID().toString(), title.trim(), category, important,
            // 有小步时父任务的完成状态由小步决定，避免保存后两者不一致。
            if (trimmed.isNotEmpty()) trimmed.all { it.done } else todo?.done ?: false,
            todo?.estimate ?: 1, todo?.createdAt.orEmpty(), trimmed, todo?.archived ?: false))
    }) { Text(if (busy) "保存中" else "保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
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
