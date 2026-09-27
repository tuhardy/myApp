package com.focusassistant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
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
        val walked = TodoAging.toggleStep(todo("2026-03-20", steps = two), "b", 1000L)
        assertTrue(walked.done)
        assertTrue(walked.steps.all { it.done })
        assertEquals(1000L, walked.completedAt)
        // 明确重做选中的小步，父任务也回到未完成。
        val back = TodoAging.reopen(walked, setOf("a"))
        assertFalse(back.done)
        assertNull(back.completedAt)
        assertEquals(listOf(false, true), back.steps.map { it.done })
        // 勾掉父任务把剩余小步一并算走过。
        val parentDone = TodoAging.complete(back, 2000L)
        assertTrue(parentDone.done && parentDone.steps.all { it.done })
        assertEquals(2000L, parentDone.completedAt)
        assertEquals(parentDone, TodoAging.complete(parentDone, 3000L))
        assertEquals(parentDone, TodoAging.toggleDone(parentDone))
        // 没有小步的事仍然只有两种状态。
        assertTrue(TodoAging.complete(todo("2026-03-20"), 1000L).done)
        assertFalse(TodoAging.reopen(TodoAging.complete(todo(""), 1000L), emptySet()).done)
    }

    @Test fun legacyCompletionDoesNotInventTimeAndPartialProgressClearsOldTime() {
        val legacy = todo("", done = true)
        assertEquals(legacy, TodoAging.complete(legacy, 1000L))
        val pending = todo("", steps = listOf(TodoStep("a", "一"), TodoStep("b", "二"))).copy(completedAt = 5L)
        val stepped = TodoAging.toggleStep(pending, "a", 1000L)
        assertFalse(stepped.done)
        assertNull(stepped.completedAt)
        assertEquals(listOf(true, false), stepped.steps.map { it.done })
        assertEquals(listOf(false, false), TodoAging.toggleStep(stepped, "a", 2000L).steps.map { it.done })
    }

    @Test fun reopeningSeveralStepsPreservesOtherProgressAndRecordsOnlyNewCompletion() {
        val original = todo("2026-03-10", steps = listOf(
            TodoStep("a", "一"), TodoStep("b", "二"), TodoStep("c", "三"), TodoStep("d", "四")))
        val done = TodoAging.complete(original, 1000L)
        val reopened = TodoAging.reopen(done, setOf("a", "c"))
        assertEquals(listOf(false, true, false, true), reopened.steps.map { it.done })
        assertEquals(done.createdAt, reopened.createdAt)
        assertFalse(reopened.done)
        assertNull(reopened.completedAt)
        val partly = TodoAging.toggleStep(reopened, "c", 2000L)
        assertFalse(partly.done)
        assertNull(partly.completedAt)
        assertEquals(TodoProgress(4, 3, 75), TodoAging.progress(partly))
        val completedAgain = TodoAging.toggleStep(partly, "a", 3000L)
        assertEquals(done.copy(completedAt = 3000L), completedAgain)
        assertEquals(1000L, done.completedAt)
        assertTrue(done.steps.all { it.done })
        assertEquals(original.steps, TodoAging.reopen(completedAgain, original.steps.map { it.id }.toSet()).steps)
    }

    @Test fun stepsAndReopenRejectInvalidTransitions() {
        val pending = todo("", steps = listOf(TodoStep("a", "一")))
        val done = TodoAging.complete(pending, 1000L)
        assertThrows(IllegalArgumentException::class.java) { TodoAging.toggleStep(pending, "missing") }
        assertThrows(IllegalArgumentException::class.java) { TodoAging.toggleStep(done, "a") }
        assertThrows(IllegalArgumentException::class.java) { TodoAging.toggleStep(pending.copy(archived = true), "a") }
        assertThrows(IllegalArgumentException::class.java) { TodoAging.reopen(pending, setOf("a")) }
        assertThrows(IllegalArgumentException::class.java) { TodoAging.reopen(done, emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { TodoAging.reopen(done, setOf("missing")) }
        assertThrows(IllegalArgumentException::class.java) { TodoAging.reopen(done.copy(archived = true), setOf("a")) }
        assertThrows(IllegalArgumentException::class.java) { TodoAging.complete(pending, -1L) }
        assertThrows(IllegalArgumentException::class.java) { TodoAging.complete(pending, Validation.MAX_TIMESTAMP_MS + 1) }
    }
}

class TodoHistoryTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-03-20T12:00:00Z").toEpochMilli()
    private val month = YearMonth.of(2026, 3)
    private fun completed(id: String, time: String?) = Todo(id, "事项 $id", "工作", done = true,
        completedAt = time?.let { Instant.parse(it).toEpochMilli() })
    private fun select(todos: List<Todo>, query: String = "", undated: Boolean = false, limit: Int = TodoHistory.PAGE_SIZE) =
        TodoHistory.select(todos, month, query, undated, limit, now, zone)

    @Test fun defaultMonthUsesInjectedClockAndZoneRatherThanSystemMonth() {
        val boundaryNow = Instant.parse("2026-03-01T00:30:00Z").toEpochMilli()
        val todo = completed("boundary", "2026-03-01T00:15:00Z")
        val localZone = ZoneId.of("America/New_York")
        val result = TodoHistory.select(listOf(todo), now = boundaryNow, zone = localZone)
        assertEquals(1, result.total)
        assertEquals(YearMonth.of(2026, 2), result.latestMonth)
        assertEquals("2026-02-28", result.groups.single().key)
        assertEquals("今天", result.groups.single().label)
        assertEquals(YearMonth.of(2026, 3), TodoHistory.select(listOf(todo), now = boundaryNow, zone = zone).latestMonth)
    }

    @Test fun paginationIsGlobalAfterFilteringAndSortingNotPerDay() {
        val newest = List(15) { completed("new$it", "2026-03-20T10:00:00Z") }
        val older = List(15) { completed("old$it", "2026-03-19T10:00:00Z") }
        val outside = completed("feb", "2026-02-28T23:59:59Z")
        val todos = older + outside + newest
        val first = select(todos)
        assertEquals(30, first.total)
        assertEquals(20, first.shown)
        assertEquals(listOf("2026-03-20", "2026-03-19"), first.groups.map { it.key })
        assertEquals(listOf("今天", "昨天"), first.groups.map { it.label })
        assertEquals(listOf(15, 5), first.groups.map { it.todos.size })
        assertEquals(newest + older.take(5), first.groups.flatMap { it.todos })
        assertEquals(30, select(todos, limit = 40).shown)
        assertEquals(listOf(TodoMonthCount(month, 30), TodoMonthCount(month.minusMonths(1), 1)), first.months)
        assertEquals(month, first.latestMonth)
        assertEquals(todos, older + outside + newest)
    }

    @Test fun searchSpansAllTimeAndMetadataIgnoresSearchAndMonth() {
        val recent = completed("recent", "2026-03-10T12:00:00Z").copy(title = "ALPHA")
        val old = completed("old", "2025-12-31T12:00:00Z").copy(steps = listOf(TodoStep("a", "alpha 小步", true)))
        val unknown = completed("unknown", null).copy(category = "Alpha 分类")
        val unrelated = completed("other", "2026-02-02T12:00:00Z")
        val all = listOf(old, unknown, unrelated, recent, recent.copy(id = "archived", archived = true), old.copy(id = "pending", done = false))
        val result = select(all, query = "  aLpHa  ", undated = true)
        assertEquals(listOf(recent, old, unknown), result.groups.flatMap { it.todos })
        assertEquals(listOf("2026年3月10日", "2025年12月31日", "完成时间未记录"), result.groups.map { it.label })
        assertEquals(3, result.total)
        assertEquals(3, result.months.size)
        assertEquals(1, result.undatedCount)
        assertEquals(result.months, select(all, query = "no match").months)
        assertEquals("3月10日", select(all).groups.single().label)
        val emptyMonth = TodoHistory.select(all, YearMonth.of(2024, 1), now = now, zone = zone)
        assertEquals(0, emptyMonth.total)
        assertEquals(month, emptyMonth.latestMonth)
    }

    @Test fun missingInvalidAndFutureDatesStayUnknownWithoutInventingHistory() {
        val unknown = completed("missing", null)
        val future = unknown.copy(id = "future", completedAt = now + 1)
        val negative = unknown.copy(id = "negative", completedAt = -1)
        val overflow = unknown.copy(id = "overflow", completedAt = Long.MAX_VALUE)
        val all = listOf(unknown, future, negative, overflow)
        all.forEach { assertNull(TodoHistory.completedDate(it, now, zone)) }
        val result = select(all, undated = true)
        assertEquals(4, result.total)
        assertEquals(4, result.undatedCount)
        assertEquals(all, result.groups.single().todos)
        assertEquals("unknown", result.groups.single().key)
        assertTrue(result.months.isEmpty())
        assertNull(result.latestMonth)
        assertTrue(select(all).groups.isEmpty())
        assertEquals(LocalDate.of(1970, 1, 1), TodoHistory.completedDate(unknown.copy(completedAt = 0), now, zone))
        assertEquals(LocalDate.of(2026, 3, 20), TodoHistory.completedDate(unknown.copy(completedAt = now), now, zone))
    }

    @Test fun localMonthBoundaryAndDstUseCalendarDates() {
        val newYork = ZoneId.of("America/New_York")
        val boundary = completed("boundary", "2026-03-01T04:30:00Z")
        assertEquals(LocalDate.of(2026, 2, 28), TodoHistory.completedDate(boundary, now, newYork))
        assertEquals(LocalDate.of(2026, 3, 1), TodoHistory.completedDate(boundary, now, ZoneId.of("Asia/Shanghai")))
        val feb = TodoHistory.select(listOf(boundary), month.minusMonths(1), now = now, zone = newYork)
        assertEquals(1, feb.total)
        assertEquals(0, TodoHistory.select(listOf(boundary), month, now = now, zone = newYork).total)
        val dstNow = Instant.parse("2026-03-09T04:30:00Z").toEpochMilli()
        val dstYesterday = completed("dst", "2026-03-08T05:15:00Z")
        val result = TodoHistory.select(listOf(dstYesterday), month, now = dstNow, zone = newYork)
        assertEquals("2026-03-08", result.groups.single().key)
        assertEquals("昨天", result.groups.single().label)
        val fallbackNow = Instant.parse("2026-11-02T05:30:00Z").toEpochMilli()
        val first = completed("first", "2026-11-01T05:30:00Z")
        val second = completed("second", "2026-11-01T06:30:00Z")
        val fallback = TodoHistory.select(listOf(first, second), YearMonth.of(2026, 11), now = fallbackNow, zone = newYork)
        assertEquals("昨天", fallback.groups.single().label)
        assertEquals(listOf(second, first), fallback.groups.single().todos)
    }

    @Test fun searchAndUndatedPaginationUseOneGlobalLimitWithoutChangingMetadata() {
        val unknown = List(25) { completed("unknown$it", null).copy(title = "匹配") }
        val old = List(8) { completed("old$it", "2025-12-31T10:00:00Z").copy(title = "匹配") }
        val recent = List(15) { completed("recent$it", "2026-03-19T10:00:00Z").copy(title = "匹配") }
        val unrelated = List(30) { completed("other$it", "2026-03-20T10:00:00Z") }
        val all = unknown + old + unrelated + recent
        val search = select(all, query = "匹配")
        assertEquals(48, search.total)
        assertEquals(20, search.shown)
        assertEquals(recent + old.take(5), search.groups.flatMap { it.todos })
        val more = select(all, query = "匹配", limit = 40)
        assertEquals(listOf(15, 8, 17), more.groups.map { it.todos.size })
        val undated = select(all, undated = true)
        assertEquals(25, undated.total)
        assertEquals(unknown.take(20), undated.groups.single().todos)
        listOf(search, more, undated, select(all, query = "不存在", limit = 1)).forEach { result ->
            assertEquals(listOf(TodoMonthCount(month, 45), TodoMonthCount(YearMonth.of(2025, 12), 8)), result.months)
            assertEquals(25, result.undatedCount)
            assertEquals(month, result.latestMonth)
        }
    }

    @Test fun crossYearResultsSortByInstantAndGroupByLocalDateWithoutImportancePinning() {
        val yearEnd = completed("yearEnd", "2025-12-31T15:59:59Z").copy(important = true)
        val yearStart = completed("yearStart", "2025-12-31T16:00:00Z")
        val january = completed("january", "2026-01-02T02:00:00Z")
        val result = TodoHistory.select(listOf(yearEnd, yearStart, january), YearMonth.of(2025, 12),
            query = "事项", now = now, zone = ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf(january, yearStart, yearEnd), result.groups.flatMap { it.todos })
        assertEquals(listOf("2026-01-02", "2026-01-01", "2025-12-31"), result.groups.map { it.key })
        assertEquals(listOf(TodoMonthCount(YearMonth.of(2026, 1), 2), TodoMonthCount(YearMonth.of(2025, 12), 1)), result.months)
        assertEquals(YearMonth.of(2026, 1), result.latestMonth)
    }

    @Test fun futureMonthsAndNonPositiveLimitsAreRejectedEvenDuringSearch() {
        assertThrows(IllegalArgumentException::class.java) { TodoHistory.select(emptyList(), month.plusMonths(1), query = "x", now = now, zone = zone) }
        assertThrows(IllegalArgumentException::class.java) { select(emptyList(), limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { select(emptyList(), limit = -1) }
        assertTrue(select(emptyList()).groups.isEmpty())
    }
}

class TodoUndoTest {
    private val a = Todo("a", "一", "工作")
    private val b = Todo("b", "二", "工作")
    private val c = Todo("c", "三", "工作")

    @Test fun chainedChangesUndoOnlyInReverseOrder() {
        val done = TodoAging.complete(b, 1000L)
        val important = done.copy(important = true)
        val completion = TodoChange(b, done, 1)
        val marking = TodoChange(done, important, 1)
        val current = listOf(a, important, c)
        assertNull(TodoUndo.apply(current, completion))
        val markedUndone = requireNotNull(TodoUndo.apply(current, marking))
        assertEquals(listOf(a, done, c), markedUndone)
        assertEquals(listOf(a, b, c), TodoUndo.apply(markedUndone, completion))
        assertEquals(listOf(a, important, c), current)
        assertNull(TodoUndo.apply(listOf(a, done.copy(title = "后来编辑"), c), completion))
        assertNull(TodoUndo.apply(listOf(a, c), completion))
    }

    @Test fun deletionsRestoreOriginalOrderAndNeverOverwriteRecreatedIds() {
        val deleteB = TodoChange(b, null, 1)
        val deleteA = TodoChange(a, null, 0)
        val restoredA = requireNotNull(TodoUndo.apply(listOf(c), deleteA))
        assertEquals(listOf(a, c), restoredA)
        assertEquals(listOf(a, b, c), TodoUndo.apply(restoredA, deleteB))
        assertNull(TodoUndo.apply(listOf(a, b.copy(title = "重建"), c), deleteB))
        assertNull(TodoUndo.apply(listOf(a, b, c), deleteB))
        assertEquals(listOf(c, b), TodoUndo.apply(listOf(c), deleteB.copy(index = 99)))
        assertEquals(listOf(b, c), TodoUndo.apply(listOf(c), deleteB.copy(index = -1)))
    }

    @Test fun multipleStepReopenDeleteAndCompletionChangesRestoreExactSnapshots() {
        val initial = b.copy(steps = listOf(TodoStep("x", "一", true), TodoStep("y", "二"), TodoStep("z", "三")))
        val partly = TodoAging.toggleStep(initial, "y", 1000L)
        val done = TodoAging.toggleStep(partly, "z", 2000L)
        val reopened = TodoAging.reopen(done, setOf("x", "z"))
        val changes = listOf(TodoChange(initial, partly, 1), TodoChange(partly, done, 1),
            TodoChange(done, reopened, 1), TodoChange(reopened, null, 1))
        var current = listOf(a, c)
        changes.asReversed().forEach { change ->
            current = requireNotNull(TodoUndo.apply(current, change))
            assertEquals(change.before, current[1])
            assertNull(TodoUndo.apply(current, change))
        }
        assertEquals(listOf(a, initial, c), current)
    }

    @Test fun undoGuardsEveryPersistedFieldButAllowsUnrelatedOrderingChanges() {
        val done = TodoAging.complete(b.copy(steps = listOf(TodoStep("x", "一步"))), 1000L)
        val change = TodoChange(b, done, 1)
        val edits = listOf(done.copy(title = "编辑"), done.copy(category = "生活"),
            done.copy(important = true), done.copy(archived = true), done.copy(done = false),
            done.copy(createdAt = "2026-03-20"), done.copy(completedAt = 2000L),
            done.copy(steps = done.steps.map { it.copy(title = "改名") }),
            done.copy(steps = done.steps.map { it.copy(done = false) }))
        edits.forEach { assertNull(TodoUndo.apply(listOf(a, it, c), change)) }
        assertEquals(listOf(c, a, b), TodoUndo.apply(listOf(c, a, done), change))
    }

    @Test fun deleteThenUndoAllowsEarlierCompletionUndoAndPreservesOtherEdits() {
        val done = TodoAging.complete(b, 1000L)
        val editedA = a.copy(title = "其他事项后来编辑")
        val restored = requireNotNull(TodoUndo.apply(listOf(editedA, c), TodoChange(done, null, 1)))
        assertEquals(listOf(editedA, b, c), TodoUndo.apply(restored, TodoChange(b, done, 1)))
        assertNull(TodoUndo.apply(listOf(a, done, done, c), TodoChange(b, done, 1)))
        assertNull(TodoUndo.apply(listOf(a, c), TodoChange(b, a, 1)))
    }
}

class TodoEditingTest {
    private val today = LocalDate.of(2026, 3, 20)
    private val pending = Todo("t", "一件事", "工作", steps = listOf(TodoStep("a", "一", true), TodoStep("b", "二")))
    private fun merge(current: Todo?, draft: Todo) = TodoEditing.merge(current, draft, today, 2000L)

    @Test fun newTodoGetsTodayButLegacyEditsDoNotInventCreatedDate() {
        val created = merge(null, pending.copy(done = true, createdAt = "2020-01-01", completedAt = 1000L, archived = true))
        assertEquals(today.toString(), created.createdAt)
        assertFalse(created.done)
        assertFalse(created.archived)
        assertNull(created.completedAt)
        assertTrue(created.steps.none { it.done })
        val edited = merge(pending, pending.copy(title = "新名称", createdAt = today.toString()))
        assertEquals("", edited.createdAt)
        assertEquals("新名称", edited.title)
    }

    @Test fun pendingDraftCannotOverwriteLatestProgressAndNewStepsStartPending() {
        val stale = pending.copy(done = true, completedAt = 1L, steps = listOf(
            TodoStep("a", "一改名", false), TodoStep("b", "二改名", true), TodoStep("c", "新一步", true)))
        val saved = merge(pending, stale)
        assertEquals(listOf(true, false, false), saved.steps.map { it.done })
        assertFalse(saved.done)
        assertNull(saved.completedAt)
        assertEquals("一改名", saved.steps.first().title)
    }

    @Test fun removingFinalPendingStepCompletesButRemovingAllStepsDoesNot() {
        val saved = merge(pending, pending.copy(steps = pending.steps.take(1)))
        assertTrue(saved.done)
        assertEquals(2000L, saved.completedAt)
        val empty = merge(pending, pending.copy(steps = emptyList()))
        assertFalse(empty.done)
        assertNull(empty.completedAt)
    }

    @Test fun pendingSaveClearsStaleCompletionAndPreservesStoredDateAndArchiveState() {
        val stored = pending.copy(createdAt = "2020-01-01", archived = true, completedAt = 1000L)
        val draft = stored.copy(title = "改名", createdAt = today.toString(), archived = false, completedAt = 1500L)
        assertEquals(stored.copy(title = "改名", completedAt = null), merge(stored, draft))
        val withoutSteps = merge(stored, draft.copy(steps = emptyList()))
        assertFalse(withoutSteps.done)
        assertNull(withoutSteps.completedAt)
        assertTrue(withoutSteps.archived)
        assertEquals(stored.createdAt, withoutSteps.createdAt)
        val completed = merge(stored, draft.copy(steps = draft.steps.take(1)))
        assertTrue(completed.done)
        assertTrue(completed.archived)
        assertEquals(2000L, completed.completedAt)
        assertEquals(stored.createdAt, completed.createdAt)
    }

    @Test fun draftsOpenedBeforeCompletionCannotChangeStoredProgressOrTimestamp() {
        val staleDraft = pending.copy(title = "旧草稿的新名称", steps = pending.steps.map { it.copy(done = false) })
        val stored = TodoAging.complete(pending, 1000L)
        assertEquals(stored.copy(title = staleDraft.title), merge(stored, staleDraft))
        val reopened = TodoAging.reopen(stored, setOf("a"))
        val saved = merge(reopened, stored.copy(title = "完成时的旧草稿"))
        assertFalse(saved.done)
        assertNull(saved.completedAt)
        assertEquals(reopened.steps, saved.steps)
    }

    @Test fun editingRejectsInvalidIdentityStepsTextAndCompletionClock() {
        val badDrafts = listOf(pending.copy(id = "other"), pending.copy(title = " "),
            pending.copy(steps = listOf(TodoStep("a", "一"), TodoStep("a", "重复"))),
            pending.copy(steps = listOf(TodoStep("a", ""))),
            pending.copy(steps = listOf(TodoStep("a", "长".repeat(Validation.MAX_STEP_TITLE + 1)))),
            pending.copy(steps = List(Validation.MAX_STEPS + 1) { TodoStep("s$it", "一步") }))
        badDrafts.forEach { assertThrows(IllegalArgumentException::class.java) { merge(pending, it) } }
        listOf(-1L, Validation.MAX_TIMESTAMP_MS + 1).forEach { now ->
            assertThrows(IllegalArgumentException::class.java) {
                TodoEditing.merge(pending, pending.copy(steps = pending.steps.take(1)), today, now)
            }
        }
        val done = TodoAging.complete(pending, 1000L)
        val replacement = done.steps.map { if (it.id == "b") it.copy(id = "replacement") else it }
        assertThrows(IllegalArgumentException::class.java) { merge(done, done.copy(steps = replacement)) }
    }

    @Test fun completedEditsOnlyChangeTextAndImportanceWithoutChangingHistoryOrProgress() {
        val current = TodoAging.complete(pending, 1000L)
        val draft = current.copy(title = "新标题", category = "生活", important = true, done = false,
            archived = true, createdAt = today.toString(), completedAt = null,
            steps = current.steps.reversed().map { it.copy(title = it.title + "改", done = false) })
        val saved = merge(current, draft)
        assertEquals(current.copy(title = "新标题", category = "生活", important = true,
            steps = current.steps.map { it.copy(title = it.title + "改") }), saved)
        assertThrows(IllegalArgumentException::class.java) { merge(current, draft.copy(steps = draft.steps.take(1))) }
        assertThrows(IllegalArgumentException::class.java) { merge(current, draft.copy(steps = draft.steps + TodoStep("c", "新增"))) }
        assertNull(merge(current.copy(completedAt = null), draft).completedAt)
    }
}
