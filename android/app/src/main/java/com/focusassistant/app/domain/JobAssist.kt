package com.focusassistant.app.domain

/** 本地职位记录与用户手动准备的求职文案；不代表招聘平台上的实际状态。 */
/** AUTO_APPLIED：智联投递，投递即附带简历，阶段与已发简历相同。 */
enum class JobAssistStatus { DRAFT, COPIED, APPLIED_SELF_REPORTED, AUTO_CONTACTED, AUTO_RESUME_SENT, AUTO_APPLIED }

/** 用户手动标记的结果；与自动推进的 status 分开存，自动记录不会覆盖，置空即回到自动阶段。 */
enum class JobOutcome(val label: String) { INTERVIEW("面试"), OFFER("Offer"), CLOSED("不合适") }

/** 进度看板阶段，与浏览器 JOB_STAGES 同序同名。 */
enum class JobStage(val label: String) { PREP("准备中"), CONTACTED("已沟通"), RESUME("已发简历"), INTERVIEW("面试"), OFFER("Offer"), CLOSED("不合适") }

enum class JobSource { MANUAL, AUTO }

data class JobOpportunity(
    val id: String,
    val company: String,
    val position: String,
    val description: String,
    val message: String,
    val status: JobAssistStatus = JobAssistStatus.DRAFT,
    val createdAt: Long,
    val updatedAt: Long,
    val note: String = "",
    val salary: String = "",
    val source: JobSource = JobSource.MANUAL,
    /** 自动建档时所用筛选方案的名称。 */
    val preset: String = "",
    val outcome: JobOutcome? = null,
    /** 自动建档的来源平台；手动职位为空。 */
    val platform: JobPlatform? = null
)

object JobAssistRules {
    const val MAX_COMPANY = 100
    const val MAX_POSITION = 120
    const val MAX_DESCRIPTION = 10_000
    const val MAX_MESSAGE = 3_000
    const val MAX_JOBS = 500
    const val MAX_NOTE = 200
    const val MAX_SALARY = 40

    fun validate(value: JobOpportunity) {
        require(value.id.isNotBlank() && value.id.length <= 128) { "职位标识无效" }
        require(value.company.isNotBlank() && value.company.length <= MAX_COMPANY) { "公司名称须为 1–$MAX_COMPANY 个字符" }
        require(value.position.isNotBlank() && value.position.length <= MAX_POSITION) { "岗位名称须为 1–$MAX_POSITION 个字符" }
        require(value.description.length <= MAX_DESCRIPTION) { "职位描述最多 $MAX_DESCRIPTION 个字符" }
        require(value.message.length <= MAX_MESSAGE) { "沟通文案最多 $MAX_MESSAGE 个字符" }
        require(value.note.length <= MAX_NOTE) { "备注最多 $MAX_NOTE 个字符" }
        require(value.salary.length <= MAX_SALARY && value.preset.length <= AutoApplyRules.MAX_PRESET_NAME) { "职位信息过长" }
        require(value.createdAt in 0..Validation.MAX_TIMESTAMP_MS && value.updatedAt in value.createdAt..Validation.MAX_TIMESTAMP_MS) { "职位记录时间无效" }
    }

    fun validateAll(values: List<JobOpportunity>) {
        require(values.size <= MAX_JOBS) { "最多保存 $MAX_JOBS 个职位记录" }
        require(values.map { it.id }.toSet().size == values.size) { "职位标识重复" }
        values.forEach(::validate)
    }

    /** 手动结果优先，否则按自动推进的 status 归档；与浏览器 jobStage 同口径。 */
    fun stage(value: JobOpportunity): JobStage = when (value.outcome) {
        JobOutcome.INTERVIEW -> JobStage.INTERVIEW
        JobOutcome.OFFER -> JobStage.OFFER
        JobOutcome.CLOSED -> JobStage.CLOSED
        null -> when (value.status) {
            JobAssistStatus.AUTO_RESUME_SENT, JobAssistStatus.AUTO_APPLIED -> JobStage.RESUME
            JobAssistStatus.AUTO_CONTACTED, JobAssistStatus.APPLIED_SELF_REPORTED -> JobStage.CONTACTED
            JobAssistStatus.DRAFT, JobAssistStatus.COPIED -> JobStage.PREP
        }
    }

    /** outcome 为 null 时清除手动结果。 */
    fun setOutcome(value: JobOpportunity, outcome: JobOutcome?, note: String, now: Long): JobOpportunity =
        value.copy(outcome = outcome, note = note.trim(), updatedAt = maxOf(value.updatedAt, now)).also(::validate)

    private fun sameJob(job: JobOpportunity, record: AutoApplyRecord) =
        job.company.trim().equals(record.company.trim(), ignoreCase = true) && job.position.trim().equals(record.position.trim(), ignoreCase = true)

    /**
     * 自动记录同步进度：已有同公司同岗位的职位就推进 status（已发简历不退回已沟通），没有则新建 source=AUTO 的职位。
     * 只代表界面上发出，不代表 HR 已读或投递成功；手动结果 outcome 不受影响。职位已满 MAX_JOBS 时不新建。
     */
    fun upsertFromRecord(values: List<JobOpportunity>, record: AutoApplyRecord, newId: String): List<JobOpportunity> {
        val status = when (record.result) {
            AutoApplyResult.CONTACTED -> JobAssistStatus.AUTO_CONTACTED
            AutoApplyResult.RESUME_SENT -> JobAssistStatus.AUTO_RESUME_SENT
            AutoApplyResult.APPLIED -> JobAssistStatus.AUTO_APPLIED
            else -> return values
        }
        if (values.any { sameJob(it, record) }) return values.map {
            if (sameJob(it, record) && !(status == JobAssistStatus.AUTO_CONTACTED && stage(it.copy(outcome = null)) == JobStage.RESUME))
                it.copy(status = status, updatedAt = maxOf(it.updatedAt, record.at)) else it
        }
        if (values.size >= MAX_JOBS) return values
        val created = JobOpportunity(newId, record.company.trim().take(MAX_COMPANY), record.position.trim().take(MAX_POSITION), "", "", status, record.at, record.at,
            salary = record.salary.trim().take(MAX_SALARY), source = JobSource.AUTO, preset = record.preset, platform = record.platform)
        return listOf(created) + values
    }

    fun statusLabel(status: JobAssistStatus): String = when (status) {
        JobAssistStatus.DRAFT -> "准备中"
        JobAssistStatus.COPIED -> "文案已复制 · 待本人处理"
        JobAssistStatus.APPLIED_SELF_REPORTED -> "已沟通 · 本人记录"
        JobAssistStatus.AUTO_CONTACTED -> "自动化已发起沟通"
        JobAssistStatus.AUTO_RESUME_SENT -> "自动化已发送简历"
        JobAssistStatus.AUTO_APPLIED -> "自动化已投递（智联）"
    }

    /** 卡片上的阶段文字：有手动结果时显示结果，否则显示自动状态。 */
    fun displayLabel(value: JobOpportunity): String = value.outcome?.label ?: statusLabel(value.status)
}
