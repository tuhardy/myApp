package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.focusassistant.app.domain.MusicListen
import com.focusassistant.app.domain.MusicPlayMode
import com.focusassistant.app.domain.MusicPlaybackSnapshot
import com.focusassistant.app.domain.MusicPlaylist
import com.focusassistant.app.domain.MusicPlaylists
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

    private fun render(state: MusicUiState, withData: Boolean = true, messages: MutableList<String> = mutableListOf(),
        onSavePlaylist: (MusicPlaylist) -> Unit = {}) {
        compose.setContent {
            FocusTheme {
                MusicScreen(if (withData) tracks else emptyList(), if (withData) listens else emptyList(),
                    if (withData) playlists else emptyList(), state, today, messages::add,
                    onPlay = { track -> state.selectedTrackId = track.id; state.playing = true },
                    onToggle = { state.playing = !state.playing },
                    playlistActions = MusicPlaylistActions(play = { _, track -> state.selectedTrackId = track.id; state.playing = true },
                        save = onSavePlaylist))
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

    @Test fun immersivePlayerShowsRealProgressAndReturnsToLibrary() {
        val state = MusicUiState().apply {
            selectedTrackId = "track-0"
            playerOpen = true
            playing = true
            playback = MusicPlaybackSnapshot("track-0", true, 42_000, 200_000,
                tracks.map { it.id }, MusicPlayMode.REPEAT_ALL, connected = true)
        }
        render(state)
        compose.onNodeWithText("0:42").assertExists()
        compose.onNodeWithText("3:20").assertExists()
        compose.onNodeWithText("列表循环").assertExists()
        compose.onNodeWithText("播放队列 · 12").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一首").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一首").assertIsDisplayed()
        capture("music-immersive-player")
        compose.onNodeWithContentDescription("返回声音馆").performClick()
        compose.runOnIdle { assertEquals(false, state.playerOpen) }
    }

    @Test fun playlistOpensDetailAndSelectsTrack() {
        val state = MusicUiState()
        render(state)
        compose.onNodeWithContentDescription("歌单 通勤流行，4 首").performClick()
        compose.onNodeWithText("4 首 · 本机歌单").assertExists()
        compose.onNodeWithText("测试歌曲0").performClick()
        capture("music-playlist")
        compose.runOnIdle {
            assertEquals("track-0", state.selectedTrackId)
            assertTrue(state.playing)
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("我的歌单", substring = true).assertExists()
    }

    @Test fun playlistDragKeepsAdjacentRowsVisibleAndSavesOrder() {
        var saved: MusicPlaylist? = null
        render(MusicUiState(), onSavePlaylist = { saved = it })
        compose.onNodeWithContentDescription("歌单 通勤流行，4 首").performClick()
        val draggedRow = compose.onNodeWithText("测试歌曲0")
        draggedRow.performTouchInput {
            down(center)
            advanceEventTime(500)
            moveBy(Offset(0f, 160f))
        }
        compose.onNodeWithText("测试歌曲0").assertIsDisplayed()
        compose.onNodeWithText("测试歌曲1").assertIsDisplayed()
        compose.onNodeWithText("测试歌曲2").assertIsDisplayed()
        compose.waitForIdle()
        val draggedTop = compose.onNodeWithText("测试歌曲0").fetchSemanticsNode().boundsInRoot.top
        val siblingTop = compose.onNodeWithText("测试歌曲1").fetchSemanticsNode().boundsInRoot.top
        assertTrue("拖拽浮层应跟随手指越过让位行：dragged=$draggedTop sibling=$siblingTop", draggedTop > siblingTop)
        capture("music-playlist-dragging")
        draggedRow.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf("track-1", "track-0", "track-2", "track-3"), saved?.trackIds)
        }
    }

    @Test fun playlistDragCanReverseWithoutCommittingOrPlaying() {
        var saveCount = 0
        val state = MusicUiState()
        render(state, onSavePlaylist = { saveCount += 1 })
        compose.onNodeWithContentDescription("歌单 通勤流行，4 首").performClick()
        val draggedRow = compose.onNodeWithText("测试歌曲0")
        draggedRow.performTouchInput {
            down(center)
            advanceEventTime(500)
            moveBy(Offset(0f, 380f))
        }
        compose.onNodeWithText("测试歌曲0").assertIsDisplayed()
        compose.onNodeWithText("测试歌曲1").assertIsDisplayed()
        compose.onNodeWithText("测试歌曲2").assertIsDisplayed()
        draggedRow.performTouchInput { moveBy(Offset(0f, -300f)) }
        compose.onNodeWithText("测试歌曲0").assertIsDisplayed()
        compose.onNodeWithText("测试歌曲1").assertIsDisplayed()
        draggedRow.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0, saveCount)
            assertEquals(null, state.selectedTrackId)
        }
    }

    @Test fun queuePinsCurrentAndCollapsesPlayedTracks() {
        val playback = MusicPlaybackSnapshot("track-2", true, 42_000, 202_000,
            tracks.map { it.id }, MusicPlayMode.REPEAT_ALL, connected = true)
        var moved: Pair<Int, Int>? = null
        var played: Int? = null
        compose.setContent {
            FocusTheme {
                MusicQueueDialog(tracks, playback, onDismiss = {}, onMove = { from, to -> moved = from to to },
                    onRemove = {}, onClear = {}, onPlay = { played = it })
            }
        }
        compose.onNodeWithText("接下来播放 · 9 首").assertIsDisplayed()
        compose.onNodeWithText("测试歌曲0").assertDoesNotExist()
        capture("music-queue", dialog = true)
        compose.onNodeWithText("已播放 2 首").performClick()
        compose.onNodeWithText("测试歌曲0").assertIsDisplayed()
        compose.onNodeWithContentDescription("测试歌曲3 的更多操作").performClick()
        compose.onNodeWithText("下移").performClick()
        compose.runOnIdle { assertEquals(0 to 1, moved) }

        moved = null
        compose.onNodeWithText("已播放 2 首").performClick()
        val draggedRow = compose.onNodeWithText("测试歌曲3")
        draggedRow.performTouchInput {
            down(center)
            advanceEventTime(500)
            moveBy(Offset(0f, 160f))
        }
        compose.onNodeWithText("测试歌曲3").assertIsDisplayed()
        compose.onNodeWithText("测试歌曲5").assertIsDisplayed()
        val draggedTop = compose.onNodeWithText("测试歌曲3").fetchSemanticsNode().boundsInRoot.top
        val siblingTop = compose.onNodeWithText("测试歌曲5").fetchSemanticsNode().boundsInRoot.top
        assertTrue("队列浮层应跟随手指越过让位行", draggedTop > siblingTop)
        capture("music-queue-dragging", dialog = true)
        draggedRow.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1 to 2, moved)
            assertTrue("拖拽松手不应触发播放", played == null)
        }
    }

    @Test fun queueMultiRowDragKeepsSettledViewportAfterCommit() {
        val playback = MusicPlaybackSnapshot("track-2", true, 42_000, 202_000,
            tracks.map { it.id }, MusicPlayMode.REPEAT_ALL, connected = true)
        var moved: Pair<Int, Int>? = null
        compose.setContent {
            FocusTheme {
                MusicQueueDialog(tracks, playback, onDismiss = {}, onMove = { from, to -> moved = from to to },
                    onRemove = {}, onClear = {}, onPlay = {})
            }
        }
        val firstRow = compose.onNodeWithText("测试歌曲3")
        val settledFirstTop = firstRow.fetchSemanticsNode().boundsInRoot.top
        firstRow.performTouchInput {
            down(center)
            advanceEventTime(500)
            moveBy(Offset(0f, 540f))
        }
        compose.onNodeWithText("测试歌曲6").assertIsDisplayed()
        firstRow.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0 to 3, moved)
            val finalFirstTop = compose.onNodeWithText("测试歌曲4").fetchSemanticsNode().boundsInRoot.top
            assertEquals("跨多行提交后应保持让位后的可见窗口", settledFirstTop, finalFirstTop, 2f)
        }
    }

    @Test fun playlistEditorKeepsSelectionOrderAndShowsRealTracks() {
        var saved: MusicPlaylist? = null
        compose.setContent {
            FocusTheme {
                MusicPlaylistEditorDialog(MusicPlaylist("draft", "夜间专注", emptyList()), tracks,
                    onDismiss = {}, onConfirm = { name, ids ->
                        saved = MusicPlaylists.validate(MusicPlaylist("draft", name, ids), emptyList(), tracks.mapTo(mutableSetOf()) { it.id })
                    })
            }
        }
        compose.onNodeWithText("测试歌曲2").performClick()
        compose.onNodeWithText("测试歌曲0").performClick()
        compose.onNodeWithText("已选 2 首").assertExists()
        capture("music-playlist-editor", dialog = true)
        compose.onNodeWithText("保存修改").performClick()
        compose.runOnIdle { assertEquals(listOf("track-2", "track-0"), saved?.trackIds) }
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
