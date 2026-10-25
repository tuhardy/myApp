package com.focusassistant.app.domain

import java.time.LocalDate
import java.time.ZoneId

data class JobFunnel(val contacted: Int, val resumes: Int, val interviews: Int, val offers: Int, val closed: Int)

data class PresetReview(val preset: String, val contacted: Int, val resumes: Int)

data class JobReviewSummary(val days: List<LocalDate>, val funnel: JobFunnel, val dailyContacted: List<Int>, val byPreset: List<PresetReview>)

/**
 * 求职复盘，与浏览器 jobReviewSummary 同口径：近 days 天（含今天）的自动联系记录（BOSS 沟通 + 智联投递）。
 * 漏斗以职位（平台 + jobKey）为单位：联系 → 其中已发简历 → 其中手动标为面试/Offer。
 * BOSS 的已发简历只说明对方回复过；智联投递即附带简历，直接计入已发简历。都不等于 HR 已查看。
 */
object JobReview {
    fun summary(records: List<AutoApplyRecord>, jobs: List<JobOpportunity>, today: LocalDate, days: Int, zone: ZoneId): JobReviewSummary {
        val keys = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val range = keys.toSet()
        val contacted = records.filter { AutoApplyRules.isReach(it) && AutoApplyRules.localDay(it.at, zone) in range }.sortedBy { it.at }
        val sent = records.filter { it.result == AutoApplyResult.RESUME_SENT || it.result == AutoApplyResult.APPLIED }.map { it.platform to it.jobKey }.toSet()
        val first = contacted.distinctBy { it.platform to it.jobKey }
        val stages = first.map { record ->
            jobs.find { it.company.trim().equals(record.company.trim(), ignoreCase = true) && it.position.trim().equals(record.position.trim(), ignoreCase = true) }
                ?.let(JobAssistRules::stage)
        }
        val byPreset = first.groupBy { it.preset }.map { (preset, list) -> PresetReview(preset, list.size, list.count { (it.platform to it.jobKey) in sent }) }
            .sortedWith(compareByDescending<PresetReview> { it.contacted }.thenBy { it.preset })
        return JobReviewSummary(
            keys,
            JobFunnel(first.size, first.count { (it.platform to it.jobKey) in sent }, stages.count { it == JobStage.INTERVIEW || it == JobStage.OFFER },
                stages.count { it == JobStage.OFFER }, stages.count { it == JobStage.CLOSED }),
            keys.map { day -> contacted.count { AutoApplyRules.localDay(it.at, zone) == day } },
            byPreset
        )
    }
}
