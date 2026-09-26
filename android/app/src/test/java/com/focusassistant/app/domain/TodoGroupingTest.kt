package com.focusassistant.app.domain

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
