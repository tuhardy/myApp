package com.focusassistant.app.domain

import com.focusassistant.app.data.JsonCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JobAssistTest {
    private fun job(status: JobAssistStatus = JobAssistStatus.DRAFT, message: String = "您好，我想进一步了解这个岗位。") =
        JobOpportunity("job-1", "示例公司", "产品经理", "职位描述", message, status, 100, 100)

    @Test fun validatesLengthAndRequiredFields() {
        assertThrows(IllegalArgumentException::class.java) { JobAssistRules.validate(job().copy(company = " ")) }
        assertThrows(IllegalArgumentException::class.java) { JobAssistRules.validate(job().copy(position = "")) }
        assertThrows(IllegalArgumentException::class.java) { JobAssistRules.validate(job().copy(description = "x".repeat(JobAssistRules.MAX_DESCRIPTION + 1))) }
        assertThrows(IllegalArgumentException::class.java) { JobAssistRules.validateAll(listOf(job(), job())) }
    }

    @Test fun manualJobIsSelfReportedContactAndLegacyDraftsStayReadable() {
        val manual = job(JobAssistStatus.APPLIED_SELF_REPORTED, message = "").copy(note = "朋友内推")
        JobAssistRules.validate(manual)
        assertEquals(JobStage.CONTACTED, JobAssistRules.stage(manual))
        assertEquals("已沟通 · 本人记录", JobAssistRules.statusLabel(manual.status))
        assertEquals(JobSource.MANUAL, manual.source)
        // 0.9.11 及以前的草稿仍保留描述与文案，按原状态归入“准备中”。
        assertEquals(JobStage.PREP, JobAssistRules.stage(job(JobAssistStatus.COPIED)))
        assertFalse(JobAssistRules.displayLabel(job()).isBlank())
    }

    @Test fun jobJsonRoundTripAndFullBackupExclusion() {
        val original = job()
        assertEquals(original, JsonCodec.readJobOpportunity(JSONObject(JsonCodec.jobOpportunity(original).toString())))
        val backup = JSONObject(com.focusassistant.app.data.BackupCodec.encode(AppState(loading = false, jobOpportunities = listOf(original))))
        assertFalse(backup.has("jobOpportunities"))
        assertFalse(backup.toString().contains(original.company))
        assertTrue(JobAssistRules.validateAll(listOf(original)) == Unit)
    }

    private fun contact(at: Long = 1_000, result: AutoApplyResult = AutoApplyResult.CONTACTED) =
        AutoApplyRecord("r$at$result", "示例科技|android|20k", "示例科技", "Android", "20K", at, result, preset = "北京")

    @Test fun autoRecordCreatesJobAndOnlyMovesForward() {
        var jobs = JobAssistRules.upsertFromRecord(emptyList(), contact(), "job-a")
        val created = jobs.single()
        assertEquals(JobSource.AUTO, created.source)
        assertEquals("北京", created.preset)
        assertEquals("20K", created.salary)
        assertEquals(JobStage.CONTACTED, JobAssistRules.stage(created))
        jobs = JobAssistRules.upsertFromRecord(jobs, contact(at = 2_000, result = AutoApplyResult.RESUME_SENT), "job-b")
        assertEquals(1, jobs.size)
        assertEquals(JobStage.RESUME, JobAssistRules.stage(jobs.single()))
        jobs = JobAssistRules.upsertFromRecord(jobs, contact(at = 3_000), "job-c")
        assertEquals("已发简历不退回已沟通", JobStage.RESUME, JobAssistRules.stage(jobs.single()))
        assertEquals(jobs, JobAssistRules.upsertFromRecord(jobs, contact(result = AutoApplyResult.FAILED), "job-d"))
    }

    @Test fun manualOutcomeWinsOverAutoRecordsAndCanBeCleared() {
        val interview = JobAssistRules.setOutcome(JobAssistRules.upsertFromRecord(emptyList(), contact(), "job-a").single(), JobOutcome.INTERVIEW, " 周三电话 ", 5_000)
        assertEquals(JobStage.INTERVIEW, JobAssistRules.stage(interview))
        assertEquals("周三电话", interview.note)
        assertEquals("面试", JobAssistRules.displayLabel(interview))
        val later = JobAssistRules.upsertFromRecord(listOf(interview), contact(at = 6_000, result = AutoApplyResult.RESUME_SENT), "job-b").single()
        assertEquals(JobStage.INTERVIEW, JobAssistRules.stage(later))
        assertEquals(JobStage.RESUME, JobAssistRules.stage(JobAssistRules.setOutcome(later, null, "", 7_000)))
        assertThrows(IllegalArgumentException::class.java) { JobAssistRules.setOutcome(later, JobOutcome.OFFER, "长".repeat(JobAssistRules.MAX_NOTE + 1), 7_000) }
        assertEquals(JobStage.PREP, JobAssistRules.stage(job(JobAssistStatus.COPIED)))
        assertEquals(JobStage.CONTACTED, JobAssistRules.stage(job(JobAssistStatus.APPLIED_SELF_REPORTED)))
    }

    @Test fun fullListDoesNotCreateNewJobs() {
        val full = (0 until JobAssistRules.MAX_JOBS).map { job().copy(id = "j$it", company = "公司$it") }
        assertEquals(JobAssistRules.MAX_JOBS, JobAssistRules.upsertFromRecord(full, contact(), "job-x").size)
    }

    @Test fun newFieldsRoundTripAndLegacyJsonReadsDefaults() {
        val value = JobAssistRules.setOutcome(JobAssistRules.upsertFromRecord(emptyList(), contact(), "job-a").single(), JobOutcome.OFFER, "谈薪中", 2_000)
        assertEquals(value, JsonCodec.readJobOpportunity(JSONObject(JsonCodec.jobOpportunity(value).toString())))
        val legacy = JsonCodec.jobOpportunity(job()).apply { listOf("note", "salary", "source", "preset", "outcome").forEach { remove(it) } }
        assertEquals(job(), JsonCodec.readJobOpportunity(legacy))
    }
}
