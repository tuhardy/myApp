package com.focusassistant.app.data

import com.focusassistant.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object BackupCodec {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_PROJECTS = 10_000
    const val MAX_TODOS = 10_000
    const val MAX_SESSIONS = 100_000
    const val MAX_PROGRESS = 200_000
    private const val MAX_DATE = 253402300799999L

    fun encode(state: AppState): String = JSONObject().put("kind", "focus-assistant-backup").put("version", 1)
        .put("projects", JSONArray(state.projects.map(JsonCodec::project)))
        .put("todos", JSONArray(state.todos.map(JsonCodec::todo)))
        .put("sessions", JSONArray(state.sessions.map(JsonCodec::session)))
        .put("progress", JSONArray(state.progress.map(JsonCodec::progress)))
        .put("settings", JsonCodec.settings(state.settings)).toString()

    fun decode(text: String): BackupData {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份不可超过 20 MiB" }
        val tokener = JSONTokener(text.removePrefix("\uFEFF"))
        val root = tokener.nextValue() as? JSONObject ?: error("备份必须为 JSON 对象")
        require(tokener.nextClean() == '\u0000') { "备份包含多余内容" }
        require(root.string("kind") == "focus-assistant-backup" && root.integer("version") == 1) { "不是受支持的完整备份（统计导出不可用于恢复）" }
        val data = BackupData(
            root.objects("projects", MAX_PROJECTS).map(JsonCodec::readProject),
            root.objects("todos", MAX_TODOS).map(JsonCodec::readTodo),
            root.objects("sessions", MAX_SESSIONS).map(JsonCodec::readSession),
            root.objects("progress", MAX_PROGRESS).map(JsonCodec::readProgress),
            JsonCodec.readSettings(root.getJSONObject("settings")))
        validate(data)
        return data
    }

    fun validate(data: BackupData) {
        require(data.projects.size <= MAX_PROJECTS && data.todos.size <= MAX_TODOS && data.sessions.size <= MAX_SESSIONS && data.progress.size <= MAX_PROGRESS) { "备份记录数超出限制" }
        fun ids(values: List<String>) {
            require(values.all { it.isNotBlank() && it.length <= 128 } && values.toSet().size == values.size) { "记录标识缺失、重复或过长" }
        }
        fun date(value: Long) { require(value in 0..MAX_DATE) { "日期无效" } }
        ids(data.projects.map { it.id }); ids(data.todos.map { it.id }); ids(data.sessions.map { it.id }); ids(data.progress.map { it.id })
        data.projects.forEach(Validation::project)
        data.todos.forEach(Validation::todo)
        Validation.settings(data.settings)
        data.sessions.forEach { session ->
            Validation.project(Project(session.projectId, session.projectTitle, session.category, session.timerMode, session.targetMinutes))
            date(session.startedAt); date(session.endedAt)
            require(session.endedAt > session.startedAt && session.durationSeconds in 1..(MAX_DATE / 1000)) { "记录时长无效" }
            require(session.timerMode != TimerMode.COUNTDOWN || session.durationSeconds <= requireNotNull(session.targetMinutes) { "倒计时记录缺少目标时长" } * 60L) { "倒计时时长超出目标" }
            require(session.segments.isNotEmpty() && session.segments.size <= 10000) { "活动片段无效" }
            var previous = session.startedAt
            var total = 0L
            session.segments.forEach { segment ->
                require(segment.startedAt >= previous && segment.endedAt > segment.startedAt && segment.endedAt <= session.endedAt) { "活动片段重叠或超出记录范围" }
                total += segment.endedAt - segment.startedAt
                previous = segment.endedAt
            }
            require(total == session.durationSeconds * 1000) { "活动片段与有效时长不一致" }
        }
        val sessions = data.sessions.associateBy { it.id }
        data.progress.forEach { entry ->
            Validation.progress(entry.note, entry.percent)
            val session = sessions[entry.sessionId] ?: error("进度引用的专注记录不存在")
            require(entry.projectId == session.projectId && entry.sessionEndedAt == session.endedAt) { "进度快照与专注记录不一致" }
            date(entry.updatedAt)
        }
        require(encode(AppState(data.projects, data.todos, data.sessions, data.progress, data.settings)).toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份不可超过 20 MiB" }
    }
}

internal fun JSONObject.string(key: String): String = get(key).let { require(it is String) { "$key 必须为文字" }; it }
internal fun JSONObject.long(key: String): Long = get(key).let { require(it is Int || it is Long) { "$key 必须为整数" }; (it as Number).toLong() }
internal fun JSONObject.integer(key: String): Int = long(key).let { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$key 超出整数范围" }; it.toInt() }
internal fun JSONObject.boolean(key: String): Boolean = get(key).let { require(it is Boolean) { "$key 必须为布尔值" }; it }
/** 目标时长可为 null（不限时的正计时）。键必须存在，避免把损坏的备份当成不限时。 */
internal fun JSONObject.nullableInteger(key: String): Int? { require(has(key)) { "缺少 $key" }; return if (isNull(key)) null else integer(key) }
internal fun JSONObject.objects(key: String, limit: Int): List<JSONObject> {
    val array = getJSONArray(key)
    require(array.length() <= limit) { "$key 记录过多" }
    return (0 until array.length()).map { array.getJSONObject(it) }
}

internal object JsonCodec {
    private fun nullable(value: Any?): Any = value ?: JSONObject.NULL
    fun project(value: Project): JSONObject = JSONObject().put("id", value.id).put("title", value.title).put("category", value.category).put("timerMode", value.timerMode.name).put("targetMinutes", nullable(value.targetMinutes))
    fun readProject(value: JSONObject) = Project(value.string("id"), value.string("title"), value.string("category"), TimerMode.valueOf(value.string("timerMode")), value.nullableInteger("targetMinutes"))
    fun step(value: TodoStep): JSONObject = JSONObject().put("id", value.id).put("title", value.title).put("done", value.done)
    fun readStep(value: JSONObject) = TodoStep(value.string("id"), value.string("title"), value.boolean("done"))
    fun todo(value: Todo): JSONObject = JSONObject().put("id", value.id).put("title", value.title).put("category", value.category).put("important", value.important).put("done", value.done)
        .put("createdAt", value.createdAt).put("steps", JSONArray(value.steps.map(::step))).put("archived", value.archived)
        .put("completedAt", nullable(value.completedAt))
    /** 旧备份缺失的日期读为空、小步读为空列表、放下状态读为否，不臆造历史。 */
    fun readTodo(value: JSONObject) = Todo(value.string("id"), value.string("title"), value.string("category"), value.boolean("important"), value.boolean("done"),
        if (value.has("createdAt")) value.string("createdAt") else "",
        if (value.has("steps")) value.objects("steps", Validation.MAX_STEPS).map(::readStep) else emptyList(),
        if (value.has("archived")) value.boolean("archived") else false,
        if (!value.has("completedAt") || value.isNull("completedAt")) null else value.long("completedAt").also {
            require(it in 0..Validation.MAX_TIMESTAMP_MS) { "完成日期无效" }
        })
    fun segment(value: TimeSegment): JSONObject = JSONObject().put("startedAt", value.startedAt).put("endedAt", value.endedAt)
    fun readSegment(value: JSONObject) = TimeSegment(value.long("startedAt"), value.long("endedAt"))
    fun session(value: FocusSession): JSONObject = JSONObject().put("id", value.id).put("projectId", value.projectId).put("projectTitle", value.projectTitle).put("category", value.category)
        .put("timerMode", value.timerMode.name).put("targetMinutes", nullable(value.targetMinutes))
        .put("startedAt", value.startedAt).put("endedAt", value.endedAt).put("durationSeconds", value.durationSeconds).put("segments", JSONArray(value.segments.map(::segment))).put("progressPrompted", value.progressPrompted)
    fun readSession(value: JSONObject) = FocusSession(value.string("id"), value.string("projectId"), value.string("projectTitle"), value.string("category"), TimerMode.valueOf(value.string("timerMode")), value.nullableInteger("targetMinutes"),
        value.long("startedAt"), value.long("endedAt"), value.long("durationSeconds"), value.objects("segments", 10000).map(::readSegment), value.boolean("progressPrompted"))
    fun progress(value: ProgressEntry): JSONObject = JSONObject().put("id", value.id).put("sessionId", value.sessionId).put("projectId", value.projectId).put("sessionEndedAt", value.sessionEndedAt).put("note", value.note).put("percent", nullable(value.percent)).put("updatedAt", value.updatedAt)
    fun readProgress(value: JSONObject): ProgressEntry {
        require(value.has("percent")) { "缺少 percent" }
        return ProgressEntry(value.string("id"), value.string("sessionId"), value.string("projectId"), value.long("sessionEndedAt"), value.string("note"), if (value.isNull("percent")) null else value.integer("percent"), value.long("updatedAt"))
    }
    fun settings(value: AppSettings): JSONObject = JSONObject().put("dailyGoalMinutes", value.dailyGoalMinutes).put("usageReminders", value.usageReminders).put("shortBreakMinutes", value.shortBreakMinutes).put("longBreakMinutes", value.longBreakMinutes)
    fun readSettings(value: JSONObject) = AppSettings(value.integer("dailyGoalMinutes"), value.boolean("usageReminders"), value.integer("shortBreakMinutes"), value.integer("longBreakMinutes"))
    fun timer(value: ActiveTimer): JSONObject = JSONObject().put("sessionId", value.sessionId).put("projectId", value.projectId).put("projectTitle", value.projectTitle).put("category", value.category)
        .put("timerMode", value.timerMode.name).put("targetMinutes", nullable(value.targetMinutes)).put("phase", value.phase.name).put("status", value.status.name)
        .put("startedAt", value.startedAt).put("accumulatedMs", value.accumulatedMs)
        .put("anchorElapsed", value.anchorElapsed).put("anchorWall", value.anchorWall).put("bootCount", value.bootCount).put("segments", JSONArray(value.segments.map(::segment)))
        .put("targetNotified", value.targetNotified).put("recoveryPending", value.recoveryPending)
    fun readTimer(value: JSONObject) = ActiveTimer(value.string("sessionId"), value.string("projectId"), value.string("projectTitle"), value.string("category"), TimerMode.valueOf(value.string("timerMode")), value.nullableInteger("targetMinutes"),
        TimerPhase.valueOf(value.string("phase")), TimerStatus.valueOf(value.string("status")), value.long("startedAt"),
        value.long("accumulatedMs"), value.long("anchorElapsed"), value.long("anchorWall"), value.integer("bootCount"), value.objects("segments", 10000).map(::readSegment), value.boolean("targetNotified"), value.boolean("recoveryPending"))
}
