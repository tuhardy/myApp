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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

internal enum class Screen { PROJECTS, TIMER, TODOS, STATISTICS, USAGE, SETTINGS }

class NativeUiModel : ViewModel() {
    internal var screen by mutableStateOf(Screen.PROJECTS)
    var projectId by mutableStateOf<String?>(null)
    var taskId by mutableStateOf<String?>(null)
    /** 从哪一步开始专注。只影响记录里的标题快照，不产生独立待办。 */
    var taskStepId by mutableStateOf<String?>(null)
    var dialog by mutableStateOf("")
    var dialogId by mutableStateOf("")
    var busy by mutableStateOf(false)
    internal var todoFilter by mutableStateOf(TodoFilter.ALL)
    var period by mutableStateOf(Period.DAY)
    var anchor by mutableStateOf(LocalDate.now())
    var recordLimit by mutableIntStateOf(UiLimits.RECORD_PAGE)
    var usageWeek by mutableStateOf(false)
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
}

@Composable
fun FocusApp(repository: FocusRepository, ui: NativeUiModel = viewModel()) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val state by repository.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current
    val usageMonitor = remember { UsageMonitor(appContext) }
    var refresh by remember { mutableIntStateOf(0) }
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var usageReport by remember { mutableStateOf<UsageReport?>(null) }
    var usageError by remember { mutableStateOf<String?>(null) }
    var usageLoading by remember { mutableStateOf(false) }
    val notificationAllowed = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val usageAllowed = remember(refresh) { usageMonitor.hasPermission() }
    val exactAllowed = remember(refresh) { TimerAlarmScheduler.canScheduleExactAlarms(context) }
    val currentProject = state.projects.find { it.id == ui.projectId } ?: state.projects.find { it.id == state.timer?.projectId }
    val selectedTask = state.todos.find { it.id == ui.taskId }

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
        TimerServiceCommands.send(context, action, currentProject?.id, ui.taskId, ui.taskStepId)
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
            val records = Statistics.select(snapshot.sessions, period, anchor)
            require(records.isNotEmpty()) { "当前周期没有可导出的完成记录。" }
            val text = withContext(Dispatchers.Default) {
                if (csv) Statistics.csv(records, snapshot.progress)
                else ExportCodec.statisticsJson(records, snapshot.progress, snapshot.sessions, period, anchor)
            }
            ui.pendingExport = text
            ui.close()
            if (csv) csvLauncher.launch("专注统计_${anchor}_${period.name}.csv") else jsonLauncher.launch("专注统计_${anchor}_${period.name}.json")
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
    LaunchedEffect(pendingProgress?.id, ui.dialog, state.loading, ui.busy) {
        if (!state.loading && !ui.busy && ui.dialog.isEmpty() && pendingProgress != null) ui.open("progress-auto", pendingProgress.id)
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
    BackHandler(enabled = ui.dialog.isEmpty() && ui.screen !in setOf(Screen.PROJECTS, Screen.TODOS, Screen.USAGE, Screen.SETTINGS)) { ui.screen = Screen.PROJECTS }

    FocusTheme {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val selected = when (ui.screen) { Screen.TIMER, Screen.STATISTICS -> Screen.PROJECTS; else -> ui.screen }
                listOf(Screen.PROJECTS, Screen.TODOS, Screen.USAGE, Screen.SETTINGS).forEach { page ->
                    val label = when (page) { Screen.PROJECTS -> "专注"; Screen.TODOS -> "待办"; Screen.USAGE -> "用时"; else -> "我的" }
                    val image = when (page) { Screen.PROJECTS -> Icons.Outlined.Timer; Screen.TODOS -> Icons.Outlined.CheckCircle; Screen.USAGE -> Icons.Outlined.BarChart; else -> Icons.Outlined.PersonOutline }
                    NavigationBarItem(selected = selected == page, onClick = { ui.screen = page }, icon = { Icon(image, label) }, label = { Text(label) })
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
                    Screen.TIMER -> TimerScreen(currentProject, state.timer, selectedTask, nowElapsed, { ui.screen = Screen.PROJECTS }, { ui.screen = Screen.STATISTICS }, { currentProject?.let(::openProjectEditor) }, { ui.open("link-task") }, { mode ->
                        // 只有未开始计时才会显示模式切换，所以直接写回项目。
                        currentProject?.let { project -> ui.perform { repository.saveProject(project.copy(timerMode = mode)) } }
                    }, { currentProject?.let { ui.open("project-duration", it.id) } },
                        { if (currentProject != null) sendTimer(TimerServiceCommands.START) }, { sendTimer(TimerServiceCommands.PAUSE) }, { sendTimer(TimerServiceCommands.RESUME) }, { sendTimer(TimerServiceCommands.FINISH) }, { ui.open("finish-early") }, { ui.open("discard") }, { startBreak(false) }, { startBreak(true) })
                    Screen.TODOS -> TodosScreen(state, ui.todoFilter, today, { ui.todoFilter = it }, { ui.open("todo") }, { ui.open("todo", it.id) },
                        { todo -> ui.perform { repository.toggleTodoDone(todo.id) } },
                        { todo, step -> ui.perform { repository.toggleTodoStep(todo.id, step.id) } },
                        { todo -> ui.perform { repository.renewTodo(todo.id); ui.message("「${todo.title}」回到今天。") } },
                        { todo -> ui.perform { repository.archiveTodo(todo.id, true); ui.message("「${todo.title}」已放下，可在「放下的」里找回。") } },
                        { todo -> ui.perform { repository.archiveTodo(todo.id, false); ui.todoFilter = TodoFilter.ALL; ui.message("「${todo.title}」回到清单。") } },
                        { ui.open("delete-todo", it.id) }, { todo, step ->
                        val project = currentProject ?: state.projects.firstOrNull()
                        if (project == null) { ui.message("请先创建一个学习项目，再关联待办开始专注。"); ui.screen = Screen.PROJECTS }
                        else if (state.timer != null) ui.open("link-task-confirm", todo.id)
                        // 子步骤不单独入账：记录仍挂在父待办上，只有标题快照记成「父任务 · 这一步」。
                        else { ui.taskId = todo.id; ui.taskStepId = step?.id; ui.projectId = project.id; ui.screen = Screen.TIMER }
                    })
                    Screen.STATISTICS -> StatisticsScreen(state, ui.period, ui.anchor, ui.recordLimit, { ui.period = it; ui.recordLimit = UiLimits.RECORD_PAGE }, {
                        ui.anchor = shiftPeriod(ui.period, ui.anchor, -1); ui.recordLimit = UiLimits.RECORD_PAGE
                    }, { ui.anchor = shiftPeriod(ui.period, ui.anchor, 1); ui.recordLimit = UiLimits.RECORD_PAGE }, { ui.anchor = LocalDate.now(); ui.period = Period.DAY }, {
                        DatePickerDialog(context, { _, year, month, day -> ui.anchor = LocalDate.of(year, month + 1, day); ui.recordLimit = UiLimits.RECORD_PAGE }, ui.anchor.year, ui.anchor.monthValue - 1, ui.anchor.dayOfMonth).show()
                    }, { ui.screen = Screen.PROJECTS }, { ui.open("statistics-help") }, { ui.open("export") }, { ui.recordLimit += UiLimits.RECORD_PAGE }, { ui.open("progress-edit", it.id) }, { ui.anchor = it; ui.period = Period.DAY; ui.recordLimit = UiLimits.RECORD_PAGE }, today)
                    Screen.USAGE -> UsageScreen(usageReport, usageAllowed, usageLoading, usageError, ui.usageWeek, state.settings,
                        { context.startActivity(usageMonitor.permissionIntent()) }, { refresh++ }, { ui.usageWeek = it }, { ui.open("usage-goal") }, { enabled ->
                            if (enabled && !usageAllowed) ui.message("请先授予使用情况访问权限。")
                            else ui.perform { repository.saveSettings(state.settings.copy(usageReminders = enabled)); if (enabled && !notificationAllowed) ui.message("请在我的页面授权通知，否则超时提醒不可见。") }
                        }, { ui.open("usage-help") }, today)
                    Screen.SETTINGS -> SettingsScreen(state, notificationAllowed, exactAllowed, usageAllowed, ::requestNotifications,
                        { context.startActivity(usageMonitor.permissionIntent()) }, { context.startActivity(TimerAlarmScheduler.permissionIntent(context)) }, {
                            ui.perform { ui.pendingExport = repository.exportBackup(); jsonLauncher.launch("专注助手备份_${LocalDate.now()}.json") }
                        }, {
                            if (state.timer != null) ui.message("请先结束或放弃当前计时，再恢复备份。")
                            else importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        }, { ui.open("rest-settings") }, { ui.open("automation") })
                }
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }

        val dialogProject = ui.pendingProject ?: state.projects.find { it.id == ui.dialogId }
        val session = state.sessions.find { it.id == ui.dialogId }
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
            "todo" -> TodoEditorDialog(state.todos.find { it.id == ui.dialogId }, ui.busy, { ui.close() },
                onDelete = { todo -> ui.open("delete-todo", todo.id) },
                onRestore = { todo -> ui.perform { repository.archiveTodo(todo.id, false); ui.todoFilter = TodoFilter.ALL; ui.close(); ui.message("「${todo.title}」回到清单。") } }
            ) { todo -> ui.perform { repository.saveTodo(todo); ui.close() } }
            "progress-auto", "progress-edit" -> if (session != null) ProgressEditorDialog(context, session,
                Statistics.latestForSession(state.progress, session.id) ?: Statistics.latestProgress(state.progress, session.projectId), ui.busy, { ui.close() }) { note, percent ->
                    ui.perform { repository.saveProgress(session.id, note, percent); ProgressDrafts.remove(appContext, session.id); ui.close() }
                }
            "history" -> ProgressHistoryDialog(state.projects.find { it.id == ui.dialogId }, ui.dialogId, state, { ui.close() }, { ui.open("progress-edit", it.id) })
            "statistics-help" -> {
                val totals = Statistics.allTime(state.sessions)
                InformationDialog("累计统计说明", listOf(
                    "范围：${totals.firstDate ?: "暂无记录"} 至 ${LocalDate.now()}，共 ${totals.count} 次专注。总览不受下方日期和周期筛选影响。",
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
            "automation" -> InformationDialog("自动化扩展", listOf("此版本仅预留自动化入口，尚未接入任何脚本引擎。", "后续根据你的具体手机脚本和工具确定接入方式，不默认申请 root、无障碍或其他应用控制权限。"), { ui.close() })
            "export" -> AlertDialog(onDismissRequest = { if (!ui.busy) ui.close() }, title = { Text("导出当前周期") }, text = { Text("CSV 适合 Excel，JSON 包含活动片段、进度历史及累计总览。仅导出当前周期的完整记录，不受分页限制。") },
                confirmButton = { TextButton(enabled = !ui.busy, onClick = { exportStatistics(false) }) { Text("JSON") } }, dismissButton = { TextButton(enabled = !ui.busy, onClick = { exportStatistics(true) }) { Text("CSV") } })
            "link-task" -> AlertDialog(onDismissRequest = { ui.close() }, title = { Text("关联待办") }, text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { if (state.timer != null) ui.open("link-task-confirm") else { ui.taskId = null; ui.taskStepId = null; ui.close() } }) { Text("不关联待办") }
                    state.todos.filterNot { it.done }.forEach { todo -> TextButton(onClick = { if (state.timer != null) ui.open("link-task-confirm", todo.id) else { ui.taskId = todo.id; ui.taskStepId = null; ui.close() } }) { Text(todo.title) } }
                }
            }, confirmButton = { TextButton(onClick = { ui.close() }) { Text("取消") } })
            "usage-goal" -> NumberSettingsDialog("每日使用目标", listOf("目标分钟数" to state.settings.dailyGoalMinutes), ui.busy, { ui.close() }) { values ->
                if (values.single() !in 1..1440) ui.message("请输入 1–1440 分钟。") else ui.perform { repository.saveSettings(state.settings.copy(dailyGoalMinutes = values.single())); ui.close() }
            }
            "rest-settings" -> NumberSettingsDialog("休息偏好", listOf("短休息（分钟）" to state.settings.shortBreakMinutes, "长休息（分钟）" to state.settings.longBreakMinutes), ui.busy, { ui.close() }) { values ->
                if (values.any { it !in 1..120 }) ui.message("休息时长需为 1–120 分钟。") else ui.perform { repository.saveSettings(state.settings.copy(shortBreakMinutes = values[0], longBreakMinutes = values[1])); ui.close() }
            }
            "restore-confirm" -> {
                val backup = ui.pendingBackup
                if (backup == null) InformationDialog("重新选择备份", listOf("备份预览已失效，请重新选择文件。未修改任何本地数据。"), { ui.close() })
                else ConfirmDialog("确认覆盖本机数据？", "备份包含 ${backup.projects.size} 个项目、${backup.todos.size} 个待办、${backup.sessions.size} 次专注和 ${backup.progress.size} 条进度。恢复会替换当前数据，不合并。建议先导出当前备份。", ui.busy, { ui.pendingBackup = null; ui.close() }) {
                    ui.perform { repository.restoreBackup(backup); ProgressDrafts.clear(appContext); ProjectDrafts.clear(); ui.pendingBackup = null; ui.projectId = null; ui.taskId = null; ui.taskStepId = null; ui.close(); ui.message("备份已恢复。"); refresh++ }
                }
            }
            "delete-project", "delete-todo", "discard", "finish-early", "switch-project", "save-project-confirm", "link-task-confirm", "break-short", "break-long" -> {
                val kind = ui.dialog
                val targetId = ui.dialogId
                val message = when (kind) {
                    "delete-project" -> "删除项目会保留专注记录和进度历史。如果正在为此项目计时，当前未保存的计时会被放弃。"
                    "delete-todo" -> "删除待办不会修改已完成的专注记录。"
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
                            "delete-todo" -> { repository.deleteTodo(targetId); if (ui.taskId == targetId) { ui.taskId = null; ui.taskStepId = null } }
                            "discard" -> repository.discardTimer()
                            "finish-early" -> sendTimer(TimerServiceCommands.FINISH_EARLY)
                            "switch-project" -> { repository.discardTimer(); ui.projectId = targetId; ui.taskId = null; ui.taskStepId = null; ui.screen = Screen.TIMER }
                            "save-project-confirm" -> { val project = requireNotNull(ui.pendingProject); repository.discardTimer(); repository.saveProject(project); ProjectDrafts.remove(project.id); ui.pendingProject = null }
                            "link-task-confirm" -> { repository.discardTimer(); ui.taskId = targetId.takeIf { it.isNotBlank() }; ui.taskStepId = null; ui.projectId = currentProject?.id ?: state.projects.firstOrNull()?.id; ui.screen = Screen.TIMER }
                            "break-short", "break-long" -> { repository.discardTimer(); sendTimer(if (kind == "break-long") TimerServiceCommands.LONG_BREAK else TimerServiceCommands.SHORT_BREAK) }
                        }
                        ui.close()
                    }
                }
            }
        }
    }
}

private fun shiftPeriod(period: Period, anchor: LocalDate, direction: Long): LocalDate = when (period) {
    Period.DAY -> anchor.plusDays(direction)
    Period.WEEK -> anchor.minusDays((anchor.dayOfWeek.value - 1).toLong()).plusWeeks(direction)
    Period.MONTH -> anchor.withDayOfMonth(1).plusMonths(direction)
    Period.YEAR -> anchor.withDayOfYear(1).plusYears(direction)
}

@Composable
private fun NumberSettingsDialog(title: String, fields: List<Pair<String, Int>>, busy: Boolean, onDismiss: () -> Unit, onSave: (List<Int>) -> Unit) {
    var values by remember(title) { mutableStateOf(fields.map { it.second.toString() }) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEachIndexed { index, field -> OutlinedTextField(value = values[index], onValueChange = { next -> values = values.mapIndexed { position, old -> if (position == index) next else old }; invalid = false }, label = { Text(field.first) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth()) }
            if (invalid) Text("请输入有效的整数。", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val parsed = values.map { it.toIntOrNull() }
        if (parsed.any { it == null }) invalid = true else onSave(parsed.filterNotNull())
    }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
