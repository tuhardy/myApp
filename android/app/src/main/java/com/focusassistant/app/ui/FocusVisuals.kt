package com.focusassistant.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** 「刻度」视觉的共用色值与小组件：首页刻度尺、计时表盘、统计图表共用一套。 */
internal val Ink = Color(0xFF292929)
internal val RestSlate = Color(0xFF5D6B7C)
internal val RestSlateDark = Color(0xFF46525F)
internal val RestSlateSoft = Color(0xFFEDF0F3)
internal val RulerMajor = Color(0xFFDCA78F)
internal val RulerMinor = Color(0xFFECC6B5)
internal val RulerPast = Color(0xFFFBE0D2)
internal val TickIdle = Color(0xFFD9D9D9)
internal val TickIdleMajor = Color(0xFFB8B8B8)
internal val BarIdle = Color(0xFFC8C8C8)
internal val BarEmpty = Color(0xFFEBEBEB)
internal val CATEGORY_COLORS = listOf(Color(0xFFC44E22), Color(0xFFE8AA84), Color(0xFF8B8A98), Color(0xFF7D97B5))

internal object VisualLimits {
    const val GAUGE_TICKS = 20
    const val DIAL_TICKS = 60
    const val DIAL_MAJOR_EVERY = 5
    const val HEAT_LEVELS = 4
    const val HOUR_LABEL_STEP = 3
    const val HOURS_PER_DAY = 24
    const val PULSE_MS = 2400
    const val BLINK_MS = 2200
    const val RISE_MS = 700
    const val HOURS_DISPLAY_MINUTES = 600
}

/** 系统「移除动画」（动画时长缩放为 0）时返回 true，装饰动画直接跳到终态。 */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduce by remember { mutableStateOf(false) }
    DisposableEffect(resolver) {
        fun update() { reduce = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        update()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

/** 与原型 data-level 0–4 相同的热力配色：背景 to 文字。 */
internal fun heatColors(level: Int): Pair<Color, Color> = when (level) {
    1 -> Color(0xFFFFF1E9) to Color(0xFF783011)
    2 -> Color(0xFFF5C4AC) to Color(0xFF66301C)
    3 -> Color(0xFFDF926B) to Color(0xFF49200F)
    4 -> Accent to Color.White
    else -> SoftSurface to Muted
}

internal fun heatLevel(seconds: Long, peak: Long): Int {
    if (seconds <= 0 || peak <= 0) return 0
    val level = (seconds * VisualLimits.HEAT_LEVELS + peak - 1) / peak
    return level.toInt().coerceIn(1, VisualLimits.HEAT_LEVELS)
}

/** 读数用的时长：不足一分钟显示秒，超过 10 小时显示小时（一位小数），其余显示分钟（一位小数）。 */
internal fun minutesLabel(seconds: Long): String {
    val minutes = seconds / UiLimits.SECONDS_PER_MINUTE.toDouble()
    return when {
        seconds in 1 until UiLimits.SECONDS_PER_MINUTE -> "$seconds 秒"
        minutes >= VisualLimits.HOURS_DISPLAY_MINUTES -> "${trimDecimal(minutes / UiLimits.MINUTES_PER_HOUR)} 小时"
        else -> "${trimDecimal(minutes)} 分钟"
    }
}

internal fun trimDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")

internal fun clockLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
internal fun weekdayLabel(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1]

/** 按横向比例放置标签：首尾贴边，其余居中对齐刻度。 */
@Composable
internal fun PositionedLabels(labels: List<Pair<Float, String>>, modifier: Modifier = Modifier, color: Color = Muted) {
    Layout(content = { labels.forEach { (_, text) -> Text(text, fontSize = 11.sp, color = color, maxLines = 1) } },
        modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val placeables = measurables.map { it.measure(loose) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                val center = labels[index].first * constraints.maxWidth
                val x = (center - placeable.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                placeable.place(x, 0)
            }
        }
    }
}

/** 24 小时刻度尺：每小时短刻度、每 3 小时长刻度并标注数字，与原型 .hour-axis 一致。 */
@Composable
internal fun HourAxis(modifier: Modifier = Modifier, firstLabel: String = "0") {
    Box(modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val hours = VisualLimits.HOURS_PER_DAY
            for (hour in 0..hours) {
                val x = (size.width - 1f) * hour / hours
                val major = hour % VisualLimits.HOUR_LABEL_STEP == 0
                drawLine(if (major) TickIdleMajor else TickIdle, Offset(x, 0f), Offset(x, (if (major) 7 else 4).dp.toPx()), strokeWidth = 1.dp.toPx())
            }
        }
        PositionedLabels((0..VisualLimits.HOURS_PER_DAY step VisualLimits.HOUR_LABEL_STEP).map { hour ->
            hour.toFloat() / VisualLimits.HOURS_PER_DAY to if (hour == 0) firstLabel else hour.toString()
        }, Modifier.padding(top = 9.dp))
    }
}

/** 20 格完成度刻度条，与首页项目卡片一致。 */
@Composable
internal fun TickGauge(percent: Int, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val filled = (percent.coerceIn(0, UiLimits.MAX_PERCENT) * VisualLimits.GAUGE_TICKS + UiLimits.MAX_PERCENT / 2) / UiLimits.MAX_PERCENT
    Canvas(modifier.height(height).clearAndSetSemantics { }) {
        val gap = 3.dp.toPx()
        val cell = (size.width - gap * (VisualLimits.GAUGE_TICKS - 1)) / VisualLimits.GAUGE_TICKS
        repeat(VisualLimits.GAUGE_TICKS) { index ->
            drawRoundRect(if (index < filled) Accent else BarEmpty, Offset(index * (cell + gap), 0f),
                androidx.compose.ui.geometry.Size(cell, size.height), androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
        }
    }
}
