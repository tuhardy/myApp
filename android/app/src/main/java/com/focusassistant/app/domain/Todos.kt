package com.focusassistant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class TodoGroup(val key: String, val label: String, val todos: List<Todo>)

data class TodoMonthCount(val month: YearMonth, val count: Int)
data class TodoHistoryResult(
    val groups: List<TodoGroup>, val total: Int, val shown: Int,
    val months: List<TodoMonthCount>, val undatedCount: Int, val latestMonth: YearMonth?
)
data class TodoChange(val before: Todo, val after: Todo?, val index: Int)

object TodoUndo {
    fun apply(todos: List<Todo>, change: TodoChange): List<Todo>? {
        if (change.after != null && change.after.id != change.before.id) return null
        val matches = todos.withIndex().filter { it.value.id == change.before.id }
        if (change.after == null) {
            if (matches.isNotEmpty()) return null
            return todos.toMutableList().apply { add(change.index.coerceIn(0, size), change.before) }
        }
        if (matches.size != 1 || matches.single().value != change.after) return null
        return todos.map { if (it.id == change.before.id) change.before else it }
    }
}

object TodoEditing {
    fun merge(current: Todo?, draft: Todo, today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): Todo {
        Validation.todo(draft)
        if (current == null) return draft.copy(createdAt = today.toString(), done = false, archived = false,
            completedAt = null, steps = draft.steps.map { it.copy(done = false) })
        require(current.id == draft.id) { "待办标识不一致" }
        val titles = draft.steps.associateBy { it.id }
        val steps = if (current.done) {
            require(current.steps.map { it.id }.toSet() == titles.keys) { "已完成事项只能修改小步名称，请先重新打开" }
            current.steps.map { it.copy(title = titles.getValue(it.id).title) }
        } else {
            val stored = current.steps.associateBy { it.id }
            draft.steps.map { it.copy(done = stored[it.id]?.done ?: false) }
        }
        val next = current.copy(title = draft.title, category = draft.category, important = draft.important, steps = steps)
        if (current.done) return next
        if (steps.isNotEmpty() && steps.all { it.done }) {
            require(now in 0..Validation.MAX_TIMESTAMP_MS) { "完成日期无效" }
            return next.copy(done = true, completedAt = now)
        }
        return next.copy(completedAt = null)
    }
}

object TodoHistory {
    const val PAGE_SIZE = 20

    fun completedDate(todo: Todo, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val time = todo.completedAt ?: return null
        if (time !in 0..Validation.MAX_TIMESTAMP_MS || time > now) return null
        return Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    }

    fun select(
        todos: List<Todo>, query: String = "", undated: Boolean = false,
        limit: Int = PAGE_SIZE, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()
    ): TodoHistoryResult = select(todos, YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)), query, undated, limit, now, zone)

    fun select(
        todos: List<Todo>, month: YearMonth, query: String = "", undated: Boolean = false,
        limit: Int = PAGE_SIZE, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()
    ): TodoHistoryResult {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        require(month.year in 1..9999 && month <= YearMonth.from(today)) { "请选择有效且不晚于当月的月份" }
        require(limit > 0) { "加载条数必须为正整数" }
        val search = query.trim()
        val all = todos.filter { it.done && !it.archived }.map { it to completedDate(it, now, zone) }
        val months = all.mapNotNull { it.second?.let(YearMonth::from) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.key }.map { TodoMonthCount(it.key, it.value) }
        val undatedCount = all.count { it.second == null }
        val filtered = all.filter { (todo, date) ->
            when {
                search.isNotEmpty() -> (listOf(todo.title, todo.category) + todo.steps.map { it.title })
                    .any { it.contains(search, ignoreCase = true) }
                undated -> date == null
                else -> date != null && YearMonth.from(date) == month
            }
        }.sortedByDescending { (todo, date) -> if (date == null) Long.MIN_VALUE else todo.completedAt!! }
        val loaded = filtered.take(limit)
        val groups = loaded.groupBy { it.second }.map { (date, items) ->
            val label = when (date) {
                null -> "完成时间未记录"
                today -> "今天"
                today.minusDays(1) -> "昨天"
                else -> "${if (search.isNotEmpty()) "${date.year}年" else ""}${date.monthValue}月${date.dayOfMonth}日"
            }
            TodoGroup(date?.toString() ?: "unknown", label, items.map { it.first })
        }
        return TodoHistoryResult(groups, filtered.size, loaded.size, months, undatedCount, months.firstOrNull()?.month)
    }
}

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

    /**
     * 勾掉父任务把剩余小步一并算走过；已完成项必须明确选择重新打开。
     * 全部走完则父任务自动完成，首次完成记录时间。
     */
    fun complete(todo: Todo, now: Long = System.currentTimeMillis()): Todo {
        require(!todo.archived) { "请先找回放下的事项" }
        if (todo.done) return todo
        require(now in 0..Validation.MAX_TIMESTAMP_MS) { "完成日期无效" }
        return todo.copy(done = true, completedAt = now, steps = todo.steps.map { it.copy(done = true) })
    }

    fun toggleDone(todo: Todo): Todo = complete(todo)

    fun toggleStep(todo: Todo, stepId: String, now: Long = System.currentTimeMillis()): Todo {
        require(!todo.done && !todo.archived) { "请先重新打开或找回该事项" }
        require(todo.steps.any { it.id == stepId }) { "请选择有效的小步" }
        val steps = todo.steps.map { if (it.id == stepId) it.copy(done = !it.done) else it }
        val next = todo.copy(steps = steps, completedAt = null)
        return if (steps.all { it.done }) complete(next, now) else next
    }

    fun reopen(todo: Todo, redoStepIds: Set<String>): Todo {
        require(todo.done && !todo.archived) { "只能重新打开未放下的已完成事项" }
        require(todo.steps.isEmpty() || todo.steps.any { it.id in redoStepIds }) { "请至少选择一个需要重做的小步" }
        return todo.copy(done = false, completedAt = null,
            steps = todo.steps.map { if (it.id in redoStepIds) it.copy(done = false) else it })
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
