package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.focusassistant.app.domain.*
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** 我的页：净资产卡、心愿、生活概览、设置分组与更新余额面板。数据为测试构造，不是示例数据。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ProfileScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()
    private fun day(monthsAgo: Long, dayOfMonth: Int = 10) = today.minusMonths(monthsAgo).withDayOfMonth(dayOfMonth).coerceAtMost(today).toString()
    private val book = AssetBook(
        listOf(AssetAccount("bank", "工资卡", AssetKind.BANK), AssetAccount("trip", "旅行基金", AssetKind.FUND), AssetAccount("card", "信用卡", AssetKind.DEBT)),
        listOf(
            AssetSnapshot("s0-bank", "bank", today.minusYears(1).withMonth(12).withDayOfMonth(20).toString(), 1_000_000),
            AssetSnapshot("s0-trip", "trip", today.minusYears(1).withMonth(12).withDayOfMonth(20).toString(), 200_000),
            AssetSnapshot("s0-card", "card", today.minusYears(1).withMonth(12).withDayOfMonth(20).toString(), 100_000),
            AssetSnapshot("s1", "bank", day(5), 1_200_000), AssetSnapshot("s2", "bank", day(3), 1_800_000, "发工资"),
            AssetSnapshot("s3", "trip", day(2), 300_000), AssetSnapshot("s4", "card", day(1), 250_000, "还信用卡"),
            AssetSnapshot("s5", "bank", day(0, 1), 2_100_000)
        ),
        listOf(Wish("w1", "日本旅行", 1_000_000, today.plusDays(120).toString(), "trip"))
    )
    private val life = LifeOverview(5 * 3600 + 20 * 60, 7, 3, null, usageAllowed = false)

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

    private val noop = ProfileActions({}, {}, {}, {}, {}, {}, {})

    @Test fun dashboardWithAssetsWishesAndSettings() {
        var opened: Screen? = null
        val hide = mutableStateOf(false)
        compose.setContent {
            FocusTheme {
                ProfileScreen(AppState(loading = false, assets = book), today, hide.value, life, true, false,
                    onToggleHide = { hide.value = !hide.value }, onAssets = {}, onUpdate = {}, onAddAccount = {}, onWish = {}, onLife = { opened = it }, actions = noop)
            }
        }
        compose.onNode(hasContentDescription("¥21,500")).assertExists()
        compose.onNodeWithText("需授权").assertExists()
        compose.onNodeWithText("查看趋势").assertExists()
        compose.onNodeWithText("近 6 个月").assertDoesNotExist()
        capture("profile-dashboard")
        compose.onNodeWithText("查看趋势").performClick()
        compose.onNodeWithText("近 6 个月").assertExists()
        compose.onNodeWithText("收起趋势").assertExists()
        compose.onNode(hasContentDescription("${today.year}年", substring = true)).assertExists()
        compose.onNodeWithText("年度").performClick()
        compose.onNode(hasContentDescription("${today.year}年12月")).assertExists()
        capture("profile-networth-annual")
        compose.onNodeWithText("近 6 个月").performClick()
        capture("profile-networth-expanded")
        compose.onNodeWithText("收起趋势").performClick()
        compose.onNodeWithText("近 6 个月").assertDoesNotExist()
        compose.onNodeWithText("待办 · 本月完成").performClick()
        assertEquals(Screen.TODOS, opened)
        compose.onNodeWithText("隐藏金额").performClick()
        compose.waitForIdle()
        assertTrue(hide.value)
        compose.onNodeWithText("显示金额").assertExists()
        compose.onNode(hasContentDescription("金额已隐藏")).assertExists()
        compose.onNodeWithText("查看趋势").performClick()
        compose.onNodeWithText("年度").performClick()
        compose.onNode(hasContentDescription("${today.year}年12月")).assertExists()
        compose.onNodeWithText("年度变化 · 金额已隐藏").assertExists()
        compose.onNode(hasContentDescription("净资产走势", substring = true) and hasContentDescription("¥")).assertDoesNotExist()
        capture("profile-networth-hidden-year")
    }

    @Test fun emptyStateShowsNoSampleData() {
        compose.setContent {
            FocusTheme {
                ProfileScreen(AppState(loading = false), today, false, LifeOverview(0, 0, 0, null, true), true, true,
                    {}, {}, {}, {}, {}, {}, noop)
            }
        }
        compose.onNodeWithText("还没有账户，添加一个开始记录").assertExists()
        compose.onNodeWithText("添加账户").assertExists()
        compose.onNodeWithText("查看趋势").assertExists()
        compose.onNodeWithText("收起趋势").performClick()
        compose.onNodeWithText("记下余额后即可开始查看历史。").assertExists()
        capture("profile-empty")
    }

    @Test fun accountWithoutSnapshotsKeepsTrendToggleAtTrailingEdge() {
        compose.setContent {
            FocusTheme {
                ProfileScreen(
                    AppState(loading = false, assets = AssetBook(accounts = listOf(AssetAccount("bank", "工资卡", AssetKind.BANK)))),
                    today, false, LifeOverview(0, 0, 0, null, true), true, true,
                    {}, {}, {}, {}, {}, {}, noop
                )
            }
        }
        compose.onNodeWithText("查看趋势").assertExists()
        compose.onNodeWithText("净资产走势").assertDoesNotExist()
        capture("profile-no-snapshots")
    }

    @Test fun balanceSheetOnlySavesChangedAccounts() {
        var saved: List<AssetSnapshot>? = null
        compose.setContent { FocusTheme { BalanceUpdateSheet(book, today, false, {}) { saved = it } } }
        capture("profile-balance-sheet", dialog = true)
        compose.onNode(hasSetTextAction() and hasText("信用卡（负债）")).performTextReplacement("1200")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        assertEquals(listOf("card" to 120_000L), saved?.map { it.accountId to it.amountCents })
    }
}
