package com.focusassistant.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
    selectedTask: Todo?,
    nowElapsed: Long,
    onBack: () -> Unit,
    onStatistics: () -> Unit,
    onSettings: () -> Unit,
    onLinkTask: () -> Unit,
    onMode: (TimerMode) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
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
            // 计时中收起模式切换，避免分心；只有待开始时可调整。
            if (!resting && project != null && timer == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TimerMode.entries.forEach { value ->
                        FilterChip(selected = mode == value, onClick = { if (mode != value) onMode(value) }, label = { Text(modeName(value)) }, modifier = Modifier.padding(horizontal = 4.dp))
                    }
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
                if (resting || mode == TimerMode.COUNTUP) {
                    OutlinedButton(onClick = onFinish, enabled = resting || elapsedMillis >= TimerEngine.MIN_SESSION_MS, modifier = Modifier.fillMaxWidth()) {
                        Text(if (resting) "结束休息" else "结束并保存专注")
                    }
                }
                TextButton(onClick = onDiscard, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (resting) "放弃本次休息" else "放弃本次专注", color = Muted) }
                if (!resting && mode == TimerMode.COUNTDOWN) Caption("倒计时到零自动保存；提前放弃不会生成专注记录。")
            }
            if (!resting) {
                OutlinedButton(onClick = onLinkTask, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (timer != null) timer.taskTitle ?: "关联待办" else selectedTask?.title ?: "关联待办", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
                if (timer?.taskTitle != null) Caption("本次记录保留开始时的待办快照。")
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
    onCreate: () -> Unit,
    onEdit: (Todo) -> Unit,
    onToggle: (Todo) -> Unit,
    onDelete: (Todo) -> Unit,
    onFocus: (Todo) -> Unit
) {
    ScreenBody {
        PageTitle("待办") { IconButton(onClick = onCreate) { Icon(Icons.Outlined.Add, "添加待办") } }
        Caption("${state.todos.count { !it.done }} 项待完成 · ${state.todos.count { it.done }} 项已完成")
        if (state.todos.isEmpty()) EmptyMessage("把想做的事记下来", "待办是具体行动，学习项目是持续积累。可以把待办关联到一次专注。")
        listOf(false, true).forEach { done ->
            val todos = state.todos.filter { it.done == done }.sortedByDescending { it.important }
            if (todos.isNotEmpty()) {
                SectionTitle(if (done) "已完成" else "待完成")
                todos.forEach { todo ->
                    Surface(color = SoftSurface, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(checked = todo.done, onCheckedChange = { onToggle(todo) })
                                Column(Modifier.weight(1f).clickable { onEdit(todo) }.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(todo.title, textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None, color = if (done) Muted else MaterialTheme.colorScheme.onSurface)
                                    Caption("${todo.category}${if (todo.important) " · 重要" else ""}")
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                if (!done) TextButton(onClick = { onFocus(todo) }) { Text("去专注") }
                                IconButton(onClick = { onEdit(todo) }) { Icon(Icons.Outlined.Edit, "编辑待办", tint = Muted) }
                                IconButton(onClick = { onDelete(todo) }) { Icon(Icons.Outlined.DeleteOutline, "删除待办", tint = Muted) }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("添加待办") }
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
        session.taskTitle?.let { Caption("关联待办：$it") }
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
