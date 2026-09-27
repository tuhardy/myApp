package com.focusassistant.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clipToBounds
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
import com.focusassistant.app.platform.AppUsage
import com.focusassistant.app.platform.UsageBucket
import com.focusassistant.app.platform.UsageReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val DAYS_PER_WEEK = 7
private const val HOURS_PER_DAY = 24
private const val USAGE_HOURS_PER_BUCKET = 2
private val USAGE_WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

private fun usageBucketLabel(index: Int, week: Boolean): String = if (week) "周${USAGE_WEEK_LABELS[index]}" else {
    val start = (index * USAGE_HOURS_PER_BUCKET).toString().padStart(2, '0')
    val end = ((index + 1) * USAGE_HOURS_PER_BUCKET).toString().padStart(2, '0')
    "$start:00–$end:00"
}

@Composable
private fun ScreenBody(content: @Composable ColumnScope.() -> Unit) {
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
private fun Caption(text: String) {
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
    else -> Color(0xFFE8C4B5)
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

private fun todoListHeading(filter: TodoFilter): String = when (filter) {
    TodoFilter.DONE -> "走过的路，都算数"
    TodoFilter.ARCHIVED -> "暂时放下，也没关系"
    TodoFilter.PENDING -> "从眼前的一步开始"
}

/** 按分类给项目一个稳定的图标与配色，纯展示，不参与任何统计。 */
private fun projectGlyph(project: Project): Pair<androidx.compose.ui.graphics.vector.ImageVector, Pair<Color, Color>> {
    val palette = listOf(
        Icons.Outlined.Headphones to (Color(0xFFF6EEE8) to Color(0xFFA36743)),
        Icons.Outlined.MenuBook to (Color(0xFFEFEDF5) to Color(0xFF77668B)),
        Icons.Outlined.Code to (Color(0xFFEDF0F4) to Color(0xFF63768B))
    )
    val index = ((project.category.hashCode() % palette.size) + palette.size) % palette.size
    return palette[index]
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
    ScreenBody {
        PageTitle("专注") {
            IconButton(onClick = onStatistics) { Icon(Icons.Outlined.TrendingUp, "专注统计") }
            IconButton(onClick = onCreate) { Icon(Icons.Outlined.Add, "新建学习项目") }
        }
        Caption("从一件小事，进入状态。")
        // 今日摘要：只汇总本机真实完成记录，没有记录就显示 0。
        val todaySessions = Statistics.select(state.sessions, Period.DAY, today)
        val todaySeconds = todaySessions.sumOf { it.durationSeconds }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("今日专注", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp, bottom = 2.dp))
            Text(targetText((todaySeconds / UiLimits.SECONDS_PER_MINUTE).toInt()), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(" / ", color = Color(0xFFB8B3AE), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            Text("${todaySessions.size}", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("次完成", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        }
        HorizontalDivider()
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
        if (state.projects.isEmpty()) {
            EmptyMessage("为重要的事，留一段时间", "创建一个可反复使用的学习项目。从一次专注开始，慢慢积累自己的进度。")
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("创建第一个项目") }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的项目", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(" ${state.projects.size}", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onCreate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建", fontSize = 12.sp)
                }
            }
            state.projects.forEach { project ->
                key(project.id) {
                    var expanded by rememberSaveable(project.id) { mutableStateOf(false) }
                    val latest = Statistics.latestProgress(state.progress, project.id)
                    val sessions = state.sessions.filter { it.projectId == project.id }.sortedByDescending { it.endedAt }
                    val (glyph, tones) = projectGlyph(project)
                    Surface(shape = RoundedCornerShape(18.dp), color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (expanded) Color(0xFFDCB29F) else Color(0xFFEAE7E4))) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable { onOpen(project) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(35.dp).clip(RoundedCornerShape(11.dp)).background(tones.first), contentAlignment = Alignment.Center) {
                                        Icon(glyph, null, tint = tones.second, modifier = Modifier.size(18.dp))
                                    }
                                    Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(project.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Caption(projectSubtitle(project))
                                    }
                                }
                                IconButton(onClick = { expanded = !expanded }) {
                                    Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.MoreHoriz,
                                        if (expanded) "收起项目进度" else "展开项目进度", tint = Muted)
                                }
                            }
                            // 最近一条进度直接可见，不必展开。
                            Row(Modifier.fillMaxWidth().clickable { onHistory(project) }.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Notes, null, tint = Color(0xFF92908D), modifier = Modifier.size(13.dp))
                                Text(latest?.note?.let { "上次：$it" } ?: "还没有进度记录",
                                    color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(start = 6.dp))
                                Icon(Icons.Outlined.ChevronRight, null, tint = Color(0xFF92908D), modifier = Modifier.size(13.dp))
                            }
                            HorizontalDivider(color = Color(0xFFF2EFED))
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Caption("累计 ${durationText(sessions.sumOf { it.durationSeconds })}")
                                Spacer(Modifier.weight(1f))
                                Row(Modifier.clickable { onOpen(project) }, verticalAlignment = Alignment.CenterVertically) {
                                    Text("打开", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(15.dp))
                                }
                            }
                            if (expanded) {
                                HorizontalDivider(color = Color(0xFFF2EFED))
                                Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("已专注 ${durationText(sessions.sumOf { it.durationSeconds })} · ${sessions.size} 次", style = MaterialTheme.typography.bodySmall, color = Muted)
                                    if (latest == null) Caption("尚未填写学习进度。完成专注后可记录，也可以稍后补写。")
                                    else {
                                        latest.percent?.let { percent ->
                                            Text("完成度 $percent%", color = Accent, fontWeight = FontWeight.Medium)
                                            LinearProgressIndicator(progress = { percent.toFloat() / UiLimits.MAX_PERCENT }, modifier = Modifier.fillMaxWidth())
                                        }
                                        Text(latest.note, style = MaterialTheme.typography.bodyMedium)
                                        Caption("专注结束 ${timestampText(latest.sessionEndedAt)}")
                                        state.sessions.find { it.id == latest.sessionId }?.let { session ->
                                            TextButton(onClick = { onProgress(session) }) { Text("修改这次进度") }
                                        }
                                    }
                                    sessions.filter { Statistics.latestForSession(state.progress, it.id) == null }.forEach { session ->
                                        OutlinedButton(onClick = { onProgress(session) }, modifier = Modifier.fillMaxWidth()) {
                                            Text("补写进度 · ${timestampText(session.endedAt)}", textAlign = TextAlign.Center)
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { onHistory(project) }) { Text("进度历史") }
                                        IconButton(onClick = { onEdit(project) }) { Icon(Icons.Outlined.Settings, "项目设置") }
                                        IconButton(onClick = { onDelete(project) }) { Icon(Icons.Outlined.DeleteOutline, "删除项目", tint = Muted) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text("一点点投入，会慢慢有答案。", color = Muted, fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
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
        PageTitle(if (resting) phaseName(timer!!.phase) else "专注计时", onBack) {
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
                    fontSize = 23.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
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
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.sizeIn(maxWidth = 272.dp, maxHeight = 272.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    // 无目标（不限时正计时）不画进度弧，只保留轨道，避免出现没有意义的进度。
                    val ringColor = if (timer?.status == TimerStatus.PAUSED) Color(0xFFB7AAA0) else Accent
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 3.dp.toPx()
                        val diameter = size.minDimension - stroke
                        val corner = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                        val box = androidx.compose.ui.geometry.Size(diameter, diameter)
                        drawArc(Color(0xFFF0EDEB), 0f, 360f, false, topLeft = corner, size = box,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        if (targetSeconds != null && targetSeconds > 0L && timer != null) {
                            val sweep = (elapsedSeconds.toFloat() / targetSeconds).coerceIn(0f, 1f) * 360f
                            drawArc(ringColor, -90f, sweep, false, topLeft = corner, size = box,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                    }
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(
                                when { timer == null -> Color(0xFFB4ADA7); timer.status == TimerStatus.PAUSED -> Color(0xFFA49C95); else -> Accent }))
                            Text(when {
                                timer == null -> "准备好，就开始"
                                timer.status == TimerStatus.PAUSED -> "已暂停"
                                resting -> "休息中"
                                else -> "正在专注"
                            }, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                        Text(clockText(shownSeconds), fontSize = if (shownSeconds >= 3600) 42.sp else 56.sp,
                            fontWeight = FontWeight.Light, letterSpacing = (-2).sp)
                        Caption(when {
                            timer == null && targetMinutes == null -> "不限时，按自己的节奏结束"
                            timer == null -> "给这件事一段完整的时间"
                            timer.status == TimerStatus.PAUSED -> "休息一下，再继续"
                            targetMinutes == null -> "已专注 · 手动结束"
                            mode == TimerMode.COUNTUP -> "已专注 · 达到目标后继续"
                            else -> "剩余时间"
                        })
                    }
                }
            }
            if (mode == TimerMode.COUNTUP && !resting) {
                val note = when {
                    targetMinutes == null -> "未设目标，按自己的节奏结束。手动结束后记录实际专注时长。"
                    timer?.targetReached(nowElapsed) == true -> "已达到目标，计时仍在继续。按自己的节奏结束。"
                    else -> "达到目标只提醒，不会停止。手动结束后记录实际专注时长。"
                }
                Text(note, color = Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (timer == null) {
                Button(onClick = onStart, enabled = project != null, modifier = Modifier.fillMaxWidth()) { Text("开始专注") }
            } else {
                Button(onClick = if (timer.status == TimerStatus.PAUSED) onResume else onPause, modifier = Modifier.fillMaxWidth()) {
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
    Column(Modifier.fillMaxSize().background(Color.White)) {
        LazyColumn(state = view.listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
            item(key = "todo-header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(buildAnnotatedString { append("待办"); withStyle(SpanStyle(color = Accent)) { append(".") } },
                            fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
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
                    EmptyMessage(title, detail)
                    if (history?.latestMonth != null && ui.query.isBlank() && !ui.undated && history.latestMonth != ui.month) {
                        OutlinedButton(onClick = { ui.selectMonth(history.latestMonth) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("查看最近有记录的月份", fontSize = 12.sp)
                        }
                    }
                    if (ui.filter == TodoFilter.PENDING) OutlinedButton(enabled = !busy, onClick = onCreate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                        Text("添加一个小目标", fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
                    }
                }
            }
            groups.forEach { group ->
                item(key = "group:${group.key}") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).semantics { heading() },
                        verticalAlignment = Alignment.CenterVertically) {
                        if (group.key == TodoGrouping.IMPORTANT_KEY) Text("▲", color = AccentDark, fontSize = 8.sp, modifier = Modifier.padding(end = 5.dp))
                        Text(group.label, fontSize = 12.sp,
                            color = if (group.key == TodoGrouping.IMPORTANT_KEY) AccentDark else Muted,
                            fontWeight = FontWeight.Medium, modifier = if (history != null) Modifier.weight(1f) else Modifier)
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${entry.label} · ${entry.change.before.title}（${ui.undo.size} 条可撤销）", fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(enabled = !busy, onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text("撤销", fontSize = 12.sp) }
                    IconButton(enabled = !busy, onClick = onDismissUndo) { Icon(Icons.Outlined.Close, "关闭撤销提示", modifier = Modifier.size(18.dp)) }
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
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = ui.month > YearMonth.of(TodoUiLimits.MIN_YEAR, 1), onClick = { ui.selectMonth(ui.month.minusMonths(1)) }) {
                    Icon(Icons.Outlined.ChevronLeft, "上一月")
                }
                TextButton(onClick = onPickMonth, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("${ui.month.year}年${ui.month.monthValue}月", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    Icon(Icons.Outlined.ExpandMore, null, tint = Muted, modifier = Modifier.size(16.dp))
                }
                IconButton(enabled = ui.month < current, onClick = { ui.selectMonth(ui.month.plusMonths(1)) }) {
                    Icon(Icons.Outlined.ChevronRight, "下一月")
                }
            }
            if (ui.month != current) TextButton(onClick = { ui.selectMonth(current) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("回到本月", fontSize = 12.sp)
            }
        }
        Text(when {
            searching -> "全部时间 · 找到 ${history.total} 件"
            ui.undated -> "完成时间未记录 · ${history.total} 件"
            else -> "${ui.month.year}年${ui.month.monthValue}月 · 完成 ${history.total} 件"
        }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })
        if (!searching && ui.undated) TextButton(onClick = { ui.showUndated(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("返回按月查看", fontSize = 12.sp)
        } else if (!searching && history.undatedCount > 0) TextButton(onClick = { ui.showUndated(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("时间未记录 · ${history.undatedCount} 件", fontSize = 12.sp)
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
            Surface(color = if (finish != null) androidx.compose.ui.graphics.lerp(Color(0xFFFFFAF6), AccentSoft, finishGlow * 0.6f) else todoBackground(todo, age), shape = shape,
                border = if (history) null else androidx.compose.foundation.BorderStroke(1.dp, if (finish != null) Accent else todoBorder(todo, age)),
                modifier = Modifier.offset { IntOffset(swipe.roundToInt(), 0) }.fillMaxWidth()
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
            .clickable(enabled = enabled, onClick = onExpand).semantics { stateDescription = if (expanded) "小步已展开" else "小步已收起" }
            .padding(horizontal = 5.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("小步 ${progress.walked} / ${progress.total}", color = AccentDark, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起小步" else "展开小步", color = Muted, fontSize = 11.sp)
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

@Composable
internal fun StatisticsScreen(
    state: AppState,
    period: Period,
    anchor: LocalDate,
    recordLimit: Int,
    onPeriod: (Period) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onPickDate: () -> Unit,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onExport: () -> Unit,
    onMoreRecords: () -> Unit,
    onProgress: (FocusSession) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    today: LocalDate
) {
    val all = remember(state.sessions, today) { Statistics.allTime(state.sessions, today) }
    val selected = remember(state.sessions, period, anchor) { Statistics.select(state.sessions, period, anchor).sortedByDescending { it.endedAt } }
    val summary = remember(selected) { Statistics.summary(selected) }
    val hours = remember(selected) { Statistics.hours(selected) }
    val trend = remember(state.sessions, period, anchor) { Statistics.trend(state.sessions, period, anchor) }
    val range = Statistics.range(period, anchor)
    ScreenBody {
        PageTitle("专注统计", onBack) { IconButton(onClick = onExport, enabled = selected.isNotEmpty()) { Icon(Icons.Outlined.FileDownload, "导出当前周期") } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption("累计专注")
                Text(durationText(all.seconds), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            HelpButton(onHelp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("自然日均", "${minuteText(all.calendarAverageSeconds)} 分钟", Modifier.weight(1f))
            Metric("活跃日均", "${minuteText(all.activeAverageSeconds)} 分钟", Modifier.weight(1f))
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Period.entries.forEach { value ->
                FilterChip(selected = period == value, onClick = { onPeriod(value) }, label = { Text(periodName(value), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) { Icon(Icons.Outlined.ChevronLeft, "上一周期") }
            TextButton(onClick = onPickDate, modifier = Modifier.weight(1f)) {
                Text(if (period == Period.DAY) anchor.toString() else "${range.first}\n— ${range.second.minusDays(1)}", textAlign = TextAlign.Center)
            }
            IconButton(onClick = onNext) { Icon(Icons.Outlined.ChevronRight, "下一周期") }
        }
        TextButton(onClick = onToday, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("回到今天") }
        Surface(color = SoftSurface, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("所选周期专注", durationText(summary.seconds))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric("完成次数", "${summary.count} 次", Modifier.weight(1f))
                    Metric("活跃日均", "${minuteText(summary.averageSeconds)} 分钟", Modifier.weight(1f))
                }
                Caption("有记录 ${summary.activeDays} 天")
            }
        }
        if (period != Period.DAY) {
            SectionTitle("${periodName(period)}专注趋势")
            ChartWithScale(trend.map { it.seconds }, trendLabels(trend, period), "所选${periodName(period)}每${if (period == Period.YEAR) "月" else "日"}完成专注时长")
        }
        SectionTitle("24 小时专注分布")
        ChartWithScale(hours, listOf("00", "06", "12", "18", "23 时"), "${HOURS_PER_DAY} 小时实际活动分布：" + hours.mapIndexed { index, seconds -> "$index 时 ${durationText(seconds)}" }.joinToString("；"))
        Caption("按实际专注片段分配到小时，不包含暂停。")
        SectionTitle("分类占比")
        if (selected.isEmpty()) Caption("当前周期还没有完成记录。")
        selected.groupBy { it.category }.map { (category, records) -> category to records.sumOf { it.durationSeconds } }.sortedByDescending { it.second }.forEach { (category, seconds) ->
            ShareRow(category.ifBlank { "未分类" }, seconds, summary.seconds)
        }
        MonthlyCalendar(state.sessions, anchor, onSelectDay)
        SectionTitle("项目排行")
        val ranks = selected.groupBy { it.projectId }.values.sortedByDescending { records -> records.sumOf { it.durationSeconds } }
        if (ranks.isEmpty()) Caption("完成一次专注后，这里会出现你的积累。")
        ranks.forEachIndexed { index, records ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${index + 1}. ${records.maxByOrNull { it.endedAt }?.projectTitle.orEmpty()}", fontWeight = FontWeight.Medium)
                Caption("${durationText(records.sumOf { it.durationSeconds })} · ${records.size} 次")
            }
        }
        HorizontalDivider()
        SectionTitle("专注记录 · ${selected.size} 次")
        if (selected.isEmpty()) EmptyMessage("这段时间还没有记录", "只有已完成的专注会计入统计，休息和放弃的计时不会保存为专注记录。")
        selected.take(recordLimit.coerceAtLeast(0)).forEach { session ->
            SessionRow(session, Statistics.latestForSession(state.progress, session.id), onProgress)
        }
        if (selected.size > recordLimit) OutlinedButton(onClick = onMoreRecords, modifier = Modifier.fillMaxWidth()) { Text("加载更多（已显示 ${recordLimit.coerceAtLeast(0)} / ${selected.size}）") }
        if (selected.isNotEmpty()) TextButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("导出当前周期全部记录") }
    }
}

private fun todoFilterName(filter: TodoFilter): String = when (filter) {
    TodoFilter.PENDING -> "待完成"
    TodoFilter.DONE -> "已完成"
    TodoFilter.ARCHIVED -> "放下的"
}

private fun emptyTodoTitle(filter: TodoFilter): String = when (filter) {
    TodoFilter.DONE -> "还没有已完成的事"
    TodoFilter.ARCHIVED -> "还没有放下的事"
    else -> "把想做的事记下来"
}

private fun emptyTodoDetail(filter: TodoFilter): String = when (filter) {
    TodoFilter.DONE -> "慢慢来，走一步算一步。"
    TodoFilter.ARCHIVED -> "放下不是删除，是承认它这阵子不重要。"
    TodoFilter.PENDING -> "记下一件想做的事，也可以把它拆成几个小步。"
}

private fun periodName(period: Period): String = when (period) {
    Period.DAY -> "日"
    Period.WEEK -> "周"
    Period.MONTH -> "月"
    Period.YEAR -> "年"
}

private fun trendLabels(points: List<TrendPoint>, period: Period): List<String> {
    if (points.isEmpty()) return emptyList()
    val indexes = listOf(0, points.lastIndex / 2, points.lastIndex).distinct()
    return indexes.map { index ->
        val label = points[index].label
        if (period == Period.YEAR) "${label}月" else label.substringAfter("-")
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Caption(label)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ChartWithScale(values: List<Long>, labels: List<String>, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption("最高 ${durationText(values.maxOrNull() ?: 0)}")
        BarChart(values, labels, description)
    }
}

@Composable
private fun ShareRow(label: String, seconds: Long, total: Long) {
    val fraction = if (total > 0) (seconds.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
        Caption("${durationText(seconds)} · ${(fraction * UiLimits.MAX_PERCENT).toInt()}%")
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), trackColor = SoftSurface)
    }
}

@Composable
private fun MonthlyCalendar(sessions: List<FocusSession>, anchor: LocalDate, onSelectDay: (LocalDate) -> Unit) {
    val first = anchor.withDayOfMonth(1)
    val zone = ZoneId.systemDefault()
    val totals = remember(sessions, first, zone) {
        Statistics.select(sessions, Period.MONTH, first).groupBy { Instant.ofEpochMilli(it.endedAt).atZone(zone).toLocalDate() }
            .mapValues { (_, records) -> records.sumOf { it.durationSeconds } }
    }
    val maximum = (totals.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val offset = first.dayOfWeek.value - 1
    val cells = ((offset + first.lengthOfMonth() + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK) * DAYS_PER_WEEK
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("${first.year} 年 ${first.monthValue} 月 · 专注日历")
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, color = Muted, fontSize = 12.sp)
            }
        }
        repeat(cells / DAYS_PER_WEEK) { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(DAYS_PER_WEEK) { weekday ->
                    val day = week * DAYS_PER_WEEK + weekday - offset + 1
                    if (day !in 1..first.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f))
                    else {
                        val date = first.withDayOfMonth(day)
                        val seconds = totals[date] ?: 0L
                        val intensity = seconds.toFloat() / maximum
                        val background = if (seconds == 0L) SoftSurface else Accent.copy(alpha = 0.16f + intensity * 0.84f)
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(background)
                                .clickable { onSelectDay(date) }.semantics { contentDescription = "$date，专注 ${durationText(seconds)}，查看当天" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(day.toString(), fontSize = 12.sp, color = if (intensity > 0.55f) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = if (date == anchor) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        Caption("周一开周 · 颜色越深，专注越多 · 点日期查看明细")
    }
}

@Composable
private fun SessionRow(session: FocusSession, progress: ProgressEntry?, onProgress: (FocusSession) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(session.projectTitle, style = MaterialTheme.typography.titleMedium)
        Caption("${timestampText(session.endedAt)} · ${session.category}")
        Text(durationText(session.durationSeconds), color = Accent, fontWeight = FontWeight.Medium)
        Caption("${modeName(session.timerMode)} · ${session.targetMinutes?.let { "目标 ${targetText(it)}" } ?: "不限时"}")
        if (progress != null) {
            progress.percent?.let { Caption("完成度 $it%") }
            Text(progress.note, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { onProgress(session) }) { Text(if (progress == null) "补写进度" else "修改进度") }
        HorizontalDivider()
    }
}

@Composable
internal fun UsageScreen(
    report: UsageReport?,
    permissionGranted: Boolean,
    loading: Boolean,
    error: String?,
    week: Boolean,
    settings: AppSettings,
    onGrant: () -> Unit,
    onRefresh: () -> Unit,
    onWeek: (Boolean) -> Unit,
    onGoal: () -> Unit,
    onReminders: (Boolean) -> Unit,
    onHelp: () -> Unit,
    today: LocalDate
) {
    val reading = permissionGranted && error == null && (loading || report == null)
    val visibleReport = report.takeIf { permissionGranted && !reading && error == null }
    ScreenBody {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption("了解习惯，而不是责备自己")
                Text(buildAnnotatedString {
                    append("时间足迹")
                    withStyle(SpanStyle(color = Accent)) { append(".") }
                }, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onRefresh, enabled = permissionGranted && !loading) {
                Icon(Icons.Outlined.Refresh, "刷新使用时长", tint = Muted)
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SoftSurface).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, tint = Muted, modifier = Modifier.size(16.dp))
            Text(if (permissionGranted) "已授权 · 本机真实使用统计" else "需授权「使用情况访问权限」", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            TextButton(onClick = if (permissionGranted) onHelp else onGrant) { Text(if (permissionGranted) "说明" else "授权") }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SoftSurface).padding(4.dp).selectableGroup()) {
            listOf("今日", "本周").forEachIndexed { index, label ->
                val selected = week == (index == 1)
                Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (selected) Color.White else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onWeek(index == 1) }).heightIn(min = 44.dp).padding(10.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) Accent else Muted, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption(if (week) "本周应用使用" else "今日应用使用")
            if (visibleReport?.hasData == true) UsageTotal(visibleReport.totalMillis)
            else Text("—", fontSize = 36.sp, color = Muted)
            Caption(when {
                !permissionGranted -> "授权后展示，不使用示例数据"
                reading -> "正在读取系统使用事件…"
                error != null -> "读取失败，请刷新重试"
                visibleReport?.hasData != true -> "暂无可确认数据，不代表用时为零"
                week -> "周一至今 · 根据系统前台事件统计"
                else -> "根据系统前台事件统计 · 不含专注助手"
            })
        }
        UsageChart(visibleReport?.buckets.orEmpty(), week, today)
        if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null && permissionGranted) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (!permissionGranted) OutlinedButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("授予使用情况访问权限") }
        if (visibleReport?.hasData == true) Text("系统记录可能不完整，统计口径见说明。", color = Muted, fontSize = 12.sp)
        UsageGoalCard(settings.dailyGoalMinutes, visibleReport?.todayMillis, onGoal)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("时间花在哪里", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Caption("按使用时长")
        }
        if (visibleReport?.apps.isNullOrEmpty()) Caption(if (permissionGranted) "暂无可展示的应用记录" else "授权后查看应用用时分布")
        visibleReport?.apps?.forEachIndexed { index, app -> UsageAppRow(app, visibleReport.totalMillis, index) }
        HorizontalDivider(color = Color(0xFFEEEEEE))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("每日超时提醒", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Caption("接近生活，而不是被屏幕占满")
            }
            Switch(checked = settings.usageReminders, onCheckedChange = onReminders)
        }
        Caption("约每 15 分钟检查，每天最多提醒一次；需要通知权限，省电限制可能造成延迟。")
    }
}

@Composable
private fun UsageTotal(millis: Long) {
    val minutes = millis / UiLimits.MILLIS_PER_SECOND / UiLimits.SECONDS_PER_MINUTE
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 36.sp, color = MaterialTheme.colorScheme.onSurface)) { append((minutes / UiLimits.MINUTES_PER_HOUR).toString()) }
        append(" 小时 ")
        withStyle(SpanStyle(fontSize = 36.sp, color = MaterialTheme.colorScheme.onSurface)) { append((minutes % UiLimits.MINUTES_PER_HOUR).toString()) }
        append(" 分钟")
    }, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun UsageChart(buckets: List<UsageBucket>, week: Boolean, today: LocalDate) {
    var selected by remember(week, today) { mutableIntStateOf(-1) }
    val count = if (week) DAYS_PER_WEEK else HOURS_PER_DAY / USAGE_HOURS_PER_BUCKET
    val maximum = buckets.maxOfOrNull { it.durationMillis }?.coerceAtLeast(1L) ?: 1L
    val highlighted = if (selected >= 0) selected else if (week) today.dayOfWeek.value - 1 else buckets.indexOfFirst { it.durationMillis == maximum }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val gridLines = 3
                repeat(gridLines + 1) { index ->
                    val y = size.height * index / gridLines
                    drawLine(Color(0xFFEDEDED), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
            }
            Row(Modifier.fillMaxSize().selectableGroup()) {
                repeat(count) { index ->
                    val bucket = buckets.getOrNull(index)
                    val label = usageBucketLabel(index, week)
                    val available = bucket?.available == true
                    val description = "$label：${if (available) durationText(bucket!!.durationMillis / UiLimits.MILLIS_PER_SECOND) else "暂无数据或时段尚未开始"}"
                    Box(Modifier.weight(1f).fillMaxHeight().selectable(selected == index, role = Role.Tab, onClick = { selected = index })
                        .semantics { contentDescription = description }, contentAlignment = Alignment.BottomCenter) {
                        if (available && bucket!!.durationMillis > 0) Box(Modifier.padding(horizontal = 3.dp).widthIn(max = 24.dp).fillMaxWidth()
                            .height((110f * bucket.durationMillis.toFloat() / maximum).coerceAtLeast(2f).dp)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(if (index == highlighted) Accent else Color(0xFFBDBDBD)))
                        else Text(if (available) "0" else "—", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val compact = maxWidth / fontScale < 300.dp
            val labels = if (week) USAGE_WEEK_LABELS else if (compact) listOf("00", "06", "12", "18", "24") else listOf("00:00", "06:00", "12:00", "18:00", "24:00")
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    Text(label, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (week) TextAlign.Center else when (index) { 0 -> TextAlign.Start; labels.lastIndex -> TextAlign.End; else -> TextAlign.Center }, modifier = Modifier.weight(1f))
                }
            }
        }
        val bucket = buckets.getOrNull(selected)
        Text(if (bucket == null) "点击柱形查看用时 · — 表示暂无数据或尚未开始"
            else "${usageBucketLabel(selected, week)} · ${if (bucket.available) durationText(bucket.durationMillis / UiLimits.MILLIS_PER_SECOND) else "暂无数据或时段尚未开始"}",
            color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun UsageGoalCard(goalMinutes: Int, todayMillis: Long?, onGoal: () -> Unit) {
    val goalSeconds = goalMinutes * UiLimits.SECONDS_PER_MINUTE
    val seconds = todayMillis?.div(UiLimits.MILLIS_PER_SECOND)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SoftSurface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TrackChanges, null, tint = Muted, modifier = Modifier.size(16.dp))
            Text("每日使用目标", modifier = Modifier.weight(1f).padding(start = 6.dp), fontSize = 13.sp)
            TextButton(onClick = onGoal, contentPadding = PaddingValues(start = 6.dp)) {
                Text(durationText(goalSeconds), fontSize = 12.sp)
                Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp))
            }
        }
        if (seconds != null) LinearProgressIndicator(progress = { (seconds.toFloat() / goalSeconds.coerceAtLeast(1L)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), trackColor = Color(0xFFDEDEDE))
        Caption(when {
            seconds == null -> "今日暂无可确认数据，稍后刷新查看目标进度。"
            seconds > goalSeconds -> "今天已超出目标 ${durationText(seconds - goalSeconds)}，给眼睛一点休息。"
            seconds == goalSeconds -> "今天已达到目标时长，休息一下吧。"
            else -> "今天还剩 ${durationText(goalSeconds - seconds)}，留一点时间给生活。"
        })
    }
}

@Composable
private fun UsageAppRow(app: AppUsage, totalMillis: Long, index: Int) {
    val colors = listOf(Color(0xFFF5E8DC) to Color(0xFF995C2C), Color(0xFFF4E5E7) to Color(0xFF9B6070), Color(0xFFE9E7F2) to Color(0xFF71608F), Color(0xFFE5EBF3) to Color(0xFF536F92))
    val (background, ink) = colors[index % colors.size]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(background), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).semantics { contentDescription = app.packageName }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.label, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(durationText(app.durationMillis / UiLimits.MILLIS_PER_SECOND), color = Muted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 140.dp))
            }
            LinearProgressIndicator(progress = { if (totalMillis > 0L) (app.durationMillis.toFloat() / totalMillis).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp), color = Color(0xFFA3A3A3), trackColor = Color(0xFFEDEDED))
        }
    }
}

@Composable
internal fun SettingsScreen(
    state: AppState,
    notificationAllowed: Boolean,
    exactAllowed: Boolean,
    usageAllowed: Boolean,
    onNotifications: () -> Unit,
    onUsagePermission: () -> Unit,
    onExactAlarm: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onRestSettings: () -> Unit,
    onAutomation: () -> Unit
) {
    ScreenBody {
        PageTitle("我的")
        SectionTitle("专注助手")
        Caption("把时间留给重要的事。")
        SectionTitle("偏好")
        ActionRow("休息时长", "短休息 ${state.settings.shortBreakMinutes} 分钟 · 长休息 ${state.settings.longBreakMinutes} 分钟", onRestSettings)
        HorizontalDivider()
        SectionTitle("系统权限")
        ActionRow("通知提醒", if (notificationAllowed) "已开启 · 管理系统通知" else "未开启 · 结束与超时提醒可能不可见", onNotifications)
        ActionRow("精确闹钟", if (exactAllowed) "已允许 · 管理系统授权" else "未允许 · 后台到时提醒可能延迟", onExactAlarm)
        ActionRow("使用情况访问", if (usageAllowed) "已允许 · 读取应用前台用时" else "未允许 · 无法读取手机用时", onUsagePermission)
        HorizontalDivider()
        SectionTitle("数据与备份")
        Caption("本机保存 ${state.projects.size} 个项目、${state.todos.size} 项待办和 ${state.sessions.size} 次专注。")
        ActionRow("导出完整备份", "保存项目、待办、专注、进度历史和偏好", onBackup)
        ActionRow("从文件恢复", "恢复前需要确认，当前计时须先结束", onRestore)
        Caption("备份文件未加密，请妥善保管。卸载应用会清除本机数据。")
        HorizontalDivider()
        SectionTitle("隐私与扩展")
        Text("学习记录保存在本机，不上传到云端。使用时长来自 Android 系统事件，不读取其他应用内容。", style = MaterialTheme.typography.bodyMedium, color = Muted)
        ActionRow("自动化扩展", "尚未接入脚本引擎 · 不执行手机自动化", onAutomation)
        Caption("本版本不申请 root 或无障碍控制权限。")
    }
}

@Composable
private fun ActionRow(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Caption(detail)
        }
        Icon(Icons.Outlined.ChevronRight, "打开$title", tint = Muted, modifier = Modifier.padding(start = 12.dp))
    }
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
        onDismissRequest = onDismiss,
        title = { Text("${project?.title ?: state.sessions.find { it.projectId == projectId }?.projectTitle ?: "项目"} · 进度历史") },
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
