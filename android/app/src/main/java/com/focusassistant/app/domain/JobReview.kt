package com.focusassistant.app.domain

import java.time.LocalDate
import java.time.ZoneId

data class JobFunnel(val contacted: Int, val resumes: Int, val interviews: Int, val offers: Int, val closed: Int)

data class PresetReview(val preset: String, val contacted: Int, val resumes: Int)

data class JobReviewSummary(val days: List<LocalDate>, val funnel: JobFunnel, val dailyContacted: List<Int>, val byPreset: List<PresetReview>)

/**
 * 求职复盘，与浏览器 jobReviewSummary 同口径：近 days 天（含今天）的自动沟通记录。
 * 漏斗以职位为单位：沟通 → 其中已发简历 → 其中手动标为面试/Offer；已发简历只说明对方回复过，不等于 HR 已查看。
 */
object JobReview {
    fun summary(records: List<AutoApplyRecord>, jobs: List<JobOpportunity>, today: LocalDate, days: Int, zone: ZoneId): JobReviewSummary {
        val keys = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val range = keys.toSet()
        val contacted = records.filter { it.result == AutoApplyResult.CONTACTED && AutoApplyRules.localDay(it.at, zone) in range }.sortedBy { it.at }
        val sent = records.filter { it.result == AutoApplyResult.RESUME_SENT }.map { it.jobKey }.toSet()
        val first = contacted.distinctBy { it.jobKey }
        val stages = first.map { record ->
            jobs.find { it.company.trim().equals(record.company.trim(), ignoreCase = true) && it.position.trim().equals(record.position.trim(), ignoreCase = true) }
                ?.let(JobAssistRules::stage)
        }
        val byPreset = first.groupBy { it.preset }.map { (preset, list) -> PresetReview(preset, list.size, list.count { it.jobKey in sent }) }
            .sortedWith(compareByDescending<PresetReview> { it.contacted }.thenBy { it.preset })
        return JobReviewSummary(
            keys,
            JobFunnel(first.size, first.count { it.jobKey in sent }, stages.count { it == JobStage.INTERVIEW || it == JobStage.OFFER },
                stages.count { it == JobStage.OFFER }, stages.count { it == JobStage.CLOSED }),
            keys.map { day -> contacted.count { AutoApplyRules.localDay(it.at, zone) == day } },
            byPreset
        )
    }
}
