package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.focusassistant.app.domain.*
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** 专注项新建/编辑面板：与浏览器原型同款的胶囊分类、分段模式、−/+ 步进与吸底保存栏。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ProjectEditorScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun resetDrafts() { ProjectDrafts.clear() }
    @After fun clearDrafts() { ProjectDrafts.clear() }

    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = requireNotNull(requireNotNull(ShadowDialog.getLatestDialog()) { "$name 没有打开面板窗口" }.window).decorView
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

    private fun render(project: Project?, durationOnly: Boolean = false, onSave: (Project) -> Unit = {}) {
        compose.setContent { FocusTheme { ProjectEditorDialog(project, busy = false, durationOnly = durationOnly, onDismiss = {}, onSave = onSave) } }
    }

    private val minutes get() = compose.onNode(hasSetTextAction() and hasContentDescription("时长（分钟）"))

    @Test fun createWithPillsSegmentsAndStepper() {
        var saved: Project? = null
        render(null) { saved = it }
        capture("project-create")
        compose.onNodeWithContentDescription("减少 5 分钟").performClick()
        minutes.assertTextEquals("20")
        compose.onNodeWithText("45 分钟").performClick()
        minutes.assertTextEquals("45")
        minutes.performTextReplacement("150")
        compose.onNodeWithText("= 2 小时 30 分钟").assertExists()
        compose.onNodeWithContentDescription("增加 5 分钟").performClick()
        minutes.assertTextEquals("155")
        compose.onNode(hasSetTextAction() and hasText("专注项名称")).performTextReplacement("算法刷题")
        compose.onNodeWithText("工作").performClick()
        compose.onNodeWithText("添加项目").performClick()
        compose.runOnIdle {
            val project = requireNotNull(saved)
            assertEquals("算法刷题", project.title)
            assertEquals("工作", project.category)
            assertEquals(TimerMode.COUNTDOWN, project.timerMode)
            assertEquals(155, project.targetMinutes)
        }
    }

    @Test fun countupDefaultsToUnlimitedAndKeepsCountdownValue() {
        var saved: Project? = null
        render(null) { saved = it }
        compose.onNodeWithText("60 分钟").performClick()
        compose.onNodeWithText("正计时").performClick()
        compose.onNodeWithText("不限时").assertExists()
        capture("project-create-countup")
        compose.onNodeWithText("倒计时").performClick()
        minutes.assertTextEquals("60")
        compose.onNodeWithText("正计时").performClick()
        compose.onNode(hasSetTextAction() and hasText("专注项名称")).performTextReplacement("自由阅读")
        compose.onNodeWithText("添加项目").performClick()
        compose.runOnIdle { assertNull(requireNotNull(saved).targetMinutes) }
    }

    @Test fun emptyTitleAndOutOfRangeDurationShowErrors() {
        var saved: Project? = null
        render(null) { saved = it }
        compose.onNodeWithText("添加项目").performClick()
        compose.onNodeWithText("请输入专注项名称。").assertExists()
        compose.onNode(hasSetTextAction() and hasText("专注项名称")).performTextReplacement("通宵")
        minutes.performTextReplacement("9999")
        compose.onNodeWithText("添加项目").performClick()
        compose.onNodeWithText("时长最多", substring = true).assertExists()
        capture("project-create-error")
        compose.runOnIdle { assertNull(saved) }
    }

    @Test fun maxBoundaryDisablesPlusAndDurationOnlyHidesFields() {
        render(Project("p", "长时段", "生活", TimerMode.COUNTDOWN, Validation.MAX_MINUTES), durationOnly = true)
        compose.onNodeWithContentDescription("增加 5 分钟").assertIsNotEnabled()
        compose.onNodeWithText("= 23 小时 59 分钟").assertExists()
        compose.onNodeWithText("分类").assertDoesNotExist()
        compose.onNodeWithText("保存时长").assertIsDisplayed()
        capture("project-duration-only")
    }
}
