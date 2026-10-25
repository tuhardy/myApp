package com.focusassistant.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** BOSS 直聘自动沟通 / 智联招聘自动投递：规则、去重、界面识别和执行状态机都是纯函数；无障碍服务只负责读界面和点按钮。 */
enum class JobPlatform(val packageName: String, val label: String, val short: String) {
    /** 包名需在真机用诊断导出核对；与 boss_automation_service.xml、清单 <queries> 保持一致。 */
    BOSS("com.hpbr.bosszhipin", "BOSS 直聘", "BOSS"),
    ZHAOPIN("com.zhaopin.social", "智联招聘", "智联")
}

data class AutoApplySettings(
    val includeKeywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    val minSalaryK: Int? = null,
    val city: String = "",
    val companyBlacklist: List<String> = emptyList(),
    val dailyLimit: Int = AutoApplyRules.DEFAULT_DAILY_LIMIT,
    val stepDelayMs: Long = AutoApplyRules.DEFAULT_STEP_DELAY_MS,
    /** 为空时使用 BOSS 自带的默认招呼语，不额外输入文字。 */
    val greeting: String = "",
    val riskAccepted: Boolean = false,
    /** 最近一次试运行识别出职位卡片时的 BOSS 版本；真实执行只允许在该版本上进行。 */
    val calibratedVersion: String? = null,
    /** 我的简历：按列表顺序取第一份关键词命中的简历，都不命中时用默认简历。 */
    val resumes: List<ResumeProfile> = emptyList(),
    /** 最近一次发简历试运行识别出消息列表时的 BOSS 版本；自动发简历只允许在该版本上进行。 */
    val resumeCalibratedVersion: String? = null,
    /** 筛选方案：只保存筛选字段，切换时写回上面的同名字段；黑名单、上限、间隔和简历是全局设置。 */
    val presets: List<FilterPreset> = emptyList(),
    val activePresetId: String? = null,
    /** 每次运行只操作一个平台；今日计数、上限、去重和试运行版本按平台分开，方案与简历共用。 */
    val platform: JobPlatform = JobPlatform.BOSS,
    /** 上面的 dailyLimit / calibratedVersion 属于 BOSS；智联另存。 */
    val zhaopinDailyLimit: Int = AutoApplyRules.DEFAULT_DAILY_LIMIT,
    val zhaopinCalibratedVersion: String? = null
)

data class FilterPreset(
    val id: String,
    val name: String,
    val includeKeywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    val minSalaryK: Int? = null,
    val city: String = "",
    val greeting: String = ""
)

/** name 须与 BOSS 里附件简历的文件名一致（可省略扩展名）。 */
data class ResumeProfile(val id: String, val name: String, val keywords: List<String> = emptyList(), val isDefault: Boolean = false)

/** APPLIED：智联界面上点了投递并看到成功提示，不代表 HR 已查看。 */
enum class AutoApplyResult { CONTACTED, ALREADY_CONTACTED, FAILED, RESUME_SENT, APPLIED }

data class AutoApplyRecord(
    val id: String,
    val jobKey: String,
    val company: String,
    val position: String,
    val salary: String,
    val at: Long,
    val result: AutoApplyResult,
    val note: String = "",
    /** 写入时所用筛选方案的名称；没有方案时为空。 */
    val preset: String = "",
    /** 0.9.12 及以前的记录都来自 BOSS。 */
    val platform: JobPlatform = JobPlatform.BOSS
)

data class AutoApplyBook(val settings: AutoApplySettings = AutoApplySettings(), val records: List<AutoApplyRecord> = emptyList())

data class JobCard(val position: String, val company: String, val salary: String, val city: String = "")

sealed interface AutoApplyDecision {
    data object Apply : AutoApplyDecision
    data class Skip(val reason: String) : AutoApplyDecision
}

object AutoApplyRules {
    const val DEFAULT_DAILY_LIMIT = 30
    const val MAX_DAILY_LIMIT = 100
    const val DEFAULT_STEP_DELAY_MS = 1_000L
    const val MIN_STEP_DELAY_MS = 1_000L
    const val MAX_STEP_DELAY_MS = 30_000L
    const val MAX_KEYWORDS = 30
    const val MAX_KEYWORD = 30
    const val MAX_CITY = 20
    const val MAX_PRESETS = 10
    const val MAX_PRESET_NAME = 20
    const val MAX_GREETING = 300
    const val MAX_RECORDS = 5_000
    const val MAX_SALARY_K = 1_000
    const val MAX_RESUMES = 10
    const val MAX_RESUME_NAME = 80

    private val salaryRange = Regex("""^\s*(\d+(?:\.\d+)?)\s*(?:[-~]\s*(\d+(?:\.\d+)?))?\s*([Kk]|万|元/(?:天|时|小时|月))""")

    /** BOSS 列表里的薪资文字，例如「15-25K·13薪」「1-1.5万」「200-300元/天」「面议」。 */
    fun isSalaryText(text: String): Boolean = text.trim() == "面议" || salaryRange.containsMatchIn(text)

    /** 返回月薪下限（K）；日薪、时薪和面议无法换算，返回 null。 */
    fun salaryLowerK(text: String): Int? {
        val match = salaryRange.find(text) ?: return null
        val low = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[3]) {
            "K", "k" -> low.toInt()
            "万" -> (low * 10).toInt()
            "元/月" -> (low / 1000).toInt()
            else -> null
        }
    }

    fun jobKey(card: JobCard): String = listOf(card.company, card.position, card.salary).joinToString("|") { it.trim().lowercase() }

    fun parseList(text: String): List<String> = text.split(',', '，', '、', ';', '；', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun localDay(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    fun limitFor(settings: AutoApplySettings, platform: JobPlatform): Int = if (platform == JobPlatform.ZHAOPIN) settings.zhaopinDailyLimit else settings.dailyLimit

    fun calibratedFor(settings: AutoApplySettings, platform: JobPlatform): String? =
        if (platform == JobPlatform.ZHAOPIN) settings.zhaopinCalibratedVersion else settings.calibratedVersion

    /** BOSS 的「发起沟通」和智联的「投递」都算主动联系，各自计入本平台的今日上限。 */
    fun isReach(record: AutoApplyRecord) = record.result == AutoApplyResult.CONTACTED || record.result == AutoApplyResult.APPLIED

    fun contactedOn(records: List<AutoApplyRecord>, day: LocalDate, zone: ZoneId, platform: JobPlatform = JobPlatform.BOSS): Int =
        records.count { it.platform == platform && isReach(it) && localDay(it.at, zone) == day }

    /** 去重与「同公司每天一次」只看同一平台的记录：BOSS 上沟通过不影响在智联投递。 */
    fun decide(card: JobCard, settings: AutoApplySettings, records: List<AutoApplyRecord>, day: LocalDate, zone: ZoneId,
        platform: JobPlatform = JobPlatform.BOSS): AutoApplyDecision {
        if (card.position.isBlank() || card.company.isBlank()) return AutoApplyDecision.Skip("未识别出职位或公司")
        val key = jobKey(card)
        val own = records.filter { it.platform == platform }
        if (own.any { it.jobKey == key && it.result != AutoApplyResult.FAILED }) return AutoApplyDecision.Skip(if (platform == JobPlatform.ZHAOPIN) "已投递过" else "已沟通过")
        val company = card.company.trim().lowercase()
        if (own.any { isReach(it) && it.company.trim().lowercase() == company && localDay(it.at, zone) == day }) {
            return AutoApplyDecision.Skip("今天已联系过这家公司")
        }
        settings.companyBlacklist.firstOrNull { card.company.contains(it, ignoreCase = true) }?.let { return AutoApplyDecision.Skip("公司在黑名单「$it」") }
        settings.excludeKeywords.firstOrNull { card.position.contains(it, ignoreCase = true) }?.let { return AutoApplyDecision.Skip("包含排除词「$it」") }
        if (settings.includeKeywords.isNotEmpty() && settings.includeKeywords.none { card.position.contains(it, ignoreCase = true) }) {
            return AutoApplyDecision.Skip("岗位不含关键词")
        }
        if (settings.city.isNotBlank() && card.city.isNotBlank() && !card.city.contains(settings.city.trim())) return AutoApplyDecision.Skip("城市不符")
        settings.minSalaryK?.let { min ->
            val low = salaryLowerK(card.salary) ?: return AutoApplyDecision.Skip("薪资无法识别")
            if (low < min) return AutoApplyDecision.Skip("薪资低于 ${min}K")
        }
        return AutoApplyDecision.Apply
    }

    /** 先按列表顺序找关键词命中的简历，再退回默认简历；都没有时返回 null（跳过）。 */
    fun pickResume(position: String, resumes: List<ResumeProfile>): String? =
        (resumes.firstOrNull { resume -> resume.keywords.any { position.contains(it, ignoreCase = true) } } ?: resumes.firstOrNull { it.isDefault })?.name

    /** 保存一份简历：同 id 覆盖，否则追加；设为默认时取消其他简历的默认。 */
    fun upsertResume(resumes: List<ResumeProfile>, value: ResumeProfile): List<ResumeProfile> {
        val cleaned = value.copy(name = value.name.trim(), keywords = value.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        val next = if (resumes.any { it.id == cleaned.id }) resumes.map { if (it.id == cleaned.id) cleaned else it } else resumes + cleaned
        return if (cleaned.isDefault) next.map { if (it.id != cleaned.id && it.isDefault) it.copy(isDefault = false) else it } else next
    }

    /** 调整优先级：up 为 true 时与上一份交换。 */
    fun moveResume(resumes: List<ResumeProfile>, id: String, up: Boolean): List<ResumeProfile> {
        val index = resumes.indexOfFirst { it.id == id }
        val other = if (up) index - 1 else index + 1
        if (index < 0 || other !in resumes.indices) return resumes
        return resumes.toMutableList().apply { this[index] = resumes[other]; this[other] = resumes[index] }
    }

    private val fileExtension = Regex("""\.(pdf|docx?|jpe?g|png)$""", RegexOption.IGNORE_CASE)
    private fun bareName(text: String): String = text.trim().replace(fileExtension, "").trim().lowercase()

    /** 简历只按整名比较（忽略大小写和扩展名），避免「简历」之类的片段误选别的附件。 */
    fun resumeNameMatches(option: String, name: String): Boolean = bareName(name).isNotEmpty() && bareName(option) == bareName(name)

    private val bracketed = Regex("""[(（\[【][^)）\]】]*[)）\]】]""")
    private fun companyKey(text: String): String = text.replace(bracketed, "").filterNot { it.isWhitespace() }.lowercase()

    /** 公司按整名比较（去掉空白和括号内容），避免「华为」误配「华为云计算」。 */
    fun sameCompany(a: String, b: String): Boolean = companyKey(a).isNotEmpty() && companyKey(a) == companyKey(b)

    private fun sameText(a: String, b: String): Boolean {
        val left = a.trim().lowercase()
        val right = b.trim().lowercase()
        return left.isNotEmpty() && right.isNotEmpty() && (left.contains(right) || right.contains(left))
    }

    /** 已自动发起沟通、还没发过简历的职位，每个职位一条。 */
    fun awaitingResume(records: List<AutoApplyRecord>): List<AutoApplyRecord> {
        val sent = records.filter { it.result == AutoApplyResult.RESUME_SENT }.map { it.jobKey }.toSet()
        return records.filter { it.platform == JobPlatform.BOSS && it.result == AutoApplyResult.CONTACTED && it.jobKey !in sent }.distinctBy { it.jobKey }
    }

    /** 按会话里的公司（及聊天页顶部的岗位）找回对应的沟通记录；岗位为空时只按公司。 */
    fun resumeTargets(company: String, position: String, records: List<AutoApplyRecord>): List<AutoApplyRecord> =
        awaitingResume(records).filter { sameCompany(it.company, company) && (position.isBlank() || sameText(it.position, position)) }

    fun resumesSentOn(records: List<AutoApplyRecord>, day: LocalDate, zone: ZoneId): Int =
        records.count { it.result == AutoApplyResult.RESUME_SENT && localDay(it.at, zone) == day }

    /** 「我的」入口卡与全局状态条共用，与浏览器 FocusModel.autoApplyEntry 同一口径。 */
    fun entry(running: Boolean, resume: Boolean, paused: Boolean, stopReason: String?, contacted: Int, resumes: Int, awaiting: Int, limit: Int,
        platform: JobPlatform = JobPlatform.BOSS): AutoApplyEntry {
        val state = when {
            running && paused -> AutoApplyEntryState.PAUSED
            running -> AutoApplyEntryState.RUNNING
            stopReason != null -> AutoApplyEntryState.STOPPED
            else -> AutoApplyEntryState.IDLE
        }
        val label = when (state) {
            AutoApplyEntryState.RUNNING -> when { platform == JobPlatform.ZHAOPIN -> "智联投递中"; resume -> "自动发简历中"; else -> "自动沟通中" }
            AutoApplyEntryState.PAUSED -> "已暂停"
            AutoApplyEntryState.STOPPED -> "已停止"
            AutoApplyEntryState.IDLE -> "空闲"
        }
        val summary = if (platform == JobPlatform.ZHAOPIN) "智联 · 投递 $contacted/$limit" else "沟通 $contacted/$limit · 简历 $resumes/$limit · 待回复 $awaiting"
        return AutoApplyEntry(state, label, summary)
    }

    private fun presetKey(name: String) = name.trim().lowercase()

    fun activePresetName(settings: AutoApplySettings): String = settings.presets.find { it.id == settings.activePresetId }?.name.orEmpty()

    /** 切换方案：把方案的筛选字段写回顶层，引擎仍只读顶层字段。 */
    fun applyPreset(settings: AutoApplySettings, id: String): AutoApplySettings {
        val preset = requireNotNull(settings.presets.find { it.id == id }) { "方案不存在" }
        return settings.copy(includeKeywords = preset.includeKeywords, excludeKeywords = preset.excludeKeywords, minSalaryK = preset.minSalaryK,
            city = preset.city, greeting = preset.greeting, activePresetId = id)
    }

    /** 把当前筛选字段存为方案：同名（忽略大小写）覆盖并保留原名，否则新建；名称为空时只取消当前方案。 */
    fun savePreset(settings: AutoApplySettings, name: String, newId: String): AutoApplySettings {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return settings.copy(activePresetId = null)
        require(trimmed.length <= MAX_PRESET_NAME) { "方案名称最多 $MAX_PRESET_NAME 个字符" }
        val existing = settings.presets.find { presetKey(it.name) == presetKey(trimmed) }
        require(existing != null || settings.presets.size < MAX_PRESETS) { "最多保存 $MAX_PRESETS 套方案" }
        val preset = FilterPreset(existing?.id ?: newId, existing?.name ?: trimmed, settings.includeKeywords, settings.excludeKeywords,
            settings.minSalaryK, settings.city, settings.greeting)
        val presets = if (existing != null) settings.presets.map { if (it.id == existing.id) preset else it } else settings.presets + preset
        return settings.copy(presets = presets, activePresetId = preset.id)
    }

    /** 删除方案不改顶层筛选字段；删的是当前方案时只取消选中。 */
    fun deletePreset(settings: AutoApplySettings, id: String): AutoApplySettings =
        settings.copy(presets = settings.presets.filterNot { it.id == id }, activePresetId = settings.activePresetId.takeUnless { it == id })

    fun validateSettings(value: AutoApplySettings) {
        fun words(list: List<String>, label: String) {
            require(list.size <= MAX_KEYWORDS) { "${label}最多 $MAX_KEYWORDS 个" }
            require(list.all { it.isNotBlank() && it.length <= MAX_KEYWORD }) { "${label}每个最多 $MAX_KEYWORD 个字符" }
        }
        words(value.includeKeywords, "关键词")
        words(value.excludeKeywords, "排除词")
        words(value.companyBlacklist, "公司黑名单")
        require(value.minSalaryK == null || value.minSalaryK in 1..MAX_SALARY_K) { "最低薪资须为 1–$MAX_SALARY_K K" }
        require(value.city.length <= MAX_CITY) { "城市最多 $MAX_CITY 个字符" }
        require(value.dailyLimit in 1..MAX_DAILY_LIMIT && value.zhaopinDailyLimit in 1..MAX_DAILY_LIMIT) { "每日上限须为 1–$MAX_DAILY_LIMIT" }
        require(value.stepDelayMs in MIN_STEP_DELAY_MS..MAX_STEP_DELAY_MS) { "步骤间隔须为 ${MIN_STEP_DELAY_MS / 1000}–${MAX_STEP_DELAY_MS / 1000} 秒" }
        require(value.greeting.length <= MAX_GREETING) { "招呼语最多 $MAX_GREETING 个字符" }
        require(value.calibratedVersion == null || value.calibratedVersion.length <= 64) { "校准版本无效" }
        require(value.resumeCalibratedVersion == null || value.resumeCalibratedVersion.length <= 64) { "校准版本无效" }
        require(value.zhaopinCalibratedVersion == null || value.zhaopinCalibratedVersion.length <= 64) { "校准版本无效" }
        require(value.resumes.size <= MAX_RESUMES) { "简历最多 $MAX_RESUMES 份" }
        require(value.resumes.count { it.isDefault } <= 1) { "只能有一份默认简历" }
        require(value.resumes.map { bareName(it.name) }.distinct().size == value.resumes.size) { "简历名称不能重复" }
        value.resumes.forEach { resume ->
            require(resume.id.isNotBlank() && resume.id.length <= 64) { "简历标识无效" }
            require(resume.name.isNotBlank() && resume.name.length <= MAX_RESUME_NAME) { "简历名称须为 1–$MAX_RESUME_NAME 个字符" }
            require(resume.isDefault || resume.keywords.isNotEmpty()) { "「${resume.name}」至少要有一个岗位关键词，或设为默认简历" }
            words(resume.keywords, "简历关键词")
        }
        require(value.presets.size <= MAX_PRESETS) { "最多保存 $MAX_PRESETS 套方案" }
        require(value.presets.map { presetKey(it.name) }.distinct().size == value.presets.size) { "方案名称不能重复" }
        value.presets.forEach { preset ->
            require(preset.id.isNotBlank() && preset.id.length <= 64) { "方案标识无效" }
            require(preset.name.isNotBlank() && preset.name.trim().length <= MAX_PRESET_NAME) { "方案名称须为 1–$MAX_PRESET_NAME 个字符" }
            words(preset.includeKeywords, "方案关键词")
            words(preset.excludeKeywords, "方案排除词")
            require(preset.minSalaryK == null || preset.minSalaryK in 1..MAX_SALARY_K) { "最低薪资须为 1–$MAX_SALARY_K K" }
            require(preset.city.length <= MAX_CITY && preset.greeting.length <= MAX_GREETING) { "方案内容过长" }
        }
        require(value.activePresetId == null || value.presets.any { it.id == value.activePresetId }) { "当前方案不存在" }
    }

    fun validateRecord(value: AutoApplyRecord) {
        require(value.id.isNotBlank() && value.id.length <= 128) { "记录标识无效" }
        require(value.jobKey.isNotBlank() && value.jobKey.length <= 400) { "职位标识无效" }
        require(value.company.length <= 200 && value.position.length <= 200 && value.salary.length <= 100 && value.note.length <= 200 && value.preset.length <= MAX_PRESET_NAME) { "记录内容过长" }
        require(value.at in 0..Validation.MAX_TIMESTAMP_MS) { "记录时间无效" }
    }

    fun addRecord(book: AutoApplyBook, record: AutoApplyRecord): AutoApplyBook {
        validateRecord(record)
        return book.copy(records = (book.records + record).takeLast(MAX_RECORDS))
    }
}

/** 无障碍节点树的只读快照；path 是从根节点出发的子节点下标，服务据此找回真实节点再点击。 */
data class UiNode(
    val path: List<Int>,
    val viewId: String = "",
    val className: String = "",
    val text: String = "",
    val description: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val children: List<UiNode> = emptyList()
) {
    val label: String get() = text.ifBlank { description }
    fun walk(): Sequence<UiNode> = sequence { yield(this@UiNode); children.forEach { yieldAll(it.walk()) } }
}

/** APPLY_DONE 只用于智联：投递后的成功提示。 */
enum class BossScreen { JOB_LIST, JOB_DETAIL, CHAT, MESSAGE_LIST, RESUME_PICKER, RESUME_CONFIRM, VERIFY, LIMIT_REACHED, LOGIN, APPLY_DONE, OTHER }

/** 消息列表里的一个会话：只取公司名和有没有未读角标，不读最后一条消息。 */
data class Conversation(val company: String, val unread: Boolean)

data class BossObservation(
    val screen: BossScreen,
    val cards: List<JobCard> = emptyList(),
    val alreadyContacted: Boolean = false,
    val now: Long,
    val conversations: List<Conversation> = emptyList(),
    /** 聊天页顶部职位卡片上的岗位名；不读聊天气泡。 */
    val chatPosition: String = "",
    val hasResumeButton: Boolean = false,
    /** 简历选择列表或确认框里出现的附件文件名。 */
    val resumeOptions: List<String> = emptyList(),
    val hasConfirm: Boolean = false,
    val hasMessagesTab: Boolean = false
)

data class BossReading(
    val observation: BossObservation,
    val cardPaths: List<List<Int>> = emptyList(),
    val contactPath: List<Int>? = null,
    val inputPath: List<Int>? = null,
    val sendPath: List<Int>? = null,
    val listPath: List<Int>? = null,
    val conversationPaths: List<List<Int>> = emptyList(),
    val resumeButtonPath: List<Int>? = null,
    val resumeOptionPaths: List<List<Int>> = emptyList(),
    val confirmPath: List<Int>? = null,
    val messagesTabPath: List<Int>? = null
)

/**
 * 按文字和 viewId 片段识别 BOSS 界面。BOSS 未公开界面结构，这里的候选值来自常见布局，
 * 需在真机用诊断导出核对；识别不到时归为 OTHER，由状态机超时停止，不盲点。
 */
object BossScreenReader {
    private val verifyWords = listOf("安全验证", "滑动验证", "拖动滑块", "请完成验证", "人机验证", "向右滑动")
    private val loginWords = listOf("手机号登录", "验证码登录", "一键登录", "密码登录", "登录/注册")
    private val limitWords = listOf("沟通次数已用完", "今日沟通已达上限", "开聊次数已用完")
    private val pickerTitles = listOf("选择附件简历", "选择简历", "请选择简历", "选择要发送的简历")
    private val unreadIds = listOf("unread", "badge", "red_dot", "reddot", "noread", "none_read", "msg_count", "new_msg")
    private val fileName = Regex("""\.(pdf|docx?|jpe?g|png)\s*$""", RegexOption.IGNORE_CASE)
    private val positionIds = listOf("position", "job_name", "jobname", "job_title")
    private val companyIds = listOf("company", "com_name", "brand")
    private val cityIds = listOf("location", "city", "area", "address")

    fun read(root: UiNode, now: Long): BossReading {
        val nodes = root.walk().toList()
        val labels = nodes.map { it.label }.filter { it.isNotBlank() }
        val tab = nodes.firstOrNull { it.label.trim() == "消息" }?.let { clickablePath(root, it.path) }
        fun obs(screen: BossScreen, cards: List<JobCard> = emptyList(), contacted: Boolean = false) =
            BossObservation(screen, cards, contacted, now, hasMessagesTab = tab != null)
        if (labels.any { label -> verifyWords.any { label.contains(it) } }) return BossReading(obs(BossScreen.VERIFY))
        if (labels.any { label -> loginWords.any { label.contains(it) } }) return BossReading(obs(BossScreen.LOGIN))
        if (labels.any { label -> limitWords.any { label.contains(it) } || (label.contains("上限") && (label.contains("沟通") || label.contains("开聊") || label.contains("简历"))) }) {
            return BossReading(obs(BossScreen.LIMIT_REACHED))
        }
        val files = nodes.filter { fileName.containsMatchIn(it.text) }
        val confirm = nodes.firstOrNull { it.label.trim() == "确定" || it.label.trim() == "确认" || it.label.trim() == "确认发送" }
        if (labels.any { it.trim() in pickerTitles }) {
            val send = confirm ?: nodes.firstOrNull { it.label.trim() == "发送" }
            return BossReading(obs(BossScreen.RESUME_PICKER).copy(resumeOptions = files.map { it.text.trim() }, hasConfirm = send != null),
                resumeOptionPaths = files.map { clickablePath(root, it.path) }, confirmPath = send?.let { clickablePath(root, it.path) })
        }
        if (confirm != null && labels.any { it.contains("简历") && (it.contains("确定") || it.contains("是否") || it.contains("吗")) }) {
            return BossReading(obs(BossScreen.RESUME_CONFIRM).copy(resumeOptions = files.map { it.text.trim() }, hasConfirm = true),
                confirmPath = clickablePath(root, confirm.path))
        }
        val contact = nodes.firstOrNull { it.label.trim() == "立即沟通" || it.label.trim() == "继续沟通" }
        if (contact != null) {
            return BossReading(obs(BossScreen.JOB_DETAIL, contacted = contact.label.trim() == "继续沟通"), contactPath = clickablePath(root, contact.path))
        }
        val input = nodes.firstOrNull { it.editable }
        val send = nodes.firstOrNull { it.label.trim() == "发送" }
        val resume = nodes.firstOrNull { it.label.trim() == "发简历" }
        if (input != null && (send != null || resume != null || nodes.any { it.viewId.contains("chat", ignoreCase = true) } || labels.any { it == "常用语" })) {
            val position = nodes.firstOrNull { node -> node.text.isNotBlank() && positionIds.any { node.viewId.contains(it, ignoreCase = true) } }?.text?.trim().orEmpty()
            return BossReading(obs(BossScreen.CHAT).copy(chatPosition = position, hasResumeButton = resume != null), inputPath = input.path,
                sendPath = send?.let { clickablePath(root, it.path) }, resumeButtonPath = resume?.let { clickablePath(root, it.path) })
        }
        val list = nodes.filter { it.scrollable }.maxByOrNull { it.children.size }
        if (list != null) {
            val parsed = list.children.mapNotNull { child -> parseCard(child)?.let { child.path to it } }
            if (parsed.isNotEmpty()) {
                return BossReading(obs(BossScreen.JOB_LIST, parsed.map { it.second }), cardPaths = parsed.map { it.first }, listPath = list.path, messagesTabPath = tab)
            }
            val chats = list.children.mapNotNull { child -> parseConversation(child)?.let { child.path to it } }
            if (chats.isNotEmpty()) {
                return BossReading(obs(BossScreen.MESSAGE_LIST).copy(conversations = chats.map { it.second }), conversationPaths = chats.map { it.first },
                    listPath = list.path, messagesTabPath = tab)
            }
        }
        return BossReading(obs(BossScreen.OTHER), messagesTabPath = tab)
    }

    /** 只认 viewId 带公司片段的文字和未读角标；最后一条消息的预览不读。 */
    fun parseConversation(item: UiNode): Conversation? {
        val nodes = item.walk().toList()
        val company = nodes.firstOrNull { node -> node.text.isNotBlank() && companyIds.any { node.viewId.contains(it, ignoreCase = true) } }?.text?.trim() ?: return null
        val unread = nodes.any { node -> unreadIds.any { node.viewId.contains(it, ignoreCase = true) } && node.text.trim() != "0" }
        return Conversation(company, unread)
    }

    fun parseCard(item: UiNode): JobCard? {
        val texts = item.walk().filter { it.text.isNotBlank() }.toList()
        val salaryIndex = texts.indexOfFirst { AutoApplyRules.isSalaryText(it.text) }
        if (salaryIndex < 0) return null
        fun byId(ids: List<String>) = texts.firstOrNull { node -> ids.any { node.viewId.contains(it, ignoreCase = true) } }?.text?.trim()
        val position = byId(positionIds) ?: texts.take(salaryIndex).lastOrNull()?.text?.trim() ?: return null
        val company = byId(companyIds) ?: texts.drop(salaryIndex + 1).firstOrNull { it.text.trim().length >= 2 }?.text?.trim() ?: return null
        return JobCard(position, company, texts[salaryIndex].text.trim(), byId(cityIds).orEmpty())
    }

}

/** 文字本身常不可点，向上找最近的可点击祖先。 */
internal fun clickablePath(root: UiNode, path: List<Int>): List<Int> {
    var best = path
    var node: UiNode? = root
    val chain = mutableListOf(root)
    for (index in path) { node = node?.children?.getOrNull(index) ?: break; chain += node }
    for (depth in chain.indices.reversed()) if (chain[depth].clickable) { best = path.take(depth); break }
    return best
}

/**
 * 按文字识别智联招聘界面。与 BOSS 一样，候选文字来自常见布局，必须用真机诊断导出核对；
 * 识别不到时归为 OTHER，由状态机超时停止。职位卡片沿用 BossScreenReader.parseCard。
 */
object ZhaopinScreenReader {
    private val verifyWords = listOf("安全验证", "滑动验证", "拖动滑块", "请完成验证", "人机验证", "向右滑动", "请依次点击")
    private val loginWords = listOf("手机号登录", "验证码登录", "一键登录", "密码登录", "登录/注册")
    private val limitWords = listOf("今日投递已达上限", "投递次数已用完", "申请次数已用完")
    private val doneWords = listOf("投递成功", "申请成功", "已成功投递", "简历已投递")
    private val pickerTitles = listOf("选择简历", "请选择简历", "选择投递简历", "选择要投递的简历")
    private val applyLabels = listOf("申请职位", "投递简历", "立即申请", "立即投递")
    private val appliedLabels = listOf("已申请", "已投递")
    private val confirmLabels = listOf("确认投递", "确定投递", "确认申请", "确定申请")
    private val buttonLabels = setOf("确定", "确认", "取消", "关闭") + confirmLabels

    fun read(root: UiNode, now: Long): BossReading {
        val nodes = root.walk().toList()
        val labels = nodes.map { it.label.trim() }.filter { it.isNotBlank() }
        fun obs(screen: BossScreen, cards: List<JobCard> = emptyList(), applied: Boolean = false) = BossObservation(screen, cards, applied, now)
        if (labels.any { label -> verifyWords.any { label.contains(it) } }) return BossReading(obs(BossScreen.VERIFY))
        if (labels.any { label -> loginWords.any { label.contains(it) } }) return BossReading(obs(BossScreen.LOGIN))
        if (labels.any { label -> limitWords.any { label.contains(it) } || (label.contains("上限") && (label.contains("投递") || label.contains("申请"))) }) {
            return BossReading(obs(BossScreen.LIMIT_REACHED))
        }
        if (labels.any { label -> doneWords.any { label.contains(it) } }) return BossReading(obs(BossScreen.APPLY_DONE))
        val confirm = nodes.firstOrNull { it.label.trim() in confirmLabels } ?: nodes.firstOrNull { it.label.trim() == "确定" || it.label.trim() == "确认" }
        if (labels.any { it in pickerTitles }) {
            // 标题和按钮以外的文字都作为候选；简历由状态机整名比对，这里不猜哪一行是简历。
            val options = nodes.filter { it.label.isNotBlank() && it.label.trim() !in pickerTitles && it.label.trim() !in buttonLabels }
            return BossReading(obs(BossScreen.RESUME_PICKER).copy(resumeOptions = options.map { it.label.trim() }, hasConfirm = confirm != null),
                resumeOptionPaths = options.map { clickablePath(root, it.path) }, confirmPath = confirm?.let { clickablePath(root, it.path) })
        }
        if (confirm != null && (confirm.label.trim() in confirmLabels || labels.any { it.contains("投递") && (it.contains("确定") || it.contains("是否") || it.contains("吗")) })) {
            return BossReading(obs(BossScreen.RESUME_CONFIRM).copy(resumeOptions = labels.filter { it !in buttonLabels }, hasConfirm = true),
                confirmPath = clickablePath(root, confirm.path))
        }
        val applied = nodes.firstOrNull { it.label.trim() in appliedLabels }
        val apply = nodes.firstOrNull { it.label.trim() in applyLabels }
        if (applied != null || apply != null) {
            return BossReading(obs(BossScreen.JOB_DETAIL, applied = applied != null), contactPath = apply?.let { clickablePath(root, it.path) })
        }
        val list = nodes.filter { it.scrollable }.maxByOrNull { it.children.size }
        if (list != null) {
            val parsed = list.children.mapNotNull { child -> BossScreenReader.parseCard(child)?.let { child.path to it } }
            if (parsed.isNotEmpty()) return BossReading(obs(BossScreen.JOB_LIST, parsed.map { it.second }), cardPaths = parsed.map { it.first }, listPath = list.path)
        }
        return BossReading(obs(BossScreen.OTHER))
    }
}

object PlatformReader {
    fun read(platform: JobPlatform, root: UiNode, now: Long): BossReading =
        if (platform == JobPlatform.ZHAOPIN) ZhaopinScreenReader.read(root, now) else BossScreenReader.read(root, now)
}

enum class AutoApplyMode { DRY_RUN, LIVE }
enum class AutoApplyPhase { SCANNING, OPENING, CONTACTING, SENDING, RETURNING, STOPPED }

sealed interface AutoApplyAction {
    data class OpenCard(val index: Int) : AutoApplyAction
    data object TapContact : AutoApplyAction
    data class SendGreeting(val text: String) : AutoApplyAction
    data object Back : AutoApplyAction
    data object ScrollList : AutoApplyAction
    data object Wait : AutoApplyAction
}

data class AutoApplyPreview(val card: JobCard, val decision: AutoApplyDecision)

data class AutoApplyRun(
    val mode: AutoApplyMode,
    val phase: AutoApplyPhase = AutoApplyPhase.SCANNING,
    val phaseSince: Long,
    val target: JobCard? = null,
    val seen: Set<String> = emptySet(),
    val idleScrolls: Int = 0,
    val backs: Int = 0,
    val contacted: Int = 0,
    val stopReason: String? = null,
    val preview: List<AutoApplyPreview> = emptyList()
)

data class AutoApplyStep(val run: AutoApplyRun, val action: AutoApplyAction = AutoApplyAction.Wait, val record: AutoApplyRecord? = null, val log: String? = null)

object AutoApplyEngine {
    const val STEP_TIMEOUT_MS = 20_000L
    const val MAX_IDLE_SCROLLS = 3
    const val MAX_BACKS = 3

    fun start(mode: AutoApplyMode, now: Long) = AutoApplyRun(mode, phaseSince = now)

    fun stop(run: AutoApplyRun, reason: String, now: Long): AutoApplyRun =
        if (run.phase == AutoApplyPhase.STOPPED) run else run.copy(phase = AutoApplyPhase.STOPPED, phaseSince = now, stopReason = reason)

    /** 暂停后继续：扫描中原地继续；处于职位中间则先返回列表重新扫描。 */
    fun resume(run: AutoApplyRun, now: Long): AutoApplyRun = when (run.phase) {
        AutoApplyPhase.STOPPED -> run
        AutoApplyPhase.SCANNING -> run.copy(phaseSince = now, idleScrolls = 0)
        else -> run.copy(phase = AutoApplyPhase.RETURNING, phaseSince = now, target = null, backs = 0)
    }

    fun step(run: AutoApplyRun, obs: BossObservation, settings: AutoApplySettings, records: List<AutoApplyRecord>, zone: ZoneId, newId: () -> String): AutoApplyStep {
        if (run.phase == AutoApplyPhase.STOPPED) return AutoApplyStep(run)
        val now = obs.now
        fun halt(reason: String) = AutoApplyStep(stop(run, reason, now), log = reason)
        when (obs.screen) {
            BossScreen.VERIFY -> return halt("出现安全验证，已停止；请手动处理后再开始")
            BossScreen.LOGIN -> return halt("BOSS 需要登录，已停止")
            BossScreen.LIMIT_REACHED -> return halt("BOSS 提示沟通次数已达上限，已停止")
            else -> Unit
        }
        fun waitOrTimeout() = if (now - run.phaseSince > STEP_TIMEOUT_MS) halt("界面未按预期变化，已停止；BOSS 界面可能已更新，需要重新校准") else AutoApplyStep(run)
        fun record(result: AutoApplyResult, note: String = ""): AutoApplyRecord? = run.target?.let {
            AutoApplyRecord(newId(), AutoApplyRules.jobKey(it), it.company, it.position, it.salary, now, result, note, AutoApplyRules.activePresetName(settings))
        }
        fun returning(contacted: Int = run.contacted) = run.copy(phase = AutoApplyPhase.RETURNING, phaseSince = now, backs = 0, contacted = contacted)
        val name = run.target?.let { "「${it.position} · ${it.company}」" }.orEmpty()
        return when (run.phase) {
            AutoApplyPhase.SCANNING -> scan(run, obs, settings, records, zone)
            AutoApplyPhase.OPENING -> when (obs.screen) {
                BossScreen.JOB_DETAIL -> if (obs.alreadyContacted) {
                    AutoApplyStep(returning(), AutoApplyAction.Back, record(AutoApplyResult.ALREADY_CONTACTED), "已沟通过，跳过$name")
                } else AutoApplyStep(run.copy(phase = AutoApplyPhase.CONTACTING, phaseSince = now), AutoApplyAction.TapContact)
                else -> waitOrTimeout()
            }
            AutoApplyPhase.CONTACTING -> when (obs.screen) {
                BossScreen.CHAT -> if (settings.greeting.isBlank()) {
                    AutoApplyStep(returning(run.contacted + 1), AutoApplyAction.Back, record(AutoApplyResult.CONTACTED, "BOSS 默认招呼语"), "已发起沟通$name")
                } else AutoApplyStep(run.copy(phase = AutoApplyPhase.SENDING, phaseSince = now), AutoApplyAction.SendGreeting(settings.greeting))
                else -> waitOrTimeout()
            }
            AutoApplyPhase.SENDING -> when (obs.screen) {
                BossScreen.CHAT -> AutoApplyStep(returning(run.contacted + 1), AutoApplyAction.Back, record(AutoApplyResult.CONTACTED, "自定义招呼语"), "已发起沟通$name")
                else -> waitOrTimeout()
            }
            AutoApplyPhase.RETURNING -> when (obs.screen) {
                BossScreen.JOB_LIST -> scan(run.copy(phase = AutoApplyPhase.SCANNING, phaseSince = now, target = null, backs = 0), obs, settings, records, zone)
                else -> if (run.backs >= MAX_BACKS) halt("无法返回职位列表，已停止") else AutoApplyStep(run.copy(backs = run.backs + 1), AutoApplyAction.Back)
            }
            AutoApplyPhase.STOPPED -> AutoApplyStep(run)
        }
    }

    /** 服务执行动作失败（找不到节点、点击无效）时调用。 */
    fun fail(run: AutoApplyRun, reason: String, now: Long, newId: () -> String): AutoApplyStep {
        val record = run.target?.takeIf { run.mode == AutoApplyMode.LIVE }?.let {
            AutoApplyRecord(newId(), AutoApplyRules.jobKey(it), it.company, it.position, it.salary, now, AutoApplyResult.FAILED, reason.take(200))
        }
        return AutoApplyStep(stop(run, reason, now), record = record, log = reason)
    }

    private fun scan(run: AutoApplyRun, obs: BossObservation, settings: AutoApplySettings, records: List<AutoApplyRecord>, zone: ZoneId): AutoApplyStep {
        val now = obs.now
        if (obs.screen != BossScreen.JOB_LIST) {
            return if (now - run.phaseSince > STEP_TIMEOUT_MS) AutoApplyStep(stop(run, "没有识别到职位列表，已停止；请打开 BOSS 的职位推荐列表后再开始", now), log = "没有识别到职位列表")
            else AutoApplyStep(run)
        }
        val day = AutoApplyRules.localDay(now, zone)
        val decisions = obs.cards.map { AutoApplyPreview(it, AutoApplyRules.decide(it, settings, records, day, zone)) }
        if (run.mode == AutoApplyMode.DRY_RUN) {
            val applying = decisions.count { it.decision == AutoApplyDecision.Apply }
            val reason = "试运行完成：识别 ${decisions.size} 个职位，其中 $applying 个符合条件；未点击任何按钮"
            return AutoApplyStep(stop(run, reason, now).copy(preview = decisions), log = reason)
        }
        val used = AutoApplyRules.contactedOn(records, day, zone)
        if (used >= settings.dailyLimit) return AutoApplyStep(stop(run, "已达到今日上限 ${settings.dailyLimit} 条，已停止", now), log = "已达到今日上限")
        val fresh = obs.cards.withIndex().filter { AutoApplyRules.jobKey(it.value) !in run.seen }
        val seen = run.seen + fresh.map { AutoApplyRules.jobKey(it.value) }
        val pick = fresh.firstOrNull { decisions[it.index].decision == AutoApplyDecision.Apply }
        if (pick != null) {
            return AutoApplyStep(run.copy(phase = AutoApplyPhase.OPENING, phaseSince = now, target = pick.value, seen = seen, idleScrolls = 0),
                AutoApplyAction.OpenCard(pick.index), log = "打开「${pick.value.position} · ${pick.value.company}」")
        }
        val idle = if (fresh.isEmpty()) run.idleScrolls + 1 else 0
        if (idle >= MAX_IDLE_SCROLLS) return AutoApplyStep(stop(run, "列表没有更多新职位，已停止", now), log = "列表没有更多新职位")
        val skipped = fresh.size
        return AutoApplyStep(run.copy(seen = seen, idleScrolls = idle, phaseSince = now), AutoApplyAction.ScrollList,
            log = if (skipped > 0) "跳过 $skipped 个不符合条件的职位" else null)
    }
}

/**
 * 自动发简历：BOSS 只允许对方回复后发附件简历。只处理本应用自动发起过沟通、会话有未读角标（对方有新消息）的公司；
 * 不读消息内容。按岗位关键词选简历名称，在选择列表或确认框里核对到同名附件才点确定；每个职位只发一次。
 */
enum class ResumePhase { FINDING, OPENING_CHAT, PICKING, CONFIRMING, SUBMITTING, RETURNING, STOPPED }

sealed interface ResumeAction {
    data object OpenMessages : ResumeAction
    data class OpenConversation(val index: Int) : ResumeAction
    data object TapResumeButton : ResumeAction
    data class PickResume(val index: Int) : ResumeAction
    data object Confirm : ResumeAction
    data object Back : ResumeAction
    data object ScrollList : ResumeAction
    data object Wait : ResumeAction
}

/** 试运行清单：resumeName 为空表示会跳过，reason 写原因。 */
data class ResumePreview(val company: String, val position: String, val resumeName: String?, val reason: String? = null)

data class ResumeRun(
    val mode: AutoApplyMode,
    val phase: ResumePhase = ResumePhase.FINDING,
    val phaseSince: Long,
    val company: String = "",
    val target: AutoApplyRecord? = null,
    val resumeName: String = "",
    val seen: Set<String> = emptySet(),
    val idleScrolls: Int = 0,
    val backs: Int = 0,
    val tabTapped: Boolean = false,
    val sent: Int = 0,
    /** 试运行确实识别到了消息列表，可据此记录校准版本。 */
    val listRecognized: Boolean = false,
    val stopReason: String? = null,
    val preview: List<ResumePreview> = emptyList()
)

data class ResumeStep(val run: ResumeRun, val action: ResumeAction = ResumeAction.Wait, val record: AutoApplyRecord? = null, val log: String? = null)

object ResumeEngine {
    fun start(mode: AutoApplyMode, now: Long) = ResumeRun(mode, phaseSince = now)

    fun stop(run: ResumeRun, reason: String, now: Long): ResumeRun =
        if (run.phase == ResumePhase.STOPPED) run else run.copy(phase = ResumePhase.STOPPED, phaseSince = now, stopReason = reason)

    fun fail(run: ResumeRun, reason: String, now: Long) = ResumeStep(stop(run, reason, now), log = reason)

    /**
     * 暂停后继续：查找中原地继续；其余阶段先返回消息列表。确认或提交时被暂停的公司本次不再处理，
     * 因为无法确定暂停前是否已经发出，宁可漏发也不重复发。
     */
    fun resume(run: ResumeRun, now: Long): ResumeStep {
        val uncertain = run.phase == ResumePhase.CONFIRMING || run.phase == ResumePhase.SUBMITTING
        return when (run.phase) {
            ResumePhase.STOPPED -> ResumeStep(run)
            ResumePhase.FINDING -> ResumeStep(run.copy(phaseSince = now, idleScrolls = 0))
            else -> ResumeStep(run.copy(phase = ResumePhase.RETURNING, phaseSince = now, company = "", target = null, resumeName = "", backs = 0),
                log = if (uncertain) "暂停前可能已发送给「${run.company}」，本次不再处理，请到 BOSS 核对" else null)
        }
    }

    fun step(run: ResumeRun, obs: BossObservation, settings: AutoApplySettings, records: List<AutoApplyRecord>, zone: ZoneId, newId: () -> String): ResumeStep {
        if (run.phase == ResumePhase.STOPPED) return ResumeStep(run)
        val now = obs.now
        fun halt(reason: String) = ResumeStep(stop(run, reason, now), log = reason)
        when (obs.screen) {
            BossScreen.VERIFY -> return halt("出现安全验证，已停止；请手动处理后再开始")
            BossScreen.LOGIN -> return halt("BOSS 需要登录，已停止")
            BossScreen.LIMIT_REACHED -> return halt("BOSS 提示次数已达上限，已停止")
            else -> Unit
        }
        fun waitOrTimeout() = if (now - run.phaseSince > AutoApplyEngine.STEP_TIMEOUT_MS) halt("界面未按预期变化，已停止；BOSS 界面可能已更新，需要重新校准") else ResumeStep(run)
        fun returning(log: String?, record: AutoApplyRecord? = null, sent: Int = run.sent) =
            ResumeStep(run.copy(phase = ResumePhase.RETURNING, phaseSince = now, backs = 0, sent = sent), ResumeAction.Back, record, log)
        val name = run.target?.let { "「${it.position} · ${it.company}」" }.orEmpty()
        fun sentRecord(note: String) = run.target!!.let {
            AutoApplyRecord(newId(), it.jobKey, it.company, it.position, it.salary, now, AutoApplyResult.RESUME_SENT, note.take(200))
        }
        fun confirmMatches(): Boolean = obs.resumeOptions.any { AutoApplyRules.resumeNameMatches(it, run.resumeName) }
        return when (run.phase) {
            ResumePhase.FINDING -> find(run, obs, settings, records, zone)
            ResumePhase.OPENING_CHAT -> when (obs.screen) {
                BossScreen.CHAT -> {
                    val target = AutoApplyRules.resumeTargets(run.company, obs.chatPosition, records).firstOrNull()
                        ?: return returning("聊天页岗位与沟通记录对不上，跳过「${run.company}」")
                    val resume = AutoApplyRules.pickResume(target.position, settings.resumes)
                        ?: return returning("没有匹配「${target.position}」的简历，也没有默认简历，跳过「${run.company}」")
                    if (!obs.hasResumeButton) return halt("聊天页没有找到「发简历」按钮，已停止；BOSS 界面可能已更新")
                    ResumeStep(run.copy(phase = ResumePhase.PICKING, phaseSince = now, target = target, resumeName = resume), ResumeAction.TapResumeButton,
                        log = "准备发送简历「$resume」给「${target.position} · ${target.company}」")
                }
                else -> waitOrTimeout()
            }
            ResumePhase.PICKING -> when (obs.screen) {
                BossScreen.RESUME_PICKER -> {
                    val index = obs.resumeOptions.indexOfFirst { AutoApplyRules.resumeNameMatches(it, run.resumeName) }
                    if (index < 0) halt("BOSS 里没有名为「${run.resumeName}」的附件简历，已停止；请核对「我的简历」里的名称")
                    else ResumeStep(run.copy(phase = ResumePhase.CONFIRMING, phaseSince = now), ResumeAction.PickResume(index))
                }
                // 只有一份附件时 BOSS 可能直接弹确认框，确认框里必须能核对到同名附件。
                BossScreen.RESUME_CONFIRM -> if (confirmMatches()) ResumeStep(run.copy(phase = ResumePhase.SUBMITTING, phaseSince = now), ResumeAction.Confirm)
                    else halt("确认框里的简历不是「${run.resumeName}」或无法核对名称，已停止，未发送")
                else -> waitOrTimeout()
            }
            ResumePhase.CONFIRMING -> when (obs.screen) {
                BossScreen.RESUME_PICKER -> if (obs.hasConfirm) ResumeStep(run.copy(phase = ResumePhase.SUBMITTING, phaseSince = now), ResumeAction.Confirm) else waitOrTimeout()
                BossScreen.RESUME_CONFIRM -> if (confirmMatches() || obs.resumeOptions.isEmpty()) ResumeStep(run.copy(phase = ResumePhase.SUBMITTING, phaseSince = now), ResumeAction.Confirm)
                    else halt("确认框里的简历不是「${run.resumeName}」，已停止，未发送")
                // 部分版本点选附件后直接发送并回到聊天页；记为已发送，避免下次重复发。
                BossScreen.CHAT -> returning("已发送简历$name", sentRecord("简历：${run.resumeName}（选择后直接发送）"), run.sent + 1)
                else -> waitOrTimeout()
            }
            ResumePhase.SUBMITTING -> when (obs.screen) {
                BossScreen.CHAT -> returning("已发送简历$name", sentRecord("简历：${run.resumeName}"), run.sent + 1)
                else -> waitOrTimeout()
            }
            ResumePhase.RETURNING -> when (obs.screen) {
                BossScreen.MESSAGE_LIST -> find(run.copy(phase = ResumePhase.FINDING, phaseSince = now, company = "", target = null, resumeName = "", backs = 0), obs, settings, records, zone)
                else -> if (run.backs >= AutoApplyEngine.MAX_BACKS) halt("无法返回消息列表，已停止") else ResumeStep(run.copy(backs = run.backs + 1), ResumeAction.Back)
            }
            ResumePhase.STOPPED -> ResumeStep(run)
        }
    }

    private fun find(run: ResumeRun, obs: BossObservation, settings: AutoApplySettings, records: List<AutoApplyRecord>, zone: ZoneId): ResumeStep {
        val now = obs.now
        if (obs.screen != BossScreen.MESSAGE_LIST) {
            if (run.mode == AutoApplyMode.LIVE && obs.hasMessagesTab && !run.tabTapped) {
                return ResumeStep(run.copy(tabTapped = true, phaseSince = now), ResumeAction.OpenMessages, log = "打开消息列表")
            }
            return if (now - run.phaseSince > AutoApplyEngine.STEP_TIMEOUT_MS) ResumeStep(stop(run, "没有识别到消息列表，已停止；请打开 BOSS 的「消息」页后再开始", now), log = "没有识别到消息列表")
            else ResumeStep(run)
        }
        // 对方有新消息、且是本应用自动沟通过的公司，才视为 HR 已回复。
        val candidates = obs.conversations.withIndex().mapNotNull { (index, chat) ->
            val target = if (chat.unread) AutoApplyRules.resumeTargets(chat.company, "", records).firstOrNull() else null
            target?.let { Triple(index, chat, it) }
        }
        if (run.mode == AutoApplyMode.DRY_RUN) {
            val preview = candidates.map { (_, chat, target) ->
                val resume = AutoApplyRules.pickResume(target.position, settings.resumes)
                ResumePreview(chat.company, target.position, resume, if (resume == null) "没有匹配的简历" else null)
            }
            val reason = "发简历试运行完成：识别 ${obs.conversations.size} 个会话，其中 ${preview.count { it.resumeName != null }} 个会发简历；未点击任何按钮"
            return ResumeStep(stop(run, reason, now).copy(preview = preview, listRecognized = true), log = reason)
        }
        val day = AutoApplyRules.localDay(now, zone)
        if (AutoApplyRules.resumesSentOn(records, day, zone) >= settings.dailyLimit) {
            return ResumeStep(stop(run, "已达到今日发简历上限 ${settings.dailyLimit} 份，已停止", now), log = "已达到今日发简历上限")
        }
        val fresh = candidates.filter { it.second.company.trim().lowercase() !in run.seen }
        val seen = run.seen + fresh.map { it.second.company.trim().lowercase() }
        val skipped = fresh.filter { AutoApplyRules.pickResume(it.third.position, settings.resumes) == null }
        val pick = fresh.firstOrNull { it !in skipped }
        val skipLog = skipped.takeIf { it.isNotEmpty() }?.joinToString("、", "没有匹配的简历，跳过：") { "「${it.third.position} · ${it.second.company}」" }
        if (pick != null) {
            return ResumeStep(run.copy(phase = ResumePhase.OPENING_CHAT, phaseSince = now, company = pick.second.company, seen = seen, idleScrolls = 0),
                ResumeAction.OpenConversation(pick.first), log = listOfNotNull(skipLog, "打开与「${pick.second.company}」的会话").joinToString("；"))
        }
        val idle = if (fresh.isEmpty()) run.idleScrolls + 1 else 0
        if (idle >= AutoApplyEngine.MAX_IDLE_SCROLLS) return ResumeStep(stop(run, "没有更多待发简历的新回复，已停止", now), log = skipLog ?: "没有更多待发简历的新回复")
        return ResumeStep(run.copy(seen = seen, idleScrolls = idle, phaseSince = now), ResumeAction.ScrollList, log = skipLog)
    }
}

/**
 * 智联自动投递：职位列表 → 详情 → 点「申请职位」→（弹出时）按名称选简历 → 确认 → 看到成功或按钮变为「已申请」→ 返回。
 * 投递即附带简历，不做回复后发简历，不读消息页。弹出简历选择时核对不到同名简历就停止，不发送。
 */
enum class ZhaopinPhase { SCANNING, OPENING, APPLYING, PICKING, SUBMITTING, RETURNING, STOPPED }

sealed interface ZhaopinAction {
    data class OpenCard(val index: Int) : ZhaopinAction
    data object TapApply : ZhaopinAction
    data class PickResume(val index: Int) : ZhaopinAction
    data object Confirm : ZhaopinAction
    data object Back : ZhaopinAction
    data object ScrollList : ZhaopinAction
    data object Wait : ZhaopinAction
}

data class ZhaopinRun(
    val mode: AutoApplyMode,
    val phase: ZhaopinPhase = ZhaopinPhase.SCANNING,
    val phaseSince: Long,
    val target: JobCard? = null,
    /** 为空表示没有匹配的简历；智联弹出简历选择时会因此停止。 */
    val resumeName: String = "",
    val seen: Set<String> = emptySet(),
    val idleScrolls: Int = 0,
    val backs: Int = 0,
    val applied: Int = 0,
    val stopReason: String? = null,
    val preview: List<AutoApplyPreview> = emptyList()
)

data class ZhaopinStep(val run: ZhaopinRun, val action: ZhaopinAction = ZhaopinAction.Wait, val record: AutoApplyRecord? = null, val log: String? = null)

object ZhaopinApplyEngine {
    fun start(mode: AutoApplyMode, now: Long) = ZhaopinRun(mode, phaseSince = now)

    fun stop(run: ZhaopinRun, reason: String, now: Long): ZhaopinRun =
        if (run.phase == ZhaopinPhase.STOPPED) run else run.copy(phase = ZhaopinPhase.STOPPED, phaseSince = now, stopReason = reason)

    /** 暂停后继续：扫描中原地继续；其余先回列表。点过投递后被暂停的职位已在 seen 中，本次不再处理，宁可漏投也不重复投。 */
    fun resume(run: ZhaopinRun, now: Long): ZhaopinStep {
        val uncertain = run.phase == ZhaopinPhase.APPLYING || run.phase == ZhaopinPhase.PICKING || run.phase == ZhaopinPhase.SUBMITTING
        val name = run.target?.let { "「${it.position} · ${it.company}」" }.orEmpty()
        return when (run.phase) {
            ZhaopinPhase.STOPPED -> ZhaopinStep(run)
            ZhaopinPhase.SCANNING -> ZhaopinStep(run.copy(phaseSince = now, idleScrolls = 0))
            else -> ZhaopinStep(run.copy(phase = ZhaopinPhase.RETURNING, phaseSince = now, target = null, resumeName = "", backs = 0),
                log = if (uncertain) "暂停前可能已投递$name，本次不再处理，请到智联核对" else null)
        }
    }

    fun step(run: ZhaopinRun, obs: BossObservation, settings: AutoApplySettings, records: List<AutoApplyRecord>, zone: ZoneId, newId: () -> String): ZhaopinStep {
        if (run.phase == ZhaopinPhase.STOPPED) return ZhaopinStep(run)
        val now = obs.now
        fun halt(reason: String) = ZhaopinStep(stop(run, reason, now), log = reason)
        when (obs.screen) {
            BossScreen.VERIFY -> return halt("出现安全验证，已停止；请手动处理后再开始")
            BossScreen.LOGIN -> return halt("智联需要登录，已停止")
            BossScreen.LIMIT_REACHED -> return halt("智联提示投递次数已达上限，已停止")
            else -> Unit
        }
        fun waitOrTimeout() = if (now - run.phaseSince > AutoApplyEngine.STEP_TIMEOUT_MS) halt("界面未按预期变化，已停止；智联界面可能已更新，需要重新校准") else ZhaopinStep(run)
        fun record(result: AutoApplyResult, note: String = ""): AutoApplyRecord? = run.target?.let {
            AutoApplyRecord(newId(), AutoApplyRules.jobKey(it), it.company, it.position, it.salary, now, result, note.take(200),
                AutoApplyRules.activePresetName(settings), JobPlatform.ZHAOPIN)
        }
        fun returning(applied: Int = run.applied) = run.copy(phase = ZhaopinPhase.RETURNING, phaseSince = now, backs = 0, applied = applied)
        val name = run.target?.let { "「${it.position} · ${it.company}」" }.orEmpty()
        fun done() = ZhaopinStep(returning(run.applied + 1), ZhaopinAction.Back,
            record(AutoApplyResult.APPLIED, if (run.resumeName.isBlank()) "智联默认简历" else "简历：${run.resumeName}"), "已投递$name")
        // 成功提示可能一闪而过，回到详情页且按钮变为「已申请」同样视为投递完成。
        fun finished() = obs.screen == BossScreen.APPLY_DONE || (obs.screen == BossScreen.JOB_DETAIL && obs.alreadyContacted)
        return when (run.phase) {
            ZhaopinPhase.SCANNING -> scan(run, obs, settings, records, zone)
            ZhaopinPhase.OPENING -> when (obs.screen) {
                BossScreen.JOB_DETAIL -> if (obs.alreadyContacted) {
                    ZhaopinStep(returning(), ZhaopinAction.Back, record(AutoApplyResult.ALREADY_CONTACTED), "已投递过，跳过$name")
                } else {
                    val resume = run.target?.let { AutoApplyRules.pickResume(it.position, settings.resumes) }.orEmpty()
                    ZhaopinStep(run.copy(phase = ZhaopinPhase.APPLYING, phaseSince = now, resumeName = resume), ZhaopinAction.TapApply)
                }
                else -> waitOrTimeout()
            }
            ZhaopinPhase.APPLYING -> when {
                finished() -> done()
                obs.screen == BossScreen.RESUME_PICKER -> {
                    if (run.resumeName.isBlank()) return halt("智联要求选择简历，但没有匹配$name 的简历，也没有默认简历；已停止，未投递")
                    val index = obs.resumeOptions.indexOfFirst { AutoApplyRules.resumeNameMatches(it, run.resumeName) }
                    if (index < 0) halt("智联里没有名为「${run.resumeName}」的简历，已停止，未投递；请核对「我的简历」里的名称")
                    else ZhaopinStep(run.copy(phase = ZhaopinPhase.PICKING, phaseSince = now), ZhaopinAction.PickResume(index))
                }
                obs.screen == BossScreen.RESUME_CONFIRM -> ZhaopinStep(run.copy(phase = ZhaopinPhase.SUBMITTING, phaseSince = now), ZhaopinAction.Confirm)
                else -> waitOrTimeout()
            }
            ZhaopinPhase.PICKING -> when {
                finished() -> done()
                obs.screen == BossScreen.RESUME_PICKER && obs.hasConfirm -> ZhaopinStep(run.copy(phase = ZhaopinPhase.SUBMITTING, phaseSince = now), ZhaopinAction.Confirm)
                obs.screen == BossScreen.RESUME_CONFIRM -> ZhaopinStep(run.copy(phase = ZhaopinPhase.SUBMITTING, phaseSince = now), ZhaopinAction.Confirm)
                else -> waitOrTimeout()
            }
            ZhaopinPhase.SUBMITTING -> if (finished()) done() else waitOrTimeout()
            ZhaopinPhase.RETURNING -> when (obs.screen) {
                BossScreen.JOB_LIST -> scan(run.copy(phase = ZhaopinPhase.SCANNING, phaseSince = now, target = null, resumeName = "", backs = 0), obs, settings, records, zone)
                else -> if (run.backs >= AutoApplyEngine.MAX_BACKS) halt("无法返回职位列表，已停止") else ZhaopinStep(run.copy(backs = run.backs + 1), ZhaopinAction.Back)
            }
            ZhaopinPhase.STOPPED -> ZhaopinStep(run)
        }
    }

    fun fail(run: ZhaopinRun, reason: String, now: Long, newId: () -> String): ZhaopinStep {
        val record = run.target?.takeIf { run.mode == AutoApplyMode.LIVE }?.let {
            AutoApplyRecord(newId(), AutoApplyRules.jobKey(it), it.company, it.position, it.salary, now, AutoApplyResult.FAILED, reason.take(200), platform = JobPlatform.ZHAOPIN)
        }
        return ZhaopinStep(stop(run, reason, now), record = record, log = reason)
    }

    private fun scan(run: ZhaopinRun, obs: BossObservation, settings: AutoApplySettings, records: List<AutoApplyRecord>, zone: ZoneId): ZhaopinStep {
        val now = obs.now
        if (obs.screen != BossScreen.JOB_LIST) {
            return if (now - run.phaseSince > AutoApplyEngine.STEP_TIMEOUT_MS) ZhaopinStep(stop(run, "没有识别到职位列表，已停止；请打开智联的职位推荐列表后再开始", now), log = "没有识别到职位列表")
            else ZhaopinStep(run)
        }
        val day = AutoApplyRules.localDay(now, zone)
        val decisions = obs.cards.map { AutoApplyPreview(it, AutoApplyRules.decide(it, settings, records, day, zone, JobPlatform.ZHAOPIN)) }
        if (run.mode == AutoApplyMode.DRY_RUN) {
            val applying = decisions.count { it.decision == AutoApplyDecision.Apply }
            val reason = "智联试运行完成：识别 ${decisions.size} 个职位，其中 $applying 个符合条件；未点击任何按钮"
            return ZhaopinStep(stop(run, reason, now).copy(preview = decisions), log = reason)
        }
        val limit = settings.zhaopinDailyLimit
        if (AutoApplyRules.contactedOn(records, day, zone, JobPlatform.ZHAOPIN) >= limit) {
            return ZhaopinStep(stop(run, "已达到智联今日上限 $limit 条，已停止", now), log = "已达到智联今日上限")
        }
        val fresh = obs.cards.withIndex().filter { AutoApplyRules.jobKey(it.value) !in run.seen }
        val seen = run.seen + fresh.map { AutoApplyRules.jobKey(it.value) }
        val pick = fresh.firstOrNull { decisions[it.index].decision == AutoApplyDecision.Apply }
        if (pick != null) {
            return ZhaopinStep(run.copy(phase = ZhaopinPhase.OPENING, phaseSince = now, target = pick.value, seen = seen, idleScrolls = 0),
                ZhaopinAction.OpenCard(pick.index), log = "打开「${pick.value.position} · ${pick.value.company}」")
        }
        val idle = if (fresh.isEmpty()) run.idleScrolls + 1 else 0
        if (idle >= AutoApplyEngine.MAX_IDLE_SCROLLS) return ZhaopinStep(stop(run, "列表没有更多新职位，已停止", now), log = "列表没有更多新职位")
        return ZhaopinStep(run.copy(seen = seen, idleScrolls = idle, phaseSince = now), ZhaopinAction.ScrollList,
            log = if (fresh.isNotEmpty()) "跳过 ${fresh.size} 个不符合条件的职位" else null)
    }
}

enum class AutoApplyEntryState { IDLE, RUNNING, PAUSED, STOPPED }

data class AutoApplyEntry(val state: AutoApplyEntryState, val label: String, val summary: String)
