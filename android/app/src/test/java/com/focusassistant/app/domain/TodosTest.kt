package com.focusassistant.app.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TodoGroupingTest {
    private fun todo(id: String, category: String, important: Boolean = false, done: Boolean = false) =
        Todo(id, "待办 $id", category, important, done)

    @Test fun importantPinnedOnTopAcrossCategoriesAndDoneSinksWithinGroup() {
        val todos = listOf(
            todo("a", "工作"),
            todo("b", "生活", important = true),
            todo("c", "工作", done = true),
            todo("d", "工作"),
            todo("e", "工作", important = true)
        )
        val groups = TodoGrouping.group(todos)
        assertEquals(listOf("important", "category:工作"), groups.map { it.key })
        assertEquals(listOf("重要", "工作"), groups.map { it.label })
        // 重要组跨分类，按原顺序；分类组内已完成沉底，未完成保持先后。
        assertEquals(listOf("b", "e"), groups[0].todos.map { it.id })
        assertEquals(listOf("a", "d", "c"), groups[1].todos.map { it.id })
    }

    @Test fun groupsFollowCategoryOrderAndSkipEmptyGroups() {
        val todos = TodoGrouping.CATEGORIES.reversed().mapIndexed { index, category -> todo("t$index", category) }
        assertEquals(TodoGrouping.CATEGORIES, TodoGrouping.group(todos).map { it.label })
        assertEquals(listOf("个人成长"), TodoGrouping.group(listOf(todo("x", "个人成长"))).map { it.label })
        assertTrue(TodoGrouping.group(emptyList()).isEmpty())
    }

    @Test fun unknownCategoryFallsIntoLastGroupAndInputIsNotModified() {
        val broken = listOf(todo("x", "已删除分类"))
        val groups = TodoGrouping.group(broken)
        assertEquals(1, groups.size)
        assertEquals(TodoGrouping.CATEGORIES.last(), groups[0].label)
        assertEquals(listOf("x"), groups[0].todos.map { it.id })
        val blank = TodoGrouping.group(listOf(todo("y", "")))
        assertEquals(TodoGrouping.CATEGORIES.last(), blank[0].label)
        // 分组只读取输入，不改动顺序或内容。
        val original = listOf(todo("a", "工作", done = true), todo("b", "工作"))
        val snapshot = original.toList()
        TodoGrouping.group(original)
        assertEquals(snapshot, original)
    }

    @Test fun everyTodoAppearsExactlyOnce() {
        val todos = listOf(
            todo("a", "工作", important = true),
            todo("b", "未知", important = true),
            todo("c", "生活"),
            todo("d", "个人成长", done = true),
            todo("e", "未知")
        )
        val grouped = TodoGrouping.group(todos).flatMap { it.todos }
        assertEquals(todos.size, grouped.size)
        assertEquals(todos.map { it.id }.toSet(), grouped.map { it.id }.toSet())
    }
}

class TodoAgingTest {
    private val today = LocalDate.parse("2026-03-20")
    private fun todo(createdAt: String, done: Boolean = false, steps: List<TodoStep> = emptyList()) =
        Todo("t", "一件事", "工作", done = done, createdAt = createdAt, steps = steps)

    @Test fun stagesAdvanceWithDaysAndDoneNeverFerments() {
        assertEquals(TodoStage.FRESH, TodoAging.age(todo("2026-03-20"), today).stage)
        assertEquals(TodoStage.FRESH, TodoAging.age(todo("2026-03-19"), today).stage)
        assertEquals(TodoStage.FRESH, TodoAging.age(todo("2026-03-18"), today).stage)
        assertEquals(TodoStage.RESTING, TodoAging.age(todo("2026-03-17"), today).stage)
        assertEquals(TodoStage.RESTING, TodoAging.age(todo("2026-03-14"), today).stage)
        assertEquals(TodoStage.STALE, TodoAging.age(todo("2026-03-13"), today).stage)
        assertEquals(TodoStage.DONE, TodoAging.age(todo("2026-03-10", done = true), today).stage)
        assertEquals("今天放进来的", TodoAging.age(todo("2026-03-20"), today).label)
        assertEquals("躺了 9 天", TodoAging.age(todo("2026-03-11"), today).label)
    }

    @Test fun futureAndBrokenDatesCountAsZeroDays() {
        assertEquals(0L, TodoAging.age(todo("2026-04-01"), today).days)
        assertEquals(TodoStage.FRESH, TodoAging.age(todo("2026-04-01"), today).stage)
        assertEquals(0L, TodoAging.age(todo(""), today).days)
        assertEquals(0L, TodoAging.age(todo("不是日期"), today).days)
    }

    @Test fun dayCountUsesCalendarDifferenceAcrossMonthsAndDst() {
        assertEquals(31L, TodoAging.daysBetween("2026-02-28", LocalDate.parse("2026-03-31")))
        // 夏令时切换当天也按日历天差，不因 23 小时的一天少算。
        assertEquals(2L, TodoAging.daysBetween("2026-03-07", LocalDate.parse("2026-03-09")))
    }

    @Test fun walkedStepsAreTheProgressAndNextStepIsTheFirstUnwalked() {
        val steps = listOf(TodoStep("a", "一", true), TodoStep("b", "二"), TodoStep("c", "三"))
        assertEquals(TodoProgress(3, 1, 33), TodoAging.progress(todo("2026-03-20", steps = steps)))
        assertEquals("b", TodoAging.nextStep(todo("2026-03-20", steps = steps))?.id)
        assertEquals(TodoProgress(0, 0, 0), TodoAging.progress(todo("2026-03-20")))
        assertEquals(TodoProgress(0, 0, 100), TodoAging.progress(todo("2026-03-20", done = true)))
        assertNull(TodoAging.nextStep(todo("2026-03-20")))
    }

    @Test fun walkingLastStepCompletesParentAndReopeningSyncsBack() {
        val two = listOf(TodoStep("a", "一", true), TodoStep("b", "二"))
        val walked = TodoAging.toggleStep(todo("2026-03-20", steps = two), "b")
        assertTrue(walked.done)
        assertTrue(walked.steps.all { it.done })
        // 任一步回退，父任务也回到未完成。
        val back = TodoAging.toggleStep(walked, "a")
        assertFalse(back.done)
        // 勾掉父任务把剩余小步一并算走过。
        val parentDone = TodoAging.toggleDone(back)
        assertTrue(parentDone.done && parentDone.steps.all { it.done })
        val parentReopened = TodoAging.toggleDone(parentDone)
        assertFalse(parentReopened.done)
        assertTrue(parentReopened.steps.none { it.done })
        // 没有小步的事仍然只有两种状态。
        assertTrue(TodoAging.toggleDone(todo("2026-03-20")).done)
    }

    @Test fun focusTitleSnapshotsParentAndStep() {
        val parent = Todo("t", "梳理想法", "工作", createdAt = "2026-03-20", steps = listOf(TodoStep("a", "画草图")))
        assertEquals("梳理想法 · 画草图", TodoAging.stepFocusTitle(parent, parent.steps[0]))
        assertEquals("梳理想法", TodoAging.stepFocusTitle(parent, null))
        assertEquals("梳理想法", TodoAging.stepFocusTitle(parent, TodoStep("b", "   ")))
    }
}
