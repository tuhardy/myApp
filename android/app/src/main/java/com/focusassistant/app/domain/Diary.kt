package com.focusassistant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 附件只保存应用私有目录内的文件名，不保存绝对路径或相册地址。 */
data class DiaryPhoto(val id: String, val file: String)
data class DiaryAudio(val id: String, val file: String, val durationMs: Long)

/**
 * 一条日记。occurredAt 是用户选定的记录时间（可补写过去），createdAt / updatedAt 是真实操作时间；
 * deletedAt 非空表示在回收站，不改变原记录时间。
 */
data class DiaryEntry(
    val id: String, val title: String, val text: String,
    val photos: List<DiaryPhoto>, val audios: List<DiaryAudio>,
    val occurredAt: Long, val createdAt: Long, val updatedAt: Long, val deletedAt: Long? = null
) {
    val files: List<String> get() = photos.map { it.file } + audios.map { it.file }
    val hasContent: Boolean get() = title.isNotBlank() || text.isNotBlank() || photos.isNotEmpty() || audios.isNotEmpty()
}

/**
 * 没写完的日记。新日记每篇一份（key = new-UUID），修改已有日记每条最多一份（key = edit:日记ID）。
 * entryId 为空表示新日记；原日记已删除时列表标为 ORPHAN，保存时另存为新日记。
 */
data class DiaryDraft(
    val key: String, val entryId: String?, val title: String, val text: String, val occurredAt: Long,
    val photos: List<DiaryPhoto>, val audios: List<DiaryAudio>, val updatedAt: Long = 0
) {
    val files: List<String> get() = photos.map { it.file } + audios.map { it.file }
    val hasContent: Boolean get() = title.isNotBlank() || text.isNotBlank() || photos.isNotEmpty() || audios.isNotEmpty()
    fun toEntry(id: String) = DiaryEntry(id, title.trim(), text.trim(), photos, audios, occurredAt, 0, 0)
}
enum class DiaryDraftKind { NEW, EDIT, ORPHAN }
data class DiaryDraftItem(val draft: DiaryDraft, val entry: DiaryEntry?, val kind: DiaryDraftKind)

data class DiaryGroup(val date: LocalDate?, val label: String, val entries: List<DiaryEntry>)
data class DiaryDateQuery(val year: Int?, val month: Int, val day: Int?, val label: String)
data class DiarySelection(
    val groups: List<DiaryGroup>, val total: Int, val months: List<TodoMonthCount>,
    val latestMonth: YearMonth?, val dateLabel: String?
)

object DiaryRules {
    const val MAX_TITLE = 50
    const val MAX_TEXT = 20_000
    const val MIN_AUDIO_MS = 1_000L
    const val MAX_ID = 128
    /** 解析不带年份的日期时用闰年校验，允许「2月29日」。 */
    private const val LEAP_YEAR = 2000
    private val FILE_NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$""")
    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val RELATIVE_DAYS = mapOf("今天" to 0L, "昨天" to 1L, "前天" to 2L)
    private enum class Part { YEAR, MONTH, DAY }
    private val DATE_PATTERNS = listOf(
        Regex("""^(\d{4})(\d{2})(\d{2})$""") to listOf(Part.YEAR, Part.MONTH, Part.DAY),
        Regex("""^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?$""") to listOf(Part.YEAR, Part.MONTH, Part.DAY),
        Regex("""^(\d{4})[-/.年](\d{1,2})月?$""") to listOf(Part.YEAR, Part.MONTH),
        Regex("""^(\d{1,2})[-/.月](\d{1,2})日?$""") to listOf(Part.MONTH, Part.DAY),
        Regex("""^(\d{1,2})月$""") to listOf(Part.MONTH)
    )

    fun isSafeFileName(name: String) = FILE_NAME.matches(name) && ".." !in name

    /** 保存与备份读入共用：至少有文字、照片或录音之一；附件文件名只能是私有目录内的简单文件名。 */
    fun validate(entry: DiaryEntry, now: Long? = null) {
        require(entry.id.isNotBlank() && entry.id.length <= MAX_ID) { "日记标识无效" }
        require(entry.title.length <= MAX_TITLE) { "标题最多 $MAX_TITLE 字" }
        require(entry.text.length <= MAX_TEXT) { "正文最多 $MAX_TEXT 字" }
        require(entry.hasContent) { "写点什么，或添加照片、录音后再保存" }
        val ids = entry.photos.map { it.id } + entry.audios.map { it.id }
        require(ids.all { it.isNotBlank() && it.length <= MAX_ID } && ids.toSet().size == ids.size) { "附件标识缺失或重复" }
        require(entry.files.all(::isSafeFileName) && entry.files.toSet().size == entry.files.size) { "附件文件名无效" }
        require(entry.audios.all { it.durationMs >= MIN_AUDIO_MS }) { "录音时长无效" }
        listOfNotNull(entry.occurredAt, entry.createdAt, entry.updatedAt, entry.deletedAt).forEach {
            require(it in 0..Validation.MAX_TIMESTAMP_MS) { "日记时间无效" }
        }
        if (now != null) require(entry.occurredAt <= now) { "记录时间不能晚于现在" }
    }

    /** 把搜索词解析为日期条件；年份、日期可省略。不是合法日期时返回 null，只按文字搜索。 */
    fun parseDateQuery(query: String, today: LocalDate): DiaryDateQuery? {
        val text = query.replace(Regex("""\s+"""), "")
        RELATIVE_DAYS[text]?.let { offset ->
            val date = today.minusDays(offset)
            return DiaryDateQuery(date.year, date.monthValue, date.dayOfMonth, text)
        }
        for ((pattern, parts) in DATE_PATTERNS) {
            val match = pattern.matchEntire(text) ?: continue
            val values = parts.withIndex().associate { (index, part) -> part to match.groupValues[index + 1].toInt() }
            val year = values[Part.YEAR]
            val month = values.getValue(Part.MONTH)
            val day = values[Part.DAY]
            if (month !in 1..12) return null
            if (day != null && (day < 1 || day > YearMonth.of(year ?: LEAP_YEAR, month).lengthOfMonth())) return null
            val label = "${year?.let { "${it}年" }.orEmpty()}${month}月${day?.let { "${it}日" }.orEmpty()}"
            return DiaryDateQuery(year, month, day, label)
        }
        return null
    }

    private fun matches(date: LocalDate?, query: DiaryDateQuery) = date != null &&
        (query.year == null || date.year == query.year) && date.monthValue == query.month && (query.day == null || date.dayOfMonth == query.day)

    fun localDate(entry: DiaryEntry, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        entry.occurredAt.takeIf { it in 0..Validation.MAX_TIMESTAMP_MS }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    fun dayLabel(date: LocalDate, today: LocalDate): String {
        val suffix = when (date) { today -> " · 今天"; today.minusDays(1) -> " · 昨天"; else -> "" }
        val year = if (date.year == today.year) "" else "${date.year}年"
        return "$year${date.monthValue}月${date.dayOfMonth}日 ${WEEKDAYS[date.dayOfWeek.value - 1]}$suffix"
    }

    /** 首页：未删除的日记按月查看；搜索覆盖全部时间，匹配标题、正文或日期；同日多条按时间倒序。 */
    fun select(entries: List<DiaryEntry>, month: YearMonth, query: String, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DiarySelection {
        val alive = entries.filter { it.deletedAt == null }.sortedByDescending { it.occurredAt }.map { it to localDate(it, zone) }
        val months = alive.mapNotNull { it.second?.let(YearMonth::from) }.groupingBy { it }.eachCount()
            .map { TodoMonthCount(it.key, it.value) }.sortedByDescending { it.month }
        val keyword = query.trim().lowercase()
        val dateQuery = if (keyword.isEmpty()) null else parseDateQuery(keyword, today)
        val matched = if (keyword.isEmpty()) alive.filter { it.second != null && YearMonth.from(it.second) == month }
        else alive.filter { (entry, date) ->
            (dateQuery != null && matches(date, dateQuery)) || "${entry.title}\n${entry.text}".lowercase().contains(keyword)
        }
        val groups = matched.groupBy { it.second }.map { (date, items) ->
            DiaryGroup(date, date?.let { dayLabel(it, today) } ?: "时间未记录", items.map { it.first })
        }
        return DiarySelection(groups, matched.size, months, months.firstOrNull()?.month, dateQuery?.label)
    }

    /** 回收站按移入时间倒序，不自动清空。 */
    fun trashed(entries: List<DiaryEntry>): List<DiaryEntry> = entries.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }

    fun moveToTrash(entry: DiaryEntry, now: Long): DiaryEntry = if (entry.deletedAt != null) entry else entry.copy(deletedAt = now)
    fun restore(entry: DiaryEntry): DiaryEntry = entry.copy(deletedAt = null)

    /** 仍被日记、已存草稿或正在编辑的内容引用的文件不能清理。 */
    fun unreferenced(candidates: Collection<String>, entries: List<DiaryEntry>, protected: Set<String> = emptySet(), drafts: List<DiaryDraft> = emptyList()): Set<String> {
        val used = entries.flatMapTo(mutableSetOf()) { it.files } + drafts.flatMap { it.files } + protected
        return candidates.filterTo(mutableSetOf()) { it !in used && isSafeFileName(it) }
    }

    const val NEW_DRAFT_PREFIX = "new-"
    const val EDIT_DRAFT_PREFIX = "edit:"
    fun newDraftKey(): String = NEW_DRAFT_PREFIX + java.util.UUID.randomUUID()
    fun editDraftKey(entryId: String): String = EDIT_DRAFT_PREFIX + entryId

    /** 以日记（或空白）为起点的工作副本。 */
    fun freshDraft(key: String, entry: DiaryEntry?, now: Long) = DiaryDraft(key, entry?.id, entry?.title.orEmpty(), entry?.text.orEmpty(),
        entry?.occurredAt ?: now, entry?.photos.orEmpty(), entry?.audios.orEmpty())

    /** 与起点相比是否有改动；新日记只改了记录时间不算改动。改回原样视为没改。 */
    fun draftChanged(draft: DiaryDraft, entry: DiaryEntry?): Boolean {
        val base = freshDraft(draft.key, entry, draft.occurredAt)
        return draft.title != base.title || draft.text != base.text || draft.photos != base.photos || draft.audios != base.audios ||
            (entry != null && draft.occurredAt != base.occurredAt)
    }

    /** 草稿可以暂时为空（例如把原日记正文删光），但字段长度、附件文件名和时间必须合法。 */
    fun validateDraft(draft: DiaryDraft) {
        require(draft.key.startsWith(NEW_DRAFT_PREFIX) || draft.key.startsWith(EDIT_DRAFT_PREFIX)) { "草稿标识无效" }
        require(draft.key.length <= MAX_ID + EDIT_DRAFT_PREFIX.length) { "草稿标识过长" }
        require(draft.entryId == null || (draft.entryId.isNotBlank() && draft.entryId.length <= MAX_ID)) { "草稿关联的日记标识无效" }
        require(draft.title.length <= MAX_TITLE && draft.text.length <= MAX_TEXT) { "草稿内容过长" }
        val ids = draft.photos.map { it.id } + draft.audios.map { it.id }
        require(ids.all { it.isNotBlank() && it.length <= MAX_ID } && ids.toSet().size == ids.size) { "草稿附件标识缺失或重复" }
        require(draft.files.all(::isSafeFileName) && draft.files.toSet().size == draft.files.size) { "草稿附件文件名无效" }
        require(draft.audios.all { it.durationMs >= MIN_AUDIO_MS }) { "草稿录音时长无效" }
        require(draft.occurredAt in 0..Validation.MAX_TIMESTAMP_MS && draft.updatedAt in 0..Validation.MAX_TIMESTAMP_MS) { "草稿时间无效" }
    }

    /** 草稿箱按最后编辑倒序；原日记已删除或在回收站时为 ORPHAN。 */
    fun listDrafts(drafts: List<DiaryDraft>, entries: List<DiaryEntry>): List<DiaryDraftItem> {
        val alive = entries.filter { it.deletedAt == null }.associateBy { it.id }
        return drafts.map { draft ->
            val entry = draft.entryId?.let(alive::get)
            DiaryDraftItem(draft, entry, when { draft.entryId == null -> DiaryDraftKind.NEW; entry != null -> DiaryDraftKind.EDIT; else -> DiaryDraftKind.ORPHAN })
        }.sortedByDescending { it.draft.updatedAt }
    }
}
