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

    @Test fun copiedStatusRequiresContentAndIsOnlyMarkedAfterExplicitAction() {
        val original = job()
        JobAssistRules.validate(original)
        assertEquals(JobAssistStatus.DRAFT, original.status)
        assertThrows(IllegalArgumentException::class.java) { JobAssistRules.markCopied(job(message = " "), 200) }
        val copied = JobAssistRules.markCopied(original, 200)
        assertEquals(JobAssistStatus.COPIED, copied.status)
        assertEquals(200L, copied.updatedAt)
        assertEquals(JobAssistStatus.DRAFT, original.status)
    }

    @Test fun appliedStatusIsExplicitlySelfReportedAndNotVerified() {
        val applied = JobAssistRules.markAppliedByUser(job(), 200)
        assertEquals(JobAssistStatus.APPLIED_SELF_REPORTED, applied.status)
        assertEquals("本人记录已投递 · 未核验", JobAssistRules.statusLabel(applied.status))
    }

    @Test fun jobJsonRoundTripAndFullBackupExclusion() {
        val original = job()
        assertEquals(original, JsonCodec.readJobOpportunity(JSONObject(JsonCodec.jobOpportunity(original).toString())))
        val backup = JSONObject(com.focusassistant.app.data.BackupCodec.encode(AppState(loading = false, jobOpportunities = listOf(original))))
        assertFalse(backup.has("jobOpportunities"))
        assertFalse(backup.toString().contains(original.company))
        assertTrue(JobAssistRules.validateAll(listOf(original)) == Unit)
    }
}
