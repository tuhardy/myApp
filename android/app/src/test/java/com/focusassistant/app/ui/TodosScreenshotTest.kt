package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.focusassistant.app.domain.*
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * 待办页截图测试：在 JVM 里用 Robolectric 渲染 Compose，不需要连接设备。
 * 截图输出到 build/reports/screenshots/ 和系统临时目录，用于人工核对 UI 是否还原设计稿；
 * 这里不做像素比对，只保证页面能渲染出非空白画面，避免因基准图漂移而误报。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TodosScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()
    private val month = YearMonth.from(today)
    private val now = System.currentTimeMillis()
    private val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val temporaryScreenshots by lazy { Files.createTempDirectory("focus-native-ui-").toFile() }

    @Before fun resetDrafts() { TodoDrafts.clear() }
    @After fun clearDrafts() { TodoDrafts.clear() }

    /** 覆盖三档发酵、重要置顶、小步小路；完成项单独进入记录册，不混入待完成。 */
    private fun sampleTodos() = listOf(
        Todo("t1", "读完《原子习惯》第三章", "个人成长", important = true, createdAt = today.toString()),
        Todo(
            "t2", "整理季度复盘文档", "工作", createdAt = today.minusDays(4).toString(),
            steps = listOf(
                TodoStep("s1", "导出上季度数据", done = true),
                TodoStep("s2", "写三条结论"),
                TodoStep("s3", "同步给团队")
            )
        ),
        Todo("t3", "把阳台的花搬进来", "生活", createdAt = today.minusDays(9).toString()),
        completed("t4", "预约体检", today)
    )

    private fun completed(id: String, title: String, date: LocalDate?): Todo = Todo(
        id, title, "生活", done = true, createdAt = today.minusDays(30).toString(),
        completedAt = date?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    )

    private fun historyTodos(): List<Todo> = listOf(
        completed("h1", "今天也读了几页书", today),
        completed("h2", "给家里打电话", month.atDay(1)),
        completed("h3", "收好旅行照片", month.minusMonths(1).atDay(1)),
        completed("h4", "旧清单里的小成就", null)
    )

    private fun eightStepTodo() = sampleTodos()[1].copy(
        steps = List(Validation.MAX_STEPS) { index -> TodoStep("editor-$index", "整理第 ${index + 1} 份材料", done = index < 2) }
    )

    /**
     * 不用 onRoot().captureToImage()：它走窗口级 PixelCopy 抓屏，在 Robolectric 里等不到重绘回调，
     * 会固定超时。改成让根视图自己画到 Bitmap 上，纯 JVM 内完成；面板必须画独立 Dialog 窗口。
     */
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = if (dialog) {
                val latest = requireNotNull(ShadowDialog.getLatestDialog()) { "$name 没有打开面板窗口" }
                check(latest.isShowing) { "$name 面板窗口已关闭" }
                requireNotNull(latest.window).decorView
            } else compose.activity.window.decorView
            check(view.width > 0 && view.height > 0) { "$name 窗口尚未完成布局" }
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        try {
            val target = File("build/reports/screenshots").apply { mkdirs() }.resolve("$name.png")
            target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            // 全白或全透明说明没真正渲染，这种情况下人工核对截图毫无意义。
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("$name 渲染结果为空白画面", pixels.any { it != pixels.first() })
            val copy = target.copyTo(temporaryScreenshots.resolve(target.name), overwrite = true)
            println("截图已输出：${target.absolutePath}")
            println("截图临时副本：${copy.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderTodos(
        todos: List<Todo> = sampleTodos(),
        ui: TodoUiState = TodoUiState(),
        onComplete: (Todo) -> Unit = {},
        onToggleStep: (Todo, TodoStep) -> Unit = { _, _ -> },
        onDelete: (Todo) -> Unit = {},
        onDetails: (Todo) -> Unit = {},
        onPickMonth: () -> Unit = {},
        onHelp: () -> Unit = {}
    ): TodoUiState {
        compose.setContent {
            FocusTheme {
                TodosScreen(
                    state = AppState(todos = todos, loading = false), ui = ui, today = today, now = now,
                    onCreate = {}, onEdit = {}, onDetails = onDetails, onComplete = onComplete,
                    onToggleStep = onToggleStep, onRenew = {}, onArchive = {}, onRestore = {},
                    onDelete = onDelete, onUndo = {}, onDismissUndo = {}, onPickMonth = onPickMonth, onHelp = onHelp
                )
            }
        }
        return ui
    }

    private fun accessibleName(vararg parts: String) = SemanticsMatcher("读屏名称包含 ${parts.joinToString()}") { node ->
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.label)
        descriptions.any { description -> parts.all { description.contains(it) } }
    }

    private fun renderEditor(todo: Todo?, onSave: (Todo) -> Unit = {}) {
        compose.setContent {
            FocusTheme {
                TodoEditorDialog(todo, busy = false, onDismiss = {}, onDelete = {}, onRestore = {}, onSave = onSave)
            }
        }
    }

    @Test fun todosScreenWithGroupsAndAging() {
        renderTodos()
        compose.onNodeWithText(sampleTodos().last().title).assertDoesNotExist()
        capture("todos-all")
    }

    @Test fun restoredOlderCompletionIsLoadedAndVisible() {
        val count = TodoHistory.PAGE_SIZE * 2 + 5
        val target = completed("restored-older", "找回后应直接看见的旧记录", month.atDay(1))
        val todos = List(count) { index -> completed("newer-$index", "较新的记录 $index", today) } + target
        val ui = TodoUiState()
        ui.revealCompleted(target, todos)
        renderTodos(todos = todos, ui = ui)
        assertTrue(ui.view().limit >= todos.size)
        compose.onNodeWithText(target.title).assertIsDisplayed()
    }

    @Test fun headerCountsOnlyAppearInTabsAndGuideStaysByTitle() {
        var helpRequests = 0
        renderTodos(todos = sampleTodos() + Todo("put-aside", "暂时放下", "生活", archived = true), onHelp = { helpRequests++ })
        val pendingTab = compose.onNode(tab and hasText("待完成 3"))
        val top = pendingTab.fetchSemanticsNode().boundsInRoot.top
        for (label in listOf("已完成 1", "放下的 1", "待完成 3")) {
            compose.onNode(tab and hasText(label)).performClick()
            compose.onNodeWithText("待完成 3 · 已完成 1").assertDoesNotExist()
            compose.onNode(tab and hasText("待完成 3")).assertExists()
            compose.onNode(tab and hasText("已完成 1")).assertExists()
            compose.onNode(tab and hasText("放下的 1")).assertExists()
            assertEquals(top, pendingTab.fetchSemanticsNode().boundsInRoot.top, 0.1f)
        }
        val guide = compose.onNodeWithText("使用指南")
        val title = compose.onNodeWithText("待办.").fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithContentDescription("新增待办").fetchSemanticsNode().boundsInRoot
        val guideBounds = guide.fetchSemanticsNode().boundsInRoot
        assertTrue(guideBounds.left >= title.right && guideBounds.right <= add.left)
        compose.onNodeWithContentDescription("待办规则说明").assertDoesNotExist()
        guide.performClick()
        compose.runOnIdle { assertEquals(1, helpRequests) }
    }

    @Test fun monthSummaryIsExplicitWhileTabsRemainGlobal() {
        val ui = TodoUiState().apply { setFilter(TodoFilter.DONE) }
        renderTodos(todos = historyTodos(), ui = ui)
        compose.onNodeWithText("${month.year}年${month.monthValue}月 · 完成 2 件").assertExists()
        compose.onNode(tab and hasText("已完成 4")).assertExists()
        compose.runOnIdle { ui.selectMonth(month.minusMonths(1)) }
        compose.onNodeWithText("${month.minusMonths(1).year}年${month.minusMonths(1).monthValue}月 · 完成 1 件").assertExists()
        compose.onNode(tab and hasText("已完成 4")).assertExists()
        compose.runOnIdle { ui.search("旧清单") }
        compose.onNodeWithText("全部时间 · 找到 1 件").assertExists()
        compose.onNode(tab and hasText("已完成 4")).assertExists()
    }

    @Test fun guideShowsFourShortSectionsAndCloses() {
        var opened by mutableStateOf(true)
        compose.setContent { FocusTheme { if (opened) TodoGuideSheet(onDismiss = { opened = false }) } }
        compose.onNodeWithText("待办怎么用").assertIsDisplayed()
        listOf("拆成小步", "删除与撤销", "回看与重做", "暂时放下").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertCountEquals(4)
        compose.onNodeWithText("点进度展开，每一步整行都能勾选。").assertIsDisplayed()
        compose.onNodeWithText("左滑后点删除，可在底部撤销；关闭撤销提示后失效。").assertIsDisplayed()
        compose.onNodeWithText("已完成按月查看，搜索覆盖全部时间；重做请到详情中「重新打开」。").assertIsDisplayed()
        compose.onNodeWithText("放下不是删除，需要时可以找回。").assertIsDisplayed()
        capture("todos-guide", dialog = true)
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("待办怎么用").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h480dp-mdpi")
    fun guideKeepsHeaderAndCloseReachableOnSmallScreen() {
        var opened by mutableStateOf(true)
        compose.setContent { FocusTheme { if (opened) TodoGuideSheet(onDismiss = { opened = false }) } }
        compose.onNodeWithText("放下不是删除，需要时可以找回。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("待办怎么用").assertIsDisplayed()
        compose.onNodeWithText("知道了").assertIsDisplayed()
        capture("todos-guide-small", dialog = true)
        compose.onNodeWithContentDescription("关闭使用指南").performClick()
        compose.onNodeWithText("待办怎么用").assertDoesNotExist()
    }

    @Test fun todosScreenEmptyState() {
        renderTodos(todos = emptyList())
        capture("todos-empty")
    }

    /** 放下的事只在这个页签出现，发酵只淡化背景，仍可「找回 / 删除」。 */
    @Test fun todosScreenArchived() {
        renderTodos(
            todos = listOf(Todo("a1", "学一门新乐器", "个人成长", archived = true, createdAt = today.minusDays(30).toString())),
            ui = TodoUiState().apply { setFilter(TodoFilter.ARCHIVED) }
        )
        capture("todos-archived")
    }

    @Test fun completedHistoryMonthAndUnknownTime() {
        val records = historyTodos()
        val ui = renderTodos(records, TodoUiState().apply { setFilter(TodoFilter.DONE) })
        compose.onNodeWithText(records.first().title).assertIsDisplayed()
        compose.onNodeWithText(records.last().title).assertDoesNotExist()
        capture("todos-completed-month")
        compose.runOnIdle { ui.showUndated(true) }
        compose.onNodeWithText(records.last().title).assertIsDisplayed()
        compose.onNodeWithText(records.first().title).assertDoesNotExist()
        capture("todos-completed-undated")
    }

    @Test fun todosExpandedSteps() {
        val todo = sampleTodos()[1]
        renderTodos(listOf(todo), TodoUiState().apply { expanded[todo.id] = true })
        compose.onNodeWithText(todo.steps.last().title).assertIsDisplayed()
        capture("todos-expanded-steps")
    }

    @Test fun monthSelectionPanel() {
        val months = TodoHistory.select(historyTodos(), month = month, now = now).months
        compose.setContent {
            FocusTheme { TodoMonthDialog(month, months, today, onDismiss = {}, onSelect = {}) }
        }
        capture("todos-month-picker", dialog = true)
    }

    @Test fun monthPanelRejectsFutureMonthsAndReturnsSelectedMonth() {
        val pickerToday = LocalDate.of(today.year, 6, 15)
        val selected = mutableListOf<YearMonth>()
        compose.setContent {
            FocusTheme {
                TodoMonthDialog(YearMonth.from(pickerToday), emptyList(), pickerToday, onDismiss = {}, onSelect = { selected += it })
            }
        }
        compose.onNodeWithContentDescription("${today.year}年7月", substring = true).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("${today.year}年1月", substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(YearMonth.of(today.year, 1)), selected) }
    }

    @Test fun createPanelWithEightStepsKeepsSaveVisible() {
        var saved: Todo? = null
        renderEditor(null) { saved = it }
        capture("todos-create", dialog = true)
        compose.onNode(hasSetTextAction() and hasText("想做什么？")).performTextReplacement("分八步整理书房")
        compose.onNode(hasText("拆成小步", substring = true) and hasClickAction()).performScrollTo().performClick()
        repeat(Validation.MAX_STEPS) { index ->
            compose.onNodeWithText("加一步").performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and hasContentDescription("第 ${index + 1} 步"))
                .performScrollTo().performTextReplacement("新建的第 ${index + 1} 步")
        }
        compose.onNodeWithText("添加待办").assertIsDisplayed()
        compose.onNodeWithText("加一步").assertDoesNotExist()
        capture("todos-create-eight-steps", dialog = true)
        compose.onNodeWithText("添加待办").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals("分八步整理书房", result.title)
            assertEquals(Validation.MAX_STEPS, result.steps.size)
            assertFalse(result.done)
            assertTrue(result.steps.none { it.done })
        }
    }

    @Test fun editEightStepsKeepsSaveVisibleWhileScrolling() {
        val todo = eightStepTodo()
        var saved: Todo? = null
        renderEditor(todo) { saved = it }
        compose.onNodeWithText("保存修改").assertIsDisplayed()
        capture("todos-edit-eight-steps", dialog = true)
        compose.onNodeWithText(todo.steps.last().title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存修改").assertIsDisplayed()
        capture("todos-edit-eight-steps-scrolled", dialog = true)
        compose.onNodeWithText("保存修改").performClick()
        compose.runOnIdle {
            assertEquals(todo.steps, requireNotNull(saved).steps)
            assertEquals(todo.title, requireNotNull(saved).title)
        }
    }

    @Test fun completionFeedbackUsesAlreadyCompletedRecord() {
        val todo = eightStepTodo().copy(done = true, completedAt = now, steps = eightStepTodo().steps.map { it.copy(done = true) })
        val ui = TodoUiState().apply {
            finishing[todo.id] = TodoFinish(token = 1L, beganAt = SystemClock.elapsedRealtime())
            expanded[todo.id] = true
        }
        renderTodos(listOf(todo), ui)
        compose.onNodeWithText("又走完一件事").assertIsDisplayed()
        capture("todos-completion-feedback")
        compose.runOnIdle { ui.finishing.clear() }
        compose.onNodeWithText("又走完一件事").assertDoesNotExist()
        compose.onNodeWithText(todo.title).assertDoesNotExist()
    }

    @Test fun defaultPendingHasExactlyThreeTabsAndNoAll() {
        val ui = renderTodos()
        compose.onAllNodes(tab).assertCountEquals(3)
        compose.onNode(tab and hasText("待完成", substring = true)).assertIsSelected()
        compose.onNode(tab and hasText("已完成", substring = true)).assertIsNotSelected()
        compose.onNode(tab and hasText("放下的", substring = true)).assertIsNotSelected()
        compose.onAllNodes(tab and hasText("全部", substring = true)).assertCountEquals(0)
        compose.runOnIdle { assertEquals(TodoFilter.PENDING, ui.filter) }
        compose.onNode(tab and hasText("已完成", substring = true)).performClick()
        compose.runOnIdle { assertEquals(TodoFilter.DONE, ui.filter) }
        compose.onNodeWithText(sampleTodos().last().title).assertIsDisplayed()
    }

    @Test fun stepExpansionAndWholeRowToggleExposeTheStepName() {
        val todo = sampleTodos()[1]
        val calls = mutableListOf<Pair<String, String>>()
        val ui = renderTodos(listOf(todo), onToggleStep = { item, step -> calls += item.id to step.id })
        compose.onNodeWithText(todo.steps.last().title).assertDoesNotExist()
        compose.onNode(accessibleName("展开", "小步") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(true, ui.expanded[todo.id]) }
        val step = todo.steps.last()
        val row = compose.onNode(accessibleName(step.title) and hasClickAction())
        row.assertIsDisplayed()
        row.performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.9f, center.y)) }
        compose.runOnIdle { assertEquals(listOf(todo.id to step.id), calls) }
        compose.onNode(accessibleName("收起", "小步") and hasClickAction()).performClick()
        compose.onNodeWithText(step.title).assertDoesNotExist()
    }

    @Test fun pendingCheckmarkCallsCompleteOnce() {
        val todo = sampleTodos().first()
        val calls = mutableListOf<String>()
        renderTodos(listOf(todo), onComplete = { calls += it.id })
        compose.onNode(accessibleName("完成", todo.title) and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf(todo.id), calls) }
    }

    @Test fun completedCheckmarkAndStepsCannotRevertCompletion() {
        val todo = sampleTodos()[1].let { source ->
            source.copy(done = true, completedAt = now, steps = source.steps.map { it.copy(done = true) })
        }
        var completes = 0
        var steps = 0
        renderTodos(listOf(todo), TodoUiState().apply { setFilter(TodoFilter.DONE); expanded[todo.id] = true },
            onComplete = { completes++ }, onToggleStep = { _, _ -> steps++ })
        compose.onNode(accessibleName("已完成", todo.title), useUnmergedTree = true)
            .assertHasNoClickAction().performTouchInput { click() }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(0, completes)
            assertEquals(0, steps)
        }
    }

    @Test fun leftSwipeOnlyRevealsDeleteAndClickDeletesExactlyOnce() {
        val todo = sampleTodos().first()
        val deleted = mutableListOf<String>()
        val ui = renderTodos(listOf(todo), onDelete = { deleted += it.id })
        compose.onNodeWithText(todo.title).performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(todo.id, ui.openSwipeId)
            assertTrue(deleted.isEmpty())
        }
        val delete = compose.onNode(hasText("删除") and hasClickAction()).assertIsDisplayed()
        delete.performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        delete.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(todo.id), deleted) }
    }

    @Test fun verticalDragDoesNotRevealOrDeleteTodo() {
        val todo = sampleTodos().first()
        var deletes = 0
        val ui = renderTodos(listOf(todo), onDelete = { deletes++ })
        compose.onNodeWithText(todo.title).performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertEquals(null, ui.openSwipeId)
            assertEquals(0, deletes)
        }
    }

    @Test fun historyMonthNavigationShowsOnlyTheSelectedMonth() {
        val records = historyTodos()
        var pickerCalls = 0
        val ui = renderTodos(records, TodoUiState().apply { setFilter(TodoFilter.DONE) }, onPickMonth = { pickerCalls++ })
        compose.onNodeWithContentDescription("下一月").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上一月").performClick()
        compose.onNodeWithText(records[2].title).assertIsDisplayed()
        compose.onNodeWithText(records.first().title).assertDoesNotExist()
        compose.runOnIdle { assertEquals(month.minusMonths(1), ui.month) }
        compose.onNodeWithText("${ui.month.year}年${ui.month.monthValue}月").performClick()
        compose.runOnIdle { assertEquals(1, pickerCalls) }
        compose.onNodeWithContentDescription("下一月").performClick()
        compose.onNodeWithText(records.first().title).assertIsDisplayed()
    }

    @Test fun completedSearchFindsOtherMonthsAndUnknownTimeThenRestoresMonth() {
        val records = historyTodos()
        val ui = renderTodos(records, TodoUiState().apply { setFilter(TodoFilter.DONE) })
        val search = compose.onNode(hasSetTextAction())
        search.performTextInput("旅行照片")
        compose.onNodeWithText(records[2].title).assertIsDisplayed()
        compose.onNodeWithText(records[0].title).assertDoesNotExist()
        search.performTextReplacement("旧清单")
        compose.onNodeWithText(records[3].title).assertIsDisplayed()
        search.performTextClearance()
        compose.onNodeWithText(records[0].title).assertIsDisplayed()
        compose.onNodeWithText(records[2].title).assertDoesNotExist()
        compose.runOnIdle { assertEquals(month, ui.month) }
    }

    @Test fun completedSearchIncludesStepNamesAndCategories() {
        val byStep = completed("search-step", "去年的资料整理", month.minusYears(1).atDay(1)).copy(
            category = "工作", steps = listOf(TodoStep("search-step-1", "核对特定检索词", true))
        )
        val byCategory = completed("search-category", "历史分类记录", null).copy(category = "个人成长")
        renderTodos(listOf(byStep, byCategory), TodoUiState().apply { setFilter(TodoFilter.DONE) })
        val search = compose.onNode(hasSetTextAction())
        search.performTextInput("特定检索词")
        compose.onNodeWithText(byStep.title).assertIsDisplayed()
        compose.onNodeWithText(byCategory.title).assertDoesNotExist()
        search.performTextReplacement("个人成长")
        compose.onNodeWithText(byCategory.title).assertIsDisplayed()
        compose.onNodeWithText(byStep.title).assertDoesNotExist()
    }

    @Test fun historyPaginationIsTwentyAcrossDatesAndSurvivesSearch() {
        val previousMonth = month.minusMonths(1)
        val records = List(TodoHistory.PAGE_SIZE + 1) { index ->
            completed("page-$index", "分页记录 ${index.toString().padStart(2, '0')}", previousMonth.atDay(if (index < 10) 20 else 10))
        }
        val ui = renderTodos(records, TodoUiState().apply {
            setFilter(TodoFilter.DONE)
            this.month = previousMonth
        })
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText(records[TodoHistory.PAGE_SIZE - 1].title))
        compose.onNodeWithText(records[TodoHistory.PAGE_SIZE - 1].title).assertIsDisplayed()
        compose.onNodeWithText(records.last().title).assertDoesNotExist()
        compose.runOnIdle { assertEquals(TodoHistory.PAGE_SIZE, ui.view().limit) }
        list.performScrollToNode(hasText("查看更多", substring = true))
        compose.onNodeWithText("查看更多", substring = true).performClick()
        list.performScrollToNode(hasText(records.last().title))
        compose.onNodeWithText(records.last().title).assertIsDisplayed()
        var index = 0
        var offset = 0
        compose.runOnIdle {
            assertEquals(TodoHistory.PAGE_SIZE * 2, ui.view().limit)
            index = ui.view().listState.firstVisibleItemIndex
            offset = ui.view().listState.firstVisibleItemScrollOffset
        }
        compose.runOnIdle { ui.search("记录 00") }
        compose.onNodeWithText(records.first().title).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.runOnIdle {
            assertEquals(previousMonth, ui.month)
            assertEquals(TodoHistory.PAGE_SIZE * 2, ui.view().limit)
            assertEquals(index, ui.view().listState.firstVisibleItemIndex)
            assertEquals(offset, ui.view().listState.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithText(records.last().title).assertIsDisplayed()
    }

    @Test fun completedEditorAllowsRenamingButCannotAddOrDeleteSteps() {
        val todo = sampleTodos()[1].let { source ->
            source.copy(done = true, completedAt = now, steps = source.steps.map { it.copy(done = true) })
        }
        var saved: Todo? = null
        renderEditor(todo) { saved = it }
        compose.onAllNodes(hasText("加一步")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("删除第", substring = true)).assertCountEquals(0)
        compose.onNode(hasSetTextAction() and hasText(todo.steps.first().title))
            .performScrollTo().performTextReplacement("修改后的步骤名称")
        compose.onNodeWithText("保存修改").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertTrue(result.done)
            assertEquals(todo.completedAt, result.completedAt)
            assertEquals(todo.steps.map { it.id }, result.steps.map { it.id })
            assertTrue(result.steps.all { it.done })
            assertEquals("修改后的步骤名称", result.steps.first().title)
        }
    }

    @Test fun completedDetailsExposeReadOnlyStepNamesAndExplicitReopen() {
        val todo = sampleTodos()[1].let { source ->
            source.copy(done = true, completedAt = now, steps = source.steps.map { it.copy(done = true) })
        }
        var reopenCalls = 0
        compose.setContent {
            FocusTheme {
                TodoDetailsDialog(todo, false, onDismiss = {}, onEdit = {}, onReopen = { reopenCalls++ },
                    onDelete = {}, onRestore = {}, onImportant = {}, onArchive = {})
            }
        }
        todo.steps.forEach { step ->
            compose.onNodeWithText(step.title, substring = true).performScrollTo().assertHasNoClickAction()
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, reopenCalls) }
        compose.onNodeWithText("重新打开").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reopenCalls) }
        capture("todos-completed-details", dialog = true)
    }

    @Test fun reopenRequiresAnExplicitRedoStepSelection() {
        val todo = sampleTodos()[1].let { source ->
            source.copy(done = true, completedAt = now, steps = source.steps.map { it.copy(done = true) })
        }
        val calls = mutableListOf<Set<String>>()
        compose.setContent {
            FocusTheme { TodoReopenDialog(todo, false, onDismiss = {}) { calls += it } }
        }
        val confirm = compose.onNode(hasText("确认重新打开") and hasClickAction())
        confirm.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNode(hasText(todo.steps.first().title) and hasClickAction()).performClick()
        confirm.performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(todo.steps.first().id)), calls) }
        capture("todos-reopen-selection", dialog = true)
    }

    @Test fun draftSurvivesCancelAndReopen() {
        var open by mutableStateOf(true)
        var saves = 0
        compose.setContent {
            FocusTheme {
                TextButton(onClick = { open = true }) { Text("测试重新打开草稿") }
                if (open) TodoEditorDialog(null, false, onDismiss = { open = false }, onDelete = {}, onRestore = {}, onSave = { saves++ })
            }
        }
        compose.onNode(hasSetTextAction() and hasText("想做什么？")).performTextReplacement("未保存的待办草稿")
        compose.onNode(hasText("拆成小步", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("加一步").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasContentDescription("第 1 步")).performScrollTo().performTextReplacement("尚未保存的小步")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("测试重新打开草稿").performClick()
        compose.onNode(hasSetTextAction() and hasText("未保存的待办草稿")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("尚未保存的小步")).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
