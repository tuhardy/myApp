package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.focusassistant.app.domain.MusicListen
import com.focusassistant.app.domain.MusicTrack
import com.focusassistant.app.domain.Period
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

/** 音乐页测试数据只存在于测试进程，用来验证真实数据接入前的布局和交互。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MusicScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.parse("2026-10-30")
    private val zone = ZoneId.systemDefault()
    private val tracks = (0 until 12).map { index -> MusicTrack("track-$index", "测试歌曲$index", "测试歌手${index % 3}", 200L + index) }
    private val playlists = listOf(MusicPlaylist("playlist", "通勤流行", tracks.take(4).map { it.id }))
    private val listens = tracks.mapIndexed { index, track ->
        MusicListen("listen-$index", track.id, today.atTime(8 + index % 8, 0).atZone(zone).toInstant().toEpochMilli(), 100L + index)
    } + MusicListen("listen-again", tracks.first().id, today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli(), 120)

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

    private fun render(state: MusicUiState, withData: Boolean = true, messages: MutableList<String> = mutableListOf()) {
        compose.setContent {
            FocusTheme {
                MusicScreen(if (withData) tracks else emptyList(), if (withData) listens else emptyList(),
                    if (withData) playlists else emptyList(), state, today, messages::add)
            }
        }
    }

    @Test fun emptyProductionStateDoesNotInventTracksOrHistory() {
        render(MusicUiState(), withData = false)
        capture("music-empty")
        compose.onNodeWithText("还没有本机歌曲").assertExists()
        compose.onNodeWithText("晴天").assertDoesNotExist()
        compose.onNodeWithText("还没有收听记录").assertExists()
    }

    @Test fun recommendationAndLibraryTabsUsePrototypeDensity() {
        val state = MusicUiState().apply { selectedTrackId = "track-0" }
        render(state)
        capture("music-recommend")
        compose.onNodeWithText("歌单").performClick()
        capture("music-playlists")
        compose.onNodeWithText("歌曲").performClick()
        capture("music-songs")
        compose.onNodeWithText("推荐").performClick()
        compose.runOnIdle { assertEquals(MusicTab.RECOMMEND, state.tab) }
    }

    @Test fun playlistOpensDetailAndPlaybackIsExplicitlyDemoOnly() {
        val state = MusicUiState()
        val messages = mutableListOf<String>()
        render(state, messages = messages)
        compose.onNodeWithContentDescription("歌单 通勤流行，4 首").performClick()
        compose.onNodeWithText("4 首 · 本机歌单").assertExists()
        compose.onNodeWithText("测试歌曲0").performClick()
        capture("music-playlist")
        compose.runOnIdle {
            assertEquals("track-0", state.selectedTrackId)
            assertTrue(state.playing)
            assertTrue(messages.last().contains("没有播放音频"))
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("我的歌单", substring = true).assertExists()
    }

    @Test fun historyExpandsRankingAndResetsWhenPeriodChanges() {
        val state = MusicUiState().apply { tab = MusicTab.HISTORY; anchor = today }
        render(state)
        capture("music-history")
        compose.onNodeWithText("查看更多（还有 2 首）").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(20, state.rankLimit) }
        compose.onNodeWithText("收起排行").assertExists()
        compose.onNodeWithText("日").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Period.DAY, state.period)
            assertEquals(10, state.rankLimit)
        }
    }

    @Test fun dayPickerDisablesFutureDates() {
        val state = MusicUiState().apply { tab = MusicTab.HISTORY; period = Period.DAY; anchor = today }
        render(state)
        compose.onNodeWithContentDescription("选择回顾周期").performClick()
        capture("music-history-picker", dialog = true)
        compose.onNodeWithText("精确跳到日期…").assertExists()
        compose.onNode(hasContentDescription("2026-10-31，收听 0 分钟")).assertIsNotEnabled()
    }
}
