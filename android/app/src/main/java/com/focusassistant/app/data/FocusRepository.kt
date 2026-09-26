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
    suspend fun saveTodo(todo: Todo) {
        Validation.todo(todo)
        mutate { it.copy(todos = upsert(it.todos, todo) { item -> item.id }) }
    }
    suspend fun deleteTodo(id: String) { mutate { it.copy(todos = it.todos.filterNot { todo -> todo.id == id }) } }
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
    suspend fun startTimer(projectId: String, taskId: String? = null) {
        mutate { current ->
            require(current.timer == null) { "已有计时，请先结束或确认放弃" }
            val project = current.projects.find { it.id == projectId } ?: error("项目不存在")
            val task = taskId?.let { requested -> current.todos.find { it.id == requested } ?: error("待办不存在") }
            current.copy(timer = TimerEngine.start(project, id(), System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount, task))
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
    suspend fun finishTimer(): TimerEvent? {
        var event: TimerEvent? = null
        mutate { current ->
            val timer = current.timer
            if (timer == null) current else {
                val completed = complete(current, timer, SystemClock.elapsedRealtime())
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
                AppState(backup.projects.toList(), backup.todos.toList(), backup.sessions.map { it.copy(progressPrompted = true, segments = it.segments.toList()) }, backup.progress.toList(), backup.settings, loading = false)
            }
        }
    }

    private fun complete(current: AppState, timer: ActiveTimer, elapsed: Long): Pair<AppState, TimerEvent> {
        val session = TimerEngine.finish(timer, elapsed, System.currentTimeMillis(), bootCount)
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
        timer = dao.timer()?.let { JsonCodec.readTimer(JSONObject(it.payload)) }, loading = false)

    private suspend fun persist(old: AppState, next: AppState) {
        if (old == next) return
        sync(old.projects, next.projects, { it.id }, dao::removeProjects) { changed -> dao.putProjects(changed.map { (index, item) -> ProjectRow(item.id, JsonCodec.project(item).toString(), index) }) }
        sync(old.todos, next.todos, { it.id }, dao::removeTodos) { changed -> dao.putTodos(changed.map { (index, item) -> TodoRow(item.id, JsonCodec.todo(item).toString(), index) }) }
        sync(old.sessions, next.sessions, { it.id }, dao::removeSessions) { changed -> dao.putSessions(changed.map { (index, item) -> SessionRow(item.id, JsonCodec.session(item).toString(), index) }) }
        sync(old.progress, next.progress, { it.id }, dao::removeProgress) { changed -> dao.putProgress(changed.map { (index, item) -> ProgressRow(item.id, JsonCodec.progress(item).toString(), index) }) }
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
