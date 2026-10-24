package com.focusassistant.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focusassistant.app.data.ExportCodec
import com.focusassistant.app.data.FocusRepository
import com.focusassistant.app.domain.*
import com.focusassistant.app.platform.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal enum class Screen { PROJECTS, TIMER, TODOS, DIARY, STATISTICS, USAGE, SETTINGS, JOB_ASSIST }

internal object TodoUiLimits {
    const val FINISH_HOLD_MS = 680L
    const val FINISH_COLLAPSE_MS = 240L
    const val STEP_PULSE_MS = 160
    const val STEP_PULSE_SCALE = 1.22f
    const val SWIPE_WIDTH_DP = 88
    const val SWIPE_THRESHOLD_DP = 36
    const val MIN_YEAR = 1900
    const val HISTORY_HEADER_ITEMS = 2
    const val SAVED_FLASH_MS = 1200
}

internal enum class TodoFinishPhase { HOLD, EXIT }

/** token 让同一件事连续保存也能重新触发闪动。 */
internal data class TodoSaved(val id: String, val token: Long)

@Stable
internal class TodoFinish(val token: Long, val beganAt: Long) {
    var phase by mutableStateOf(TodoFinishPhase.HOLD)
}

@Stable
internal class TodoViewState(index: Int = 0, offset: Int = 0, limit: Int = TodoHistory.PAGE_SIZE) {
    val listState = LazyListState(index, offset)
    var limit by mutableIntStateOf(limit)
}

internal data class TodoHistorySnapshot(
    val month: YearMonth, val query: String, val undated: Boolean, val limit: Int, val index: Int, val offset: Int
)
internal data class TodoUndoEntry(val change: TodoChange, val label: String, val filter: TodoFilter, val history: TodoHistorySnapshot?)

@Stable
internal class TodoUiState {
    @set:JvmName("assignFilter")
    var filter by mutableStateOf(TodoFilter.PENDING)
    var month by mutableStateOf(YearMonth.now())
    var query by mutableStateOf("")
    var undated by mutableStateOf(false)
    var openSwipeId by mutableStateOf<String?>(null)
    var reduceMotion by mutableStateOf(false)
    /** 刚保存的待办：列表滚到它并轻闪一次；只是界面提示，不代表持久化状态。 */
    var justSaved by mutableStateOf<TodoSaved?>(null)
    val expanded = mutableStateMapOf<String, Boolean>()
    val undo = mutableStateListOf<TodoUndoEntry>()
    val finishing = mutableStateMapOf<String, TodoFinish>()
    private val views = mutableStateMapOf<String, TodoViewState>()

    private fun viewKey(): String = when {
        filter != TodoFilter.DONE -> filter.name
        query.isNotBlank() -> "search"
        undated -> "undated"
        else -> month.toString()
    }
    fun view(): TodoViewState = views.getOrPut(viewKey()) { TodoViewState() }
    fun setFilter(value: TodoFilter) { filter = value; openSwipeId = null }
    fun search(value: String) {
        if (query == value) return
        views.remove("search")
        query = value
        openSwipeId = null
    }
    fun selectMonth(value: YearMonth) {
        if (value > YearMonth.now() || value.year < TodoUiLimits.MIN_YEAR) return
        month = value; query = ""; undated = false; openSwipeId = null
    }
    fun showUndated(value: Boolean) { undated = value; openSwipeId = null }
    fun loadMore() { view().limit += TodoHistory.PAGE_SIZE }
    fun snapshot(): TodoHistorySnapshot? = if (filter != TodoFilter.DONE) null else view().let {
        TodoHistorySnapshot(month, query, undated, it.limit, it.listState.firstVisibleItemIndex, it.listState.firstVisibleItemScrollOffset)
    }
    fun restore(snapshot: TodoHistorySnapshot) {
        filter = TodoFilter.DONE; month = snapshot.month; query = snapshot.query; undated = snapshot.undated; openSwipeId = null
        views[viewKey()] = TodoViewState(snapshot.index, snapshot.offset, snapshot.limit)
    }
    fun revealCompleted(todo: Todo, todos: List<Todo>) {
        val date = TodoHistory.completedDate(todo)
        filter = TodoFilter.DONE; query = ""; undated = date == null; openSwipeId = null
        if (date != null) month = YearMonth.from(date)
        val records = TodoHistory.select(todos, month = month, undated = undated, limit = maxOf(TodoHistory.PAGE_SIZE, todos.size))
        val position = records.groups.flatMap { it.todos }.indexOfFirst { it.id == todo.id }
        if (position >= 0) {
            val limit = maxOf(view().limit, (position / TodoHistory.PAGE_SIZE + 1) * TodoHistory.PAGE_SIZE)
            var listIndex = TodoUiLimits.HISTORY_HEADER_ITEMS
            for (group in records.groups) {
                listIndex++
                val withinGroup = group.todos.indexOfFirst { it.id == todo.id }
                if (withinGroup >= 0) {
                    views[viewKey()] = TodoViewState(listIndex + withinGroup, 0, limit)
                    break
                }
                listIndex += group.todos.size
            }
        }
    }
    fun clear() {
        filter = TodoFilter.PENDING; month = YearMonth.now(); query = ""; undated = false; openSwipeId = null
        views.clear(); expanded.clear(); undo.clear(); finishing.clear(); justSaved = null
    }
}

class NativeUiModel : ViewModel() {
    internal var screen by mutableStateOf(Screen.PROJECTS)
    var projectId by mutableStateOf<String?>(null)
    var dialog by mutableStateOf("")
    var dialogId by mutableStateOf("")
    var busy by mutableStateOf(false)
    internal val todos = TodoUiState()
    internal val diary = DiaryUiState()
    private val todoAnimations = mutableMapOf<String, Job>()
    private var todoAnimationToken = 0L
    var period by mutableStateOf(Period.DAY)
    /** 统计页第 5 个周期「全部」：为 true 时忽略 period 与 anchor。 */
    var statsAll by mutableStateOf(false)
    var anchor by mutableStateOf(LocalDate.now())
    var recordLimit by mutableIntStateOf(UiLimits.RECORD_PAGE)
    var usageWeek by mutableStateOf(false)
    /** 「隐藏金额」只影响显示，不写入本机数据；进程重启后恢复显示。 */
    var hideAmounts by mutableStateOf(false)
    var pendingProject by mutableStateOf<Project?>(null)
    var pendingBackup by mutableStateOf<BackupData?>(null)
    var pendingExport: String? = null
    var notificationRequested = false
    private val channel = Channel<String>(Channel.BUFFERED)
    val messages = channel.receiveAsFlow()

    fun message(text: String) { channel.trySend(text) }
    fun open(kind: String, id: String = "") { dialogId = id; dialog = kind }
    fun close() {
        val previous = dialog
        dialog = ""
        dialogId = ""
        if (previous == "progress-auto") screen = Screen.PROJECTS
        if (previous == "project") pendingProject = null
    }
    internal fun stopTodoAnimation(id: String) {
        todoAnimations.remove(id)?.cancel()
        todos.finishing.remove(id)
    }
    internal fun recordTodoChange(change: TodoChange, label: String, history: TodoHistorySnapshot?, celebrate: Boolean) {
        val id = change.before.id
        stopTodoAnimation(id)
        todos.openSwipeId = null
        val filter = when { change.before.archived -> TodoFilter.ARCHIVED; change.before.done -> TodoFilter.DONE; else -> TodoFilter.PENDING }
        todos.undo += TodoUndoEntry(change, if (!change.before.done && change.after?.done == true) "已完成" else label, filter, history)
        if (!celebrate || change.before.done || change.after?.done != true || todos.reduceMotion || screen != Screen.TODOS || todos.filter != TodoFilter.PENDING) return
        val feedback = TodoFinish(++todoAnimationToken, SystemClock.elapsedRealtime())
        todos.finishing[id] = feedback
        todoAnimations[id] = viewModelScope.launch {
            try {
                delay(TodoUiLimits.FINISH_HOLD_MS)
                if (todos.finishing[id] !== feedback) return@launch
                feedback.phase = TodoFinishPhase.EXIT
                delay(TodoUiLimits.FINISH_COLLAPSE_MS)
                if (todos.finishing[id] === feedback) todos.finishing.remove(id)
            } finally {
                if (todoAnimations[id] === currentCoroutineContext()[Job]) todoAnimations.remove(id)
            }
        }
    }
    internal fun clearTodoUi() {
        todoAnimations.values.toList().forEach { it.cancel() }
        todoAnimations.clear()
        todos.clear()
        TodoDrafts.clear()
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(error.message ?: "操作未完成，请重试。") }
            finally { busy = false }
        }
    }
    /** 不占用忙碌状态的后台整理，例如清理放弃草稿留下的附件文件。 */
    fun background(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message(error.message ?: "整理附件文件未完成。") }
        }
    }
}

@Composable
fun FocusApp(repository: FocusRepository, ui: NativeUiModel = viewModel()) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val state by repository.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val navScope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val usageMonitor = remember { UsageMonitor(appContext) }
    var refresh by remember { mutableIntStateOf(0) }
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var usageReport by remember { mutableStateOf<UsageReport?>(null) }
    var usageError by remember { mutableStateOf<String?>(null) }
    var usageLoading by remember { mutableStateOf(false) }
    var profileUsage by remember { mutableStateOf<Long?>(null) }
    val notificationAllowed = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val usageAllowed = remember(refresh) { usageMonitor.hasPermission() }
    val exactAllowed = remember(refresh) { TimerAlarmScheduler.canScheduleExactAlarms(context) }
    val bossStatus by BossAutomation.status.collectAsStateWithLifecycle()
    val bossServiceEnabled = remember(refresh, bossStatus.connected) { BossAutomation.enabled(context) }
    val bossVersion = remember(refresh) { BossUiMap.installedVersion(context) }
    val jobEntry = AutoApplyRules.entry(bossStatus.task?.live == true, bossStatus.task?.resume == true, bossStatus.paused != null, bossStatus.stopReason,
        AutoApplyRules.contactedOn(state.autoApply.records, today, ZoneId.systemDefault()), AutoApplyRules.resumesSentOn(state.autoApply.records, today, ZoneId.systemDefault()),
        AutoApplyRules.awaitingResume(state.autoApply.records).size, state.autoApply.settings.dailyLimit)
    val currentProject = state.projects.find { it.id == ui.projectId } ?: state.projects.find { it.id == state.timer?.projectId }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        refresh++
        if (!allowed) ui.message("未授权通知，计时仍可使用；结束和超时提醒可能不可见。")
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !ui.notificationRequested) {
            ui.notificationRequested = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    fun sendTimer(action: String) {
        if (action in setOf(TimerServiceCommands.START, TimerServiceCommands.RESUME, TimerServiceCommands.SHORT_BREAK, TimerServiceCommands.LONG_BREAK) && Build.VERSION.SDK_INT >= 33 && !notificationAllowed && !ui.notificationRequested) {
            ui.notificationRequested = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        TimerServiceCommands.send(context, action, currentProject?.id)
    }
    fun openProject(project: Project) {
        val active = state.timer
        if (active != null && (active.projectId != project.id || active.phase != TimerPhase.FOCUS)) ui.open("switch-project", project.id)
        else { ui.projectId = project.id; ui.screen = Screen.TIMER }
    }
    fun openProjectEditor(project: Project?) {
        ui.pendingProject = null
        ui.open("project", project?.id.orEmpty())
    }
    fun startBreak(longBreak: Boolean) {
        if (state.timer != null) ui.open(if (longBreak) "break-long" else "break-short")
        else sendTimer(if (longBreak) TimerServiceCommands.LONG_BREAK else TimerServiceCommands.SHORT_BREAK)
    }

    fun closeTodoDialog() { if (ui.dialog.startsWith("todo")) ui.close() }
    fun editTodo(todo: Todo?) { ui.todos.openSwipeId = null; ui.open("todo", todo?.id.orEmpty()) }
    fun changeTodo(label: String, celebrate: Boolean = false, after: (TodoChange) -> Unit = {}, action: suspend () -> TodoChange?) {
        val history = ui.todos.snapshot()
        ui.perform {
            val change = action()
            if (change != null) {
                ui.recordTodoChange(change, label, history, celebrate)
                after(change)
            }
        }
    }
    fun deleteTodo(todo: Todo) { changeTodo("已删除", after = { closeTodoDialog() }) { repository.deleteTodo(todo.id) } }
    fun restoreTodo(todo: Todo) {
        changeTodo("已找回", after = { change ->
            closeTodoDialog()
            val restored = requireNotNull(change.after)
            if (restored.done) ui.todos.revealCompleted(restored, repository.state.value.todos) else ui.todos.setFilter(TodoFilter.PENDING)
        }) { repository.archiveTodo(todo.id, false) }
    }
    fun undoTodo() {
        val entry = ui.todos.undo.lastOrNull() ?: return
        ui.perform {
            val restored = repository.undoTodo(entry.change)
            if (ui.todos.undo.lastOrNull() == entry) ui.todos.undo.removeAt(ui.todos.undo.lastIndex)
            if (!restored) ui.message("这件事之后已有修改，为避免覆盖，未撤销该操作。")
            else {
                ui.stopTodoAnimation(entry.change.before.id)
                ui.todos.setFilter(entry.filter)
                if (entry.filter == TodoFilter.DONE) {
                    if (entry.history != null) ui.todos.restore(entry.history)
                    else ui.todos.revealCompleted(entry.change.before, repository.state.value.todos)
                }
            }
        }
    }
    fun dismissTodoUndo() {
        ui.todos.undo.clear()
        TodoDrafts.removeMissing(state.todos.map { it.id }.toSet())
    }

    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = ui.pendingExport
        ui.pendingExport = null
        if (uri != null && payload != null) ui.perform { DocumentFiles.write(appContext, uri, payload); ui.message("文件已保存。请妥善保管未加密的个人数据。") }
        else if (uri != null) ui.message("导出内容已失效，请重新导出。")
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val payload = ui.pendingExport
        ui.pendingExport = null
        if (uri != null && payload != null) ui.perform { DocumentFiles.write(appContext, uri, payload); ui.message("CSV 已保存，可使用 Excel 打开。") }
        else if (uri != null) ui.message("导出内容已失效，请重新导出。")
    }
    val diagnosticLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) ui.perform { DocumentFiles.write(appContext, uri, BossAutomation.diagnosticExport()); ui.message("诊断文件已保存；其中只有控件结构，不含职位与聊天正文。") }
    }
    fun startBoss(task: BossTask) {
        if (task != BossTask.DIAGNOSE && !state.autoApply.settings.riskAccepted) { ui.open("auto-risk"); return }
        if (Build.VERSION.SDK_INT >= 33 && !notificationAllowed && !ui.notificationRequested) {
            ui.notificationRequested = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        BossAutomation.start(context, task)?.let(ui::message)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ui.perform {
            ui.pendingBackup = DocumentFiles.readBackup(appContext, uri)
            ui.open("restore-confirm")
        }
    }
    fun exportStatistics(csv: Boolean) {
        ui.perform {
            val snapshot = repository.state.value
            val period = ui.period
            val anchor = ui.anchor
            val allPeriod = ui.statsAll
            val records = if (allPeriod) Statistics.allTimeSessions(snapshot.sessions) else Statistics.select(snapshot.sessions, period, anchor)
            require(records.isNotEmpty()) { "当前周期没有可导出的完成记录。" }
            val text = withContext(Dispatchers.Default) {
                if (csv) Statistics.csv(records, snapshot.progress)
                else ExportCodec.statisticsJson(records, snapshot.progress, snapshot.sessions, period, anchor, allPeriod)
            }
            ui.pendingExport = text
            ui.close()
            val name = if (allPeriod) "专注统计_全部_${LocalDate.now()}" else "专注统计_${anchor}_${period.name}"
            if (csv) csvLauncher.launch("$name.csv") else jsonLauncher.launch("$name.json")
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { refresh++; today = LocalDate.now() } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { ui.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { TimerServiceCommands.errors.collect { ui.message(it) } }
    LaunchedEffect(state.loading) {
        if (!state.loading && state.timer != null) TimerServiceCommands.send(context, TimerServiceCommands.RESTORE)
    }
    LaunchedEffect(state.settings.usageReminders, state.loading) {
        if (!state.loading) UsageReminderScheduler.schedule(appContext, state.settings.usageReminders)
    }
    LaunchedEffect(lifecycle, state.timer?.sessionId, state.timer?.status) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowElapsed = SystemClock.elapsedRealtime()
                today = LocalDate.now()
                val interval = if (state.timer?.status == TimerStatus.RUNNING) UiLimits.MILLIS_PER_SECOND else UiLimits.MILLIS_PER_SECOND * UiLimits.SECONDS_PER_MINUTE
                delay(interval)
            }
        }
    }
    val pendingProgress = state.sessions.filter { !it.progressPrompted && Statistics.latestForSession(state.progress, it.id) == null }.maxByOrNull { it.endedAt }
    // 正在写日记时不弹专注进度提示，等离开编辑页后再提示。
    val writingDiary = ui.screen == Screen.DIARY && ui.diary.view == DiaryView.EDITOR
    LaunchedEffect(pendingProgress?.id, ui.dialog, state.loading, ui.busy, writingDiary) {
        if (!state.loading && !ui.busy && !writingDiary && ui.dialog.isEmpty() && pendingProgress != null) ui.open("progress-auto", pendingProgress.id)
    }
    LaunchedEffect(ui.dialog, ui.dialogId) {
        if (ui.dialog in setOf("progress-auto", "progress-edit")) {
            try { repository.markProgressPrompted(ui.dialogId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ui.message(error.message ?: "进度提醒状态未保存") }
        }
    }
    LaunchedEffect(ui.screen, ui.usageWeek, refresh, usageAllowed, today) {
        if (ui.screen != Screen.USAGE) return@LaunchedEffect
        usageReport = null
        usageError = null
        usageLoading = false
        if (!usageAllowed) return@LaunchedEffect
        usageLoading = true
        try {
            usageReport = usageMonitor.queryOverview(today, ui.usageWeek, System.currentTimeMillis())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { usageError = error.message ?: "无法读取使用统计" }
        finally { usageLoading = false }
    }
    // 生活概览的「用屏 · 今日」只在打开我的页面且已授权时读取，失败就显示「—」，不拿示例顶替。
    LaunchedEffect(ui.screen, refresh, usageAllowed, today) {
        if (ui.screen != Screen.SETTINGS || !usageAllowed) { profileUsage = null; return@LaunchedEffect }
        profileUsage = try { usageMonitor.queryOverview(today, false, System.currentTimeMillis()).todayMillis }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { null }
    }
    BackHandler(enabled = ui.dialog.isEmpty() && ui.screen !in setOf(Screen.PROJECTS, Screen.TODOS, Screen.DIARY, Screen.USAGE, Screen.SETTINGS)) {
        ui.screen = if (ui.screen == Screen.JOB_ASSIST) Screen.SETTINGS else Screen.PROJECTS
    }

    FocusTheme {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            // 全屏编辑日记时隐藏底部导航；求职辅助运行或暂停时，在导航上方显示状态条。
            if (!writingDiary && ui.screen != Screen.JOB_ASSIST) Column {
                if (bossStatus.task?.live == true) JobRunBar(jobEntry, onOpen = { ui.screen = Screen.JOB_ASSIST }, onTogglePause = { BossAutomation.togglePause() })
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.drawBehind {
                        drawLine(Line, start = Offset(0f, 0f), end = Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                    }
                ) {
                    val selected = when (ui.screen) { Screen.TIMER, Screen.STATISTICS -> Screen.PROJECTS; else -> ui.screen }
                    listOf(Screen.PROJECTS, Screen.TODOS, Screen.DIARY, Screen.USAGE, Screen.SETTINGS).forEach { page ->
                        val label = when (page) { Screen.PROJECTS -> "专注"; Screen.TODOS -> "待办"; Screen.DIARY -> "日记"; Screen.USAGE -> "用时"; else -> "我的" }
                        val image = when (page) {
                            Screen.PROJECTS -> Icons.Outlined.Timer
                            Screen.TODOS -> Icons.Outlined.AssignmentTurnedIn
                            Screen.DIARY -> Icons.Outlined.Book
                            Screen.USAGE -> Icons.Outlined.BarChart
                            else -> Icons.Outlined.PersonOutline
                        }
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentDark, selectedTextColor = AccentDark,
                            indicatorColor = AccentSoft, unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                        NavigationBarItem(selected = selected == page, alwaysShowLabel = true, onClick = {
                            // 已在日记页时再点「日记」：二级页回首页，已在首页则滚回顶部；从别的页签切回仍回到离开时的页面。
                            if (page == Screen.DIARY && ui.screen == Screen.DIARY) {
                                if (ui.diary.reselect()) navScope.launch { ui.diary.homeList.animateScrollToItem(0) }
                            } else ui.screen = page
                        }, icon = { Icon(image, label, modifier = Modifier.size(20.dp)) },
                            label = { Text(label, fontSize = 12.sp) }, colors = colors,
                            modifier = Modifier.heightIn(min = 64.dp)
                        )
                    }
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else when (ui.screen) {
                    Screen.PROJECTS -> ProjectsScreen(state, today, ::openProject, { openProjectEditor(null) }, { ui.screen = Screen.STATISTICS }, ::openProjectEditor,
                        { ui.open("delete-project", it.id) }, { ui.open("progress-edit", it.id) }, { ui.open("history", it.id) }, {
                            ui.projectId = state.timer?.projectId?.takeIf { id -> state.projects.any { it.id == id } } ?: ui.projectId
                            ui.screen = Screen.TIMER
                        })
                    Screen.TIMER -> TimerScreen(currentProject, state.timer, nowElapsed, { ui.screen = Screen.PROJECTS }, { ui.screen = Screen.STATISTICS }, { currentProject?.let(::openProjectEditor) }, { mode ->
                        // 只有未开始计时才会显示模式切换，所以直接写回项目。
                        currentProject?.let { project -> ui.perform { repository.saveProject(project.copy(timerMode = mode)) } }
                    }, { currentProject?.let { ui.open("project-duration", it.id) } },
                        { if (currentProject != null) sendTimer(TimerServiceCommands.START) }, { sendTimer(TimerServiceCommands.PAUSE) }, { sendTimer(TimerServiceCommands.RESUME) }, { sendTimer(TimerServiceCommands.FINISH) }, { ui.open("finish-early") }, { ui.open("discard") }, { startBreak(false) }, { startBreak(true) })
                    Screen.TODOS -> TodosScreen(
                        state = state, ui = ui.todos, today = today, now = System.currentTimeMillis(), busy = ui.busy,
                        onCreate = { editTodo(null) }, onEdit = ::editTodo,
                        onDetails = { ui.todos.openSwipeId = null; ui.open("todo-detail", it.id) },
                        onComplete = { todo -> changeTodo("已完成", celebrate = true) { repository.completeTodo(todo.id) } },
                        onToggleStep = { todo, step -> changeTodo(if (step.done) "已退回一步" else "走过了一步", celebrate = true) { repository.toggleTodoStep(todo.id, step.id) } },
                        onRenew = { todo -> changeTodo("已续到今天") { repository.renewTodo(todo.id) } },
                        onArchive = { todo -> changeTodo("已放下") { repository.archiveTodo(todo.id, true) } },
                        onRestore = ::restoreTodo, onDelete = ::deleteTodo, onUndo = ::undoTodo, onDismissUndo = ::dismissTodoUndo,
                        onPickMonth = { ui.open("todo-month") }, onHelp = { ui.open("todo-help") }
                    )
                    Screen.DIARY -> DiaryScreen(state, ui.diary, today, ui.busy, DiaryActions(
                        save = { entry, existing, draftKey ->
                            ui.perform {
                                // 保存与移出草稿在同一事务中完成。
                                val obsolete = repository.saveDiary(entry, existing, draftKey)
                                repository.state.value.diaries.find { it.id == entry.id }?.let { ui.diary.showSaved(it, created = !existing) }
                                repository.deleteDiaryFiles(obsolete, ui.diary.editorFiles())
                                ui.message(if (existing) "已保存修改" else "已保存")
                            }
                        },
                        trash = { entry ->
                            ui.perform {
                                repository.trashDiary(entry.id)
                                ui.diary.openSwipeId = null
                                if (ui.diary.view == DiaryView.DETAIL) ui.diary.view = DiaryView.HOME
                                ui.message("已移入回收站，可在「更多 · 回收站」恢复")
                            }
                        },
                        restore = { entry ->
                            ui.perform { repository.restoreDiary(entry.id); ui.message("已恢复到 ${timestampText(entry.occurredAt).substringBefore(' ')}") }
                        },
                        purge = { entry -> ui.open("diary-purge", entry.id) },
                        storeDraft = { draft ->
                            ui.background { repository.deleteDiaryFiles(repository.saveDiaryDraft(draft), ui.diary.editorFiles()) }
                        },
                        dropDraft = { key, extra ->
                            ui.background { repository.deleteDiaryFiles(repository.removeDiaryDraft(key) + extra, ui.diary.editorFiles()) }
                        },
                        deleteDraft = { draft -> ui.open("diary-draft-delete", draft.key) },
                        pickMonth = { ui.open("diary-month") },
                        help = { ui.open("diary-help") },
                        message = ui::message
                    ))
                    Screen.STATISTICS -> StatisticsScreen(state, ui.period, ui.statsAll, ui.anchor, ui.recordLimit, today,
                        onPeriod = { ui.period = it; ui.statsAll = false; ui.recordLimit = UiLimits.RECORD_PAGE },
                        onAll = { ui.statsAll = true; ui.recordLimit = UiLimits.RECORD_PAGE },
                        onPrevious = { ui.anchor = Statistics.shift(ui.period, ui.anchor, -1); ui.recordLimit = UiLimits.RECORD_PAGE },
                        onNext = { ui.anchor = Statistics.shift(ui.period, ui.anchor, 1); ui.recordLimit = UiLimits.RECORD_PAGE },
                        onToday = { ui.anchor = LocalDate.now(); ui.recordLimit = UiLimits.RECORD_PAGE },
                        onAnchor = { ui.anchor = it; ui.recordLimit = UiLimits.RECORD_PAGE },
                        onPickDate = {
                            DatePickerDialog(context, { _, year, month, day -> ui.anchor = LocalDate.of(year, month + 1, day); ui.recordLimit = UiLimits.RECORD_PAGE }, ui.anchor.year, ui.anchor.monthValue - 1, ui.anchor.dayOfMonth).show()
                        },
                        onBack = { ui.screen = Screen.PROJECTS }, onHelp = { ui.open("statistics-help") }, onExport = { ui.open("export") },
                        onMoreRecords = { ui.recordLimit += UiLimits.RECORD_PAGE }, onProgress = { ui.open("progress-edit", it.id) },
                        onSelectDay = { ui.anchor = it; ui.period = Period.DAY; ui.statsAll = false; ui.recordLimit = UiLimits.RECORD_PAGE })
                    Screen.USAGE -> UsageScreen(usageReport, usageAllowed, usageLoading, usageError, ui.usageWeek, state.settings, state.sessions,
                        { context.startActivity(usageMonitor.permissionIntent()) }, { refresh++ }, { ui.usageWeek = it }, { ui.open("usage-goal") }, { ui.open("usage-zoom") }, { enabled ->
                            if (enabled && !usageAllowed) ui.message("请先授予使用情况访问权限。")
                            else ui.perform { repository.saveSettings(state.settings.copy(usageReminders = enabled)); if (enabled && !notificationAllowed) ui.message("请在我的页面授权通知，否则超时提醒不可见。") }
                        }, { ui.open("usage-help") }, today)
                    Screen.SETTINGS -> ProfileScreen(state, today, ui.hideAmounts, lifeOverview(state, today, profileUsage, usageAllowed),
                        jobEntry, notificationAllowed, exactAllowed,
                        onToggleHide = { ui.hideAmounts = !ui.hideAmounts }, onAssets = { ui.open("assets") },
                        onUpdate = { ui.open("asset-update") }, onAddAccount = { ui.open("asset-account") },
                        onWish = { id -> ui.open("wish", id) },
                        onLife = { page -> if (page == Screen.TODOS) ui.todos.setFilter(TodoFilter.DONE); ui.screen = page },
                        actions = ProfileActions(::requestNotifications, { context.startActivity(usageMonitor.permissionIntent()) },
                            { context.startActivity(TimerAlarmScheduler.permissionIntent(context)) }, {
                                ui.perform { ui.pendingExport = repository.exportBackup(); jsonLauncher.launch("专注助手备份_${LocalDate.now()}.json") }
                            }, {
                                if (state.timer != null) ui.message("请先结束或放弃当前计时，再恢复备份。")
                                else importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }, { ui.open("rest-settings") }, { ui.screen = Screen.JOB_ASSIST }))
                    Screen.JOB_ASSIST -> JobAssistScreen(state.jobOpportunities, ui.busy,
                        auto = AutoApplyPanel(state.autoApply.settings, AutoApplyRules.contactedOn(state.autoApply.records, today, ZoneId.systemDefault()),
                            bossStatus, bossServiceEnabled, bossVersion, AutoApplyRules.resumesSentOn(state.autoApply.records, today, ZoneId.systemDefault()),
                            AutoApplyRules.awaitingResume(state.autoApply.records).size, state.autoApply.records.sortedByDescending { it.at },
                            JobReview.summary(state.autoApply.records, state.jobOpportunities, today, 7, ZoneId.systemDefault()),
                            JobReview.summary(state.autoApply.records, state.jobOpportunities, today, 30, ZoneId.systemDefault())),
                        autoActions = AutoApplyActions(
                            openAccessibility = { context.startActivity(BossAutomation.settingsIntent()) },
                            acceptRisk = { ui.open("auto-risk") },
                            editRules = { ui.open("auto-rules") },
                            diagnose = { startBoss(BossTask.DIAGNOSE) },
                            exportDiagnostics = { diagnosticLauncher.launch("BOSS界面诊断_${LocalDate.now()}.json") },
                            dryRun = { startBoss(BossTask.DRY_RUN) },
                            start = { ui.open("auto-start") },
                            stop = { BossAutomation.stop() },
                            togglePause = { BossAutomation.togglePause() },
                            resumeDryRun = { startBoss(BossTask.RESUME_DRY_RUN) },
                            resumeStart = { ui.open("auto-resume-start") },
                            addResume = { ui.open("resume-edit") },
                            editResume = { ui.open("resume-edit", it) },
                            deleteResume = { ui.open("resume-delete", it) },
                            moveResumeUp = { id -> ui.perform { repository.moveResumeProfile(id, up = true) } },
                            clearRecords = { ui.open("auto-records-clear") },
                            applyPreset = { id -> ui.perform { repository.applyFilterPreset(id); ui.message("已切换到方案「${state.autoApply.settings.presets.find { it.id == id }?.name.orEmpty()}」") } },
                            managePresets = { ui.open("auto-presets") },
                            editStage = { ui.open("job-stage", it) }),
                        onBack = { ui.screen = Screen.SETTINGS },
                        onAdd = { ui.open("job-edit") },
                        onEdit = { ui.open("job-edit", it) },
                        onDelete = { ui.open("job-delete", it) })
                }
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }

        val dialogProject = ui.pendingProject ?: state.projects.find { it.id == ui.dialogId }
        val session = state.sessions.find { it.id == ui.dialogId }
        val dialogTodo = state.todos.find { it.id == ui.dialogId }
        val dialogJob = state.jobOpportunities.find { it.id == ui.dialogId }
        when (ui.dialog) {
            "project", "project-duration" -> ProjectEditorDialog(dialogProject, ui.busy, durationOnly = ui.dialog == "project-duration", onDismiss = { ui.close() }) { project ->
                if (state.timer?.projectId == project.id) { ui.pendingProject = project; ui.open("save-project-confirm", project.id) }
                else {
                    // 只调整时长时留在计时页，不要把用户弹回首页。
                    val stay = ui.dialog == "project-duration"
                    ui.perform {
                        repository.saveProject(project)
                        ProjectDrafts.remove(ui.dialogId.takeIf { it.isNotBlank() })
                        ui.close()
                        if (!stay) ui.screen = Screen.PROJECTS
                    }
                }
            }
            "todo" -> {
                if (dialogTodo == null && ui.dialogId.isNotEmpty()) InformationDialog("这件事已不在清单中", listOf("待办可能已在其他窗口删除，请返回清单查看。"), { ui.close() })
                else TodoEditorDialog(dialogTodo, ui.busy, { ui.close() }, onDelete = ::deleteTodo, onRestore = ::restoreTodo,
                    todos = state.todos) { todo ->
                    val draftId = ui.dialogId.takeIf { it.isNotEmpty() }
                    ui.perform {
                        repository.saveTodo(todo, existing = draftId != null)
                        TodoDrafts.remove(draftId)
                        if (draftId == null) TodoDrafts.lastCategory = todo.category
                        ui.todos.justSaved = TodoSaved(todo.id, SystemClock.elapsedRealtime())
                        val saved = repository.state.value.todos.find { it.id == todo.id }
                        when {
                            saved?.archived == true -> ui.todos.setFilter(TodoFilter.ARCHIVED)
                            saved?.done == true && dialogTodo?.done != true -> ui.todos.revealCompleted(saved, repository.state.value.todos)
                            saved?.done == true -> ui.todos.setFilter(TodoFilter.DONE)
                            else -> ui.todos.setFilter(TodoFilter.PENDING)
                        }
                        ui.close()
                    }
                }
            }
            "todo-detail" -> {
                if (dialogTodo == null) InformationDialog("这件事已不在清单中", listOf("可以使用底部撤销恢复刚删除的待办。"), { ui.close() })
                else TodoDetailsDialog(dialogTodo, ui.busy, onDismiss = { ui.close() }, onEdit = { editTodo(dialogTodo) },
                    onReopen = { ui.open("todo-reopen", dialogTodo.id) }, onDelete = { deleteTodo(dialogTodo) }, onRestore = { restoreTodo(dialogTodo) },
                    onImportant = { changeTodo("已更新重要标记", after = { closeTodoDialog() }) { repository.setTodoImportant(dialogTodo.id, !dialogTodo.important) } },
                    onArchive = { changeTodo("已放下", after = { closeTodoDialog() }) { repository.archiveTodo(dialogTodo.id, true) } })
            }
            "todo-reopen" -> {
                if (dialogTodo == null) InformationDialog("这件事已不在清单中", listOf("待办可能已被删除，请返回清单查看。"), { ui.close() })
                else TodoReopenDialog(dialogTodo, ui.busy, onDismiss = { ui.open("todo-detail", dialogTodo.id) }) { steps ->
                    changeTodo("已重新打开", after = {
                        ui.todos.setFilter(TodoFilter.PENDING)
                        ui.todos.expanded[dialogTodo.id] = true
                        ui.close()
                    }) { repository.reopenTodo(dialogTodo.id, steps) }
                }
            }
            "todo-month" -> TodoMonthDialog(ui.todos.month, TodoHistory.select(state.todos, now = System.currentTimeMillis()).months, today,
                onDismiss = { ui.close() }, onSelect = { month -> ui.todos.selectMonth(month); ui.close() })
            "todo-help" -> TodoGuideSheet(onDismiss = { ui.close() })
            "diary-month" -> TodoMonthDialog(ui.diary.month, DiaryRules.select(state.diaries, ui.diary.month, "", today).months, today,
                onDismiss = { ui.close() }, onSelect = { month -> ui.diary.selectMonth(month); ui.close() }, unit = "条", describe = { "$it 条日记" })
            "diary-help" -> InformationDialog("关于日记", listOf(
                "日记可以只写文字，也可以只放照片或只录一段原声；同一天可以记多条。",
                "照片会复制一份保存到本应用内，相册原图删除后日记里的照片仍在。录音只保存原声，暂不转文字。",
                "录音只在你点击时开始；离开日记、切到后台或锁屏会停止并保留已录内容，不在后台继续录音。",
                "没写完就返回或退到后台时，内容会自动存入「更多 · 草稿」，关掉应用也还在；保存为日记后移出草稿。",
                "日记与草稿保存在本机，暂不包含在「导出完整备份」中；恢复备份不会改动它们。卸载应用或清除数据会删除日记。",
                "首版不设单独的日记锁。"
            ), { ui.close() })
            "diary-draft-delete" -> {
                val draft = state.diaryDrafts.find { it.key == ui.dialogId }
                if (draft == null) InformationDialog("这份草稿已不在草稿箱", listOf("可能已保存为日记或已被删除。"), { ui.close() })
                else {
                    val attachments = diaryAttachments(draft.photos.size, draft.audios.size)
                    ConfirmDialog("删除这份草稿？", "草稿会被删除${if (attachments.isNotEmpty()) "，其中新添加的照片与录音也会一并删除" else ""}；已保存的日记不受影响。", ui.busy, { ui.close() }) {
                        ui.perform {
                            repository.deleteDiaryFiles(repository.removeDiaryDraft(draft.key), ui.diary.editorFiles())
                            ui.close()
                            ui.message("已删除草稿")
                        }
                    }
                }
            }
            "diary-purge" -> {
                val entry = state.diaries.find { it.id == ui.dialogId && it.deletedAt != null }
                if (entry == null) InformationDialog("这条日记已不在回收站", listOf("可能已被恢复或删除。"), { ui.close() })
                else {
                    val attachments = diaryAttachments(entry.photos.size, entry.audios.size)
                    ConfirmDialog("永久删除这条日记？", "「${diaryName(entry)}」${if (attachments.isNotEmpty()) "及其 $attachments" else ""}将被永久删除，无法恢复。", ui.busy, { ui.close() }) {
                        ui.perform {
                            val files = repository.purgeDiary(entry.id)
                            repository.deleteDiaryFiles(files, ui.diary.editorFiles())
                            ui.close()
                            ui.message("已永久删除")
                        }
                    }
                }
            }
            "progress-auto", "progress-edit" -> if (session != null) ProgressEditorDialog(context, session,
                Statistics.latestForSession(state.progress, session.id) ?: Statistics.latestProgress(state.progress, session.projectId), ui.busy, { ui.close() }) { note, percent ->
                    ui.perform { repository.saveProgress(session.id, note, percent); ProgressDrafts.remove(appContext, session.id); ui.close() }
                }
            "history" -> ProgressHistoryDialog(state.projects.find { it.id == ui.dialogId }, ui.dialogId, state, { ui.close() }, { ui.open("progress-edit", it.id) })
            "statistics-help" -> {
                val totals = Statistics.allTime(state.sessions)
                InformationDialog("累计统计说明", listOf(
                    "范围：${totals.firstDate ?: "暂无记录"} 至 ${LocalDate.now()}，共 ${totals.count} 次专注。「全部」汇总截至今天的全部记录，不受日、周、月、年的所选日期影响。",
                    "自然日均 = 总有效时长 ÷ ${totals.calendarDays} 个自然日。包含首次完成日至今天的空白日。",
                    "活跃日均 = 总有效时长 ÷ ${totals.activeDays} 个有记录日。无记录时均显示 0。",
                    "只统计已完成的倒计时、手动结束的正计时，不含暂停、休息或放弃的计时。小时分布拆分所选完成记录的实际活动片段。",
                    "数据来自本机真实记录，保存在设备内；卸载会清除本地数据，请定期导出备份。"
                ), { ui.close() })
            }
            "usage-help" -> InformationDialog("使用时长与提醒", listOf(
                "需要在系统设置中授予使用情况访问权限。本应用只读取前台使用事件，不读取其他应用内容。",
                usageReport?.warning ?: "Android 使用事件有保留期限和记录延迟，多窗口应用时长合计不等于严格的亮屏时间。",
                "每日超时提醒约每 15 分钟检查一次，可能受系统省电和后台限制延迟。每天最多提醒一次，需要通知权限。"
            ), { ui.close() })
            "assets" -> AssetListSheet(state.assets, today, ui.hideAmounts, ui.busy, { ui.close() }, { ui.open("asset-detail", it) },
                { ui.open("asset-account") }, { ui.open("asset-update") })
            "asset-account" -> {
                val account = state.assets.accounts.find { it.id == ui.dialogId }
                AccountEditorSheet(account, ui.busy, { if (account != null) ui.open("asset-detail", account.id) else ui.open("assets") }) { name, kind, opening ->
                    ui.perform {
                        val id = repository.saveAssetAccount(account?.copy(name = name, kind = kind) ?: AssetAccount("", name, kind), opening)
                        if (account != null) ui.open("asset-detail", id) else ui.open("assets")
                        ui.message(if (account != null) "账户已更新" else "账户已添加")
                    }
                }
            }
            "asset-detail" -> {
                val account = state.assets.accounts.find { it.id == ui.dialogId }
                if (account == null) InformationDialog("这个账户已不存在", listOf("可能已在其他窗口删除。"), { ui.open("assets") })
                else AccountDetailSheet(account, state.assets, today, ui.hideAmounts, ui.busy, { ui.close() }, { ui.open("assets") },
                    onRecord = { snapshot -> ui.perform { repository.addAssetSnapshots(listOf(snapshot)); ui.message("余额已记下") } },
                    onDeleteSnapshot = { snapshot -> ui.perform { repository.deleteAssetSnapshot(snapshot.id); ui.message("已删除这条记录") } },
                    onEdit = { ui.open("asset-account", account.id) }, onDelete = { ui.open("asset-delete", account.id) })
            }
            "asset-delete" -> {
                val account = state.assets.accounts.find { it.id == ui.dialogId }
                // 不在组合阶段直接改状态，交给副作用关闭。
                if (account == null) LaunchedEffect(Unit) { ui.close() }
                else {
                    val linked = state.assets.wishes.any { it.scope == WishScope.ACCOUNT && it.accountId == account.id }
                    val allWish = state.assets.wishes.any { it.scope == WishScope.ALL }
                    ConfirmDialog("删除账户", "删除「${account.name}」和它的全部余额记录？${if (linked) "关联该账户的单账户心愿也会一并移除。" else ""}${if (allWish) "按所有资产计算的心愿会保留，并按剩余账户计算。" else ""}此操作无法撤销。", ui.busy,
                        { ui.open("asset-detail", account.id) }) {
                        ui.perform { repository.deleteAssetAccount(account.id); ui.open("assets"); ui.message("账户已删除") }
                    }
                }
            }
            "asset-update" -> BalanceUpdateSheet(state.assets, today, ui.busy, { ui.close() }) { snapshots ->
                if (snapshots.isEmpty()) ui.close()
                else ui.perform { repository.addAssetSnapshots(snapshots); ui.close(); ui.message("已更新 ${snapshots.size} 个账户") }
            }
            "wish" -> {
                val wish = state.assets.wishes.find { it.id == ui.dialogId }
                val accounts = state.assets.accounts.filterNot { it.kind.liability }
                WishEditorSheet(wish, accounts, today, ui.busy, { ui.close() }, onSave = { value ->
                    ui.perform { repository.saveWish(value); ui.close(); ui.message(if (wish != null) "心愿已更新" else "心愿已添加") }
                }, onArchive = {
                    if (wish != null) ui.perform { repository.saveWish(wish.copy(archived = true)); ui.close(); ui.message("心愿已归档") }
                })
            }
            "job-edit" -> {
                if (ui.dialogId.isNotBlank() && dialogJob == null) InformationDialog("职位记录已不存在", listOf("可能已在其他窗口删除。"), { ui.close() })
                else JobOpportunityEditorDialog(dialogJob, ui.busy, { ui.close() }) { value ->
                    ui.perform { repository.saveJobOpportunity(value); ui.close(); ui.message("职位已保存") }
                }
            }
            "auto-risk" -> AutoApplyRiskDialog(ui.busy, { ui.close() }) {
                ui.perform { repository.saveAutoApplySettings(state.autoApply.settings.copy(riskAccepted = true)); ui.close(); ui.message("已确认风险。建议先做界面诊断和试运行。") }
            }
            "auto-rules" -> AutoApplyRulesDialog(state.autoApply.settings, ui.busy, { ui.close() }) { value ->
                // 有当前方案时同步更新该方案，避免切换回来时丢失刚改的规则。
                val active = AutoApplyRules.activePresetName(value)
                ui.perform {
                    repository.saveAutoApplySettings(if (active.isBlank()) value else AutoApplyRules.savePreset(value, active, value.activePresetId.orEmpty()))
                    ui.close(); ui.message(if (active.isBlank()) "沟通规则已保存" else "沟通规则已保存，并更新方案「$active」")
                }
            }
            "auto-presets" -> FilterPresetDialog(state.autoApply.settings, ui.busy, { ui.close() },
                onSave = { name -> ui.perform { repository.saveFilterPreset(name); ui.close(); ui.message("方案已保存") } },
                onDelete = { id -> ui.open("preset-delete", id) })
            "preset-delete" -> {
                val preset = state.autoApply.settings.presets.find { it.id == ui.dialogId }
                if (preset == null) InformationDialog("方案已不存在", listOf("可能已被删除。"), { ui.close() })
                else ConfirmDialog("删除方案？", "删除「${preset.name}」后，当前规则保持不变。", ui.busy, { ui.close() }) {
                    ui.perform { repository.deleteFilterPreset(preset.id); ui.close(); ui.message("方案已删除") }
                }
            }
            "job-stage" -> {
                val job = dialogJob
                if (job == null) InformationDialog("职位记录已不存在", listOf("可能已被删除。"), { ui.close() })
                else JobStageDialog(job, ui.busy, { ui.close() }) { outcome, note ->
                    ui.perform { repository.setJobOutcome(job.id, outcome, note); ui.close(); ui.message("阶段已更新") }
                }
            }
            "resume-edit" -> {
                val resumes = state.autoApply.settings.resumes
                val existing = resumes.find { it.id == ui.dialogId }
                if (ui.dialogId.isNotBlank() && existing == null) InformationDialog("简历已不存在", listOf("可能已被删除。"), { ui.close() })
                else ResumeEditDialog(existing, ui.busy, { ui.close() },
                    onSave = { value -> ui.perform { repository.saveResumeProfile(value); ui.close(); ui.message(if (existing == null) "简历已添加" else "简历已更新") } },
                    validate = { value -> AutoApplyRules.validateSettings(state.autoApply.settings.copy(resumes = AutoApplyRules.upsertResume(resumes, value))) })
            }
            "resume-delete" -> {
                val resume = state.autoApply.settings.resumes.find { it.id == ui.dialogId }
                if (resume == null) InformationDialog("简历已不存在", listOf("可能已被删除。"), { ui.close() })
                else ConfirmDialog("删除简历？", "“${resume.name}”将从「我的简历」移除，只删除本应用里的名称和关键词，不影响 BOSS 里的附件。", ui.busy, { ui.close() }) {
                    ui.perform { repository.deleteResumeProfile(resume.id); ui.close(); ui.message("简历已删除") }
                }
            }
            "auto-records-clear" -> ConfirmDialog("清空投递记录？", "将删除全部 ${state.autoApply.records.size} 条记录。清空后去重和今日计数一并重置：之前沟通过的职位可能再次沟通，" +
                "等待回复的会话也不会再自动发简历。此操作无法撤销。", ui.busy, { ui.close() }) {
                ui.perform { repository.clearAutoApplyRecords(); ui.close(); ui.message("投递记录已清空") }
            }
            "auto-start" -> {
                val settings = state.autoApply.settings
                val left = settings.dailyLimit - AutoApplyRules.contactedOn(state.autoApply.records, today, ZoneId.systemDefault())
                ConfirmDialog("开始自动沟通？", "将切换到 BOSS 直聘，界面变化后执行下一步（两步至少间隔 ${settings.stepDelayMs / 1000} 秒），今天最多再发起 ${maxOf(left, 0)} 条沟通。" +
                    "请先打开职位推荐列表。运行中保持亮屏；触碰 BOSS、离开 BOSS 或熄屏会暂停，可从悬浮按钮继续或停止。", ui.busy, { ui.close() }) {
                    ui.close()
                    startBoss(BossTask.LIVE)
                }
            }
            "auto-resume-start" -> {
                val settings = state.autoApply.settings
                val left = settings.dailyLimit - AutoApplyRules.resumesSentOn(state.autoApply.records, today, ZoneId.systemDefault())
                ConfirmDialog("开始自动发简历？", "将切换到 BOSS 直聘消息页，只处理本应用自动沟通过、且对方有新消息的会话，按岗位选择简历名称后发送；" +
                    "今天最多再发 ${maxOf(left, 0)} 份。不读取聊天内容；找不到同名附件简历会停止。运行中保持亮屏；触碰 BOSS、离开 BOSS 或熄屏会暂停，可从悬浮按钮继续或停止。", ui.busy, { ui.close() }) {
                    ui.close()
                    startBoss(BossTask.RESUME_LIVE)
                }
            }
            "job-delete" -> {
                val job = dialogJob
                if (job == null) InformationDialog("职位记录已不存在", listOf("可能已在其他窗口删除。"), { ui.close() })
                else ConfirmDialog("删除职位记录？", "“${job.company} · ${job.position}”及其文案将从本机删除。此操作无法撤销。", ui.busy, { ui.close() }) {
                    ui.perform { repository.deleteJobOpportunity(job.id); ui.close(); ui.message("职位记录已删除") }
                }
            }
            "export" -> AlertDialog(onDismissRequest = { if (!ui.busy) ui.close() }, containerColor = Color.White,
                title = { Text(if (ui.statsAll) "导出全部记录" else "导出当前周期", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }, text = { Text("CSV 适合 Excel，JSON 包含活动片段、进度历史及累计总览。仅导出当前周期的完整记录，不受分页限制。") },
                confirmButton = { TextButton(enabled = !ui.busy, onClick = { exportStatistics(false) }) { Text("JSON") } }, dismissButton = { TextButton(enabled = !ui.busy, onClick = { exportStatistics(true) }) { Text("CSV") } })
            "usage-goal" -> GoalWheelSheet(state.settings.dailyGoalMinutes, ui.busy, { ui.close() }) { minutes ->
                ui.perform { repository.saveSettings(state.settings.copy(dailyGoalMinutes = minutes)); ui.close() }
            }
            "usage-zoom" -> UsageZoomSheet(usageReport.takeIf { usageAllowed && usageError == null }, state.sessions, today) { ui.close() }
            "rest-settings" -> NumberSettingsDialog("休息偏好", listOf("短休息（分钟）" to state.settings.shortBreakMinutes, "长休息（分钟）" to state.settings.longBreakMinutes), ui.busy, { ui.close() }) { values ->
                if (values.any { it !in 1..120 }) ui.message("休息时长需为 1–120 分钟。") else ui.perform { repository.saveSettings(state.settings.copy(shortBreakMinutes = values[0], longBreakMinutes = values[1])); ui.close() }
            }
            "restore-confirm" -> {
                val backup = ui.pendingBackup
                if (backup == null) InformationDialog("重新选择备份", listOf("备份预览已失效，请重新选择文件。未修改任何本地数据。"), { ui.close() })
                else ConfirmDialog("确认覆盖本机数据？", "备份包含 ${backup.projects.size} 个项目、${backup.todos.size} 个待办、${backup.sessions.size} 次专注和 ${backup.progress.size} 条进度。恢复会替换当前数据，不合并。建议先导出当前备份。日记与资产不在备份范围内，恢复不会改动它们。", ui.busy, { ui.pendingBackup = null; ui.close() }) {
                    ui.perform { repository.restoreBackup(backup); ProgressDrafts.clear(appContext); ProjectDrafts.clear(); ui.clearTodoUi(); ui.pendingBackup = null; ui.projectId = null; ui.close(); ui.message("备份已恢复。"); refresh++ }
                }
            }
            "delete-project", "discard", "finish-early", "switch-project", "save-project-confirm", "break-short", "break-long" -> {
                val kind = ui.dialog
                val targetId = ui.dialogId
                val message = when (kind) {
                    "delete-project" -> "删除项目会保留专注记录和进度历史。如果正在为此项目计时，当前未保存的计时会被放弃。"
                    "finish-early" -> "倒计时还没到零。提前结束会按已专注的实际时长保存为记录，暂停时间不计入。"
                    else -> "当前未完成的计时会被放弃，不计入统计。如需保留正计时，请先取消，再结束并记录。"
                }
                val heading = when {
                    kind.startsWith("delete") -> "确认删除？"
                    kind == "finish-early" -> "提前结束并保存？"
                    else -> "放弃当前计时？"
                }
                ConfirmDialog(heading, message, ui.busy, {
                    if (kind == "save-project-confirm") ui.open("project", targetId) else ui.close()
                }) {
                    ui.perform {
                        when (kind) {
                            "delete-project" -> { if (repository.state.value.timer?.projectId == targetId) repository.discardTimer(); repository.deleteProject(targetId); if (ui.projectId == targetId) ui.projectId = null; ui.screen = Screen.PROJECTS }
                            "discard" -> repository.discardTimer()
                            "finish-early" -> sendTimer(TimerServiceCommands.FINISH_EARLY)
                            "switch-project" -> { repository.discardTimer(); ui.projectId = targetId; ui.screen = Screen.TIMER }
                            "save-project-confirm" -> { val project = requireNotNull(ui.pendingProject); repository.discardTimer(); repository.saveProject(project); ProjectDrafts.remove(project.id); ui.pendingProject = null }
                            "break-short", "break-long" -> { repository.discardTimer(); sendTimer(if (kind == "break-long") TimerServiceCommands.LONG_BREAK else TimerServiceCommands.SHORT_BREAK) }
                        }
                        ui.close()
                    }
                }
            }
        }
    }
}


@Composable
private fun NumberSettingsDialog(title: String, fields: List<Pair<String, Int>>, busy: Boolean, onDismiss: () -> Unit, onSave: (List<Int>) -> Unit) {
    var values by remember(title) { mutableStateOf(fields.map { it.second.toString() }) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEachIndexed { index, field -> OutlinedTextField(value = values[index], onValueChange = { next -> values = values.mapIndexed { position, old -> if (position == index) next else old }; invalid = false }, label = { Text(field.first) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth()) }
            if (invalid) Text("请输入有效的整数。", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val parsed = values.map { it.toIntOrNull() }
        if (parsed.any { it == null }) invalid = true else onSave(parsed.filterNotNull())
    }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
