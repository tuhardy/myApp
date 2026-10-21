package com.focusassistant.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusassistant.app.domain.AppSettings
import com.focusassistant.app.domain.FocusSession
import com.focusassistant.app.domain.NightSummary
import com.focusassistant.app.domain.Period
import com.focusassistant.app.domain.Statistics
import com.focusassistant.app.domain.UsageInsights
import com.focusassistant.app.platform.AppUsage
import com.focusassistant.app.platform.UsageBucket
import com.focusassistant.app.platform.UsageReport
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 用时页（刻度版）：与浏览器原型 #page-usage 同款布局，数据全部来自本机前台事件与真实专注记录。 */
private val ScreenGray = Color(0xFF9A9A9A)
private val ScreenBar = Color(0xFFBDBDBD)
private val TodayBar = Color(0xFF6F6F6F)
private val NightTint = Color(0xFFE9ECEF)
private val NightCardSurface = Color(0xFFEEF1F4)
private val WheelIdle = Color(0xFFA3A3A3)
private val USAGE_WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

internal object UsageLimits {
    const val GOAL_TICKS = 40
    const val GOAL_MIN_MINUTES = 30
    const val GOAL_MAX_MINUTES = 1440
    const val GOAL_MINUTE_STEP = 5
    const val ZOOM_HOUR_DP = 56
    const val WHEEL_ITEM_DP = 44
    const val WHEEL_VISIBLE = 5
    const val ZOOM_HEIGHT_FRACTION = 0.88f
    const val ZOOM_NOW_FRACTION = 0.6f
    const val MILLIS_PER_MINUTE = 60_000L
    const val MILLIS_PER_HOUR = 3_600_000L
    const val HOURS_PER_DAY = 24
}

private fun dayBounds(today: LocalDate, zone: ZoneId): Pair<Long, Long> =
    today.atStartOfDay(zone).toInstant().toEpochMilli() to today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

/** 刻度环中央放不下「4 小时 30 分钟」，改用紧凑写法。 */
private fun compactGoal(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0 && rest > 0 -> "${hours}时${rest}分"
        hours > 0 -> "$hours 小时"
        else -> "$rest 分钟"
    }
}

/** 片段时长：不足一分钟显示秒，其余四舍五入到分钟。 */
private fun spanDuration(millis: Long): String =
    if (millis < UsageLimits.MILLIS_PER_MINUTE) "${(millis / UiLimits.MILLIS_PER_SECOND).coerceAtLeast(1)} 秒"
    else durationText((millis + UsageLimits.MILLIS_PER_MINUTE / 2) / UsageLimits.MILLIS_PER_MINUTE * UiLimits.SECONDS_PER_MINUTE)

@Composable
internal fun UsageScreen(
    report: UsageReport?,
    permissionGranted: Boolean,
    loading: Boolean,
    error: String?,
    week: Boolean,
    settings: AppSettings,
    sessions: List<FocusSession>,
    onGrant: () -> Unit,
    onRefresh: () -> Unit,
    onWeek: (Boolean) -> Unit,
    onGoal: () -> Unit,
    onZoom: () -> Unit,
    onReminders: (Boolean) -> Unit,
    onHelp: () -> Unit,
    today: LocalDate
) {
    val reading = permissionGranted && error == null && (loading || report == null)
    val visibleReport = report.takeIf { permissionGranted && !reading && error == null }
    val zone = ZoneId.systemDefault()
    val night = remember(visibleReport, week, today) {
        visibleReport?.takeIf { it.hasData }?.let { data ->
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val dates = if (week) generateSequence(monday) { it.plusDays(1) }.takeWhile { it <= today }.toList() else listOf(today)
            UsageInsights.night(data.spans, dates, zone)
        }
    }
    ScreenBody {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption("了解习惯，而不是责备自己")
                Text(buildAnnotatedString {
                    append("时间足迹")
                    withStyle(SpanStyle(color = Accent)) { append(".") }
                }, fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = (-0.9).sp, fontWeight = FontWeight.SemiBold)
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
                Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (selected) AccentSoft else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onWeek(index == 1) }).heightIn(min = 44.dp).padding(10.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) AccentDark else Muted, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        UsageHero(week, visibleReport, when {
            !permissionGranted -> "授权后展示，不使用示例数据"
            reading -> "正在读取系统使用事件…"
            error != null -> "读取失败，请刷新重试"
            visibleReport?.hasData != true -> "暂无可确认数据，不代表用时为零"
            week -> "周一至今 · 根据系统前台事件统计"
            else -> "根据系统前台事件统计 · 不含专注助手"
        }, settings.dailyGoalMinutes, onGoal)
        if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null && permissionGranted) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (!permissionGranted) OutlinedButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("授予使用情况访问权限") }
        Caption(goalSummary(settings.dailyGoalMinutes, visibleReport?.todayMillis))
        if (week) UsageWeekChart(visibleReport?.buckets.orEmpty(), Statistics.trend(sessions, Period.WEEK, today).map { it.seconds }, today)
        else UsageRibbon(visibleReport, sessions, today, onZoom)
        NightCard(week, night, permissionGranted)
        if (visibleReport?.hasData == true) Text("系统记录可能不完整，统计口径见说明。", color = Muted, fontSize = 12.sp)
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

private fun goalSummary(goalMinutes: Int, todayMillis: Long?): String {
    val goalSeconds = goalMinutes * UiLimits.SECONDS_PER_MINUTE
    val seconds = todayMillis?.div(UiLimits.MILLIS_PER_SECOND)
    return when {
        seconds == null -> "今日暂无可确认数据，稍后刷新查看目标进度。"
        seconds > goalSeconds -> "今天已超出目标 ${durationText(seconds - goalSeconds)}，给眼睛一点休息。"
        seconds == goalSeconds -> "今天已达到目标时长，休息一下吧。"
        else -> "今天还剩 ${durationText(goalSeconds - seconds)}，留一点时间给生活。"
    }
}

/** 暖橙主卡：左边总时长，右边 40 格目标刻度环。 */
@Composable
private fun UsageHero(week: Boolean, report: UsageReport?, caption: String, goalMinutes: Int, onGoal: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(AccentSoft).padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (week) "本周应用使用" else "今日应用使用", color = AccentDark, fontSize = 12.sp)
            if (report?.hasData == true) UsageTotal(report.totalMillis)
            else Text("—", fontSize = 40.sp, fontWeight = FontWeight.Light, color = Muted, style = NumeralStyle)
            Text(caption, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        GoalRing(goalMinutes, report?.todayMillis, onGoal)
    }
}

@Composable
private fun UsageTotal(millis: Long) {
    val minutes = millis / UsageLimits.MILLIS_PER_MINUTE
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, color = Ink, fontFeatureSettings = "'tnum'")) { append((minutes / UiLimits.MINUTES_PER_HOUR).toString()) }
        append(" 小时 ")
        withStyle(SpanStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, color = Ink, fontFeatureSettings = "'tnum'")) { append((minutes % UiLimits.MINUTES_PER_HOUR).toString()) }
        append(" 分钟")
    }, color = AccentDark, fontSize = 12.sp, maxLines = 1)
}

/** 目标刻度环：按今日已用 / 目标点亮，超出部分从起点再走一圈并换深色。 */
@Composable
private fun GoalRing(goalMinutes: Int, todayMillis: Long?, onGoal: () -> Unit) {
    val ticks = UsageLimits.GOAL_TICKS
    val ratio = todayMillis?.let { it.toFloat() / (goalMinutes * UsageLimits.MILLIS_PER_MINUTE).coerceAtLeast(1L) }
    val lit = ratio?.let { (it * ticks).roundToInt().coerceIn(0, ticks) } ?: 0
    val over = ratio?.let { ((it - 1f).coerceAtLeast(0f) * ticks).roundToInt().coerceIn(0, ticks) } ?: 0
    val percent = ratio?.let { "${(it * UiLimits.MAX_PERCENT).roundToInt()}%" } ?: "—"
    val description = "每日使用目标 ${durationText(goalMinutes * UiLimits.SECONDS_PER_MINUTE)}，" +
        (if (ratio == null) "今日暂无可确认数据" else "已用 $percent") + "，点按修改"
    Box(Modifier.size(104.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onGoal)
        .clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2 - 4.dp.toPx()
            val inner = outer - 9.dp.toPx()
            repeat(ticks) { index ->
                val angle = Math.toRadians(index * 360.0 / ticks - 90.0)
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                val color = when { index < over -> Ink; index < lit -> Accent; else -> RulerMinor }
                drawLine(color, center + direction * inner, center + direction * outer, 2.dp.toPx(), StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(percent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AccentDark, style = NumeralStyle)
            Text("目标 ${compactGoal(goalMinutes)}", fontSize = 10.sp, color = Muted, maxLines = 1)
        }
    }
}

/**
 * 今日双轨 24 小时刻度尺：上轨灰色为前台用屏（多窗口重叠只画一次），下轨暖橙为真实专注片段；
 * 23–5 点铺石板灰底，现在之后用虚点表示尚未发生，不冒充零用时。点按打开放大面板。
 */
@Composable
private fun UsageRibbon(report: UsageReport?, sessions: List<FocusSession>, today: LocalDate, onZoom: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val (dayStart, dayEnd) = dayBounds(today, zone)
    val reduceMotion = rememberReduceMotion()
    val todaySpans = remember(report, dayStart) { report?.let { UsageInsights.clip(it.spans, dayStart, dayEnd) }.orEmpty() }
    val screen = remember(todaySpans) { UsageInsights.union(todaySpans) }
    val focus = remember(sessions, dayStart) { UsageInsights.focusSpans(sessions, dayStart, dayEnd) }
    val overlap = remember(todaySpans, focus) { UsageInsights.overlapCount(todaySpans, focus) }
    val rise = remember(screen, focus) { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(screen, focus, reduceMotion) { if (reduceMotion) rise.snapTo(1f) else rise.animateTo(1f, tween(VisualLimits.RISE_MS)) }
    val dayMs = (dayEnd - dayStart).toFloat()
    val nowFraction = ((System.currentTimeMillis() - dayStart) / dayMs).coerceIn(0f, 1f)
    val known = report?.hasData == true
    val focusMillis = focus.sumOf { it.second - it.first }
    val screenText = if (known) durationText((report?.todayMillis ?: 0L) / UiLimits.MILLIS_PER_SECOND) else "—"
    val description = "今天用屏 $screenText，专注 ${durationText(focusMillis / UiLimits.MILLIS_PER_SECOND)}" +
        (if (known && focus.isNotEmpty()) "，专注期间打开手机 $overlap 次" else "") + "。点按放大查看细节"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(40.dp).clickable(role = Role.Button, onClickLabel = "放大查看", onClick = onZoom)
            .clearAndSetSemantics { contentDescription = description }) {
            Canvas(Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val hourWidth = width / UsageLimits.HOURS_PER_DAY
                drawRect(NightTint, Offset.Zero, Size(hourWidth * UsageInsights.NIGHT_END_HOUR, height))
                drawRect(NightTint, Offset(hourWidth * UsageInsights.NIGHT_START_HOUR, 0f), Size(width - hourWidth * UsageInsights.NIGHT_START_HOUR, height))
                for (hour in 0..UsageLimits.HOURS_PER_DAY) {
                    val x = (width - 1f) * hour / UsageLimits.HOURS_PER_DAY
                    val major = hour % 6 == 0
                    drawLine(if (major) RulerMajor else RulerMinor, Offset(x, height - (if (major) 9 else 4).dp.toPx()), Offset(x, height), 1.dp.toPx())
                }
                drawLine(RulerMajor, Offset(0f, height), Offset(width, height), 1.dp.toPx())
                val screenTop = 2.dp.toPx()
                val screenHeight = 20.dp.toPx()
                val focusTop = 25.dp.toPx()
                val focusHeight = 12.dp.toPx()
                if (nowFraction < 1f) {
                    var x = width * nowFraction + 5.dp.toPx()
                    while (x < width) {
                        drawCircle(TickIdle, 1.2.dp.toPx(), Offset(x, screenTop + screenHeight / 2))
                        x += 6.dp.toPx()
                    }
                }
                fun bar(from: Long, to: Long, top: Float, barHeight: Float, color: Color) {
                    val left = width * (from - dayStart) / dayMs
                    val barWidth = maxOf(2.dp.toPx(), width * (to - from) / dayMs)
                    val risen = barHeight * rise.value
                    drawRoundRect(color, Offset(left, top + barHeight - risen), Size(barWidth, risen), CornerRadius(2.dp.toPx()))
                }
                screen.forEach { (from, to) -> bar(from, to, screenTop, screenHeight, ScreenGray) }
                focus.forEach { (from, to) -> bar(from, to, focusTop, focusHeight, Accent) }
                val x = width * nowFraction
                drawLine(Ink, Offset(x, 0f), Offset(x, height + 4.dp.toPx()), 1.5.dp.toPx())
            }
        }
        PositionedLabels(listOf(0f to "0 时", 0.25f to "6", 0.5f to "12", 0.75f to "18", 1f to "24"), color = AccentDark)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendSwatch(ScreenGray)
            Text("用屏 $screenText", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            LegendSwatch(Accent)
            Text("专注 ${durationText(focusMillis / UiLimits.MILLIS_PER_SECOND)}", color = Muted, fontSize = 12.sp)
        }
        if (known && focus.isNotEmpty()) Text(if (overlap > 0) "专注时打开手机 $overlap 次" else "专注时没有打开手机", color = AccentDark, fontSize = 12.sp)
    }
}

@Composable
private fun LegendSwatch(color: Color) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

/** 本周：每天「用屏灰 / 专注橙」两根并排，今天深灰高亮；未开始或无数据的日子显示 —，专注为 0 不画。 */
@Composable
private fun UsageWeekChart(buckets: List<UsageBucket>, focusSeconds: List<Long>, today: LocalDate) {
    var selected by remember(today) { mutableIntStateOf(-1) }
    val todayIndex = today.dayOfWeek.value - 1
    val maximum = maxOf(buckets.maxOfOrNull { it.durationMillis } ?: 0L, (focusSeconds.maxOrNull() ?: 0L) * UiLimits.MILLIS_PER_SECOND, 1L)
    fun describe(index: Int): String {
        val bucket = buckets.getOrNull(index)
        val screen = if (bucket?.available == true) durationText(bucket.durationMillis / UiLimits.MILLIS_PER_SECOND) else "暂无数据或尚未开始"
        return "周${USAGE_WEEK_LABELS[index]} · 用屏 $screen · 专注 ${durationText(focusSeconds.getOrElse(index) { 0L })}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                repeat(4) { index ->
                    val y = size.height * index / 3
                    drawLine(Color(0xFFEDEDED), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
            }
            Row(Modifier.fillMaxSize().selectableGroup()) {
                repeat(USAGE_WEEK_LABELS.size) { index ->
                    val bucket = buckets.getOrNull(index)
                    val available = bucket?.available == true
                    val screenMillis = bucket?.durationMillis?.takeIf { available } ?: 0L
                    val focusMillis = focusSeconds.getOrElse(index) { 0L } * UiLimits.MILLIS_PER_SECOND
                    val description = describe(index)
                    Box(Modifier.weight(1f).fillMaxHeight().selectable(selected == index, role = Role.Tab, onClick = { selected = index })
                        .clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.BottomCenter) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
                            if (screenMillis > 0) Box(Modifier.width(14.dp)
                                .height((110f * screenMillis / maximum).coerceAtLeast(2f).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(if (index == todayIndex) TodayBar else ScreenBar))
                            else Text(if (available) "0" else "—", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
                            if (focusMillis > 0) Box(Modifier.width(14.dp)
                                .height((110f * focusMillis / maximum).coerceAtLeast(2f).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Accent))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            USAGE_WEEK_LABELS.forEach { Text(it, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendSwatch(ScreenBar)
            Text("用屏", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            LegendSwatch(Accent)
            Text("专注", color = Muted, fontSize = 12.sp)
        }
        Text(if (selected < 0) "点击柱形查看当天 · — 表示暂无数据或尚未开始" else describe(selected),
            color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** 石板灰夜间足迹卡：今日看前一晚 23 点至今天 5 点，本周看周一至今每晚之和。 */
@Composable
private fun NightCard(week: Boolean, night: NightSummary?, permissionGranted: Boolean) {
    val label = if (week) "本周夜间足迹" else "昨夜足迹"
    val (title, detail) = when {
        night == null -> label to if (permissionGranted) "暂无可确认的夜间数据，不代表没有使用。" else "授权后查看 23 点到 5 点的使用情况。"
        night.millis == 0L -> "$label · 没有" to "23 点到 5 点没有记录到前台使用，睡得很安静。"
        week -> "$label · ${durationText(night.millis / UiLimits.MILLIS_PER_SECOND)}" to
            "本周 23 点到 5 点共用了 ${durationText(night.millis / UiLimits.MILLIS_PER_SECOND)}，夜里的时间留给睡眠吧。"
        else -> "$label · ${durationText(night.millis / UiLimits.MILLIS_PER_SECOND)}" to
            "最后一次放下手机是 ${night.lastUse?.let { clockLabel(it) } ?: "—"}，今晚试着早一点说晚安。"
    }
    val quiet = night?.millis == 0L
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NightCardSurface).padding(14.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.padding(top = 1.dp).size(22.dp).clearAndSetSemantics { }) {
            drawCircle(if (quiet) Color(0xFFA3ADB8) else RestSlate)
            drawCircle(NightCardSurface, radius = size.minDimension * 0.42f, center = Offset(size.width * 0.32f, size.height * 0.36f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = RestSlateDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = RestSlate, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun UsageAppRow(app: AppUsage, totalMillis: Long, index: Int) {
    val colors = listOf(Color(0xFFF5E8DC) to Color(0xFF995C2C), Color(0xFFF4E5E7) to Color(0xFF9B6070), Color(0xFFE9E7F2) to Color(0xFF71608F), Color(0xFFE5EBF3) to Color(0xFF536F92))
    val (background, ink) = colors[index % colors.size]
    val percent = if (totalMillis > 0L) (app.durationMillis * UiLimits.MAX_PERCENT / totalMillis).toInt() else 0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(background), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).semantics { contentDescription = app.packageName }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.label, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(durationText(app.durationMillis / UiLimits.MILLIS_PER_SECOND), color = Muted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 140.dp))
            }
            TickGauge(percent, Modifier.fillMaxWidth(), height = 8.dp, color = ScreenGray, gap = 2.dp)
        }
    }
}

private data class ZoomEvent(val from: Long, val to: Long, val focus: Boolean, val title: String, val overlap: Boolean)

/**
 * 刻度尺放大面板：上方时间轴每小时 56dp，可左右滚动，打开时滚到当前时刻附近；
 * 下方按时间列出每段用屏（同一应用间隔不足 1 分钟合并）与专注，点轴上片段定位到明细，点明细把轴滚到该片段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageZoomSheet(report: UsageReport?, sessions: List<FocusSession>, today: LocalDate, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val (dayStart, dayEnd) = dayBounds(today, zone)
    val labels = remember(report) { report?.apps?.associate { it.packageName to it.label }.orEmpty() }
    val focusParts = remember(sessions, dayStart) {
        sessions.flatMap { session -> UsageInsights.focusSpans(listOf(session), dayStart, dayEnd).map { Triple(it.first, it.second, session.projectTitle) } }
    }
    val screen = remember(report, dayStart) { report?.let { UsageInsights.compact(UsageInsights.clip(it.spans, dayStart, dayEnd)) }.orEmpty() }
    val events = remember(screen, focusParts, labels) {
        val ranges = focusParts.map { it.first to it.second }
        (screen.map { ZoomEvent(it.startedAt, it.endedAt, false, labels[it.packageName] ?: it.packageName, UsageInsights.overlaps(it, ranges)) } +
            focusParts.map { ZoomEvent(it.first, it.second, true, it.third, false) })
            .sortedWith(compareBy<ZoomEvent> { it.from }.thenBy { !it.focus })
    }
    val screenMillis = UsageInsights.union(UsageInsights.clip(report?.spans.orEmpty(), dayStart, dayEnd)).sumOf { it.second - it.first }
    val focusMillis = focusParts.sumOf { it.second - it.first }
    var selected by remember { mutableIntStateOf(-1) }
    val axisScroll = rememberScrollState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val hourPx = with(density) { UsageLimits.ZOOM_HOUR_DP.dp.toPx() }
    val sidePx = with(density) { 20.dp.toPx() }
    val now = System.currentTimeMillis()
    val maximumHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * UsageLimits.ZOOM_HEIGHT_FRACTION
    fun axisOffset(time: Long) = sidePx + (time - dayStart).toFloat() / UsageLimits.MILLIS_PER_HOUR * hourPx
    fun axisTarget(time: Long) = (axisOffset(time) - axisScroll.viewportSize * UsageLimits.ZOOM_NOW_FRACTION).roundToInt().coerceIn(0, axisScroll.maxValue)
    LaunchedEffect(Unit) {
        snapshotFlow { axisScroll.maxValue }.first { it > 0 && it != Int.MAX_VALUE }
        axisScroll.scrollTo(axisTarget(now.coerceIn(dayStart, dayEnd)))
    }
    fun select(index: Int, fromAxis: Boolean) {
        selected = index
        scope.launch {
            if (fromAxis) { if (reduceMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index) }
            else { val target = axisTarget(events[index].from); if (reduceMotion) axisScroll.scrollTo(target) else axisScroll.animateScrollTo(target) }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = { Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Line).clearAndSetSemantics {}) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${today.monthValue}月${today.dayOfMonth}日 · 时间细节", fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭时间细节", tint = Muted, modifier = Modifier.size(20.dp)) }
            }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendSwatch(ScreenGray)
                Text("用屏 ${screen.size} 段 · ${durationText(screenMillis / UiLimits.MILLIS_PER_SECOND)}", color = Muted, fontSize = 12.sp)
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendSwatch(Accent)
                Text("专注 ${focusParts.size} 段 · ${durationText(focusMillis / UiLimits.MILLIS_PER_SECOND)}", color = Muted, fontSize = 12.sp)
            }
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(axisScroll)) {
                Column(Modifier.padding(horizontal = 20.dp).width((UsageLimits.ZOOM_HOUR_DP * UsageLimits.HOURS_PER_DAY).dp)) {
                    Canvas(Modifier.fillMaxWidth().height(92.dp).clearAndSetSemantics { }.pointerInput(events) {
                        detectTapGestures { tap ->
                            val screenLane = tap.y < 41.dp.toPx()
                            val slop = 6.dp.toPx()
                            val hit = events.indexOfFirst { event ->
                                val left = (event.from - dayStart).toFloat() / UsageLimits.MILLIS_PER_HOUR * hourPx
                                val right = maxOf(left + 4.dp.toPx(), (event.to - dayStart).toFloat() / UsageLimits.MILLIS_PER_HOUR * hourPx)
                                event.focus != screenLane && tap.x in (left - slop)..(right + slop)
                            }
                            if (hit >= 0) select(hit, fromAxis = true)
                        }
                    }) {
                        val width = size.width
                        val height = size.height
                        val hourWidth = width / UsageLimits.HOURS_PER_DAY
                        val laneBottom = 70.dp.toPx()
                        drawRect(NightTint, Offset.Zero, Size(hourWidth * UsageInsights.NIGHT_END_HOUR, laneBottom))
                        drawRect(NightTint, Offset(hourWidth * UsageInsights.NIGHT_START_HOUR, 0f), Size(width - hourWidth * UsageInsights.NIGHT_START_HOUR, laneBottom))
                        for (quarter in 0..UsageLimits.HOURS_PER_DAY * 4) {
                            val x = (width - 1f) * quarter / (UsageLimits.HOURS_PER_DAY * 4)
                            val major = quarter % 4 == 0
                            if (major) drawLine(Color(0xFFF3F3F3), Offset(x, 0f), Offset(x, laneBottom), 1.dp.toPx())
                            drawLine(if (major) RulerMajor else RulerMinor, Offset(x, height - (if (major) 10 else 4).dp.toPx()), Offset(x, height), 1.dp.toPx())
                        }
                        drawLine(RulerMajor, Offset(0f, height), Offset(width, height), 1.dp.toPx())
                        events.forEachIndexed { index, event ->
                            val left = (event.from - dayStart).toFloat() / UsageLimits.MILLIS_PER_HOUR * hourWidth
                            val barWidth = maxOf(4.dp.toPx(), (event.to - event.from).toFloat() / UsageLimits.MILLIS_PER_HOUR * hourWidth)
                            val top = if (event.focus) 44.dp.toPx() else 8.dp.toPx()
                            val barHeight = if (event.focus) 20.dp.toPx() else 30.dp.toPx()
                            val color = when {
                                event.focus -> if (index == selected) AccentDark else Accent
                                else -> if (index == selected) Ink else ScreenGray
                            }
                            drawRoundRect(color, Offset(left, top), Size(barWidth, barHeight), CornerRadius(3.dp.toPx()))
                        }
                        if (now in dayStart until dayEnd) {
                            val x = (now - dayStart).toFloat() / UsageLimits.MILLIS_PER_HOUR * hourWidth
                            drawLine(Ink, Offset(x, 2.dp.toPx()), Offset(x, height + 4.dp.toPx()), 1.5.dp.toPx())
                        }
                    }
                    PositionedLabels((0..UsageLimits.HOURS_PER_DAY).map { it.toFloat() / UsageLimits.HOURS_PER_DAY to it.toString() },
                        Modifier.padding(top = 4.dp), AccentDark)
                }
            }
            Text("左右滑动查看全天，点片段定位到下方明细。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            HorizontalDivider(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), color = Line)
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
                if (events.isEmpty()) item {
                    Text(if (report?.hasData == true) "今天还没有记录。" else "暂无可确认数据，不代表没有使用。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
                itemsIndexed(events) { index, event -> ZoomRow(event, index == selected) { select(index, fromAxis = false) } }
            }
        }
    }
}

@Composable
private fun ZoomRow(event: ZoomEvent, selected: Boolean, onClick: () -> Unit) {
    val time = "${clockLabel(event.from)}–${clockLabel(event.to)}"
    val duration = spanDuration(event.to - event.from)
    val description = "$time ${if (event.focus) "专注" else "用屏"} ${event.title} $duration${if (event.overlap) "，专注中" else ""}"
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) AccentSoft else Color.Transparent)
        .clickable(onClickLabel = "在时间轴上定位", onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp)
        .clearAndSetSemantics { contentDescription = description; this.selected = selected },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(time, color = Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(92.dp))
        LegendSwatch(if (event.focus) Accent else ScreenGray)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(event.title, color = if (event.focus) AccentDark else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (event.overlap) Text("专注中", color = AccentDark, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(999.dp)).background(AccentSoft).padding(horizontal = 6.dp))
        }
        Text(duration, color = Muted, fontSize = 12.sp, maxLines = 1)
    }
}

/**
 * 每日目标滚轮：小时 0–24、分钟按 5 分钟一档，停下后吸附到正中；30 分钟到 24 小时之外禁用保存。
 * 操作栏「取消」在左、「保存目标」在右。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalWheelSheet(goalMinutes: Int, busy: Boolean, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val step = UsageLimits.GOAL_MINUTE_STEP
    val minutesPerHour = UiLimits.MINUTES_PER_HOUR.toInt()
    val hourValues = remember { (0..UsageLimits.GOAL_MAX_MINUTES / minutesPerHour).toList() }
    val minuteValues = remember { (0 until minutesPerHour step step).toList() }
    // 非 5 的倍数的旧目标取最接近的一档，进位时小时跟着加一。
    val rounded = remember { ((goalMinutes + step / 2) / step * step).coerceIn(0, UsageLimits.GOAL_MAX_MINUTES) }
    var hours by remember { mutableIntStateOf(rounded / minutesPerHour) }
    var minutes by remember { mutableIntStateOf(rounded % minutesPerHour) }
    val total = hours * minutesPerHour + minutes
    val valid = total in UsageLimits.GOAL_MIN_MINUTES..UsageLimits.GOAL_MAX_MINUTES
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = { Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Line).clearAndSetSemantics {}) }) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("给屏幕时间一点边界", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(enabled = !busy, onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭", tint = Muted, modifier = Modifier.size(20.dp)) }
            }
            Text("目标用于计算进度和每日超时提醒，不会限制任何应用。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(SoftSurface).padding(12.dp))
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, start = 12.dp, end = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(UsageLimits.WHEEL_ITEM_DP.dp).clip(RoundedCornerShape(12.dp)).background(AccentSoft))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WheelColumn(hourValues, hours, "小时", { it.toString() }, Modifier.weight(1f)) { hours = it }
                    WheelColumn(minuteValues, minutes, "分钟", { it.toString().padStart(2, '0') }, Modifier.weight(1f)) { minutes = it }
                }
            }
            Text(if (valid) "每天 ${durationText(total * UiLimits.SECONDS_PER_MINUTE)}" else "目标需在 30 分钟到 24 小时之间",
                color = if (valid) Muted else MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, shape = RoundedCornerShape(13.dp), modifier = Modifier.heightIn(min = 52.dp)) { Text("取消") }
                Button(onClick = { onSave(total) }, enabled = valid && !busy, shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.widthIn(min = 160.dp).heightIn(min = 52.dp)) {
                    Text(if (busy) "保存中" else "保存目标", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun WheelColumn(values: List<Int>, value: Int, unit: String, format: (Int) -> String, modifier: Modifier, onSelect: (Int) -> Unit) {
    val itemHeight = UsageLimits.WHEEL_ITEM_DP.dp
    val state = rememberLazyListState(initialFirstVisibleItemIndex = values.indexOf(value).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val currentSelect by rememberUpdatedState(onSelect)
    val centered by remember { derivedStateOf { (state.firstVisibleItemIndex + (state.firstVisibleItemScrollOffset / itemPx).roundToInt()).coerceIn(0, values.lastIndex) } }
    LaunchedEffect(centered) { currentSelect(values[centered]) }
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress && state.firstVisibleItemScrollOffset != 0) {
            if (reduceMotion) state.scrollToItem(centered) else state.animateScrollToItem(centered)
        }
    }
    fun moveTo(index: Int) {
        val target = index.coerceIn(0, values.lastIndex)
        scope.launch { if (reduceMotion) state.scrollToItem(target) else state.animateScrollToItem(target) }
    }
    Box(modifier.height(itemHeight * UsageLimits.WHEEL_VISIBLE).semantics {
        contentDescription = unit
        stateDescription = "${format(values[centered])} $unit"
        customActions = listOf(
            CustomAccessibilityAction("增加") { moveTo(centered + 1); true },
            CustomAccessibilityAction("减少") { moveTo(centered - 1); true }
        )
    }) {
        LazyColumn(state = state, contentPadding = PaddingValues(vertical = itemHeight * (UsageLimits.WHEEL_VISIBLE / 2)),
            modifier = Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black, 0.65f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }) {
            itemsIndexed(values) { index, item ->
                val isSelected = index == centered
                Box(Modifier.fillMaxWidth().height(itemHeight).clickable(onClickLabel = "选择") { moveTo(index) }
                    .semantics { contentDescription = "${format(item)} $unit"; selected = isSelected }, contentAlignment = Alignment.Center) {
                    Text(format(item), fontSize = if (isSelected) 26.sp else 20.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) AccentDark else WheelIdle, modifier = Modifier.padding(end = 36.dp))
                }
            }
        }
        Text(unit, color = AccentDark, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(start = 54.dp).clearAndSetSemantics { })
    }
}
