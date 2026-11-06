package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.focusassistant.app.domain.ActiveTimer
import com.focusassistant.app.domain.Project
import com.focusassistant.app.domain.TimerMode
import com.focusassistant.app.domain.TimerPhase
import com.focusassistant.app.domain.TimerStatus
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 计时页在较矮竖屏中也保持一屏，顶部与底部操作不被表盘挤出。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class TimerScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val project = Project("p", "Kotlin 与 Android  Compose 长时间专注项目", "学习", TimerMode.COUNTDOWN, 25)

    private fun render(timer: ActiveTimer? = null) {
        compose.setContent {
            FocusTheme {
                TimerScreen(
                    project = project.copy(timerMode = timer?.timerMode ?: project.timerMode,
                        targetMinutes = timer?.targetMinutes ?: project.targetMinutes),
                    timer = timer,
                    nowElapsed = 70_000L,
                    onBack = {}, onStatistics = {}, onSettings = {}, onMode = {}, onDuration = {},
                    onStart = {}, onPause = {}, onResume = {}, onFinish = {}, onFinishEarly = {},
                    onDiscard = {}, onShortBreak = {}, onLongBreak = {}
                )
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
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

    private fun assertControlsVisible(vararg labels: String) {
        compose.onRoot().fetchSemanticsNode().boundsInRoot.let { root ->
            (labels + arrayOf("短休息", "长休息")).forEach { label ->
                val node = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
                val bounds = node.boundsInRoot
                assertTrue("$label 超出计时页顶部", bounds.top >= root.top)
                assertTrue("$label 超出计时页底部", bounds.bottom <= root.bottom)
            }
        }
    }

    private fun timer(mode: TimerMode, status: TimerStatus, phase: TimerPhase = TimerPhase.FOCUS, target: Int? = 25) = ActiveTimer(
        sessionId = "s", projectId = project.id, projectTitle = project.title, category = project.category,
        timerMode = mode, targetMinutes = target, phase = phase, status = status,
        startedAt = 10_000L, accumulatedMs = if (status == TimerStatus.PAUSED) 65_000L else 0L,
        anchorElapsed = 10_000L, anchorWall = 10_000L
    )

    @Test fun idleFitsOneScreen() {
        render()
        capture("timer-idle-compact")
        assertControlsVisible("开始专注", "调整")
    }

    @Test fun runningCountdownKeepsFinishAndDiscardVisible() {
        render(timer(TimerMode.COUNTDOWN, TimerStatus.RUNNING))
        capture("timer-running-compact")
        assertControlsVisible("暂停", "结束并保存专注", "放弃本次专注")
    }

    @Test fun pausedUnlimitedCountupFitsOneScreen() {
        render(timer(TimerMode.COUNTUP, TimerStatus.PAUSED, target = null))
        capture("timer-paused-countup-compact")
        assertControlsVisible("继续计时", "结束并保存专注", "放弃本次专注")
    }

    @Test fun shortBreakFitsOneScreen() {
        render(timer(TimerMode.COUNTDOWN, TimerStatus.RUNNING, TimerPhase.SHORT_BREAK, 5))
        capture("timer-short-break-compact")
        assertControlsVisible("暂停", "结束休息", "放弃本次休息")
    }
}
