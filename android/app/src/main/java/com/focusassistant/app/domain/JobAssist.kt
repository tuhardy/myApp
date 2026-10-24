package com.focusassistant.app.domain

/** 本地职位记录与用户手动准备的求职文案；不代表招聘平台上的实际状态。 */
enum class JobAssistStatus { DRAFT, COPIED, APPLIED_SELF_REPORTED, AUTO_CONTACTED, AUTO_RESUME_SENT }

data class JobOpportunity(
    val id: String,
    val company: String,
    val position: String,
    val description: String,
    val message: String,
    val status: JobAssistStatus = JobAssistStatus.DRAFT,
    val createdAt: Long,
    val updatedAt: Long
)

object JobAssistRules {
    const val MAX_COMPANY = 100
    const val MAX_POSITION = 120
    const val MAX_DESCRIPTION = 10_000
    const val MAX_MESSAGE = 3_000
    const val MAX_JOBS = 500

    fun validate(value: JobOpportunity) {
        require(value.id.isNotBlank() && value.id.length <= 128) { "职位标识无效" }
        require(value.company.isNotBlank() && value.company.length <= MAX_COMPANY) { "公司名称须为 1–$MAX_COMPANY 个字符" }
        require(value.position.isNotBlank() && value.position.length <= MAX_POSITION) { "岗位名称须为 1–$MAX_POSITION 个字符" }
        require(value.description.length <= MAX_DESCRIPTION) { "职位描述最多 $MAX_DESCRIPTION 个字符" }
        require(value.message.length <= MAX_MESSAGE) { "沟通文案最多 $MAX_MESSAGE 个字符" }
        require(value.createdAt in 0..Validation.MAX_TIMESTAMP_MS && value.updatedAt in value.createdAt..Validation.MAX_TIMESTAMP_MS) { "职位记录时间无效" }
    }

    fun validateAll(values: List<JobOpportunity>) {
        require(values.size <= MAX_JOBS) { "最多保存 $MAX_JOBS 个职位记录" }
        require(values.map { it.id }.toSet().size == values.size) { "职位标识重复" }
        values.forEach(::validate)
    }

    fun markCopied(value: JobOpportunity, now: Long): JobOpportunity {
        require(value.message.isNotBlank()) { "请先填写求职沟通文案" }
        require(now in value.createdAt..Validation.MAX_TIMESTAMP_MS) { "操作时间无效" }
        return value.copy(status = JobAssistStatus.COPIED, updatedAt = now)
    }

    /** 仅表示用户自行记录已投递，应用无法核验招聘平台结果。 */
    fun markAppliedByUser(value: JobOpportunity, now: Long): JobOpportunity {
        require(now in value.createdAt..Validation.MAX_TIMESTAMP_MS) { "操作时间无效" }
        return value.copy(status = JobAssistStatus.APPLIED_SELF_REPORTED, updatedAt = now)
    }

    /** 自动投递看到聊天页或发完简历后，同步同公司同岗位的本地草稿；只代表界面上发出，不代表 HR 已读或投递成功。 */
    fun markAutoContacted(values: List<JobOpportunity>, record: AutoApplyRecord): List<JobOpportunity> {
        val status = when (record.result) {
            AutoApplyResult.CONTACTED -> JobAssistStatus.AUTO_CONTACTED
            AutoApplyResult.RESUME_SENT -> JobAssistStatus.AUTO_RESUME_SENT
            else -> return values
        }
        return values.map {
            val same = it.company.trim().equals(record.company.trim(), ignoreCase = true) && it.position.trim().equals(record.position.trim(), ignoreCase = true)
            // 已发简历的草稿不因之后的沟通记录退回“已发起沟通”。
            if (same && !(status == JobAssistStatus.AUTO_CONTACTED && it.status == JobAssistStatus.AUTO_RESUME_SENT))
                it.copy(status = status, updatedAt = maxOf(it.updatedAt, record.at)) else it
        }
    }

    fun statusLabel(status: JobAssistStatus): String = when (status) {
        JobAssistStatus.DRAFT -> "准备中"
        JobAssistStatus.COPIED -> "文案已复制 · 待本人处理"
        JobAssistStatus.APPLIED_SELF_REPORTED -> "本人记录已投递 · 未核验"
        JobAssistStatus.AUTO_CONTACTED -> "自动化已发起沟通"
        JobAssistStatus.AUTO_RESUME_SENT -> "自动化已发送简历"
    }
}
