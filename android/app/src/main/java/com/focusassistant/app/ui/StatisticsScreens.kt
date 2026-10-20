package com.focusassistant.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusassistant.app.domain.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToLong

/** 统计页的五个区块，顺序即 LazyColumn 中各区块所在的条目序号。 */
private enum class StatsSection(val label: String) { OVERVIEW("概览"), TIME("时段"), SHARE("分布"), RANK("排行"), RECORDS("明细") }

private data class PeriodNames(val current: String, val previous: String, val other: String, val before: String)

private fun periodNames(period: Period): PeriodNames = when (period) {
    Period.DAY -> PeriodNames("今天", "昨天", "这一天", "前一天")
    Period.WEEK -> PeriodNames("本周", "上周", "这一周", "前一周")
    Period.MONTH -> PeriodNames("本月", "上月", "这个月", "前一个月")
    Period.YEAR -> PeriodNames("今年", "去年", "这一年", "前一年")
}

private const val STATS_PICKER_YEARS = 12
private const val STATS_MIN_YEAR = 1900
private const val STATS_MAX_YEAR = 2100
private const val SECONDS_PER_HOUR = 3600L

/** 周期导航中间的文字：直接显示周期本身，当前周期前加「今天 / 本周 / 本月 / 今年」。 */
private fun statsPeriodText(period: Period, anchor: LocalDate, today: LocalDate, allPeriod: Boolean, all: AllTimeSummary): String {
    if (allPeriod) return all.firstDate?.let { "$it 起 · 共 ${all.calendarDays} 天" } ?: "还没有专注记录"
    val (start, end) = Statistics.range(period, anchor)
    val last = end.minusDays(1)
    fun year(date: LocalDate) = if (date.year == today.year) "" else "${date.year}年"
    fun monthDay(date: LocalDate) = "${date.monthValue}月${date.dayOfMonth}日"
    val text = when (period) {
        Period.DAY -> "${year(start)}${monthDay(start)} ${weekdayLabel(start)}"
        Period.WEEK -> "${year(start)}${monthDay(start)}–" + when {
            start.monthValue == last.monthValue && start.year == last.year -> "${last.dayOfMonth}日"
            start.year == last.year -> monthDay(last)
            else -> "${year(last)}${monthDay(last)}"
        }
        Period.MONTH -> "${start.year}年${start.monthValue}月"
        Period.YEAR -> "${start.year}年"
    }
    return if (start == Statistics.range(period, today).first) "${periodNames(period).current} · $text" else text
}

private sealed interface RecordItem { val key: String }
private data class RecordDay(val date: LocalDate, val count: Int, val seconds: Long) : RecordItem { override val key = "day-$date" }
private data class RecordEntry(val session: FocusSession) : RecordItem { override val key = "record-${session.id}" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsScreen(
    state: AppState,
    period: Period,
    allPeriod: Boolean,
    anchor: LocalDate,
    recordLimit: Int,
    today: LocalDate,
    onPeriod: (Period) -> Unit,
    onAll: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onAnchor: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onExport: () -> Unit,
    onMoreRecords: () -> Unit,
    onProgress: (FocusSession) -> Unit,
    onSelectDay: (LocalDate) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val reduceMotion = rememberReduceMotion()
    val all = remember(state.sessions, today) { Statistics.allTime(state.sessions, today) }
    val selected = remember(state.sessions, period, anchor, allPeriod, today) {
        if (allPeriod) Statistics.allTimeSessions(state.sessions, today) else Statistics.select(state.sessions, period, anchor).sortedByDescending { it.endedAt }
    }
    val summary = remember(selected) { Statistics.summary(selected) }
    val hours = remember(selected) { Statistics.hours(selected) }
    val trend = remember(state.sessions, period, anchor, allPeriod, today) {
        when {
            allPeriod -> Statistics.monthlyTrend(state.sessions, today)
            period == Period.WEEK || period == Period.YEAR -> Statistics.trend(state.sessions, period, anchor)
            else -> emptyList()
        }
    }
    val comparison = remember(state.sessions, period, anchor) { Statistics.compare(state.sessions, period, anchor) }
    val daily = remember(state.sessions) { Statistics.dailySeconds(state.sessions) }
    val current = allPeriod || Statistics.range(period, anchor).first == Statistics.range(period, today).first
    val names = periodNames(period)
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val openRecords = remember { mutableStateMapOf<String, Boolean>() }
    val activeSection by remember {
        derivedStateOf {
            if (!listState.canScrollForward && listState.firstVisibleItemIndex > 0) StatsSection.RECORDS.ordinal
            else listState.firstVisibleItemIndex.coerceAtMost(StatsSection.RECORDS.ordinal)
        }
    }
    val recordItems = remember(selected, recordLimit) {
        val totals = selected.groupBy { Statistics.completionDate(it, zone) }
        buildList<RecordItem> {
            var day: LocalDate? = null
            selected.take(recordLimit.coerceAtLeast(0)).forEach { session ->
                val date = Statistics.completionDate(session, zone)
                if (date != day) {
                    day = date
                    val records = totals[date].orEmpty()
                    add(RecordDay(date, records.size, records.sumOf { it.durationSeconds }))
                }
                add(RecordEntry(session))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 吸顶：标题、周期切换、周期导航与区块跳转不随内容滚动。
        Column(Modifier.fillMaxWidth().background(Color.White).padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            PageTitle("专注统计", onBack) {
                IconButton(onClick = onExport, enabled = selected.isNotEmpty()) { Icon(Icons.Outlined.FileDownload, "导出当前周期") }
            }
            val options = listOf<Pair<String, Period?>>("日" to Period.DAY, "周" to Period.WEEK, "月" to Period.MONTH, "年" to Period.YEAR, "全部" to null)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (label, value) ->
                    val active = if (value == null) allPeriod else !allPeriod && period == value
                    SegmentedButton(selected = active, onClick = { if (value == null) onAll() else onPeriod(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size), icon = {},
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = AccentSoft, activeContentColor = AccentDark, activeBorderColor = Line, inactiveBorderColor = Line)) {
                        Text(label, fontSize = 13.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!allPeriod) IconButton(onClick = onPrevious) { Icon(Icons.Outlined.ChevronLeft, "上一统计周期") }
                TextButton(onClick = { pickerOpen = true }, enabled = !allPeriod, modifier = Modifier.weight(1f)) {
                    Text(statsPeriodText(period, anchor, today, allPeriod, all), color = Ink, textAlign = TextAlign.Center,
                        fontSize = if (allPeriod) 13.sp else 15.sp, fontWeight = if (allPeriod) FontWeight.Normal else FontWeight.SemiBold)
                    if (!allPeriod) Icon(Icons.Outlined.ExpandMore, "选择统计周期", tint = Muted, modifier = Modifier.padding(start = 2.dp).size(16.dp))
                }
                if (!allPeriod) IconButton(onClick = onNext) { Icon(Icons.Outlined.ChevronRight, "下一统计周期") }
                if (!current) TextButton(onClick = onToday, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("回到${names.current}", fontSize = 12.sp) }
            }
        }
        PrimaryTabRow(selectedTabIndex = activeSection, containerColor = Color.White, contentColor = AccentDark) {
            StatsSection.entries.forEach { section ->
                Tab(selected = activeSection == section.ordinal, onClick = {
                    scope.launch { if (reduceMotion) listState.scrollToItem(section.ordinal) else listState.animateScrollToItem(section.ordinal) }
                }, text = { Text(section.label, fontSize = 13.sp) }, selectedContentColor = AccentDark, unselectedContentColor = Muted)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)) {
            item(key = "overview") { StatsOverview(allPeriod, current, names, summary, all, comparison, onHelp) }
            item(key = "time") {
                Column {
                    StatsHeading("时段")
                    when {
                        !allPeriod && period == Period.DAY -> DayTimeline(selected, anchor, zone)
                        !allPeriod && period == Period.MONTH -> MonthHeatCalendar(anchor, today, daily, onSelectDay)
                        else -> TrendChart(trend, allPeriod, period)
                    }
                    Spacer(Modifier.height(26.dp))
                    ScrubBars(hours, { hour -> "%02d:00–%02d:00".format(hour, hour + 1) }, "最专注", "这个周期还没有专注时段", "专注时段分布", 2.dp) { HourAxis() }
                    StatsCaption("按实际专注片段逐小时拆分，暂停时间不计入；点按或左右滑动查看具体时段。", Modifier.padding(top = 6.dp))
                }
            }
            item(key = "share") { ShareSection(selected, summary.seconds) }
            item(key = "rank") { RankSection(selected) }
            item(key = "records-heading") { StatsHeading("明细", "${selected.size} 条记录") }
            if (selected.isEmpty()) item(key = "records-empty") { StatsCaption("这段时间还没有记录。只有已完成的专注会计入统计，休息和放弃的计时不会保存。") }
            items(recordItems, key = { it.key }) { item ->
                when (item) {
                    is RecordDay -> RecordDayHeader(item, today)
                    is RecordEntry -> RecordRow(item.session, Statistics.latestForSession(state.progress, item.session.id), openRecords[item.session.id] == true,
                        { openRecords[item.session.id] = openRecords[item.session.id] != true }, onProgress)
                }
            }
            if (selected.size > recordLimit) item(key = "records-more") {
                OutlinedButton(onClick = onMoreRecords, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("查看更多记录（已显示 ${recordLimit.coerceAtLeast(0)} / ${selected.size}）")
                }
            }
            item(key = "method") { MethodNote() }
        }
    }
    if (pickerOpen && !allPeriod) PeriodPickerSheet(period, anchor, today, daily, { pickerOpen = false }, { pickerOpen = false; onAnchor(it) }, { pickerOpen = false; onPickDate() })
}

@Composable
private fun StatsCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = modifier)
}

@Composable
private fun StatsHeading(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        if (detail != null) Text(detail, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun StatsMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink)
        Text(label, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun StatsOverview(allPeriod: Boolean, current: Boolean, names: PeriodNames, summary: Summary, all: AllTimeSummary, comparison: Comparison, onHelp: () -> Unit) {
    Surface(color = AccentSoft, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            if (allPeriod) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("累计总时长", fontSize = 12.sp, color = Muted)
                    HelpButton(onHelp)
                }
                Text(durationText(all.seconds), fontSize = 30.sp, fontWeight = FontWeight.Light, color = AccentDark)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatsMetric("自然日均 / 分钟", minuteText(all.calendarAverageSeconds), Modifier.weight(1f))
                    StatsMetric("活跃日均 / 分钟", minuteText(all.activeAverageSeconds), Modifier.weight(1f))
                }
            } else {
                Text(if (current) "${names.current}专注" else "${names.other}专注", fontSize = 12.sp, color = AccentDark, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 2.dp)) {
                    Text(trimDecimal(summary.seconds / UiLimits.SECONDS_PER_MINUTE.toDouble()), fontSize = 48.sp, fontWeight = FontWeight.Light,
                        letterSpacing = (-1).sp, color = Ink, modifier = Modifier.alignByBaseline())
                    Text(" 分钟", fontSize = 12.sp, color = AccentDark, modifier = Modifier.alignByBaseline())
                }
                if (comparison.current > 0 || comparison.previous > 0) {
                    val name = if (current) names.previous else names.before
                    val minutes = (comparison.difference / UiLimits.SECONDS_PER_MINUTE.toDouble()).roundToLong()
                    val text = when {
                        comparison.previous == 0L -> "${name}没有记录"
                        minutes == 0L -> "与${name}持平"
                        else -> "${if (minutes > 0) "▲" else "▼"} 比$name${if (minutes > 0) "多" else "少"} ${minutesLabel(abs(minutes) * UiLimits.SECONDS_PER_MINUTE)}"
                    }
                    Text(text, fontSize = 12.sp, color = if (minutes > 0 && comparison.previous > 0) AccentDark else Muted,
                        modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(Color.White).padding(horizontal = 10.dp, vertical = 2.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFFF1D3C4))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsMetric("完成专注", summary.count.toString(), Modifier.weight(1f))
                StatsMetric("专注天数", summary.activeDays.toString(), Modifier.weight(1f))
                if (!allPeriod) StatsMetric("活跃日均 / 分钟", minuteText(summary.averageSeconds), Modifier.weight(1f))
            }
        }
    }
}

/** 点按或按住左右滑动选择一列；不消费事件，纵向滚动仍交给列表。 */
private suspend fun PointerInputScope.scrubIndex(count: Int, onIndex: (Int) -> Unit) {
    if (count == 0) return
    val width = size.width.toFloat().coerceAtLeast(1f)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onIndex((down.position.x / width * count).toInt().coerceIn(0, count - 1))
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            onIndex((change.position.x / width * count).toInt().coerceIn(0, count - 1))
        }
    }
}

/** 可交互柱状图：默认读数为峰值，点按或滑动后显示所选一列，其余柱子变淡。 */
@Composable
private fun ScrubBars(values: List<Long>, labelFor: (Int) -> String, defaultPrefix: String, emptyText: String, title: String, gap: Dp, axis: @Composable () -> Unit) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val peak = values.maxOrNull() ?: 0L
    val index = selected ?: values.indexOf(peak).takeIf { peak > 0 && it >= 0 }
    val readout = when {
        index == null -> emptyText
        selected == null -> "$defaultPrefix ${labelFor(index)} · ${minutesLabel(values[index])}"
        else -> "${labelFor(index)} · ${minutesLabel(values[index])}"
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(readout, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AccentDark, textAlign = TextAlign.End)
        }
        Text(if (peak > 0) minutesLabel(peak) else "", fontSize = 11.sp, color = Muted, modifier = Modifier.align(Alignment.End))
        Canvas(Modifier.fillMaxWidth().height(124.dp)
            .pointerInput(values) { scrubIndex(values.size) { selected = it } }
            .semantics {
                contentDescription = title
                stateDescription = readout
                customActions = listOf(
                    CustomAccessibilityAction("上一项") { if (values.isEmpty()) false else { selected = ((index ?: 0) - 1).coerceAtLeast(0); true } },
                    CustomAccessibilityAction("下一项") { if (values.isEmpty()) false else { selected = ((index ?: -1) + 1).coerceAtMost(values.lastIndex); true } }
                )
            }) {
            drawLine(Color(0xFFECECEC), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            drawLine(Color(0xFFF4F4F4), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            if (values.isEmpty()) return@Canvas
            val cell = size.width / values.size
            val spacing = gap.toPx().coerceAtMost(cell * 0.5f)
            values.forEachIndexed { position, value ->
                val height = maxOf(2.dp.toPx(), if (peak > 0) size.height * value / peak else 0f)
                val color = when {
                    position == selected -> AccentDark
                    value == 0L -> BarEmpty
                    value == peak -> Accent
                    else -> BarIdle
                }
                drawRoundRect(color.copy(alpha = if (selected != null && position != selected) 0.35f else 1f),
                    Offset(position * cell + spacing / 2, size.height - height), Size(cell - spacing, height), CornerRadius(4.dp.toPx()))
            }
        }
        HorizontalDivider(color = Line)
        axis()
    }
}

@Composable
private fun TrendChart(trend: List<TrendPoint>, allPeriod: Boolean, period: Period) {
    fun label(point: TrendPoint): String = when {
        allPeriod -> point.label
        period == Period.YEAR -> "${point.label}月"
        else -> point.label.substringAfter("-")
    }
    val count = trend.size
    val positions = if (count <= 7) trend.indices.toList() else List(5) { it * (count - 1) / 4 }.distinct()
    ScrubBars(trend.map { it.seconds }, { label(trend[it]) }, "最多", "这个周期还没有专注记录", if (allPeriod) "每月专注时长" else "专注时长趋势", 4.dp) {
        PositionedLabels(positions.map { (it + 0.5f) / count to label(trend[it]) }, Modifier.padding(top = 6.dp))
    }
    StatsCaption("按专注结束时刻汇总；点按或左右滑动查看每一项。", Modifier.padding(top = 6.dp))
}

/** 日视图：当天时间线，每段专注按真实起止时间画出，点按或滑动选中最近的一段。 */
@Composable
private fun DayTimeline(sessions: List<FocusSession>, anchor: LocalDate, zone: ZoneId) {
    val dayStart = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = anchor.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayMs = (dayEnd - dayStart).toFloat()
    val segments = remember(sessions, dayStart) {
        sessions.flatMap { session -> session.segments.map { Triple(session, maxOf(dayStart, it.startedAt), minOf(dayEnd, it.endedAt)) } }
            .filter { it.third > it.second }.sortedBy { it.second }
    }
    var selected by remember(segments) { mutableStateOf<Int?>(null) }
    val readout = selected?.let { index ->
        val (session, from, to) = segments[index]
        "${clockLabel(from, zone)}–${clockLabel(to, zone)} · ${session.projectTitle} · ${minutesLabel((to - from) / UiLimits.MILLIS_PER_SECOND)}"
    } ?: if (segments.isEmpty()) "这一天没有专注记录"
        else "${segments.size} 段专注 · ${clockLabel(segments.first().second, zone)} 开始 · ${clockLabel(segments.last().third, zone)} 结束"
    val now = System.currentTimeMillis()
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当天时间线", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(readout, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AccentDark, textAlign = TextAlign.End)
        }
        Canvas(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(SoftSurface)
            .pointerInput(segments) {
                if (segments.isEmpty()) return@pointerInput
                val width = size.width.toFloat().coerceAtLeast(1f)
                fun nearest(x: Float): Int {
                    val time = dayStart + (x / width).coerceIn(0f, 1f) * dayMs
                    return segments.indices.minByOrNull { index ->
                        val (_, from, to) = segments[index]
                        when { time < from -> from - time; time > to -> time - to; else -> 0f }
                    } ?: 0
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    selected = nearest(down.position.x)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        selected = nearest(change.position.x)
                    }
                }
            }
            .semantics {
                contentDescription = "当天专注时间线"
                stateDescription = readout
                customActions = listOf(
                    CustomAccessibilityAction("上一段") { if (segments.isEmpty()) false else { selected = ((selected ?: 0) - 1).coerceAtLeast(0); true } },
                    CustomAccessibilityAction("下一段") { if (segments.isEmpty()) false else { selected = ((selected ?: -1) + 1).coerceAtMost(segments.lastIndex); true } }
                )
            }) {
            segments.forEachIndexed { index, (_, from, to) ->
                val chosen = index == selected
                val inset = (if (chosen) 4 else 8).dp.toPx()
                val left = size.width * (from - dayStart) / dayMs
                val width = maxOf(4.dp.toPx(), size.width * (to - from) / dayMs)
                drawRoundRect((if (chosen) AccentDark else Accent).copy(alpha = if (selected != null && !chosen) 0.35f else 1f),
                    Offset(left, inset), Size(width, size.height - inset * 2), CornerRadius(3.dp.toPx()))
            }
            if (now in dayStart until dayEnd) {
                val x = size.width * (now - dayStart) / dayMs
                drawLine(Ink, Offset(x, 2.dp.toPx()), Offset(x, size.height - 2.dp.toPx()), 1.5.dp.toPx())
            }
        }
        HourAxis(Modifier.padding(top = 2.dp))
    }
}

/** 月视图：热力月历，点日期进入当天。 */
@Composable
private fun MonthHeatCalendar(anchor: LocalDate, today: LocalDate, daily: Map<LocalDate, Long>, onSelectDay: (LocalDate) -> Unit) {
    val first = anchor.withDayOfMonth(1)
    val days = (1..first.lengthOfMonth()).map { first.withDayOfMonth(it) }
    val peak = days.maxOfOrNull { daily[it] ?: 0L } ?: 0L
    val offset = first.dayOfWeek.value - 1
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("专注日历", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${first.year} 年 ${first.monthValue} 月", fontSize = 12.sp, color = Muted)
        }
        WeekdayRow()
        (List<LocalDate?>(offset) { null } + days).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                week.forEach { date ->
                    if (date == null) Spacer(Modifier.weight(1f).aspectRatio(1f))
                    else {
                        val seconds = daily[date] ?: 0L
                        val (background, foreground) = heatColors(heatLevel(seconds, peak))
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(9.dp)).background(background)
                            .border(2.dp, if (date == today) Ink else Color.Transparent, RoundedCornerShape(9.dp))
                            .clickable { onSelectDay(date) }
                            .semantics { contentDescription = "$date，专注 ${durationText(seconds)}，查看当天" }, contentAlignment = Alignment.Center) {
                            Text(date.dayOfMonth.toString(), fontSize = 12.sp, color = foreground)
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("点日期查看当天", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
            Text("少", fontSize = 11.sp, color = Muted)
            (0..VisualLimits.HEAT_LEVELS).forEach { level ->
                Box(Modifier.padding(start = 5.dp).size(11.dp).clip(RoundedCornerShape(3.dp)).background(heatColors(level).first))
            }
            Text("多", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

@Composable
private fun WeekdayRow() {
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
            Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ShareSection(sessions: List<FocusSession>, totalSeconds: Long) {
    val categories = remember(sessions) {
        sessions.groupBy { it.category.ifBlank { "未分类" } }.map { (name, records) -> name to records.sumOf { it.durationSeconds } }.sortedByDescending { it.second }
    }
    val totalMinutes = totalSeconds / UiLimits.SECONDS_PER_MINUTE.toDouble()
    val inHours = totalMinutes >= VisualLimits.HOURS_DISPLAY_MINUTES
    Column {
        StatsHeading("分布", "按专注项分类")
        Row(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(17.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(108.dp).semantics {
                contentDescription = if (categories.isEmpty()) "暂无分类统计" else "共 ${minutesLabel(totalSeconds)}：" + categories.joinToString("；") { "${it.first} ${durationText(it.second)}" }
            }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 16.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    if (categories.isEmpty() || totalSeconds <= 0) drawCircle(Color(0xFFF2F2F2), (size.minDimension - stroke) / 2, style = Stroke(stroke))
                    else {
                        var start = -90f
                        categories.forEachIndexed { index, (_, seconds) ->
                            val sweep = 360f * seconds / totalSeconds
                            drawArc(CATEGORY_COLORS[index % CATEGORY_COLORS.size], start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                            start += sweep
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (inHours) trimDecimal(totalMinutes / UiLimits.MINUTES_PER_HOUR) else totalMinutes.roundToLong().toString(), fontSize = 22.sp, color = Ink)
                    Text(if (inHours) "小时" else "分钟", fontSize = 11.sp, color = Muted)
                }
            }
            Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                if (categories.isEmpty()) StatsCaption("完成专注后，查看你把时间留给了什么。")
                categories.forEachIndexed { index, (name, seconds) ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(CATEGORY_COLORS[index % CATEGORY_COLORS.size]))
                            Text(name, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                        Text("${durationText(seconds)} · ${if (totalSeconds > 0) seconds * UiLimits.MAX_PERCENT / totalSeconds else 0}%",
                            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(start = 13.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RankSection(sessions: List<FocusSession>) {
    val ranks = remember(sessions) {
        sessions.groupBy { it.projectId }.values.map { records -> records.maxByOrNull { it.endedAt }?.projectTitle.orEmpty() to records }
            .sortedByDescending { (_, records) -> records.sumOf { it.durationSeconds } }
    }
    val maximum = ranks.firstOrNull()?.second?.sumOf { it.durationSeconds }?.coerceAtLeast(1L) ?: 1L
    Column {
        StatsHeading("排行", "按累计时长")
        if (ranks.isEmpty()) StatsCaption("完成一次专注后，这里会出现你的积累。")
        ranks.forEachIndexed { index, (title, records) ->
            val seconds = records.sumOf { it.durationSeconds }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", fontSize = 12.sp, color = Muted, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${durationText(seconds)} / ${records.size} 次", fontSize = 12.sp, color = Muted)
                    }
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFF1F1F1))) {
                        Box(Modifier.fillMaxWidth(seconds.toFloat() / maximum).fillMaxHeight().background(Accent))
                    }
                }
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable
private fun RecordDayHeader(day: RecordDay, today: LocalDate) {
    val heading = (if (day.date == today) "今天 · " else "") + (if (day.date.year == today.year) "" else "${day.date.year}年") +
        "${day.date.monthValue}月${day.date.dayOfMonth}日 ${weekdayLabel(day.date)}"
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(heading, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${day.count} 次 · ${minutesLabel(day.seconds)}", fontSize = 12.sp, color = Muted)
    }
}

/** 紧凑行：时间段 · 项目 · 时长；展开后才显示计时方式、精确时间、进度笔记与编辑入口。 */
@Composable
private fun RecordRow(session: FocusSession, progress: ProgressEntry?, expanded: Boolean, onToggle: () -> Unit, onProgress: (FocusSession) -> Unit) {
    val zone = ZoneId.systemDefault()
    val crossDay = Statistics.completionDate(session, zone) != java.time.Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle)
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }.padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("${if (crossDay) "前一天 " else ""}${clockLabel(session.startedAt, zone)}–${clockLabel(session.endedAt, zone)}", fontSize = 12.sp, color = Muted)
            Text(session.projectTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            Text(clockText(session.durationSeconds), fontSize = 13.sp, color = AccentDark)
            if (progress == null) Text("待补写", fontSize = 11.sp, color = AccentDark,
                modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(AccentSoft).padding(horizontal = 7.dp, vertical = 1.dp))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Muted, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatsCaption("${modeName(session.timerMode)} · ${session.category.ifBlank { "未分类" }} · ${session.targetMinutes?.let { "目标 ${targetText(it)}" } ?: "不限时"}\n" +
                "${timestampText(session.startedAt)} — ${timestampText(session.endedAt)}")
            if (progress != null) Text("${progress.percent?.let { "完成度 $it% · " } ?: ""}${progress.note}", fontSize = 13.sp, lineHeight = 20.sp)
            TextButton(onClick = { onProgress(session) }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (progress == null) "补写进度" else "编辑进度") }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun MethodNote() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }.semantics { stateDescription = if (open) "已展开" else "已收起" },
            verticalAlignment = Alignment.CenterVertically) {
            Text("统计口径", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Muted, modifier = Modifier.size(16.dp))
        }
        if (open) StatsCaption("倒计时到时或确认提前结束、正计时手动结束后计入。有效时长精确到秒，不含暂停和休息；放弃的计时不保存。" +
            "按本地结束日期筛选记录，时段分布再将这些记录的实际专注片段按小时拆分；日均按有记录的天数计算。「全部」汇总截至今天的全部记录。")
    }
}

/** 选择面板：日 / 周为热力月历（周视图点整行），月为 12 格，年为 12 年；另可打开系统日期选择器。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPickerSheet(period: Period, anchor: LocalDate, today: LocalDate, daily: Map<LocalDate, Long>,
    onDismiss: () -> Unit, onPick: (LocalDate) -> Unit, onPickDate: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialYear = if (today.year - anchor.year in 0 until STATS_PICKER_YEARS) today.year else anchor.year
    var cursor by remember(period, anchor) {
        mutableStateOf(when (period) {
            Period.MONTH -> anchor.withDayOfYear(1)
            Period.YEAR -> LocalDate.of(initialYear.coerceIn(STATS_MIN_YEAR, STATS_MAX_YEAR), 1, 1)
            else -> anchor.withDayOfMonth(1)
        })
    }
    fun shift(direction: Long) {
        val next = when (period) {
            Period.MONTH -> cursor.plusYears(direction)
            Period.YEAR -> cursor.plusYears(direction * STATS_PICKER_YEARS)
            else -> cursor.plusMonths(direction)
        }
        if (next.year in STATS_MIN_YEAR..STATS_MAX_YEAR) cursor = next
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shift(-1) }) { Icon(Icons.Outlined.ChevronLeft, if (period == Period.YEAR) "更早的年份" else if (period == Period.MONTH) "上一年" else "上一月") }
                Text(when (period) {
                    Period.YEAR -> "${(cursor.year - STATS_PICKER_YEARS + 1).coerceAtLeast(STATS_MIN_YEAR)}–${cursor.year} 年"
                    Period.MONTH -> "${cursor.year} 年"
                    else -> "${cursor.year} 年 ${cursor.monthValue} 月"
                }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { shift(1) }) { Icon(Icons.Outlined.ChevronRight, if (period == Period.YEAR) "更晚的年份" else if (period == Period.MONTH) "下一年" else "下一月") }
            }
            when (period) {
                Period.DAY, Period.WEEK -> PickerCalendar(period, cursor, anchor, today, daily, onPick)
                Period.MONTH -> {
                    val totals = (1..12).map { month -> daily.filterKeys { it.year == cursor.year && it.monthValue == month }.values.sum() }
                    val peak = totals.maxOrNull() ?: 0L
                    val chosen = Statistics.range(Period.MONTH, anchor).first
                    PickerGrid((1..12).map { month ->
                        val date = LocalDate.of(cursor.year, month, 1)
                        PickerCell("${month}月", if (totals[month - 1] > 0) minutesLabel(totals[month - 1]) else "—", heatLevel(totals[month - 1], peak), date == chosen) { onPick(date) }
                    })
                }
                Period.YEAR -> {
                    val years = ((cursor.year - STATS_PICKER_YEARS + 1)..cursor.year).filter { it in STATS_MIN_YEAR..STATS_MAX_YEAR }
                    val totals = years.map { year -> daily.filterKeys { it.year == year }.values.sum() }
                    val peak = totals.maxOrNull() ?: 0L
                    PickerGrid(years.mapIndexed { index, year ->
                        PickerCell("$year", if (totals[index] > 0) minutesLabel(totals[index]) else "—", heatLevel(totals[index], peak), year == anchor.year) { onPick(LocalDate.of(year, 1, 1)) }
                    })
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = Line)
            TextButton(onClick = onPickDate, modifier = Modifier.fillMaxWidth()) { Text("跳到其他日期…") }
        }
    }
}

private data class PickerCell(val title: String, val detail: String, val level: Int, val selected: Boolean, val onClick: () -> Unit)

@Composable
private fun PickerGrid(cells: List<PickerCell>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    val (background, foreground) = heatColors(cell.level)
                    Column(Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).background(background)
                        .border(2.dp, if (cell.selected) Ink else Color.Transparent, RoundedCornerShape(12.dp)).clickable(onClick = cell.onClick)
                        .semantics { contentDescription = "${cell.title}，专注 ${cell.detail}"; stateDescription = if (cell.selected) "已选择" else "" },
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
private fun PickerCalendar(period: Period, cursor: LocalDate, anchor: LocalDate, today: LocalDate, daily: Map<LocalDate, Long>, onPick: (LocalDate) -> Unit) {
    val first = cursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val monthEnd = cursor.plusMonths(1)
    val weeks = generateSequence(first) { it.plusWeeks(1) }.takeWhile { it < monthEnd }.toList()
    val peak = weeks.maxOfOrNull { week -> (0L..6L).maxOf { daily[week.plusDays(it)] ?: 0L } } ?: 0L
    val selectedWeek = Statistics.range(Period.WEEK, anchor).first
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WeekdayRow()
        weeks.forEach { week ->
            val weekMode = period == Period.WEEK
            val weekSeconds = (0L..6L).sumOf { daily[week.plusDays(it)] ?: 0L }
            val rowModifier = if (weekMode) Modifier.clip(RoundedCornerShape(13.dp))
                .border(2.dp, if (week == selectedWeek) Ink else Color.Transparent, RoundedCornerShape(13.dp))
                .clickable { onPick(week) }
                .semantics(mergeDescendants = true) { contentDescription = "$week 至 ${week.plusDays(6)}，专注 ${durationText(weekSeconds)}" }
                .padding(3.dp) else Modifier
            Row(Modifier.fillMaxWidth().then(rowModifier), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0L..6L).forEach { offset ->
                    val date = week.plusDays(offset)
                    val seconds = daily[date] ?: 0L
                    val (background, foreground) = heatColors(heatLevel(seconds, peak))
                    val dayModifier = if (weekMode) Modifier else Modifier
                        .border(2.dp, if (date == anchor) Ink else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onPick(date) }
                        .semantics { contentDescription = "$date，专注 ${durationText(seconds)}" }
                    Box(Modifier.weight(1f).height(if (weekMode) 34.dp else 40.dp).clip(RoundedCornerShape(10.dp)).background(background)
                        .then(dayModifier).alpha(if (date.monthValue == cursor.monthValue) 1f else 0.45f), contentAlignment = Alignment.Center) {
                        Text(date.dayOfMonth.toString(), fontSize = 13.sp, color = foreground,
                            fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
