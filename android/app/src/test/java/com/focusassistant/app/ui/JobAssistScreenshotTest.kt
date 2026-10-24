package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.focusassistant.app.domain.*
import com.focusassistant.app.platform.BossAutomationStatus
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

/** 求职辅助：进度看板、数据复盘与筛选方案。数据为测试构造，不是示例数据。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class JobAssistScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val noon = today.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3600_000L
    private fun record(company: String, daysAgo: Long, result: AutoApplyResult = AutoApplyResult.CONTACTED, preset: String = "Android·北京") =
        AutoApplyRecord("$company$daysAgo$result", "$company|android 开发|20k", company, "Android 开发", "20-30K", noon - daysAgo * 86_400_000L, result, preset = preset)
    private val records = listOf(record("示例科技", 0), record("示例科技", 0, AutoApplyResult.RESUME_SENT), record("演示网络", 1), record("测试数据", 3, preset = "Kotlin·远程"))
    private val settings = AutoApplyRules.savePreset(AutoApplyRules.savePreset(AutoApplySettings(includeKeywords = listOf("Kotlin")), "Kotlin·远程", "p2")
        .copy(includeKeywords = listOf("Android"), city = "北京"), "Android·北京", "p1")
    private val jobs = records.fold(emptyList<JobOpportunity>()) { list, item -> JobAssistRules.upsertFromRecord(list, item, "j${item.id}") }
        .map { if (it.company == "示例科技") JobAssistRules.setOutcome(it, JobOutcome.INTERVIEW, "周三一面", noon) else it } +
        JobOpportunity("manual", "手动公司", "产品经理", "", "", JobAssistStatus.APPLIED_SELF_REPORTED, noon, noon, note = "朋友内推")

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
            println("截图已输出：${target.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun show(applied: MutableList<String> = mutableListOf(), staged: MutableList<String> = mutableListOf()) {
        val panel = AutoApplyPanel(settings, 1, BossAutomationStatus(connected = true), true, "13.0", 1, 0, records.sortedByDescending { it.at },
            JobReview.summary(records, jobs, today, 7, zone), JobReview.summary(records, jobs, today, 30, zone))
        val actions = AutoApplyActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            applyPreset = { applied += it }, managePresets = {}, editStage = { staged += it })
        compose.setContent {
            FocusTheme { JobAssistScreen(jobs, false, panel, actions, {}, {}, {}, {}) }
        }
    }

    @Test fun progressBoardFiltersByStageAndOpensStageDialog() {
        val staged = mutableListOf<String>()
        show(staged = staged)
        compose.onNodeWithText("进度").performClick()
        compose.onNodeWithText("全部 4").assertExists()
        compose.onNodeWithText("面试 1").performClick()
        compose.onAllNodesWithText("阶段与备注").assertCountEquals(1)
        compose.onNodeWithText("周三一面").assertExists()
        compose.onNodeWithText("自动沟通加入 · 方案 Android·北京").assertExists()
        compose.onNodeWithText("阶段与备注").performClick()
        assertEquals(1, staged.size)
        compose.onNodeWithText("全部 4").performClick()
        compose.onAllNodesWithText("手动添加").assertCountEquals(2)
        compose.onNodeWithText("朋友内推").assertExists()
        compose.onNodeWithText("复核文案").assertDoesNotExist()
        compose.onNodeWithText("本人已投递").assertDoesNotExist()
        // 只有手动职位可以改名：4 个职位里 1 个手动。
        compose.onAllNodesWithText("编辑").assertCountEquals(1)
        capture("job-assist-progress")
    }

    @Test fun dataTabShowsFunnelAndPresetBreakdown() {
        show()
        compose.onNodeWithText("数据").performClick()
        compose.onNodeWithText("数据复盘").assertExists()
        compose.onNodeWithText("未用方案").assertDoesNotExist()
        compose.onNodeWithText("Android·北京").assertExists()
        compose.onNodeWithText("2 · 1").assertExists()
        compose.onNodeWithText("近 30 天").performClick()
        compose.onNodeWithText("Kotlin·远程").assertExists()
        capture("job-assist-data")
    }

    @Test fun presetChipSwitchesPreset() {
        val applied = mutableListOf<String>()
        show(applied = applied)
        compose.onNodeWithText("Kotlin·远程").performClick()
        compose.onNodeWithText("Android·北京").performClick()
        assertEquals(listOf("p2"), applied)
        compose.onNodeWithText("管理方案").assertExists()
    }
}
