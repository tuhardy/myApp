package com.focusassistant.app.domain

data class TodoGroup(val key: String, val label: String, val todos: List<Todo>)

/**
 * 待办查看分组：标为重要的事跨分类自成一组排在最前，其余按 [CATEGORIES] 顺序分组。
 * 已完成的事沉到各组末尾，同组内保持原有先后顺序；空组不返回。
 */
object TodoGrouping {
    const val IMPORTANT_KEY = "important"
    const val IMPORTANT_LABEL = "重要"
    val CATEGORIES = listOf("个人成长", "工作", "生活")

    fun categoryKey(category: String) = "category:$category"

    fun group(todos: List<Todo>, categories: List<String> = CATEGORIES): List<TodoGroup> {
        val buckets = LinkedHashMap<String, MutableList<Todo>>()
        buckets[IMPORTANT_KEY] = mutableListOf()
        categories.forEach { buckets[categoryKey(it)] = mutableListOf() }
        val fallback = buckets.keys.last()
        todos.forEach { todo ->
            val key = if (todo.important) IMPORTANT_KEY else categoryKey(todo.category)
            // 分类被改坏或备份里带了未知分类的事不能凭空消失，归到最后一组兜底。
            (buckets[key] ?: buckets.getValue(fallback)).add(todo)
        }
        return buckets.entries.filter { it.value.isNotEmpty() }.map { (key, list) ->
            TodoGroup(
                key = key,
                label = if (key == IMPORTANT_KEY) IMPORTANT_LABEL else key.removePrefix("category:"),
                todos = list.sortedBy { it.done }
            )
        }
    }
}
