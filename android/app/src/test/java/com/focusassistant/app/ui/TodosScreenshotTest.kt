package com.focusassistant.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.focusassistant.app.domain.*
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 待办页截图测试：在 JVM 里用 Robolectric 渲染 Compose，不需要连接设备。
 * 截图输出到 build/reports/screenshots/，用于人工核对 UI 是否还原设计稿；
 * 这里不做像素比对，只保证页面能渲染出非空白画面，避免因基准图漂移而误报。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TodosScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.of(2026, 9, 27)

    /** 覆盖三档发酵、重要置顶、小步小路与已完成沉底，一张图就能看全布局。 */
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
        Todo("t4", "预约体检", "生活", done = true, createdAt = today.minusDays(2).toString())
    )

    /**
     * 不用 onRoot().captureToImage()：它走窗口级 PixelCopy 抓屏，在 Robolectric 里等不到重绘回调，
     * 会固定超时。改成让根视图自己画到 Bitmap 上，纯 JVM 内完成。
     */
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.activity.let { activity ->
            val view = activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { bmp ->
                compose.runOnUiThread { view.draw(Canvas(bmp)) }
            }
        }
        val target = File("build/reports/screenshots").apply { mkdirs() }.resolve("$name.png")
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 全白或全透明说明没真正渲染，这种情况下人工核对截图毫无意义。
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("$name 渲染结果为空白画面", pixels.distinct().size > 1)
        println("截图已输出：${target.absolutePath}")
    }

    private fun renderTodos(filter: TodoFilter, todos: List<Todo>) {
        compose.setContent {
            FocusTheme {
                TodosScreen(
                    state = AppState(todos = todos, loading = false),
                    filter = filter,
                    today = today,
                    onFilter = {}, onCreate = {}, onEdit = {}, onToggle = {}, onToggleStep = { _, _ -> },
                    onRenew = {}, onArchive = {}, onRestore = {}, onDelete = {}
                )
            }
        }
    }

    @Test fun todosScreenWithGroupsAndAging() {
        renderTodos(TodoFilter.ALL, sampleTodos())
        capture("todos-all")
    }

    @Test fun todosScreenEmptyState() {
        renderTodos(TodoFilter.ALL, emptyList())
        capture("todos-empty")
    }

    /** 放下的事只在这个页签出现，卡片压到半透明并给出「找回 / 删除」。 */
    @Test fun todosScreenArchived() {
        renderTodos(TodoFilter.ARCHIVED, listOf(
            Todo("a1", "学一门新乐器", "个人成长", archived = true, createdAt = today.minusDays(30).toString())
        ))
        capture("todos-archived")
    }
}
