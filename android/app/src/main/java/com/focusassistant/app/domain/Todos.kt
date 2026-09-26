package com.focusassistant.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class TodoGroup(val key: String, val label: String, val todos: List<Todo>)

enum class TodoStage { FRESH, RESTING, STALE, DONE }
data class TodoAge(val days: Long, val stage: TodoStage, val label: String)
data class TodoProgress(val total: Int, val walked: Int, val percent: Int)

/**
 * 待办发酵：一件事在清单里待得越久，阶段越靠后。按本地日历天差计算，
 * 不按 24 小时整除，避免夏令时偏差；已完成的事不参与发酵。
 */
object TodoAging {
    const val RESTING_DAYS = 3L
    const val STALE_DAYS = 7L

    /** 放入日期缺失或损坏时按今天处理，不让它凭空变成躺了很久的事。 */
    fun daysBetween(createdAt: String, today: LocalDate): Long {
        val from = runCatching { LocalDate.parse(createdAt) }.getOrNull() ?: return 0
        return ChronoUnit.DAYS.between(from, today)
    }

    fun age(todo: Todo, today: LocalDate): TodoAge {
        val days = daysBetween(todo.createdAt, today).coerceAtLeast(0)
        return when {
            todo.done -> TodoAge(days, TodoStage.DONE, "已完成")
            days <= 0 -> TodoAge(days, TodoStage.FRESH, "今天放进来的")
            days < RESTING_DAYS -> TodoAge(days, TodoStage.FRESH, "躺了 $days 天")
            days < STALE_DAYS -> TodoAge(days, TodoStage.RESTING, "躺了 $days 天")
            else -> TodoAge(days, TodoStage.STALE, "躺了 $days 天")
        }
    }

    /** 走过的小步即进度。没有小步的事只有做完与没做完两种状态。 */
    fun progress(todo: Todo): TodoProgress {
        val total = todo.steps.size
        if (total == 0) return TodoProgress(0, 0, if (todo.done) 100 else 0)
        val walked = todo.steps.count { it.done }
        return TodoProgress(total, walked, Math.round(walked * 100.0 / total).toInt())
    }

    fun nextStep(todo: Todo): TodoStep? = todo.steps.firstOrNull { !it.done }

    /** 专注记录里保留的是「父任务 · 这一步」，子步骤不单独入账。 */
    fun stepFocusTitle(todo: Todo, step: TodoStep?): String {
        val child = step?.title?.trim().orEmpty()
        return if (child.isEmpty()) todo.title.trim() else "${todo.title.trim()} · $child"
    }

    /**
     * 勾掉父任务把剩余小步一并算走过；任一步回退父任务也回到未完成。
     * 全部走完则父任务自动完成。
     */
    fun toggleDone(todo: Todo): Todo {
        val done = !todo.done
        return todo.copy(done = done, steps = todo.steps.map { it.copy(done = done) })
    }

    fun toggleStep(todo: Todo, stepId: String): Todo {
        val steps = todo.steps.map { if (it.id == stepId) it.copy(done = !it.done) else it }
        return todo.copy(steps = steps, done = steps.isNotEmpty() && steps.all { it.done })
    }
}

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
