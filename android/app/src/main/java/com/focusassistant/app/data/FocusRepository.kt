package com.focusassistant.app.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.Room
import androidx.room.withTransaction
import com.focusassistant.app.domain.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FocusRepository(context: Context, scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val database = Room.databaseBuilder(appContext, FocusDatabase::class.java, "focus-assistant.db")
        .addMigrations(FocusDatabase.MIGRATION_1_2)
        .enableMultiInstanceInvalidation().build()
    private val dao = database.dao()
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var loadedRevision: Long? = null
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private val bootCount: Int get() = Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    init {
        scope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val loaded = database.withTransaction {
                        dao.initializeRevision(RevisionRow())
                        val current = load()
                        val recovered = current.timer?.let { TimerEngine.recover(it, bootCount, SystemClock.elapsedRealtime()) }
                        val next = current.copy(timer = recovered)
                        persist(current, next)
                        next to dao.revision()
                    }
                    mutableState.value = loaded.first
                    loadedRevision = loaded.second
                    // 启动时还没有任何编辑草稿，未被正式日记引用的附件都是上次中断留下的，可安全清理。
                    DiaryFiles.cleanOrphans(appContext, loaded.first.diaries.flatMapTo(mutableSetOf()) { it.files })
                }
                ready.complete(Unit)
                dao.observeRevision().collect { revision ->
                    mutex.withLock {
                        if (revision != loadedRevision) {
                            val loaded = database.withTransaction { load() to dao.revision() }
                            mutableState.value = loaded.first
                            loadedRevision = loaded.second
                        }
                    }
                }
            } catch (error: Exception) {
                ready.completeExceptionally(error)
                throw error
            }
        }
        scope.launch(Dispatchers.IO) {
            ready.await()
            while (isActive) {
                delay(CHECKPOINT_DELAY_MS)
                mutate { current ->
                    val timer = current.timer
                    if (timer?.status == TimerStatus.RUNNING) current.copy(timer = TimerEngine.checkpoint(timer, SystemClock.elapsedRealtime())) else current
                }
            }
        }
    }

    suspend fun saveProject(project: Project) {
        Validation.project(project)
        mutate { current ->
            require(current.timer?.projectId != project.id) { "请先结束或放弃该项目的计时再编辑" }
            current.copy(projects = upsert(current.projects, project) { it.id })
        }
    }
    suspend fun deleteProject(id: String) {
        mutate { current ->
            require(current.timer?.projectId != id) { "请先确认放弃该项目的计时" }
            current.copy(projects = current.projects.filterNot { it.id == id })
        }
    }
    suspend fun saveTodo(todo: Todo, existing: Boolean = false) {
        // 新建的事从今天开始发酵；已有的事保留原本的放入日期。
        mutate { current ->
            val previous = current.todos.find { it.id == todo.id }
            require(!existing || previous != null) { "这件待办已被删除，未保存修改" }
            val stored = TodoEditing.merge(previous, todo, today(), System.currentTimeMillis())
            Validation.todo(stored)
            current.copy(todos = upsert(current.todos, stored) { it.id })
        }
    }
    suspend fun deleteTodo(id: String): TodoChange? = updateTodo(id) { null }
    /** 勾掉父任务把剩余小步一并算走过，已完成项必须明确选择重新打开。 */
    suspend fun completeTodo(id: String): TodoChange? = updateTodo(id) { TodoAging.complete(it) }
    suspend fun toggleTodoDone(id: String): TodoChange? = completeTodo(id)
    suspend fun toggleTodoStep(id: String, stepId: String): TodoChange? = updateTodo(id) { TodoAging.toggleStep(it, stepId) }
    /** 「续一天」把放入日期重置为今天，让它回到眼前。 */
    suspend fun renewTodo(id: String): TodoChange? = updateTodo(id) { it.copy(createdAt = today().toString()) }
    /** 「放下」只置 archived 不删除，之后可以找回。 */
    suspend fun archiveTodo(id: String, archived: Boolean): TodoChange? = updateTodo(id) {
        if (it.archived == archived) it else if (archived) it.copy(archived = true)
        else it.copy(archived = false, createdAt = today().toString())
    }
    suspend fun setTodoImportant(id: String, important: Boolean): TodoChange? = updateTodo(id) { it.copy(important = important) }
    suspend fun reopenTodo(id: String, redoStepIds: Set<String>): TodoChange? = updateTodo(id) { TodoAging.reopen(it, redoStepIds) }
    suspend fun undoTodo(change: TodoChange): Boolean {
        var restored = false
        mutate { current ->
            val todos = TodoUndo.apply(current.todos, change) ?: return@mutate current
            Validation.todo(change.before)
            restored = true
            current.copy(todos = todos)
        }
        return restored
    }
    private suspend fun updateTodo(id: String, change: (Todo) -> Todo?): TodoChange? {
        var result: TodoChange? = null
        mutate { current ->
            val index = current.todos.indexOfFirst { it.id == id }
            if (index < 0) return@mutate current
            val todo = current.todos[index]
            val next = change(todo)
            if (next == todo) return@mutate current
            next?.let(Validation::todo)
            result = TodoChange(todo, next, index)
            current.copy(todos = if (next == null) current.todos.filterNot { it.id == id }
                else current.todos.map { if (it.id == id) next else it })
        }
        return result
    }
    private fun today() = java.time.LocalDate.now()
    suspend fun saveSettings(settings: AppSettings) {
        Validation.settings(settings)
        mutate { it.copy(settings = settings) }
    }
    suspend fun saveProgress(sessionId: String, note: String, percent: Int?) {
        Validation.progress(note, percent)
        mutate { current ->
            val session = current.sessions.find { it.id == sessionId } ?: error("专注记录不存在")
            val previous = current.progress.maxOfOrNull { it.updatedAt } ?: 0L
            val revision = ProgressEntry(id(), session.id, session.projectId, session.endedAt, note.trim(), percent,
                maxOf(System.currentTimeMillis(), previous))
            current.copy(progress = current.progress + revision)
        }
    }
    suspend fun markProgressPrompted(sessionId: String) {
        mutate { current -> current.copy(sessions = current.sessions.map { if (it.id == sessionId) it.copy(progressPrompted = true) else it }) }
    }
    suspend fun startTimer(projectId: String) {
        mutate { current ->
            require(current.timer == null) { "已有计时，请先结束或确认放弃" }
            val project = current.projects.find { it.id == projectId } ?: error("项目不存在")
            current.copy(timer = TimerEngine.start(project, id(), System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount))
        }
    }
    suspend fun startBreak(longBreak: Boolean) {
        mutate { current ->
            require(current.timer == null) { "已有计时，请先结束或确认放弃" }
            val wall = System.currentTimeMillis()
            current.copy(timer = ActiveTimer(id(), "", if (longBreak) "长休息" else "短休息", "", TimerMode.COUNTDOWN,
                if (longBreak) current.settings.longBreakMinutes else current.settings.shortBreakMinutes,
                if (longBreak) TimerPhase.LONG_BREAK else TimerPhase.SHORT_BREAK, TimerStatus.RUNNING, wall,
                anchorElapsed = SystemClock.elapsedRealtime(), anchorWall = wall, bootCount = bootCount))
        }
    }
    suspend fun pauseTimer() {
        mutate { current -> current.copy(timer = current.timer?.let {
            val elapsed = SystemClock.elapsedRealtime()
            if (it.timerMode == TimerMode.COUNTDOWN && it.targetReached(elapsed)) it else TimerEngine.pause(it, elapsed)
        }) }
    }
    suspend fun resumeTimer() {
        mutate { current -> current.copy(timer = current.timer?.let {
            if (it.status == TimerStatus.PAUSED) TimerEngine.resume(it, System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount) else it
        }) }
    }
    /** [early] 为用户确认的提前结束：倒计时未到零也按实际时长保存。 */
    suspend fun finishTimer(early: Boolean = false): TimerEvent? {
        var event: TimerEvent? = null
        mutate { current ->
            val timer = current.timer
            if (timer == null) current else {
                val completed = complete(current, timer, SystemClock.elapsedRealtime(), early)
                event = completed.second
                completed.first
            }
        }
        return event
    }
    suspend fun discardTimer() { mutate { it.copy(timer = null) } }
    suspend fun tickTimer(): TimerEvent? {
        var event: TimerEvent? = null
        mutate { current ->
            val result = TimerEngine.tick(current.timer, SystemClock.elapsedRealtime())
            event = result.event
            val session = result.session
            val sessions = if (session != null && current.sessions.none { it.id == session.id }) current.sessions + session else current.sessions
            current.copy(timer = result.timer, sessions = sessions)
        }
        return event
    }
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock { database.withTransaction { BackupCodec.encode(load()) } }
    }
    suspend fun restoreBackup(backup: BackupData) {
        withContext(Dispatchers.IO) {
            BackupCodec.validate(backup)
            mutate { current ->
                require(current.timer == null) { "请先结束或确认放弃当前计时，再恢复备份" }
                // 现有备份不含日记：恢复时原样保留本机日记，不能因为备份里没有而清空。
                AppState(backup.projects.toList(), backup.todos.toList(), backup.sessions.map { it.copy(progressPrompted = true, segments = it.segments.toList()) }, backup.progress.toList(), backup.settings, loading = false, diaries = current.diaries)
            }
        }
    }

    /**
     * 保存日记。附件文件必须已在私有目录中，缺失时不保存、不冒充成功。
     * 返回旧版本中不再使用的附件文件名，由调用方在确认无草稿引用后清理。
     */
    suspend fun saveDiary(draft: DiaryEntry, existing: Boolean): Set<String> {
        val missing = withContext(Dispatchers.IO) { draft.files.filterNot { DiaryFiles.exists(appContext, it) } }
        require(missing.isEmpty()) { "有 ${missing.size} 个附件文件已不可用，未保存。请移除后重试。" }
        var obsolete = emptySet<String>()
        mutate { current ->
            val previous = current.diaries.find { it.id == draft.id }
            require(!existing || (previous != null && previous.deletedAt == null)) { "这条日记已被删除，未保存修改" }
            val now = System.currentTimeMillis()
            val stored = draft.copy(createdAt = previous?.createdAt ?: now, updatedAt = now, deletedAt = null)
            DiaryRules.validate(stored, now)
            obsolete = previous?.files.orEmpty().toSet() - stored.files.toSet()
            current.copy(diaries = if (previous == null) listOf(stored) + current.diaries else current.diaries.map { if (it.id == stored.id) stored else it })
        }
        return obsolete
    }
    /** 删除只移入回收站，不删除正文与附件。 */
    suspend fun trashDiary(id: String) = updateDiary(id) { DiaryRules.moveToTrash(it, System.currentTimeMillis()) }
    /** 恢复保留原标识、记录时间与全部附件。 */
    suspend fun restoreDiary(id: String) = updateDiary(id) { DiaryRules.restore(it) }
    /** 永久删除只针对回收站中的记录，返回其附件文件名供清理。 */
    suspend fun purgeDiary(id: String): Set<String> {
        var files = emptySet<String>()
        mutate { current ->
            val entry = current.diaries.find { it.id == id && it.deletedAt != null } ?: return@mutate current
            files = entry.files.toSet()
            current.copy(diaries = current.diaries.filterNot { it.id == id })
        }
        return files
    }
    /** 只删除既不属于任何日记、也不被草稿使用的文件。 */
    suspend fun deleteDiaryFiles(candidates: Collection<String>, protected: Set<String>) = withContext(Dispatchers.IO) {
        DiaryFiles.delete(appContext, DiaryRules.unreferenced(candidates, state.value.diaries, protected))
    }
    private suspend fun updateDiary(id: String, change: (DiaryEntry) -> DiaryEntry) {
        mutate { current ->
            current.diaries.find { it.id == id } ?: return@mutate current
            current.copy(diaries = current.diaries.map { if (it.id == id) change(it) else it })
        }
    }

    private fun complete(current: AppState, timer: ActiveTimer, elapsed: Long, early: Boolean = false): Pair<AppState, TimerEvent> {
        val session = TimerEngine.finish(timer, elapsed, System.currentTimeMillis(), bootCount, early)
        val sessions = if (session != null && current.sessions.none { it.id == session.id }) current.sessions + session else current.sessions
        return current.copy(sessions = sessions, timer = null) to TimerEvent(TimerEventKind.COMPLETED, timer.projectTitle, session?.id)
    }
    private suspend fun mutate(change: (AppState) -> AppState) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val committed = database.withTransaction {
                val current = if (dao.revision() == loadedRevision) mutableState.value else load()
                val next = change(current)
                persist(current, next)
                next to dao.revision()
            }
            mutableState.value = committed.first
            loadedRevision = committed.second
        }
    }
    private suspend fun load(): AppState = AppState(
        projects = dao.projects().map { JsonCodec.readProject(JSONObject(it.payload)) },
        todos = dao.todos().map { JsonCodec.readTodo(JSONObject(it.payload)) },
        sessions = dao.sessions().map { JsonCodec.readSession(JSONObject(it.payload)) },
        progress = dao.progress().map { JsonCodec.readProgress(JSONObject(it.payload)) },
        settings = dao.settings()?.let { JsonCodec.readSettings(JSONObject(it.payload)) } ?: AppSettings(),
        timer = dao.timer()?.let { JsonCodec.readTimer(JSONObject(it.payload)) }, loading = false,
        diaries = dao.diaries().map { JsonCodec.readDiary(JSONObject(it.payload)) })

    private suspend fun persist(old: AppState, next: AppState) {
        if (old == next) return
        sync(old.projects, next.projects, { it.id }, dao::removeProjects) { changed -> dao.putProjects(changed.map { (index, item) -> ProjectRow(item.id, JsonCodec.project(item).toString(), index) }) }
        sync(old.todos, next.todos, { it.id }, dao::removeTodos) { changed -> dao.putTodos(changed.map { (index, item) -> TodoRow(item.id, JsonCodec.todo(item).toString(), index) }) }
        sync(old.sessions, next.sessions, { it.id }, dao::removeSessions) { changed -> dao.putSessions(changed.map { (index, item) -> SessionRow(item.id, JsonCodec.session(item).toString(), index) }) }
        sync(old.progress, next.progress, { it.id }, dao::removeProgress) { changed -> dao.putProgress(changed.map { (index, item) -> ProgressRow(item.id, JsonCodec.progress(item).toString(), index) }) }
        sync(old.diaries, next.diaries, { it.id }, dao::removeDiaries) { changed -> dao.putDiaries(changed.map { (index, item) -> DiaryRow(item.id, JsonCodec.diary(item).toString(), index) }) }
        if (old.settings != next.settings) dao.putSettings(SettingsRow(payload = JsonCodec.settings(next.settings).toString()))
        if (old.timer != next.timer) {
            if (next.timer == null) dao.clearTimer() else dao.putTimer(TimerRow(payload = JsonCodec.timer(next.timer).toString()))
        }
        dao.bumpRevision()
    }
    private suspend fun <T> sync(old: List<T>, next: List<T>, key: (T) -> String, remove: suspend (List<String>) -> Unit, put: suspend (List<IndexedValue<T>>) -> Unit) {
        if (old == next) return
        val ids = next.map(key).toSet()
        old.map(key).filterNot { it in ids }.chunked(SQLITE_BATCH_SIZE).forEach { remove(it) }
        val previous = old.withIndex().associateBy { key(it.value) }
        val changed = next.withIndex().filter { previous[key(it.value)] != it }
        if (changed.isNotEmpty()) put(changed)
    }
    private fun <T> upsert(items: List<T>, value: T, key: (T) -> String): List<T> =
        if (items.any { key(it) == key(value) }) items.map { if (key(it) == key(value)) value else it } else items + value
    private fun id() = UUID.randomUUID().toString()

    private companion object {
        const val CHECKPOINT_DELAY_MS = 4000L
        const val SQLITE_BATCH_SIZE = 500
    }
}
