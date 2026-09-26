package com.focusassistant.app.domain

enum class TimerMode { COUNTDOWN, COUNTUP }
enum class TimerPhase { FOCUS, SHORT_BREAK, LONG_BREAK }
enum class TimerStatus { RUNNING, PAUSED }
enum class Period { DAY, WEEK, MONTH, YEAR }
enum class TimerEventKind { COMPLETED, TARGET_REACHED, RECOVERED }

data class Project(val id: String, val title: String, val category: String, val timerMode: TimerMode, val targetMinutes: Int?)
data class TodoStep(val id: String, val title: String, val done: Boolean = false)
/**
 * createdAt 是「放进清单的那天」的本地日期，用于计算停留天数；steps 是可选的一串小步。
 * archived 只表示放下，不删除记录。
 */
data class Todo(
    val id: String, val title: String, val category: String, val important: Boolean = false,
    val done: Boolean = false, val estimate: Int = 1,
    val createdAt: String = "", val steps: List<TodoStep> = emptyList(), val archived: Boolean = false
)
data class TimeSegment(val startedAt: Long, val endedAt: Long)
data class FocusSession(
    val id: String, val projectId: String, val projectTitle: String, val category: String,
    val timerMode: TimerMode, val targetMinutes: Int?, val taskId: String? = null, val taskTitle: String? = null,
    val startedAt: Long, val endedAt: Long, val durationSeconds: Long,
    val segments: List<TimeSegment>, val progressPrompted: Boolean = false
)
data class ProgressEntry(val id: String, val sessionId: String, val projectId: String, val sessionEndedAt: Long, val note: String, val percent: Int?, val updatedAt: Long)
data class AppSettings(val dailyGoalMinutes: Int = 240, val usageReminders: Boolean = false, val shortBreakMinutes: Int = 5, val longBreakMinutes: Int = 15)
data class ActiveTimer(
    val sessionId: String, val projectId: String, val projectTitle: String, val category: String,
    val timerMode: TimerMode, val targetMinutes: Int?, val phase: TimerPhase, val status: TimerStatus,
    val startedAt: Long, val taskId: String? = null, val taskTitle: String? = null,
    val accumulatedMs: Long = 0, val anchorElapsed: Long = 0, val anchorWall: Long = startedAt,
    val bootCount: Int = 0, val segments: List<TimeSegment> = emptyList(),
    val targetNotified: Boolean = false, val recoveryPending: Boolean = false
) {
    /** 不限时的正计时没有目标，此时为 null；倒计时必定有目标。 */
    val targetMs: Long? get() = targetMinutes?.let { it * 60_000L }
    fun elapsedMs(nowElapsed: Long): Long {
        val active = accumulatedMs + if (status == TimerStatus.RUNNING) (nowElapsed - anchorElapsed).coerceAtLeast(0) else 0
        val limit = targetMs
        return if (timerMode == TimerMode.COUNTDOWN && limit != null) active.coerceAtMost(limit) else active
    }
    fun remainingSeconds(nowElapsed: Long): Long {
        val limit = targetMs ?: return 0
        return ((limit - elapsedMs(nowElapsed)).coerceAtLeast(0) + 999) / 1000
    }
    /** 无目标时永远返回 false：不限时的正计时不会“到点”。 */
    fun targetReached(nowElapsed: Long): Boolean {
        val limit = targetMs ?: return false
        return elapsedMs(nowElapsed) >= limit
    }
}
data class AppState(
    val projects: List<Project> = emptyList(), val todos: List<Todo> = emptyList(),
    val sessions: List<FocusSession> = emptyList(), val progress: List<ProgressEntry> = emptyList(),
    val settings: AppSettings = AppSettings(), val timer: ActiveTimer? = null, val loading: Boolean = true
)
data class TimerEvent(val kind: TimerEventKind, val projectTitle: String, val sessionId: String? = null)
data class BackupData(val projects: List<Project>, val todos: List<Todo>, val sessions: List<FocusSession>, val progress: List<ProgressEntry>, val settings: AppSettings)

object Validation {
    const val MAX_TITLE = 80
    const val MAX_NOTE = 2000
    /** 一件事最多 8 个小步，每步 1–40 字。 */
    const val MAX_STEPS = 8
    const val MAX_STEP_TITLE = 40
    /** 小时 0–23、分钟 0–59，合计至少 1 分钟。 */
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 23 * 60 + 59
    fun project(value: Project) {
        require(value.id.isNotBlank() && value.id.length <= 128) { "项目标识无效" }
        require(value.title.isNotBlank() && value.title.length <= MAX_TITLE) { "项目名称须为 1–80 个字符" }
        require(value.category.length <= MAX_TITLE) { "分类过长" }
        target(value.timerMode, value.targetMinutes)
    }
    /** 倒计时必须有目标；正计时可以不设目标（不限时）。 */
    fun target(mode: TimerMode, minutes: Int?) {
        if (minutes == null) {
            require(mode == TimerMode.COUNTUP) { "倒计时必须设置时长" }
            return
        }
        require(minutes in MIN_MINUTES..MAX_MINUTES) { "时长须为 1 分钟至 23 小时 59 分钟" }
    }
    fun todo(value: Todo) {
        require(value.id.isNotBlank() && value.id.length <= 128) { "待办标识无效" }
        require(value.title.isNotBlank() && value.title.length <= MAX_TITLE && value.category.length <= MAX_TITLE) { "待办名称或分类无效" }
        require(value.estimate in 1..1000) { "预计专注次数须为 1–1000" }
        require(value.createdAt.isBlank() || DATE_KEY.matches(value.createdAt)) { "放入日期须为 YYYY-MM-DD" }
        require(value.steps.size <= MAX_STEPS) { "每件事最多 $MAX_STEPS 个小步" }
        val ids = value.steps.map { it.id }
        require(ids.all { it.isNotBlank() && it.length <= 128 } && ids.toSet().size == ids.size) { "小步标识缺失或重复" }
        value.steps.forEach { require(it.title.isNotBlank() && it.title.length <= MAX_STEP_TITLE) { "小步名称须为 1–$MAX_STEP_TITLE 个字符" } }
    }
    private val DATE_KEY = Regex("""^\d{4}-\d{2}-\d{2}$""")
    fun settings(value: AppSettings) {
        require(value.dailyGoalMinutes in 1..1440) { "每日目标须为 1–1440 分钟" }
        require(value.shortBreakMinutes in 1..120 && value.longBreakMinutes in 1..120) { "休息时长须为 1–120 分钟" }
    }
    fun progress(note: String, percent: Int?) {
        require(note.isNotBlank() && note.length <= MAX_NOTE) { "进度文字须为 1–2000 个字符" }
        require(percent == null || percent in 0..100) { "完成度须为 0–100" }
    }
}
