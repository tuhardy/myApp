package com.focusassistant.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import java.time.YearMonth
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusassistant.app.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val HOURS_PER_DAY = 24

@Composable
internal fun ScreenBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Caption(text: String) {
    Text(text, color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EmptyMessage(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 已放下的事不显示发酵状态，已完成的也不再发酵。 */
private fun todoAgeLabel(todo: Todo, age: TodoAge): String? = when {
    todo.archived -> "已放下"
    todo.done -> null
    else -> age.label
}

/** L：躺得越久底色越浅，正文与操作始终保持可读，不降低整卡透明度。 */
private fun todoBackground(todo: Todo, age: TodoAge): Color = when {
    todo.done || todo.archived -> Color.White
    age.stage == TodoStage.RESTING -> Color(0xFFFDFDFD)
    age.stage == TodoStage.STALE -> Color(0xFFFAFAFA)
    else -> Color.White
}

/** 卡片描边随发酵变浅，对应原型的 age-resting / age-stale。 */
private fun todoBorder(todo: Todo, age: TodoAge): Color = when {
    todo.done || todo.archived -> Line
    age.stage == TodoStage.RESTING -> Color(0xFFEFEFEF)
    age.stage == TodoStage.STALE -> Color(0xFFF3F3F3)
    else -> TodoAccentBorder
}

/**
 * 组标题已说明重要或分类时，卡片不重复同一个标签；重要组补回原分类。
 * 返回「标签文字 to 是否用重要样式」，顺序与原型一致：重要在前，分类在后。
 */
private fun todoMetaTags(todo: Todo, groupKey: String): List<Pair<String, Boolean>> {
    val tags = mutableListOf<Pair<String, Boolean>>()
    if (todo.important && groupKey != TodoGrouping.IMPORTANT_KEY) tags += "重要" to true
    if (groupKey == TodoGrouping.IMPORTANT_KEY || groupKey != TodoGrouping.categoryKey(todo.category)) {
        todo.category.ifBlank { null }?.let { tags += it to false }
    }
    return tags
}

/** 原型 .task-card 的暖橙描边，「查看最近有记录的月份」按钮也用它。 */
private val TodoAccentBorder = Color(0xFFE8C4B5)

private fun todoListHeading(filter: TodoFilter): String = when (filter) {
    TodoFilter.DONE -> "走过的路，都算数"
    TodoFilter.ARCHIVED -> "暂时放下，也没关系"
    TodoFilter.PENDING -> "从眼前的一步开始"
}

@Composable
internal fun ProjectsScreen(
    state: AppState,
    today: LocalDate,
    onOpen: (Project) -> Unit,
    onCreate: () -> Unit,
    onStatistics: () -> Unit,
    onEdit: (Project) -> Unit,
    onDelete: (Project) -> Unit,
    onProgress: (FocusSession) -> Unit,
    onHistory: (Project) -> Unit,
    onResumeTimer: () -> Unit
) {
    val reduceMotion = rememberReduceMotion()
    // 「接着学」标在正在计时的项目上；没有计时时标在最近一次完成专注的项目上。
    val currentId = state.timer?.projectId ?: state.sessions.maxByOrNull { it.endedAt }?.projectId
    ScreenBody {
        Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(buildAnnotatedString { append("今天，接着学"); withStyle(SpanStyle(color = Accent)) { append(".") } },
                    fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = (-0.9).sp, fontWeight = FontWeight(650), modifier = Modifier.semantics { heading() })
                Text("${today.monthValue}月${today.dayOfMonth}日 ${weekdayLabel(today)}", fontSize = 12.sp, letterSpacing = 0.4.sp, lineHeight = 18.sp, color = Muted)
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White).border(1.dp, Line, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "查看专注统计", onClick = onStatistics)
                    .semantics { contentDescription = "查看专注统计" },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(18.dp)) {
                    val w = size.width
                    val h = size.height
                    drawLine(Muted, Offset(w * 4f / 24, h * 4f / 24), Offset(w * 4f / 24, h * 20f / 24), 1.5.dp.toPx(), StrokeCap.Round)
                    drawLine(Muted, Offset(w * 4f / 24, h * 20f / 24), Offset(w * 21f / 24, h * 20f / 24), 1.5.dp.toPx(), StrokeCap.Round)
                    drawLine(Muted, Offset(w * 9f / 24, h * 15f / 24), Offset(w * 9f / 24, h * 11f / 24), 1.5.dp.toPx(), StrokeCap.Round)
                    drawLine(Muted, Offset(w * 14f / 24, h * 15f / 24), Offset(w * 14f / 24, h * 6f / 24), 1.5.dp.toPx(), StrokeCap.Round)
                    drawLine(Muted, Offset(w * 19f / 24, h * 15f / 24), Offset(w * 19f / 24, h * 9f / 24), 1.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        state.timer?.let { timer ->
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onResumeTimer).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Timer, null, tint = Accent, modifier = Modifier.padding(end = 11.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (timer.phase == TimerPhase.FOCUS) timer.projectTitle else phaseName(timer.phase),
                            fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Caption(if (timer.status == TimerStatus.PAUSED) "已暂停 · 返回继续" else "正在专注 · 返回查看")
                    }
                    Icon(Icons.Outlined.ChevronRight, "返回计时", tint = Muted)
                }
            }
        }
        // 今日摘要只汇总本机真实完成记录，没有记录就显示 0 与空刻度尺。
        TodayPanel(Statistics.select(state.sessions, Period.DAY, today), today, reduceMotion)
        if (state.projects.isEmpty()) {
            EmptyMessage("为重要的事，留一段时间", "创建一个可反复使用的学习项目。从一次专注开始，慢慢积累自己的进度。")
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("创建第一个项目") }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("持续推进的小目标", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onCreate)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Add, null, tint = AccentDark, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("新建项目", fontSize = 12.sp, color = AccentDark)
                }
            }
            state.projects.forEach { project ->
                key(project.id) {
                    ProjectCard(project, state, project.id == currentId, onOpen, onEdit, onDelete, onProgress, onHistory)
                }
            }
        }
    }
}

/** 今天的两个数字 + 24 小时刻度尺：专注片段按真实起止时间落在尺上，已过去的时段铺浅色底。 */
@Composable
private fun TodayPanel(sessions: List<FocusSession>, today: LocalDate, reduceMotion: Boolean) {
    val zone = ZoneId.systemDefault()
    val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayMs = (dayEnd - dayStart).toFloat()
    val spans = remember(sessions, dayStart) {
        UsageInsights.focusSpans(sessions, dayStart, dayEnd)
    }
    val rise = remember(spans) { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(spans, reduceMotion) { if (reduceMotion) rise.snapTo(1f) else rise.animateTo(1f, tween(VisualLimits.RISE_MS)) }
    val nowFraction = ((System.currentTimeMillis() - dayStart) / dayMs).coerceIn(0f, 1f)
    val description = if (spans.isEmpty()) "今天还没有专注记录"
        else "今天的专注时段：" + spans.joinToString("，") { "${clockLabel(it.first)} 至 ${clockLabel(it.second)}" }
    Surface(color = AccentSoft, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("今天的小积累", color = AccentDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(top = 6.dp, bottom = 16.dp)) {
                BigFigure(trimDecimal(sessions.sumOf { it.durationSeconds } / UiLimits.SECONDS_PER_MINUTE.toDouble()), "分钟专注")
                Spacer(Modifier.width(28.dp))
                BigFigure(sessions.size.toString(), "次完成")
            }
            Box(Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = description }) {
                Canvas(Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    drawRect(RulerPast, size = androidx.compose.ui.geometry.Size(width * nowFraction, height))
                    for (hour in 0..HOURS_PER_DAY) {
                        val x = (width - 1f) * hour / HOURS_PER_DAY
                        val major = hour % 6 == 0
                        drawLine(if (major) RulerMajor else RulerMinor, Offset(x, height - (if (major) 11 else 5).dp.toPx()), Offset(x, height), 1.dp.toPx())
                    }
                    drawLine(RulerMajor, Offset(0f, height), Offset(width, height), 1.dp.toPx())
                    val top = 3.dp.toPx()
                    spans.forEach { (from, to) ->
                        val left = width * (from - dayStart) / dayMs
                        val barWidth = maxOf(3.dp.toPx(), width * (to - from) / dayMs)
                        val barHeight = (height - top) * rise.value
                        drawRoundRect(Accent, Offset(left, height - barHeight), androidx.compose.ui.geometry.Size(barWidth, barHeight),
                            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                    }
                    val x = width * nowFraction
                    drawLine(Ink, Offset(x, -3.dp.toPx()), Offset(x, height + 4.dp.toPx()), 1.5.dp.toPx())
                    drawCircle(Ink, 3.dp.toPx(), Offset(x, -3.dp.toPx()))
                }
                if (spans.isEmpty()) Text("今天还空着，从一个项目开始", color = AccentDark, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp))
            }
            PositionedLabels(listOf(0f to "0 时", 0.25f to "6", 0.5f to "12", 0.75f to "18", 1f to "24"), Modifier.padding(top = 4.dp), AccentDark)
        }
    }
}

@Composable
private fun BigFigure(value: String, unit: String) {
    Row {
        Text(value, fontSize = 46.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.92).sp, color = Ink,
            style = NumeralStyle, modifier = Modifier.alignByBaseline())
        Text(unit, fontSize = 12.sp, color = AccentDark, modifier = Modifier.alignByBaseline().padding(start = 6.dp))
    }
}

/** 项目卡片：左侧播放圆钮、标题旁状态、单行元信息、20 格完成度刻度。 */
@Composable
private fun ProjectCard(
    project: Project, state: AppState, current: Boolean,
    onOpen: (Project) -> Unit, onEdit: (Project) -> Unit, onDelete: (Project) -> Unit,
    onProgress: (FocusSession) -> Unit, onHistory: (Project) -> Unit
) {
    var expanded by rememberSaveable(project.id) { mutableStateOf(false) }
    val latest = Statistics.latestProgress(state.progress, project.id)
    val sessions = state.sessions.filter { it.projectId == project.id }.sortedByDescending { it.endedAt }
    val pending = sessions.filter { Statistics.latestForSession(state.progress, it.id) == null }
    val timer = state.timer?.takeIf { it.projectId == project.id && it.phase == TimerPhase.FOCUS }
    val status = when {
        timer?.status == TimerStatus.RUNNING -> "计时中"
        timer?.status == TimerStatus.PAUSED -> "已暂停"
        current -> "接着学"
        else -> null
    }
    val totalSeconds = sessions.sumOf { it.durationSeconds }
    val total = if (totalSeconds >= UiLimits.MINUTES_PER_HOUR * UiLimits.SECONDS_PER_MINUTE)
        "${trimDecimal(totalSeconds / (UiLimits.MINUTES_PER_HOUR * UiLimits.SECONDS_PER_MINUTE).toDouble())} 小时" else durationText(totalSeconds)
    val meta = listOfNotNull(projectSubtitle(project), "累计 $total", "${sessions.size} 次", pending.size.takeIf { it > 0 }?.let { "$it 条待补写" })
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (current || expanded) Color(0xFFE8C4B5) else Line)) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)).clickable { onOpen(project) }
                    .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (current) Accent else AccentSoft), contentAlignment = Alignment.Center) {
                        Icon(if (timer?.status == TimerStatus.RUNNING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null,
                            tint = if (current) Color.White else AccentDark, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(project.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                            if (status != null) StatusChip(status, paused = timer?.status == TimerStatus.PAUSED, running = timer?.status == TimerStatus.RUNNING, reduceMotion = rememberReduceMotion())
                        }
                        Text(meta.joinToString(" · "), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        latest?.percent?.let { percent ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TickGauge(percent, Modifier.weight(1f))
                                Text("$percent%", color = AccentDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起项目进度" else "展开项目进度", tint = Muted)
                }
            }
            if (expanded) {
                HorizontalDivider(color = Line)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("当前学习进度", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(latest?.percent?.let { "$it%" } ?: "未填写完成度", fontSize = 11.sp, color = Muted,
                            modifier = Modifier.border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    if (latest == null) Caption("还没有进度记录。完成一次专注后，记下学到了哪里、接下来做什么。")
                    else {
                        Text(latest.note, style = MaterialTheme.typography.bodyMedium)
                        Caption("专注结束：${timestampText(latest.sessionEndedAt)} 更新：${timestampText(latest.updatedAt)}")
                    }
                    pending.forEach { session ->
                        OutlinedButton(onClick = { onProgress(session) }, modifier = Modifier.fillMaxWidth()) {
                            Text("补写进度 · ${timestampText(session.endedAt)}", textAlign = TextAlign.Center)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onHistory(project) }) { Text("进度历史") }
                        if (latest != null) state.sessions.find { it.id == latest.sessionId }?.let { session ->
                            TextButton(onClick = { onProgress(session) }) { Text("编辑本次进度") }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onEdit(project) }) { Icon(Icons.Outlined.Settings, "项目设置") }
                        IconButton(onClick = { onDelete(project) }) { Icon(Icons.Outlined.DeleteOutline, "删除项目", tint = Muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, paused: Boolean, running: Boolean, reduceMotion: Boolean) {
    val breathe = if (running && !reduceMotion) {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "status-breathe")
        transition.animateFloat(1f, 0.35f, androidx.compose.animation.core.infiniteRepeatable(tween(800),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "status-dot").value
    } else 1f
    Row(Modifier.padding(start = 8.dp).clip(CircleShape).background(if (paused) SoftSurface else AccentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (running || paused) Box(Modifier.padding(end = 5.dp).size(6.dp).alpha(breathe).clip(CircleShape).background(if (paused) Muted else AccentDark))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (paused) Muted else AccentDark)
    }
}

private fun phaseName(phase: TimerPhase): String = when (phase) {
    TimerPhase.FOCUS -> "专注"
    TimerPhase.SHORT_BREAK -> "短休息"
    TimerPhase.LONG_BREAK -> "长休息"
}

@Composable
internal fun TimerScreen(
    project: Project?,
    timer: ActiveTimer?,
    nowElapsed: Long,
    onBack: () -> Unit,
    onStatistics: () -> Unit,
    onSettings: () -> Unit,
    onMode: (TimerMode) -> Unit,
    onDuration: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onFinishEarly: () -> Unit,
    onDiscard: () -> Unit,
    onShortBreak: () -> Unit,
    onLongBreak: () -> Unit
) {
    val resting = timer != null && timer.phase != TimerPhase.FOCUS
    val mode = timer?.timerMode ?: project?.timerMode ?: TimerMode.COUNTDOWN
    val targetMinutes = if (timer != null) timer.targetMinutes else project?.targetMinutes
    val targetSeconds = targetMinutes?.let { it * UiLimits.SECONDS_PER_MINUTE }
    val elapsedMillis = timer?.elapsedMs(nowElapsed) ?: 0L
    val elapsedSeconds = elapsedMillis / UiLimits.MILLIS_PER_SECOND
    val shownSeconds = if (mode == TimerMode.COUNTUP) elapsedSeconds else timer?.remainingSeconds(nowElapsed) ?: targetSeconds ?: 0L
    ScreenBody {
        PageTitle(if (resting) phaseName(timer!!.phase) else "专注计时", onBack, fontSize = 18.sp) {
            IconButton(onClick = onStatistics) { Icon(Icons.Outlined.TrendingUp, "专注统计") }
            if (project != null) IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "项目计时设置") }
        }
        if (project == null && timer == null) {
            EmptyMessage("先选择一个学习项目", "在专注首页创建或选择项目，即可开始计时。也可以先给自己一段休息。")
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回项目") }
        } else {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!resting) Text((timer?.category ?: project?.category).orEmpty(), color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
                Text(if (resting) "休息一下，再继续" else timer?.projectTitle ?: project!!.title,
                    fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            }
            // 计时中收起模式切换与时长入口，避免分心；只有待开始时可调整。
            if (!resting && project != null && timer == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TimerMode.entries.forEach { value ->
                        FilterChip(selected = mode == value, onClick = { if (mode != value) onMode(value) }, label = { Text(modeName(value)) }, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
                // 直接打开时长设置，不必先进入完整的项目设置。
                OutlinedButton(onClick = onDuration, modifier = Modifier.fillMaxWidth()) {
                    Text(targetText(targetMinutes), modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    Text(if (targetMinutes == null) "设置" else "调整", color = Accent, fontSize = 13.sp)
                }
            }
            val reduceMotion = rememberReduceMotion()
            val dialColor = if (resting) RestSlate else Accent
            val dialDark = if (resting) RestSlateDark else AccentDark
            val targetMillis = targetSeconds?.let { it * UiLimits.MILLIS_PER_SECOND }
            val progress = if (targetMillis == null || targetMillis <= 0L) 0f else (elapsedMillis.toFloat() / targetMillis).coerceIn(0f, 1f)
            // 倒计时显示剩余的弧段，正计时显示已走过的弧段；不限时没有弧段。
            val visible = when {
                targetMillis == null || targetMillis <= 0L -> 0f
                mode == TimerMode.COUNTUP && !resting -> progress
                else -> 1f - progress
            }
            val dialState = when { timer == null -> DialState.IDLE; timer.status == TimerStatus.PAUSED -> DialState.PAUSED; else -> DialState.RUNNING }
            val remainingMillis = targetMillis?.let { (it - elapsedMillis).coerceAtLeast(0L) } ?: 0L
            val caption = when {
                timer == null && targetMinutes == null -> "不限时，按自己的节奏结束"
                timer == null && mode == TimerMode.COUNTUP -> "从零累加 · ${targetText(targetMinutes)}目标后继续"
                timer == null -> "到时自动完成并记录"
                timer.status == TimerStatus.PAUSED -> "已暂停 · 暂停时间不计入"
                !resting && targetMinutes == null -> "已专注 · 手动结束"
                !resting && mode == TimerMode.COUNTUP -> if (timer.targetReached(nowElapsed)) "已达到目标，完成后点「结束并保存专注」"
                    else "还差 ${(remainingMillis + 59_999) / 60_000} 分钟达到目标"
                else -> "预计 ${clockLabel(System.currentTimeMillis() + remainingMillis)} 结束"
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                TimerDial(visible, dialState, dialColor, reduceMotion) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(if (timer == null) Color(0xFFB4ADA7) else dialColor))
                            Text(when {
                                timer == null -> "准备好，就开始"
                                timer.status == TimerStatus.PAUSED -> "已暂停"
                                resting -> "休息中"
                                else -> "正在专注"
                            }, color = dialDark, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                        val blink = if (dialState == DialState.PAUSED && !reduceMotion) {
                            rememberInfiniteTransition(label = "paused-blink").animateFloat(1f, 0.35f,
                                infiniteRepeatable(tween(VisualLimits.BLINK_MS / 2), RepeatMode.Reverse), label = "paused-alpha").value
                        } else 1f
                        Text(clockText(shownSeconds), fontSize = if (shownSeconds >= 3600) 44.sp else 62.sp,
                            fontWeight = FontWeight.Light, letterSpacing = (-1.24).sp, color = Ink,
                            style = NumeralStyle, modifier = Modifier.alpha(blink))
                        Text(caption, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            if (timer == null) {
                Button(onClick = onStart, enabled = project != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始专注")
                }
            } else {
                Button(onClick = if (timer.status == TimerStatus.PAUSED) onResume else onPause, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (resting) RestSlate else Accent)) {
                    Icon(if (timer.status == TimerStatus.PAUSED) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (timer.status == TimerStatus.PAUSED) "继续计时" else "暂停")
                }
                // 倒计时未到零也可以提前结束，但要确认；已到零由计时循环自动保存。
                val early = !resting && mode == TimerMode.COUNTDOWN && timer.targetReached(nowElapsed) != true
                if (resting || mode == TimerMode.COUNTUP || early) {
                    OutlinedButton(onClick = if (early) onFinishEarly else onFinish,
                        enabled = resting || elapsedMillis >= TimerEngine.MIN_SESSION_MS, modifier = Modifier.fillMaxWidth()) {
                        Text(if (resting) "结束休息" else "结束并保存专注")
                    }
                }
                TextButton(onClick = onDiscard, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (resting) "放弃本次休息" else "放弃本次专注", color = Muted) }
                if (!resting && mode == TimerMode.COUNTDOWN) Caption("倒计时到零自动保存；提前结束会按已专注时长保存，放弃则不生成记录。")
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onShortBreak, modifier = Modifier.weight(1f)) { Text("短休息") }
            OutlinedButton(onClick = onLongBreak, modifier = Modifier.weight(1f)) { Text("长休息") }
        }
        Caption("休息和暂停不计入专注统计。返回首页不会停止计时。")
    }
}

private enum class DialState { IDLE, RUNNING, PAUSED }

/**
 * 60 格分钟表盘：剩余（倒计时）或已走（正计时）弧段上的刻度点亮，弧端小钮随进度走。
 * 计时中小钮像心跳一样脉动；暂停时弧段变淡；未开始时只显示灰色刻度。系统移除动画时直接到终态。
 */
@Composable
private fun TimerDial(visible: Float, state: DialState, color: Color, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val idle = state == DialState.IDLE
    val arc by animateFloatAsState(visible, if (reduceMotion) snap() else tween(400, easing = LinearEasing), label = "dial-arc")
    val lit by animateFloatAsState(if (idle) 0f else 1f, if (reduceMotion) snap() else tween(700), label = "dial-lit")
    val knob by animateFloatAsState(if (idle) 0f else 1f, if (reduceMotion) snap() else spring(dampingRatio = 0.55f), label = "dial-knob")
    val dim by animateFloatAsState(if (state == DialState.PAUSED) 0.45f else 1f, if (reduceMotion) snap() else tween(300), label = "dial-dim")
    val tint by animateColorAsState(color, if (reduceMotion) snap() else tween(500), label = "dial-color")
    val pulse = if (state == DialState.RUNNING && !reduceMotion) {
        rememberInfiniteTransition(label = "dial-pulse").animateFloat(0f, 1f,
            infiniteRepeatable(tween(VisualLimits.PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "dial-pulse-value").value
    } else -1f
    Box(Modifier.sizeIn(maxWidth = 280.dp, maxHeight = 280.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2f - 2.dp.toPx()
            for (index in 0 until VisualLimits.DIAL_TICKS) {
                val major = index % VisualLimits.DIAL_MAJOR_EVERY == 0
                val angle = Math.toRadians(index * 360.0 / VisualLimits.DIAL_TICKS - 90.0)
                val length = (if (major) 11 else 5).dp.toPx()
                val width = (if (major) 2.4f else 1.2f).dp.toPx()
                val start = Offset(center.x + kotlin.math.cos(angle).toFloat() * (outer - length), center.y + kotlin.math.sin(angle).toFloat() * (outer - length))
                val end = Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer)
                drawLine(if (major) TickIdleMajor else TickIdle, start, end, width)
                if (lit > 0f && index.toFloat() / VisualLimits.DIAL_TICKS < arc) drawLine(tint.copy(alpha = lit * dim), start, end, width)
            }
            val ring = outer - 18.dp.toPx()
            val stroke = 4.dp.toPx()
            drawCircle(Color(0xFFF0F0F0), ring, center, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            if (arc > 0f) drawArc(tint.copy(alpha = dim), -90f, arc * 360f, false, topLeft = Offset(center.x - ring, center.y - ring),
                size = androidx.compose.ui.geometry.Size(ring * 2, ring * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round))
            if (knob > 0.01f) {
                val angle = Math.toRadians(arc * 360.0 - 90.0)
                val point = Offset(center.x + kotlin.math.cos(angle).toFloat() * ring, center.y + kotlin.math.sin(angle).toFloat() * ring)
                val radius = 7.dp.toPx() * knob
                if (pulse >= 0f) drawCircle(tint.copy(alpha = 0.35f * (1f - pulse)), 7.dp.toPx() * (1f + 2.2f * pulse), point)
                drawCircle(Color.White, radius, point)
                drawCircle(tint, radius, point, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx() * knob))
            }
        }
        content()
    }
}

@Composable
internal fun TodosScreen(
    state: AppState,
    ui: TodoUiState,
    today: LocalDate,
    now: Long = System.currentTimeMillis(),
    busy: Boolean = false,
    onCreate: () -> Unit,
    onEdit: (Todo) -> Unit,
    onDetails: (Todo) -> Unit,
    onComplete: (Todo) -> Unit,
    onToggleStep: (Todo, TodoStep) -> Unit,
    onRenew: (Todo) -> Unit,
    onArchive: (Todo) -> Unit,
    onRestore: (Todo) -> Unit,
    onDelete: (Todo) -> Unit,
    onUndo: () -> Unit,
    onDismissUndo: () -> Unit,
    onPickMonth: () -> Unit,
    onHelp: () -> Unit
) {
    ObserveTodoMotion(ui)
    val live = state.todos.filterNot { it.archived }
    val done = live.count { it.done }
    val pending = live.size - done
    val archived = state.todos.count { it.archived }
    val view = ui.view()
    val historyMonth = minOf(ui.month, YearMonth.from(today))
    LaunchedEffect(historyMonth) { if (ui.month > historyMonth) ui.selectMonth(historyMonth) }
    val history = if (ui.filter == TodoFilter.DONE) TodoHistory.select(
        state.todos, month = historyMonth, query = ui.query, undated = ui.undated, limit = view.limit, now = now
    ) else null
    val visible = when (ui.filter) {
        TodoFilter.PENDING -> live.filter { !it.done || (!ui.reduceMotion && ui.finishing.containsKey(it.id)) }
        TodoFilter.DONE -> emptyList()
        TodoFilter.ARCHIVED -> state.todos.filter { it.archived }
    }
    // 临时完成项保持原位置，不能被分组的「完成沉底」规则移动。
    val visibleById = visible.associateBy { it.id }
    val groups = history?.groups ?: TodoGrouping.group(visible.map { it.copy(done = false) }).map { group ->
        group.copy(todos = group.todos.map { item -> visibleById.getValue(item.id) })
    }
    // 保存后把那张卡片带进视野；不在当前视图（例如已完成跳到其它月份）时不滚动。
    val saved = ui.justSaved
    LaunchedEffect(saved, groups.isNotEmpty()) {
        if (saved == null) return@LaunchedEffect
        val key = "todo:${saved.id}"
        withFrameNanos { }
        val index = view.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index
        if (index == null) {
            // 两种视图都是两项固定表头：标题行 + 月份控制 / 列表引导。
            var position = TodoUiLimits.HISTORY_HEADER_ITEMS
            for (group in groups) {
                position++
                val within = group.todos.indexOfFirst { it.id == saved.id }
                if (within >= 0) { view.listState.animateScrollToItem(position + within); break }
                position += group.todos.size
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        LazyColumn(state = view.listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
            item(key = "todo-header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(buildAnnotatedString { append("待办"); withStyle(SpanStyle(color = Accent)) { append(".") } },
                            fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = (-0.9).sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onHelp, contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false).heightIn(min = 48.dp)) {
                            Text("使用指南", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(enabled = !busy, onClick = onCreate,
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(Accent)) {
                        Icon(Icons.Outlined.Add, "新增待办", tint = Color.White, modifier = Modifier.size(21.dp))
                    }
                }
                TodoTabs(ui.filter, pending, done, archived, ui::setFilter)
            }
            if (history != null) {
                item(key = "history-controls") { TodoHistoryControls(ui, history, today, onPickMonth) }
            } else {
                item(key = "todo-intro") {
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(todoListHeading(ui.filter), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("小步前进，也很好", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            if (groups.isEmpty()) {
                item(key = "todo-empty") {
                    val title = when {
                        history == null -> emptyTodoTitle(ui.filter)
                        ui.query.isNotBlank() -> "没有找到这件事"
                        ui.undated -> "没有时间未记录的事项"
                        else -> "这一月，还没有完成记录"
                    }
                    val detail = when {
                        history == null -> emptyTodoDetail(ui.filter)
                        ui.query.isNotBlank() -> "搜索范围是全部时间，换个名称、分类或小步关键词试试。"
                        ui.undated -> "有明确完成时间的事都在对应月份里。"
                        else -> "可以切换月份回看，或搜索全部时间的完成记录。"
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 35.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                        Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp))
                        if (history?.latestMonth != null && ui.query.isBlank() && !ui.undated && history.latestMonth != ui.month) {
                            OutlinedButton(onClick = { ui.selectMonth(history.latestMonth) },
                                modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(11.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, TodoAccentBorder),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentDark)) {
                                Text("查看最近有记录的月份", fontSize = 12.sp)
                            }
                        }
                    }
                    if (ui.filter == TodoFilter.PENDING) TodoDashedButton(enabled = !busy, onClick = onCreate)
                }
            }
            groups.forEach { group ->
                item(key = "group:${group.key}") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).semantics { heading() },
                        verticalAlignment = Alignment.CenterVertically) {
                        if (group.key == TodoGrouping.IMPORTANT_KEY) Text("▲", color = AccentDark, fontSize = 8.sp, modifier = Modifier.padding(end = 5.dp))
                        Text(group.label, fontSize = 12.sp,
                            color = if (group.key == TodoGrouping.IMPORTANT_KEY) AccentDark else Muted,
                            fontWeight = if (history != null) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = if (history != null) Modifier.weight(1f) else Modifier)
                        Text(if (history != null) "${group.todos.size} 件已展示" else "${group.todos.size}", fontSize = 11.sp, color = Muted,
                            modifier = Modifier.padding(start = 7.dp))
                    }
                }
                group.todos.forEach { todo ->
                    item(key = "todo:${todo.id}") {
                        TodoCard(todo, group.key, ui, today, now, busy, onEdit, onDetails, onComplete,
                            onToggleStep, onRenew, onArchive, onRestore, onDelete)
                    }
                }
            }
            if (history != null && history.total > 0) {
                item(key = "history-footer") {
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (history.shown < history.total) OutlinedButton(onClick = ui::loadMore,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看更多", fontSize = 12.sp) }
                        Text("已显示 ${history.shown} / ${history.total} 件", color = Muted, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
        ui.undo.lastOrNull()?.let { entry ->
            Surface(shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                color = Color.White, shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val more = if (ui.undo.size > 1) "（可依次撤销 ${ui.undo.size} 次）" else ""
                    Text("${entry.label} · ${entry.change.before.title}$more", fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(enabled = !busy, onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("撤销", color = AccentDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(enabled = !busy, onClick = onDismissUndo, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("关闭提示", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ObserveTodoMotion(ui: TodoUiState) {
    val resolver = LocalContext.current.contentResolver
    DisposableEffect(resolver, ui) {
        fun update() {
            ui.reduceMotion = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            if (ui.reduceMotion) { ui.finishing.clear(); ui.openSwipeId = null }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        update()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
}

@Composable
private fun TodoTabs(filter: TodoFilter, pending: Int, done: Int, archived: Int, onFilter: (TodoFilter) -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = Line)
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            TodoFilter.entries.forEach { value ->
                val selected = filter == value
                val count = when (value) { TodoFilter.PENDING -> pending; TodoFilter.DONE -> done; TodoFilter.ARCHIVED -> archived }
                Column(Modifier.weight(1f).height(48.dp)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onFilter(value) }),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                        Text("${todoFilterName(value)} $count", fontSize = 12.sp,
                            color = if (selected) AccentDark else Muted, maxLines = 1,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                    Box(Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(2.dp)
                        .background(if (selected) Accent else Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun TodoHistoryControls(ui: TodoUiState, history: TodoHistoryResult, today: LocalDate, onPickMonth: () -> Unit) {
    val searching = ui.query.isNotBlank()
    val current = YearMonth.from(today)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
        OutlinedTextField(value = ui.query, onValueChange = ui::search, singleLine = true,
            placeholder = { Text("搜索已完成的事·全部时间", fontSize = 13.sp) },
            trailingIcon = if (ui.query.isNotEmpty()) { { IconButton(onClick = { ui.search("") }) {
                Icon(Icons.Outlined.Close, "清空搜索", modifier = Modifier.size(18.dp))
            } } } else null,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Line,
                focusedContainerColor = SoftSurface, unfocusedContainerColor = SoftSurface, cursorColor = Accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索已完成的事·全部时间" })
        if (!searching && !ui.undated) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = ui.month > YearMonth.of(TodoUiLimits.MIN_YEAR, 1), onClick = { ui.selectMonth(ui.month.minusMonths(1)) }) {
                    Icon(Icons.Outlined.ChevronLeft, "上一月")
                }
                TextButton(onClick = onPickMonth, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("${ui.month.year}年${ui.month.monthValue}月", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Outlined.ExpandMore, null, tint = Muted, modifier = Modifier.padding(start = 4.dp).size(13.dp))
                }
                IconButton(enabled = ui.month < current, onClick = { ui.selectMonth(ui.month.plusMonths(1)) }) {
                    Icon(Icons.Outlined.ChevronRight, "下一月")
                }
                if (ui.month != current) TextButton(onClick = { ui.selectMonth(current) }, modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp)) {
                    Text("回到本月", color = AccentDark, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Text(when {
            searching -> "全部时间 · 找到 ${history.total} 件"
            ui.undated -> "完成时间未记录 · ${history.total} 件"
            else -> "${ui.month.year}年${ui.month.monthValue}月 · 完成 ${history.total} 件"
        }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })
        if (!searching && ui.undated) TextButton(onClick = { ui.showUndated(false) }, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)) {
            Text("返回按月查看", color = AccentDark, fontSize = 12.sp, textDecoration = TextDecoration.Underline)
        } else if (!searching && history.undatedCount > 0) TextButton(onClick = { ui.showUndated(true) }, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)) {
            Text("时间未记录 · ${history.undatedCount} 件", color = AccentDark, fontSize = 12.sp, textDecoration = TextDecoration.Underline)
        }
    }
}

@Composable
private fun TodoCard(
    todo: Todo, groupKey: String, ui: TodoUiState, today: LocalDate, now: Long, busy: Boolean,
    onEdit: (Todo) -> Unit, onDetails: (Todo) -> Unit, onComplete: (Todo) -> Unit,
    onToggleStep: (Todo, TodoStep) -> Unit, onRenew: (Todo) -> Unit, onArchive: (Todo) -> Unit,
    onRestore: (Todo) -> Unit, onDelete: (Todo) -> Unit
) {
    val finish = if (ui.filter == TodoFilter.PENDING && todo.done && !ui.reduceMotion) ui.finishing[todo.id] else null
    val finishProgress = if (finish != null) rememberTodoFinishProgress(finish) else 1f
    val finishGlow = if (finish != null) kotlin.math.sin(finishProgress * Math.PI).toFloat() else 0f
    val age = TodoAging.age(todo, today)
    val history = ui.filter == TodoFilter.DONE
    val allowed = !busy && finish == null
    val density = LocalDensity.current
    val swipeWidth = with(density) { TodoUiLimits.SWIPE_WIDTH_DP.dp.toPx() }
    val threshold = with(density) { TodoUiLimits.SWIPE_THRESHOLD_DP.dp.toPx() }
    var dragging by remember(todo.id) { mutableStateOf(false) }
    var dragOffset by remember(todo.id) { mutableFloatStateOf(0f) }
    val opened = ui.openSwipeId == todo.id && allowed
    val currentOpened by rememberUpdatedState(opened)
    val swipe by animateFloatAsState(if (dragging) dragOffset else if (opened) -swipeWidth else 0f,
        animationSpec = if (dragging || ui.reduceMotion) snap() else tween(TodoUiLimits.FINISH_COLLAPSE_MS.toInt()), label = "todo-swipe")
    val collapse by animateFloatAsState(if (finish?.phase == TodoFinishPhase.EXIT) 0f else 1f,
        animationSpec = if (ui.reduceMotion || finish == null) snap() else tween(TodoUiLimits.FINISH_COLLAPSE_MS.toInt()), label = "todo-exit")
    var normalHeight by remember(todo.id) { mutableIntStateOf(0) }
    // 刚保存的卡片暖橙底色淡出 1.2 秒，对应浏览器 `.just-saved`；系统关闭动画时不闪。
    val savedFlash = remember(todo.id) { Animatable(0f) }
    val savedToken = ui.justSaved?.takeIf { it.id == todo.id }?.token
    LaunchedEffect(savedToken) {
        // 保存后卡片在别的视图里时不闪；稍后切过去看到它也不再补闪。
        if (savedToken == null || ui.reduceMotion || SystemClock.elapsedRealtime() - savedToken > TodoUiLimits.SAVED_FLASH_MS) return@LaunchedEffect
        savedFlash.snapTo(1f)
        savedFlash.animateTo(0f, tween(TodoUiLimits.SAVED_FLASH_MS, easing = LinearEasing))
        if (ui.justSaved?.token == savedToken) ui.justSaved = null
    }
    val shape = RoundedCornerShape(if (history) 0.dp else 15.dp)
    fun act(action: () -> Unit) {
        if (!allowed || dragging) return
        if (ui.openSwipeId != null) ui.openSwipeId = null else action()
    }
    Box(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
        val fixed = if (finish != null && normalHeight > 0) constraints.copy(minHeight = normalHeight, maxHeight = normalHeight) else constraints
        val placeable = measurable.measure(fixed)
        layout(placeable.width, (placeable.height * collapse).roundToInt()) { placeable.placeRelative(0, 0) }
    }.alpha(collapse).padding(bottom = if (history) 2.dp else 10.dp), propagateMinConstraints = true) {
        Box(Modifier.fillMaxWidth().clip(shape).onSizeChanged { if (finish == null) normalHeight = it.height + with(density) { (if (history) 2.dp else 10.dp).roundToPx() } }, propagateMinConstraints = true) {
            if ((opened || dragging) && allowed) {
                Box(Modifier.matchParentSize().background(AccentDark), contentAlignment = Alignment.CenterEnd) {
                    if (opened && !dragging) TextButton(enabled = allowed, onClick = { ui.openSwipeId = null; onDelete(todo) },
                        modifier = Modifier.width(TodoUiLimits.SWIPE_WIDTH_DP.dp).fillMaxHeight().heightIn(min = 48.dp)
                            .semantics { contentDescription = "删除待办：${todo.title}" }) {
                        Text("删除", color = Color.White, fontSize = 13.sp)
                    } else Text("删除", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.width(TodoUiLimits.SWIPE_WIDTH_DP.dp).clearAndSetSemantics {}, textAlign = TextAlign.Center)
                }
            }
            Surface(color = if (finish != null) androidx.compose.ui.graphics.lerp(Color(0xFFFFFAF6), AccentSoft, finishGlow * 0.6f)
                else androidx.compose.ui.graphics.lerp(todoBackground(todo, age), AccentSoft, savedFlash.value), shape = shape,
                border = if (history || todo.archived) null else androidx.compose.foundation.BorderStroke(1.dp, if (finish != null) Accent else todoBorder(todo, age)),
                modifier = Modifier.offset { IntOffset(swipe.roundToInt(), 0) }.fillMaxWidth()
                    .then(if (todo.archived && !history) Modifier.dashedBorder(Line, 15.dp) else Modifier)
                    .pointerInput(todo.id, allowed, swipeWidth) {
                        if (allowed) detectHorizontalDragGestures(
                            onDragStart = { dragOffset = if (currentOpened) -swipeWidth else 0f; dragging = true; ui.openSwipeId = todo.id },
                            onHorizontalDrag = { change, amount -> change.consume(); dragOffset = (dragOffset + amount).coerceIn(-swipeWidth, 0f) },
                            onDragEnd = { ui.openSwipeId = if (dragOffset <= -threshold) todo.id else null; dragging = false },
                            onDragCancel = { ui.openSwipeId = null; dragging = false }
                        )
                    }.clickable(enabled = allowed, onClickLabel = "收起滑动操作") { ui.openSwipeId = null }
                    .semantics {
                        if (allowed) customActions = listOf(
                            CustomAccessibilityAction("待办详情") { ui.openSwipeId = null; onDetails(todo); true },
                            CustomAccessibilityAction("删除待办") { ui.openSwipeId = null; onDelete(todo); true }
                        )
                    }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = if (history) 0.dp else 8.dp, vertical = 6.dp)) {
                    if (finish != null) {
                        TodoFinishNote(finishProgress)
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            TodoCheckbox(todo.done, todo.title, allowed && !dragging && !todo.done && !todo.archived,
                                readOnly = todo.done || todo.archived, round = history) { act { onComplete(todo) } }
                            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                                .clickable(enabled = allowed && !dragging, onClickLabel = if (todo.done || todo.archived) "查看待办" else "编辑待办") {
                                    act { if (todo.done || todo.archived) onDetails(todo) else onEdit(todo) }
                                }.padding(vertical = 6.dp)) {
                                Text(todo.title, fontSize = if (history) 14.sp else 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                                TodoMetaRow(todo, groupKey, age, now)
                            }
                            IconButton(enabled = allowed && !dragging, onClick = { act { onDetails(todo) } }) {
                                Icon(Icons.Outlined.MoreHoriz, "待办详情：${todo.title}", tint = Muted, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    if (todo.steps.isNotEmpty() && !todo.archived && (!todo.done || finish != null)) {
                        StepPath(todo, ui.expanded[todo.id] == true, allowed && !dragging,
                            onExpand = { act { ui.expanded[todo.id] = ui.expanded[todo.id] != true } },
                            onToggleStep = { step -> act { onToggleStep(todo, step) } }, finishGlow = finishGlow, reduceMotion = ui.reduceMotion)
                    }
                    if (!todo.done && !todo.archived && age.stage != TodoStage.FRESH) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AgeActionPill("续一天", AccentDark, allowed && !dragging) { act { onRenew(todo) } }
                            AgeActionPill("放下", Muted, allowed && !dragging) { act { onArchive(todo) } }
                        }
                    }
                    if (todo.archived) AgeActionPill("找回", AccentDark, allowed && !dragging) { act { onRestore(todo) } }
                    if (history) HorizontalDivider(color = Line, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun rememberTodoFinishProgress(finish: TodoFinish): Float {
    val progress = remember(finish.token) {
        Animatable(((SystemClock.elapsedRealtime() - finish.beganAt).toFloat() / TodoUiLimits.FINISH_HOLD_MS).coerceIn(0f, 1f))
    }
    LaunchedEffect(finish.token) {
        val remaining = (TodoUiLimits.FINISH_HOLD_MS - (SystemClock.elapsedRealtime() - finish.beganAt)).coerceAtLeast(0L)
        progress.animateTo(1f, tween(remaining.toInt()))
    }
    return progress.value
}

@Composable
private fun TodoFinishNote(progress: Float) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(34.dp)) {
            val pulse = kotlin.math.sin(progress * Math.PI).toFloat()
            drawCircle(AccentSoft, radius = size.minDimension / 2)
            drawCircle(Accent.copy(alpha = 0.10f * pulse), radius = size.minDimension * (0.5f + 0.12f * pulse))
            val drawn = (progress / 0.65f).coerceIn(0f, 1f)
            val a = Offset(size.width * 0.25f, size.height * 0.51f)
            val b = Offset(size.width * 0.43f, size.height * 0.69f)
            val c = Offset(size.width * 0.77f, size.height * 0.32f)
            fun segment(from: Offset, to: Offset, fraction: Float) = from + (to - from) * fraction
            drawLine(AccentDark, a, segment(a, b, (drawn * 3).coerceIn(0f, 1f)), 2.dp.toPx(), StrokeCap.Round)
            if (drawn > 1f / 3) drawLine(AccentDark, b, segment(b, c, ((drawn - 1f / 3) * 1.5f).coerceIn(0f, 1f)), 2.dp.toPx(), StrokeCap.Round)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("又走完一件事", color = AccentDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("已收进完成记录，随时可以回看", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TodoCheckbox(done: Boolean, title: String, enabled: Boolean, readOnly: Boolean, round: Boolean = false, onToggle: () -> Unit) {
    val interaction = if (readOnly) Modifier.semantics { contentDescription = "${if (done) "已完成" else "已放下"}：$title" }
        else Modifier.toggleable(value = done, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = "完成待办：$title" }
    Box(Modifier.size(48.dp).then(interaction), contentAlignment = Alignment.Center) { TodoCheckMark(done, round) }
}

@Composable
private fun TodoCheckMark(done: Boolean, round: Boolean = false) {
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    Box(Modifier.size(if (round) 18.dp else 20.dp).clip(shape).background(if (done) AccentSoft else Color.Transparent)
        .border(1.5.dp, if (done) Accent else Muted, shape), contentAlignment = Alignment.Center) {
        if (done) Icon(Icons.Outlined.Check, null, tint = AccentDark, modifier = Modifier.size(if (round) 12.dp else 14.dp))
    }
}

@Composable
private fun TodoStepCheckMark(done: Boolean, reduceMotion: Boolean) {
    val scale = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(done) }
    LaunchedEffect(done, reduceMotion) {
        val pulse = !previous && done && !reduceMotion
        previous = done
        scale.snapTo(1f)
        if (pulse) {
            scale.animateTo(TodoUiLimits.STEP_PULSE_SCALE, tween(TodoUiLimits.STEP_PULSE_MS))
            scale.animateTo(1f, tween(TodoUiLimits.STEP_PULSE_MS))
        }
    }
    Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }) { TodoCheckMark(done) }
}

@Composable
private fun TodoMetaRow(todo: Todo, groupKey: String, age: TodoAge, now: Long) {
    val tags = todoMetaTags(todo, groupKey)
    val parts = mutableListOf<String>()
    todoAgeLabel(todo, age)?.let(parts::add)
    if (todo.done && !todo.archived) {
        parts += TodoHistory.completedDate(todo, now)?.let { "$it 完成" } ?: "完成时间未记录"
        if (todo.steps.isNotEmpty()) parts += "${todo.steps.size} 个小步已走完"
    }
    if (tags.isNotEmpty() || parts.isNotEmpty()) Text(buildAnnotatedString {
        tags.forEachIndexed { index, (label, important) ->
            if (index > 0) append(" · ")
            withStyle(SpanStyle(color = if (important) AccentDark else Muted,
                background = if (todo.done) Color.Transparent else if (important) AccentSoft else SoftSurface)) { append(label) }
        }
        if (tags.isNotEmpty() && parts.isNotEmpty()) append(" · ")
        append(parts.joinToString(" · "))
    }, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
}

@Composable
private fun AgeActionPill(label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
        Text(label, fontSize = 11.sp, color = tint, modifier = Modifier.border(1.dp, Line, CircleShape).padding(horizontal = 11.dp, vertical = 5.dp))
    }
}

/** 小路只展示；展开入口与每个步骤均使用整行触控区域。 */
@Composable
private fun StepPath(todo: Todo, expanded: Boolean, enabled: Boolean, onExpand: () -> Unit, onToggleStep: (TodoStep) -> Unit, finishGlow: Float = 0f, reduceMotion: Boolean = false) {
    val progress = TodoAging.progress(todo)
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClickLabel = if (expanded) "收起小步" else "展开小步", onClick = onExpand).semantics { stateDescription = if (expanded) "小步已展开" else "小步已收起" }
            .padding(horizontal = 5.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("小步 ${progress.walked} / ${progress.total}", color = AccentDark, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", color = Muted, fontSize = 11.sp)
            }
            val next = TodoAging.nextStep(todo)
            Text(if (next == null) "每一步都走完了" else "下一步 · ${next.title}", color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                todo.steps.forEachIndexed { index, step ->
                    if (index > 0) Box(Modifier.weight(1f).height(2.dp).background(if (step.done && todo.steps[index - 1].done) Accent else Line))
                    Canvas(Modifier.size(11.dp)) {
                        val radius = size.minDimension / 2
                        if (index == todo.steps.lastIndex && finishGlow > 0f) {
                            drawCircle(Accent.copy(alpha = 0.18f * finishGlow), radius = radius + 5.dp.toPx() * finishGlow)
                        }
                        drawCircle(if (step.done) Accent else Color(0xFFBCBCBC), radius)
                        if (!step.done) drawCircle(Color.White, (radius - 1.5.dp.toPx()).coerceAtLeast(0f))
                    }
                }
            }
        }
        if (expanded) {
            HorizontalDivider(color = Line)
            todo.steps.forEach { step ->
                key(step.id) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp))
                        .toggleable(value = step.done, enabled = enabled && !todo.done && !todo.archived, role = Role.Checkbox,
                            onValueChange = { onToggleStep(step) }).padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TodoStepCheckMark(step.done, reduceMotion)
                        Text(step.title, fontSize = 14.sp, color = if (step.done) Muted else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 10.dp))
                    }
                }
            }
        }
    }
}

/** 对应原型 .add-task-inline：虚线框，只在待完成为空时出现。 */
@Composable
private fun TodoDashedButton(enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .dashedBorder(Color(0xFFC9C9C9), 14.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Add, null, tint = AccentDark, modifier = Modifier.size(16.dp))
        Text("添加一个小目标", color = AccentDark, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
    }
}

/** Compose 的 border 不支持虚线，自绘 1dp 圆角虚线描边。 */
private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(color, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
}

private fun todoFilterName(filter: TodoFilter): String = when (filter) {
    TodoFilter.PENDING -> "待完成"
    TodoFilter.DONE -> "已完成"
    TodoFilter.ARCHIVED -> "放下的"
}

private fun emptyTodoTitle(filter: TodoFilter): String = when (filter) {
    TodoFilter.PENDING -> "给下一步，留一点空间"
    else -> "这里暂时是空的"
}

private fun emptyTodoDetail(filter: TodoFilter): String = when (filter) {
    TodoFilter.DONE -> "可以切换月份回看，或搜索全部时间的完成记录。"
    TodoFilter.ARCHIVED -> "放下不是删除，需要时还可以找回。"
    TodoFilter.PENDING -> "记下一件想做的事，也可以把它拆成几个小步。"
}

@Composable
internal fun ProgressHistoryDialog(
    project: Project?,
    projectId: String,
    state: AppState,
    onDismiss: () -> Unit,
    onEdit: ((FocusSession) -> Unit)? = null
) {
    val history = state.progress.withIndex().filter { it.value.projectId == projectId }
        .sortedWith(compareByDescending<IndexedValue<ProgressEntry>> { it.value.sessionEndedAt }.thenByDescending { it.value.updatedAt }.thenByDescending { it.index })
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color.White,
        title = { Text("${project?.title ?: state.sessions.find { it.projectId == projectId }?.projectTitle ?: "项目"} · 进度历史", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (history.isEmpty()) Text("还没有进度记录。完成专注后可以填写，也可在专注记录里补写。", color = Muted)
                history.forEach { indexed ->
                    val entry = indexed.value
                    val revisions = state.progress.filter { it.sessionId == entry.sessionId }
                    val revision = revisions.indexOfFirst { it.id == entry.id } + 1
                    val latest = Statistics.latestForSession(state.progress, entry.sessionId)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("专注结束 ${timestampText(entry.sessionEndedAt)}", fontWeight = FontWeight.Medium)
                        Caption("修订 $revision · ${timestampText(entry.updatedAt)}${if (latest?.id == entry.id) " · 本次最新" else ""}")
                        entry.percent?.let { Text("完成度 $it%", color = Accent) }
                        Text(entry.note)
                        val session = state.sessions.find { it.id == entry.sessionId }
                        if (onEdit != null && session != null && latest?.id == entry.id) TextButton(onClick = { onEdit(session) }) { Text("修改本次进度") }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
