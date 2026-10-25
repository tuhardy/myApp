package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.focusassistant.app.domain.*
import com.focusassistant.app.platform.AppUsage
import com.focusassistant.app.platform.UsageBucket
import com.focusassistant.app.platform.UsageReport
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** 用时页刻度版：主卡刻度环、双轨刻度尺、夜间足迹、放大面板与目标滚轮。数据为测试构造，不是示例数据。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class UsageScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private fun at(hour: Int, minute: Int = 0, date: LocalDate = today) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private fun report(): UsageReport {
        val spans = listOf(
            UsageSpan("chat", at(23, 20, today.minusDays(1)), at(0, 40)),
            UsageSpan("chat", at(7, 40), at(7, 52)),
            UsageSpan("browser", at(9, 10), at(9, 22)),
            UsageSpan("video", at(12, 30), at(13, 10)),
            UsageSpan("chat", at(15, 0), at(15, 6))
        )
        val dayStart = at(0)
        val clipped = spans.map { it.copy(startedAt = maxOf(dayStart, it.startedAt)) }
        val totals = clipped.groupBy { it.packageName }.mapValues { (_, list) -> list.sumOf { it.endedAt - it.startedAt } }
        val apps = listOf(AppUsage("chat", "微信", totals.getValue("chat")), AppUsage("video", "哔哩哔哩", totals.getValue("video")), AppUsage("browser", "浏览器", totals.getValue("browser")))
        val total = apps.sumOf { it.durationMillis }
        return UsageReport(apps, total, null, List(12) { UsageBucket("%02d".format(it * 2), 0L, true) }, total, true, spans, at(23, 0, today.minusDays(1)))
    }

    private val sessions = listOf(
        FocusSession("f1", "p", "Spring Boot 实战", "学习", TimerMode.COUNTDOWN, 25, at(9, 0), at(9, 25), 1500, listOf(TimeSegment(at(9, 0), at(9, 25))))
    )

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = if (dialog) requireNotNull(requireNotNull(ShadowDialog.getLatestDialog()) { "$name 没有打开面板窗口" }.window).decorView
                else compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        try {
            val target = File("build/reports/screenshots").apply { mkdirs() }.resolve("$name.png")
            target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("$name 渲染结果为空白画面", pixels.any { it != pixels.first() })
            println("截图已输出：${target.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderScreen(period: UsagePeriod, onZoom: () -> Unit = {}, onGoal: () -> Unit = {}) {
        compose.setContent {
            FocusTheme {
                UsageScreen(report(), true, false, null, period, AppSettings(dailyGoalMinutes = 60), sessions,
                    {}, {}, {}, onGoal, onZoom, {}, {}, today)
            }
        }
    }

    @Test fun todayWithRibbonRingAndNightCard() {
        var zoomed = false
        var goal = false
        renderScreen(UsagePeriod.TODAY, onZoom = { zoomed = true }, onGoal = { goal = true })
        capture("usage-today")
        compose.onNodeWithText("昨夜足迹 · 1 小时 20 分钟").assertExists()
        compose.onNodeWithText("最后一次放下手机是 00:40，今晚试着早一点说晚安。").assertExists()
        compose.onNodeWithText("专注时打开手机 1 次").assertExists()
        compose.onNode(hasContentDescription("点按放大查看细节", substring = true)).performClick()
        compose.onNode(hasContentDescription("每日使用目标", substring = true)).performClick()
        compose.runOnIdle { assertTrue(zoomed); assertTrue(goal) }
    }

    @Test fun weekWithPairedBars() {
        renderScreen(UsagePeriod.WEEK)
        capture("usage-week")
        compose.onNodeWithText("本周应用使用").assertExists()
        compose.onNodeWithText("一").assertExists()
        compose.onNodeWithText("日").assertExists()
        compose.onNode(hasText("· 用屏", substring = true) and hasText("· 专注", substring = true)).assertExists()
        compose.onNode(hasContentDescription("周一", substring = true)).performClick()
        compose.onNode(hasText("周一", substring = true) and hasText("用屏", substring = true)).assertExists()
        compose.onNode(hasText("本周夜间足迹", substring = true)).assertExists()
    }

    @Test fun monthAndYearShowPeriodTotalsAndNightCards() {
        renderScreen(UsagePeriod.MONTH)
        compose.onNodeWithText("本月").assertExists()
        compose.onNodeWithText("本月应用使用").assertExists()
        compose.onNode(hasContentDescription("1日", substring = true)).assertExists()
        compose.onNode(hasText("日", substring = true) and hasText("用屏", substring = true) and hasText("专注", substring = true)).assertExists()
        compose.onNode(hasText("本月夜间足迹", substring = true)).assertExists()
        renderScreen(UsagePeriod.YEAR)
        compose.onNodeWithText("全年").assertExists()
        compose.onNodeWithText("全年应用使用").assertExists()
        compose.onNodeWithText("1月").assertExists()
        compose.onNodeWithText("12月").assertExists()
        compose.onNode(hasText("月", substring = true) and hasText("用屏", substring = true) and hasText("专注", substring = true)).assertExists()
        compose.onNode(hasText("全年夜间足迹", substring = true)).assertExists()
    }

    @Test fun zoomSheetListsSegmentsAndMarksFocusOverlap() {
        compose.setContent { FocusTheme { UsageZoomSheet(report(), sessions, today) {} } }
        capture("usage-zoom", dialog = true)
        compose.onNode(hasContentDescription("浏览器", substring = true) and hasContentDescription("专注中", substring = true)).assertExists()
        compose.onNode(hasContentDescription("专注 Spring Boot 实战", substring = true)).performClick()
        compose.onNode(hasContentDescription("专注 Spring Boot 实战", substring = true)).assertIsSelected()
    }

    private fun wheel(unit: String) = compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasContentDescription(unit)))

    @Test fun goalWheelSelectsAndSavesOnTheRight() {
        var saved: Int? = null
        compose.setContent { FocusTheme { GoalWheelSheet(240, busy = false, onDismiss = {}) { saved = it } } }
        capture("usage-goal-wheel", dialog = true)
        compose.onNodeWithText("每天 4 小时").assertExists()
        // 点可见的相邻格，再用滚动把分钟列滚到 30（LazyColumn 只组合可见格）。
        compose.onNodeWithContentDescription("3 小时").performClick()
        wheel("分钟").performScrollToIndex(6)
        compose.waitForIdle()
        compose.onNodeWithText("每天 3 小时 30 分钟").assertExists()
        val save = compose.onNodeWithText("保存目标").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText("取消").fetchSemanticsNode().boundsInRoot
        assertTrue("保存按钮应在取消右侧", save.left > cancel.left)
        compose.onNodeWithText("保存目标").performClick()
        compose.runOnIdle { assertEquals(210, saved) }
    }

    @Test fun goalWheelDisablesSaveBelowThirtyMinutes() {
        compose.setContent { FocusTheme { GoalWheelSheet(60, busy = false, onDismiss = {}) {} } }
        compose.onNodeWithContentDescription("0 小时").performClick()
        compose.onNodeWithContentDescription("10 分钟").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("目标需在 30 分钟到 24 小时之间").assertExists()
        compose.onNodeWithText("保存目标").assertIsNotEnabled()
    }
}
