package com.focusassistant.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusassistant.app.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/*
 * 「我的」页：与浏览器原型 #page-profile 同一布局（身份 → 净资产 → 心愿 → 生活概览 → 设置 → 隐私小字）。
 * 资产、心愿只来自本机记录，没有记录时显示空状态，不放示例。口径见 rules/profile.md 与 domain/Assets.kt。
 */

private val CardLine = Color(0xFFF2D9CB)
private val RowLine = Color(0xFFEEEEEE)
private const val HIDDEN_MONEY = "¥ ••••"

internal data class LifeOverview(val focusSeconds: Long, val doneThisMonth: Int, val diaryStreak: Int, val usageMillis: Long?, val usageAllowed: Boolean)

/** 专注取本周，待办取本月完成，日记取连续天数，用屏取今日真实记录（未授权时不显示数字）。 */
internal fun lifeOverview(state: AppState, today: LocalDate, usageMillis: Long?, usageAllowed: Boolean): LifeOverview {
    val month = YearMonth.from(today)
    return LifeOverview(
        Statistics.summary(Statistics.select(state.sessions, Period.WEEK, today)).seconds,
        state.todos.count { it.done && TodoHistory.completedDate(it)?.let(YearMonth::from) == month },
        DiaryRules.streak(state.diaries, today), usageMillis, usageAllowed
    )
}

internal class ProfileActions(
    val onNotifications: () -> Unit, val onUsagePermission: () -> Unit, val onExactAlarm: () -> Unit,
    val onBackup: () -> Unit, val onRestore: () -> Unit, val onRestSettings: () -> Unit, val onAutomation: () -> Unit
)

private fun compactDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds / 60 % 60
    return if (hours > 0) "$hours 小时 $minutes 分" else "$minutes 分钟"
}

private fun shownMoney(cents: Long, hide: Boolean, sign: Boolean = false) = if (hide) HIDDEN_MONEY else AssetRules.money(cents, sign)

@Composable
internal fun ProfileScreen(
    state: AppState,
    today: LocalDate,
    hideAmounts: Boolean,
    life: LifeOverview,
    notificationAllowed: Boolean,
    exactAllowed: Boolean,
    onToggleHide: () -> Unit,
    onAssets: () -> Unit,
    onUpdate: () -> Unit,
    onAddAccount: () -> Unit,
    onWish: (String) -> Unit,
    onLife: (Screen) -> Unit,
    actions: ProfileActions
) {
    ScreenBody {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Caption("PERSONAL SPACE")
                Text("我的", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            TextButton(onClick = onToggleHide) { Text(if (hideAmounts) "显示金额" else "隐藏金额", color = Muted, fontSize = 12.sp) }
        }
        NetWorthCard(state.assets, today, hideAmounts, onToggleHide, onAssets, onUpdate, onAddAccount)
        SectionHeading("心愿", "单个账户或所有资产")
        WishRow(state.assets, today, hideAmounts, onWish)
        SectionHeading("生活概览", "点开看详情")
        LifeGrid(life, onLife)
        SectionHeading("设置", null)
        SettingsGroup(state, notificationAllowed, exactAllowed, life.usageAllowed, actions)
        Text("你的时间与资产，只属于你。所有记录保存在本机，不上传云端；资产是你手动记下的余额，没有连接银行或任何真实账户。" +
            "备份文件未加密，日记与资产暂不在备份范围内，卸载应用会清除它们。本版本不申请 root 或无障碍控制权限。",
            fontSize = 11.sp, color = Muted, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Text("专注助手 · Android\n更少打扰，更多属于你的时间", fontSize = 11.sp, color = Muted, lineHeight = 20.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp))
    }
}

@Composable
private fun SectionHeading(title: String, caption: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        caption?.let { Text(it, fontSize = 11.sp, color = Muted) }
    }
}

@Composable
private fun NetWorthCard(book: AssetBook, today: LocalDate, hide: Boolean, onToggleHide: () -> Unit, onAssets: () -> Unit, onUpdate: () -> Unit, onAdd: () -> Unit) {
    var annual by remember { mutableStateOf(false) }
    var year by remember(today.year) { mutableIntStateOf(today.year) }
    var selectedMonth by remember(annual, year, today) { mutableStateOf<YearMonth?>(null) }
    val summary = AssetRules.summary(book, today)
    val yearSummary = AssetRules.yearSummary(book, year, today)
    val series = if (annual) yearSummary.points else AssetRules.series(book, today)
    val selected = series.firstOrNull { it.month == selectedMonth && it.known } ?: series.lastOrNull { it.known }
    val knownCount = series.count { it.known }
    val empty = book.accounts.isEmpty()
    val change = summary.monthChangeCents
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(AccentSoft).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("净资产", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            val periodSummary = if (annual) when { yearSummary.changeCents == null -> "年度变化 · 暂无年初对比"; hide -> "年度变化 · 金额已隐藏"; else -> "年度变化 ${AssetRules.money(yearSummary.changeCents, sign = true)}" } else "余额历史 · 非收支统计"
            Text(periodSummary, fontSize = 10.sp, color = Muted, modifier = Modifier.clearAndSetSemantics { contentDescription = if (annual && hide && yearSummary.changeCents != null) "年度变化，金额已隐藏" else periodSummary })
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "查看资产详情", onClick = onAssets)) {
            Text(shownMoney(summary.netCents, hide), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, color = Ink, modifier = Modifier.clearAndSetSemantics { contentDescription = if (hide) "金额已隐藏" else AssetRules.money(summary.netCents) })
            val pct = summary.monthRatio?.takeIf { !hide }?.let { String.format(Locale.ROOT, " · %.1f%%", it * 100) }.orEmpty()
            Text(when { empty -> "还没有账户，添加一个开始记录"; change == null -> "本月暂无对比"; hide -> "本月变化 · 金额已隐藏"; else -> "本月 ${if (change >= 0) "↑" else "↓"} ${shownMoney(change, hide, sign = true)}$pct" },
                fontSize = 12.sp, color = if ((change ?: 0L) < 0L || empty) Muted else AccentDark,
                modifier = Modifier.padding(top = 6.dp).clearAndSetSemantics { contentDescription = when { empty -> "还没有账户，添加一个开始记录"; change == null -> "本月暂无对比"; hide -> "本月变化，金额已隐藏"; else -> "本月变化 ${AssetRules.money(change, sign = true)}" } })
        }
        Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .65f)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false to "近 6 个月", true to "年度").forEach { (isYear, label) ->
                FilterChip(selected = annual == isYear, onClick = { annual = isYear; selectedMonth = null }, label = { Text(label, fontSize = 11.sp) }, modifier = Modifier.height(34.dp))
            }
        }
        if (annual) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { year--; selectedMonth = null }) { Text("‹") }
            Text("$year 年", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            TextButton(enabled = year < today.year, onClick = { year++; selectedMonth = null }) { Text("›") }
        }
        val trend = series.filter { it.known }.joinToString("，") { "${it.month.monthValue}月 ${AssetRules.money(it.netCents)}" }
        Sparkline(series, Modifier.fillMaxWidth().padding(top = 6.dp).height(72.dp)
            .clearAndSetSemantics { contentDescription = "${if (annual) "$year 年" else "近 6 个月"}净资产走势${if (hide || trend.isEmpty()) "" else "：$trend"}" })
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            series.forEach { point -> TextButton(enabled = point.known, onClick = { selectedMonth = point.month }, modifier = Modifier.weight(1f).heightIn(min = 34.dp)) { Text("${point.month.monthValue}月", fontSize = 9.sp, color = if (point.month == selected?.month) AccentDark else Muted, maxLines = 1) } }
        }
        Text(selected?.let { "${it.month.year}年${it.month.monthValue}月 · ${shownMoney(it.netCents, hide)}" } ?: "还没有余额记录。添加账户并记下余额后，这里会显示历史。", fontSize = 11.sp, color = Ink,
            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = selected?.let { "${it.month.year}年${it.month.monthValue}月 · ${if (hide) "金额已隐藏" else AssetRules.money(it.netCents)}" }
                    ?: "还没有余额记录。添加账户并记下余额后，这里会显示历史。"
            })
        Text(if (knownCount == 0) "记下余额后即可开始查看历史。" else if (knownCount == 1) "目前只有一个月份的记录，已显示单点；积累更多月份后可查看变化趋势。" else "点选月份查看余额；实心点表示当月记录含备注。", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
        HorizontalDivider(Modifier.padding(top = 10.dp), color = CardLine)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("资产 ${shownMoney(summary.assetsCents, hide)} · 负债 ${shownMoney(summary.debtsCents, hide)}", fontSize = 11.sp, color = Muted,
                modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = if (hide) "资产与负债金额已隐藏" else "资产 ${AssetRules.money(summary.assetsCents)} · 负债 ${AssetRules.money(summary.debtsCents)}" })
            SoftButton("历史明细", onClick = onAssets)
            SoftButton(if (empty) "添加账户" else "更新余额", onClick = if (empty) onAdd else onUpdate)
        }
    }
}

/** 已知点连线；单个已知月份也绘制为实心/空心点。 */
@Composable
private fun Sparkline(series: List<NetWorthPoint>, modifier: Modifier) {
    Canvas(modifier) {
        val known = series.withIndex().filter { it.value.known }
        if (known.isEmpty()) return@Canvas
        val pad = 6.dp.toPx()
        val min = known.minOf { it.value.netCents }
        val span = (known.maxOf { it.value.netCents } - min).takeIf { it > 0 } ?: 1L
        val points = known.map { (index, point) ->
            Offset(pad + index * (size.width - pad * 2) / maxOf(1, series.size - 1), size.height - pad - (point.netCents - min).toFloat() / span * (size.height - pad * 2)) to point
        }
        if (points.size > 1) {
            val path = Path().apply { points.forEachIndexed { i, (offset, _) -> if (i == 0) moveTo(offset.x, offset.y) else lineTo(offset.x, offset.y) } }
            drawPath(path, Accent, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
        }
        points.forEach { (offset, point) ->
            val noted = point.notes.isNotEmpty()
            val radius = (if (noted) 3.5f else 2.5f) * density
            drawCircle(if (noted) Accent else Color.White, radius, offset)
            if (!noted) drawCircle(Accent, radius, offset, style = Stroke(1.5f * density))
        }
    }
}

@Composable
private fun SoftButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 13.sp, color = if (enabled) Ink else Muted, maxLines = 1) }
}

@Composable
private fun WishRow(book: AssetBook, today: LocalDate, hide: Boolean, onWish: (String) -> Unit) {
    val wishes = book.wishes.filterNot { it.archived }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        wishes.forEach { wish ->
            val progress = AssetRules.wishProgress(wish, book, today)
            val status = when {
                progress.reached -> "已达成"
                progress.overdue -> "已过截止日期"
                progress.monthlyNeededCents != null -> "还剩 ${progress.daysLeft} 天 · 每月还需 ${shownMoney(progress.monthlyNeededCents, hide)}"
                else -> "还差 ${shownMoney(progress.remainingCents, hide)}"
            }
            val percent = (progress.ratio * 100).toInt()
            Column(
                Modifier.width(158.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = "编辑心愿") { onWish(wish.id) }.padding(14.dp)
                    .clearAndSetSemantics { contentDescription = "${wish.name}，${if (wish.scope == WishScope.ALL) "所有资产" else "单个账户"}，${if (hide) "进度金额已隐藏" else "已存 $percent%"}，$status" },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(wish.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(SoftSurface)) {
                    if (!hide) Box(Modifier.fillMaxWidth(progress.ratio.toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Accent))
                }
                Text("${shownMoney(progress.savedCents, hide)} / ${shownMoney(wish.targetCents, hide)}", fontSize = 12.sp, color = Ink)
                Text(status, fontSize = 11.sp, lineHeight = 17.sp, color = if (progress.reached) AccentDark else Muted)
            }
        }
        if (wishes.size < AssetRules.MAX_WISHES) Box(
            Modifier.width(158.dp).heightIn(min = 110.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button) { onWish("") },
            contentAlignment = Alignment.Center
        ) { Text("＋ 新心愿", fontSize = 13.sp, color = Muted) }
    }
}

@Composable
private fun LifeGrid(life: LifeOverview, onLife: (Screen) -> Unit) {
    val tiles = listOf(
        Triple("专注 · 本周", compactDuration(life.focusSeconds), Screen.STATISTICS),
        Triple("待办 · 本月完成", "${life.doneThisMonth} 件", Screen.TODOS),
        Triple("日记 · 连续", "${life.diaryStreak} 天", Screen.DIARY),
        Triple("用屏 · 今日", when {
            !life.usageAllowed -> "需授权"
            life.usageMillis == null -> "—"
            else -> compactDuration(life.usageMillis / 1000)
        }, Screen.USAGE)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, screen) ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp).clip(RoundedCornerShape(15.dp)).background(SoftSurface)
                            .clickable(role = Role.Button) { onLife(screen) }.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(label, fontSize = 11.sp, color = Muted)
                        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(state: AppState, notificationAllowed: Boolean, exactAllowed: Boolean, usageAllowed: Boolean, actions: ProfileActions) {
    val rows = listOf(
        SettingItem(Icons.Outlined.Tune, "休息偏好", "短休息 ${state.settings.shortBreakMinutes} 分钟 / 长休息 ${state.settings.longBreakMinutes} 分钟", actions.onRestSettings),
        SettingItem(Icons.Outlined.Notifications, "通知提醒", if (notificationAllowed) "已开启 · 管理系统通知" else "未开启 · 结束与超时提醒可能不可见", actions.onNotifications),
        SettingItem(Icons.Outlined.Alarm, "精确闹钟", if (exactAllowed) "已允许 · 管理系统授权" else "未允许 · 后台到时提醒可能延迟", actions.onExactAlarm),
        SettingItem(Icons.Outlined.QueryStats, "使用情况访问", if (usageAllowed) "已允许 · 读取应用前台用时" else "未允许 · 无法读取手机用时", actions.onUsagePermission),
        SettingItem(Icons.Outlined.Backup, "导出完整备份", "项目、待办、专注、进度与偏好；不含日记和资产", actions.onBackup),
        SettingItem(Icons.Outlined.Restore, "从文件恢复", "需先确认，当前计时须先结束；不改动日记和资产", actions.onRestore),
        SettingItem(Icons.Outlined.Code, "自动化扩展", "为手机自动化脚本留一个位置", actions.onAutomation, tag = "规划中")
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(17.dp)).padding(horizontal = 13.dp)) {
        rows.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = RowLine)
            Row(
                Modifier.fillMaxWidth().clickable(onClick = item.onClick).padding(vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Box(Modifier.width(27.dp), contentAlignment = Alignment.Center) { Icon(item.icon, null, tint = Muted, modifier = Modifier.size(19.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(item.detail, fontSize = 12.sp, color = Muted)
                }
                item.tag?.let {
                    Text(it, fontSize = 11.sp, color = Muted, modifier = Modifier.border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
                }
                Icon(Icons.Outlined.ChevronRight, "打开${item.title}", tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private class SettingItem(val icon: ImageVector, val title: String, val detail: String, val onClick: () -> Unit, val tag: String? = null)

// ---- 资产与心愿弹层 ----

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ProfileSheet(
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !currentBusy })
    val maximumHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * UiLimits.TODO_EDITOR_HEIGHT_FRACTION
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState,
        containerColor = Color.White, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Line).clearAndSetSemantics {})
        }
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(enabled = !busy, onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭", tint = Muted, modifier = Modifier.size(20.dp)) }
            }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
                content = content
            )
            if (footer != null) {
                HorizontalDivider(color = Line)
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = footer
                )
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        enabled = enabled, shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent),
        modifier = modifier.heightIn(min = 52.dp), onClick = onClick
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun SheetError(error: String?) {
    error?.let {
        Text(it, color = AccentDark, fontSize = 12.sp, lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(AccentSoft).padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive })
    }
}

@Composable
private fun SheetNotice(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, lineHeight = 19.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(SoftSurface).padding(12.dp))
}

@Composable
private fun SheetField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean, placeholder: String = "", decimal: Boolean = false, max: Int = 20) {
    TextField(
        value = value, onValueChange = { if (it.length <= max) onValueChange(it) }, enabled = enabled,
        label = { Text(label) }, placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true, textStyle = TextStyle(fontSize = 16.sp),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, disabledContainerColor = Color.White,
            focusedIndicatorColor = Accent, unfocusedIndicatorColor = Line,
            focusedLabelColor = AccentDark, unfocusedLabelColor = Muted, cursorColor = Accent
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
}

@Composable
private fun FieldCaption(text: String) = Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))

/** 日期行：点开系统日期选择器；onClear 非空时可清空（心愿截止日期可不设）。 */
@Composable
private fun DateRow(label: String, value: LocalDate?, enabled: Boolean, min: LocalDate? = null, max: LocalDate? = null, onPick: (LocalDate) -> Unit, onClear: (() -> Unit)? = null) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (value != null && onClear != null) TextButton(enabled = enabled, onClick = onClear) { Text("不设", fontSize = 12.sp) }
        val initial = value ?: LocalDate.now()
        SoftButton(value?.toString() ?: "选择日期", enabled = enabled, onClick = {
            DatePickerDialog(context, { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                val zone = ZoneId.systemDefault()
                min?.let { datePicker.minDate = it.atStartOfDay(zone).toInstant().toEpochMilli() }
                max?.let { datePicker.maxDate = it.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 }
            }.show()
        })
    }
}

@Composable
internal fun AssetListSheet(book: AssetBook, today: LocalDate, hide: Boolean, busy: Boolean, onDismiss: () -> Unit, onAccount: (String) -> Unit, onAdd: () -> Unit, onUpdate: () -> Unit) {
    val summary = AssetRules.summary(book, today)
    var historyYear by remember(today.year) { mutableIntStateOf(today.year) }
    ProfileSheet("我的资产", busy, onDismiss, footer = {
        SoftButton("＋ 添加账户", Modifier.weight(1f).heightIn(min = 52.dp), enabled = !busy && book.accounts.size < AssetRules.MAX_ACCOUNTS, onClick = onAdd)
        PrimaryButton("更新余额", !busy && book.accounts.isNotEmpty(), Modifier.weight(1f), onUpdate)
    }) {
        Text("净资产 ${shownMoney(summary.netCents, hide)} = 资产 ${shownMoney(summary.assetsCents, hide)} − 负债 ${shownMoney(summary.debtsCents, hide)}。余额由你手动记下，只保存在本机。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(SoftSurface).padding(12.dp)
                .clearAndSetSemantics { contentDescription = if (hide) "净资产、资产与负债金额已隐藏。余额由你手动记下，只保存在本机。" else "净资产 ${AssetRules.money(summary.netCents)} = 资产 ${AssetRules.money(summary.assetsCents)} − 负债 ${AssetRules.money(summary.debtsCents)}。余额由你手动记下，只保存在本机。" })
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("净资产月度历史", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(enabled = historyYear > 1, onClick = { historyYear-- }) { Text("‹") }
            Text("$historyYear 年", fontSize = 12.sp)
            TextButton(enabled = historyYear < today.year, onClick = { historyYear++ }) { Text("›") }
        }
        val history = AssetRules.yearSeries(book, historyYear, today)
        if (history.none { it.known }) Text("暂无历史余额", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 8.dp))
        history.filter { it.known }.asReversed().forEach { point ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${point.month.year}年${point.month.monthValue}月", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                val amountLabel = if (hide) "金额已隐藏" else AssetRules.money(point.netCents)
                Text(shownMoney(point.netCents, hide), fontSize = 12.sp, color = Ink, modifier = Modifier.clearAndSetSemantics { contentDescription = amountLabel })
            }
            HorizontalDivider(color = RowLine)
        }
        if (book.accounts.isEmpty()) Text("还没有账户。添加储蓄卡、现金或负债账户，记下当前余额就能开始看走势。", color = Muted, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 16.dp))
        listOf("资产" to false, "负债" to true).forEach { (label, liability) ->
            val accounts = book.accounts.filter { it.kind.liability == liability }
            if (accounts.isEmpty()) return@forEach
            Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() })
            accounts.forEachIndexed { index, account ->
                if (index > 0) HorizontalDivider(color = RowLine)
                val latest = AssetRules.latest(book.snapshots, account.id, today)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) { onAccount(account.id) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(account.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${account.kind.label} · ${latest?.let { "${it.date} 更新" } ?: "还没有记录"}", fontSize = 12.sp, color = Muted)
                    }
                    Text(shownMoney(latest?.amountCents ?: 0, hide), fontSize = 14.sp, color = Ink, modifier = Modifier.clearAndSetSemantics { contentDescription = if (hide) "金额已隐藏" else AssetRules.money(latest?.amountCents ?: 0) })
                    Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** 新建账户时同时记下当前余额；编辑只改名称和类型。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AccountEditorSheet(account: AssetAccount?, busy: Boolean, onDismiss: () -> Unit, onSave: (String, AssetKind, Long?) -> Unit) {
    var name by remember(account?.id) { mutableStateOf(account?.name.orEmpty()) }
    var kind by remember(account?.id) { mutableStateOf(account?.kind ?: AssetKind.BANK) }
    var opening by remember(account?.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ProfileSheet(if (account == null) "添加账户" else "编辑账户", busy, onDismiss, footer = {
        SoftButton("取消", Modifier.weight(1f).heightIn(min = 52.dp), enabled = !busy, onClick = onDismiss)
        PrimaryButton(if (account == null) "添加账户" else "保存", !busy, Modifier.weight(1f)) {
            if (name.isBlank()) { error = "请输入账户名称。"; return@PrimaryButton }
            if (account != null) onSave(name.trim(), kind, null)
            else AssetRules.parseAmount(opening).onFailure { error = it.message }.onSuccess { onSave(name.trim(), kind, it) }
        }
    }) {
        SheetError(error)
        SheetField(name, { name = it; error = null }, "账户名称", !busy, "例如：工资卡", max = AssetRules.MAX_NAME)
        FieldCaption("类型")
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AssetKind.entries.forEach { value -> ChoicePill(value.label, kind == value, !busy) { kind = value; error = null } }
        }
        if (account == null) SheetField(opening, { opening = it; error = null }, "当前余额（元）", !busy, if (kind.liability) "填欠款金额" else "0", decimal = true)
    }
}

@Composable
internal fun AccountDetailSheet(
    account: AssetAccount, book: AssetBook, today: LocalDate, hide: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onBack: () -> Unit, onRecord: (AssetSnapshot) -> Unit, onDeleteSnapshot: (AssetSnapshot) -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit
) {
    val history = book.snapshots.withIndex().filter { it.value.accountId == account.id }
        .sortedWith(compareByDescending<IndexedValue<AssetSnapshot>> { it.value.date }.thenByDescending { it.index }).map { it.value }
    var amount by remember(account.id, history.size) { mutableStateOf(history.firstOrNull()?.let { AssetRules.amountText(it.amountCents) }.orEmpty()) }
    var date by remember(account.id) { mutableStateOf(today) }
    var note by remember(account.id, history.size) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ProfileSheet(account.name, busy, onDismiss, footer = {
        SoftButton("返回", Modifier.weight(1f).heightIn(min = 52.dp), enabled = !busy, onClick = onBack)
        SoftButton("编辑账户", Modifier.weight(1f).heightIn(min = 52.dp), enabled = !busy, onClick = onEdit)
        SoftButton("删除账户", Modifier.weight(1f).heightIn(min = 52.dp), enabled = !busy, onClick = onDelete)
    }) {
        Text(account.kind.label, fontSize = 12.sp, color = Muted)
        SheetError(error)
        SheetField(amount, { amount = it; error = null }, "记一次余额（元）", !busy, decimal = true)
        DateRow("日期", date, !busy, max = today, onPick = { date = it; error = null })
        SheetField(note, { note = it; error = null }, "备注（可空）", !busy, "发工资、买基金……", max = AssetRules.MAX_NOTE)
        PrimaryButton("记下这笔", !busy, Modifier.fillMaxWidth().padding(top = 14.dp)) {
            AssetRules.parseAmount(amount).onFailure { error = it.message }.onSuccess { cents ->
                val snapshot = AssetSnapshot("", account.id, date.toString(), cents, note.trim())
                runCatching { AssetRules.validateSnapshot(snapshot.copy(id = "draft"), today) }.onFailure { error = it.message }.onSuccess { onRecord(snapshot) }
            }
        }
        Text("余额记录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp).semantics { heading() })
        if (history.isEmpty()) Text("还没有记录。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        history.forEachIndexed { index, snapshot ->
            val previous = history.getOrNull(index + 1)
            val change = previous?.let { shownMoney(snapshot.amountCents - it.amountCents, hide, sign = true) } ?: "首次记录"
            HorizontalDivider(color = RowLine)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shownMoney(snapshot.amountCents, hide), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clearAndSetSemantics { contentDescription = if (hide) "金额已隐藏" else AssetRules.money(snapshot.amountCents) })
                    Text(listOf(snapshot.date, change, snapshot.note).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = Muted, modifier = Modifier.clearAndSetSemantics { contentDescription = listOf(snapshot.date, if (hide && previous != null) "金额已隐藏" else change, snapshot.note).filter { it.isNotBlank() }.joinToString(" · ") })
                }
                IconButton(enabled = !busy, onClick = { onDeleteSnapshot(snapshot) }) {
                    Icon(Icons.Outlined.DeleteOutline, "删除 ${snapshot.date} 的记录", tint = Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 一次更新多个账户：只给改动过的账户生成快照，共用一个备注；什么都没改就直接关闭。 */
@Composable
internal fun BalanceUpdateSheet(book: AssetBook, today: LocalDate, busy: Boolean, onDismiss: () -> Unit, onSave: (List<AssetSnapshot>) -> Unit) {
    val current = remember(book) { book.accounts.associate { it.id to AssetRules.balance(book.snapshots, it.id, today) } }
    val texts = remember { mutableStateMapOf<String, String>().apply { book.accounts.forEach { put(it.id, AssetRules.amountText(current.getValue(it.id))) } } }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ProfileSheet("更新余额", busy, onDismiss, footer = {
        SoftButton("取消", Modifier.weight(1f).heightIn(min = 52.dp), enabled = !busy, onClick = onDismiss)
        PrimaryButton("保存", !busy, Modifier.weight(1f)) {
            val changed = mutableListOf<AssetSnapshot>()
            for (account in book.accounts) {
                val text = texts[account.id].orEmpty()
                val before = current[account.id] ?: 0L
                if (text.trim() == AssetRules.amountText(before)) continue
                val cents = AssetRules.parseAmount(text).getOrElse { error = "${account.name}：${it.message}"; return@PrimaryButton }
                if (cents != before) changed += AssetSnapshot("", account.id, today.toString(), cents, note.trim())
            }
            onSave(changed)
        }
    }) {
        SheetNotice("只有改动过的账户会记下新余额；负债填欠款金额。")
        SheetError(error)
        book.accounts.forEach { account ->
            SheetField(texts[account.id].orEmpty(), { texts[account.id] = it; error = null }, "${account.name}（${account.kind.label}）", !busy, decimal = true)
        }
        SheetField(note, { note = it; error = null }, "这次的备注（可空）", !busy, max = AssetRules.MAX_NOTE)
    }
}

/** 心愿进度按单个资产账户或全部非负债资产计算。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun WishEditorSheet(wish: Wish?, accounts: List<AssetAccount>, today: LocalDate, busy: Boolean, onDismiss: () -> Unit, onSave: (Wish) -> Unit, onArchive: () -> Unit) {
    var name by remember(wish?.id) { mutableStateOf(wish?.name.orEmpty()) }
    var target by remember(wish?.id) { mutableStateOf(wish?.let { AssetRules.amountText(it.targetCents) }.orEmpty()) }
    var deadline by remember(wish?.id) { mutableStateOf(wish?.deadline?.let(AssetRules::parseDate)) }
    var scope by remember(wish?.id, accounts.isEmpty()) { mutableStateOf(wish?.scope ?: if (accounts.isEmpty()) WishScope.ALL else WishScope.ACCOUNT) }
    var accountId by remember(wish?.id) { mutableStateOf(wish?.accountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    ProfileSheet(if (wish == null) "新心愿" else "编辑心愿", busy, onDismiss, footer = {
        if (wish != null) SoftButton("归档", Modifier.weight(1f).heightIn(min = 52.dp), enabled = !busy, onClick = onArchive)
        SoftButton("取消", Modifier.weight(1f).heightIn(min = 52.dp), enabled = !busy, onClick = onDismiss)
        PrimaryButton(if (wish == null) "添加心愿" else "保存", !busy, Modifier.weight(1f)) {
            if (name.isBlank()) { error = "请输入心愿名称。"; return@PrimaryButton }
            val cents = AssetRules.parseAmount(target, "目标金额").getOrElse { error = it.message; return@PrimaryButton }
            if (cents <= 0) { error = "目标金额需大于 0。"; return@PrimaryButton }
            if (scope == WishScope.ACCOUNT && accountId.isBlank()) { error = "请选择用来攒钱的账户。"; return@PrimaryButton }
            onSave(Wish(wish?.id.orEmpty(), name.trim(), cents, deadline?.toString(), if (scope == WishScope.ALL) null else accountId, wish?.archived ?: false, scope))
        }
    }) {
        SheetNotice("所有资产按全部非负债账户的余额合计计算，不扣除负债；之后新增的资产账户也会自动计入。")
        SheetError(error)
        SheetField(name, { name = it; error = null }, "心愿", !busy, "例如：旅行基金", max = AssetRules.MAX_NAME)
        SheetField(target, { target = it; error = null }, "目标金额（元）", !busy, decimal = true)
        DateRow("截止日期（可空）", deadline, !busy, min = today, onPick = { deadline = it; error = null }, onClear = { deadline = null })
        FieldCaption("攒钱范围")
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill("单个账户", scope == WishScope.ACCOUNT, !busy) { scope = WishScope.ACCOUNT; error = null }
            ChoicePill("所有资产", scope == WishScope.ALL, !busy) { scope = WishScope.ALL; error = null }
        }
        if (scope == WishScope.ACCOUNT) {
            FieldCaption("攒在哪个账户")
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { account -> ChoicePill(account.name, accountId == account.id, !busy) { accountId = account.id; error = null } }
            }
        }
    }
}
