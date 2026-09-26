package com.focusassistant.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    const val MIN_TIMER = 1
    const val MAX_TIMER = 120
    const val MAX_TITLE = 80
    const val MAX_NOTE = 2000
    const val MAX_PERCENT = 100
    const val RECORD_PAGE = 20
    val CATEGORIES = listOf("个人成长", "工作", "生活")
    val PRESETS = listOf(15, 25, 45, 60)
}

internal val Accent = Color(0xFFC44E22)
internal val SoftSurface = Color(0xFFF5F5F5)
internal val Muted = Color(0xFF626262)

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
internal fun projectSubtitle(project: Project) = "${modeName(project.timerMode)} · ${if (project.timerMode == TimerMode.COUNTUP) "目标 " else ""}${project.targetMinutes} 分钟"
internal fun clockText(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(Locale.ROOT, safe / UiLimits.SECONDS_PER_MINUTE, safe % UiLimits.SECONDS_PER_MINUTE)
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

@Composable
internal fun ProjectEditorDialog(project: Project?, busy: Boolean, onDismiss: () -> Unit, onSave: (Project) -> Unit) {
    var title by rememberSaveable(project?.id, project?.title) { mutableStateOf(project?.title.orEmpty()) }
    var category by rememberSaveable(project?.id, project?.category) { mutableStateOf(project?.category ?: UiLimits.CATEGORIES.first()) }
    var mode by rememberSaveable(project?.id, project?.timerMode) { mutableStateOf(project?.timerMode?.name ?: TimerMode.COUNTDOWN.name) }
    var minutes by rememberSaveable(project?.id, project?.targetMinutes) { mutableStateOf((project?.targetMinutes ?: 25).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (project == null) "新建学习项目" else "项目设置") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = title, onValueChange = { if (it.length <= UiLimits.MAX_TITLE) title = it }, label = { Text("项目名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("分类", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                UiLimits.CATEGORIES.forEach { value -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value, fontSize = 11.sp) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimerMode.entries.forEach { value -> FilterChip(selected = mode == value.name, onClick = { mode = value.name }, label = { Text(modeName(value)) }) }
            }
            OutlinedTextField(value = minutes, onValueChange = { minutes = it }, label = { Text(if (mode == TimerMode.COUNTUP.name) "目标时长（分钟）" else "倒计时时长（分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                UiLimits.PRESETS.forEach { value -> OutlinedButton(onClick = { minutes = value.toString() }, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f)) { Text("$value", fontSize = 12.sp) } }
            }
            Text(if (mode == TimerMode.COUNTUP.name) "达到目标只提示，计时继续；手动结束后保存实际时长。" else "到零自动完成；暂停和未完成计时不计入统计。", color = Muted, fontSize = 12.sp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            val duration = minutes.toIntOrNull()
            when {
                title.isBlank() -> error = "请输入项目名称。"
                duration == null || duration !in UiLimits.MIN_TIMER..UiLimits.MAX_TIMER -> error = "请输入 1–120 的整数分钟。"
                else -> onSave(Project(project?.id ?: UUID.randomUUID().toString(), title.trim(), category, TimerMode.valueOf(mode), duration))
            }
        }) { Text(if (busy) "保存中" else "保存项目") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun TodoEditorDialog(todo: Todo?, busy: Boolean, onDismiss: () -> Unit, onSave: (Todo) -> Unit) {
    var title by rememberSaveable(todo?.id) { mutableStateOf(todo?.title.orEmpty()) }
    var category by rememberSaveable(todo?.id) { mutableStateOf(todo?.category ?: UiLimits.CATEGORIES.first()) }
    var important by rememberSaveable(todo?.id) { mutableStateOf(todo?.important ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (todo == null) "添加待办" else "编辑待办") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = title, onValueChange = { if (it.length <= UiLimits.MAX_TITLE) title = it }, label = { Text("想完成什么？") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                UiLimits.CATEGORIES.forEach { value -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value, fontSize = 11.sp) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = important, onCheckedChange = { important = it }); Text("重要事项") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        if (title.isBlank()) error = "请输入待办名称。"
        else onSave(Todo(todo?.id ?: UUID.randomUUID().toString(), title.trim(), category, important, todo?.done ?: false, todo?.estimate ?: 1))
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
