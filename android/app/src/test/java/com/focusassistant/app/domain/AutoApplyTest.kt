package com.focusassistant.app.domain

import com.focusassistant.app.data.BackupCodec
import com.focusassistant.app.data.JsonCodec
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoApplyTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 22)
    private val noon = day.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3600_000L
    private val card = JobCard("Android 开发工程师", "示例科技", "15-25K·13薪", "上海")
    private var ids = 0
    private val newId = { "r${ids++}" }

    private fun record(company: String = card.company, position: String = card.position, at: Long = noon, result: AutoApplyResult = AutoApplyResult.CONTACTED) =
        AutoApplyRecord("x$company$position$at$result", AutoApplyRules.jobKey(JobCard(position, company, card.salary)), company, position, card.salary, at, result)

    private fun decide(settings: AutoApplySettings = AutoApplySettings(), records: List<AutoApplyRecord> = emptyList(), value: JobCard = card) =
        AutoApplyRules.decide(value, settings, records, day, zone)

    @Test fun parsesSalaryLowerBound() {
        assertEquals(15, AutoApplyRules.salaryLowerK("15-25K·13薪"))
        assertEquals(10, AutoApplyRules.salaryLowerK("1-1.5万"))
        assertNull(AutoApplyRules.salaryLowerK("200-300元/天"))
        assertNull(AutoApplyRules.salaryLowerK("面议"))
        assertTrue(AutoApplyRules.isSalaryText("面议"))
        assertFalse(AutoApplyRules.isSalaryText("示例科技"))
    }

    @Test fun matchesKeywordsSalaryCityAndBlacklist() {
        assertEquals(AutoApplyDecision.Apply, decide())
        assertEquals(AutoApplyDecision.Apply, decide(AutoApplySettings(includeKeywords = listOf("android"), minSalaryK = 15, city = "上海")))
        assertTrue(decide(AutoApplySettings(includeKeywords = listOf("iOS"))) is AutoApplyDecision.Skip)
        assertTrue(decide(AutoApplySettings(excludeKeywords = listOf("工程师"))) is AutoApplyDecision.Skip)
        assertTrue(decide(AutoApplySettings(minSalaryK = 16)) is AutoApplyDecision.Skip)
        assertTrue(decide(AutoApplySettings(minSalaryK = 10), value = card.copy(salary = "面议")) is AutoApplyDecision.Skip)
        assertTrue(decide(AutoApplySettings(city = "北京")) is AutoApplyDecision.Skip)
        assertTrue(decide(AutoApplySettings(companyBlacklist = listOf("示例"))) is AutoApplyDecision.Skip)
        assertEquals(listOf("Java 开发", "Kotlin"), AutoApplyRules.parseList(" Java 开发，Kotlin;Kotlin "))
    }

    @Test fun deduplicatesJobForeverAndCompanyPerDay() {
        assertEquals(AutoApplyDecision.Skip("已沟通过"), decide(records = listOf(record(at = noon - 30L * 86_400_000))))
        assertEquals(AutoApplyDecision.Skip("今天已联系过这家公司"), decide(records = listOf(record(position = "后端开发"))))
        assertEquals(AutoApplyDecision.Apply, decide(records = listOf(record(position = "后端开发", at = noon - 86_400_000))))
        // 失败记录不阻止重试。
        assertEquals(AutoApplyDecision.Apply, decide(records = listOf(record(result = AutoApplyResult.FAILED))))
    }

    @Test fun validatesSettingsBounds() {
        AutoApplyRules.validateSettings(AutoApplySettings())
        assertEquals(30, AutoApplySettings().dailyLimit)
        assertEquals(1_000L, AutoApplySettings().stepDelayMs)
        AutoApplyRules.validateSettings(AutoApplySettings(stepDelayMs = 1_000))
        AutoApplyRules.validateSettings(AutoApplySettings(stepDelayMs = 3_000))
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.validateSettings(AutoApplySettings(dailyLimit = 101)) }
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.validateSettings(AutoApplySettings(dailyLimit = 0)) }
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.validateSettings(AutoApplySettings(stepDelayMs = 999)) }
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.validateSettings(AutoApplySettings(greeting = "x".repeat(301))) }
    }

    private fun obs(screen: BossScreen, at: Long = noon, cards: List<JobCard> = emptyList(), contacted: Boolean = false) = BossObservation(screen, cards, contacted, at)

    @Test fun liveRunContactsOneJobAndReturns() {
        val settings = AutoApplySettings(greeting = "您好")
        var run = AutoApplyEngine.start(AutoApplyMode.LIVE, noon)
        var step = AutoApplyEngine.step(run, obs(BossScreen.JOB_LIST, cards = listOf(card.copy(position = "测试"), card)), settings.copy(includeKeywords = listOf("Android")), emptyList(), zone, newId)
        assertEquals(AutoApplyAction.OpenCard(1), step.action)
        run = step.run
        step = AutoApplyEngine.step(run, obs(BossScreen.JOB_DETAIL, noon + 3000), settings, emptyList(), zone, newId)
        assertEquals(AutoApplyAction.TapContact, step.action)
        step = AutoApplyEngine.step(step.run, obs(BossScreen.CHAT, noon + 6000), settings, emptyList(), zone, newId)
        assertEquals(AutoApplyAction.SendGreeting("您好"), step.action)
        step = AutoApplyEngine.step(step.run, obs(BossScreen.CHAT, noon + 9000), settings, emptyList(), zone, newId)
        assertEquals(AutoApplyAction.Back, step.action)
        assertEquals(AutoApplyResult.CONTACTED, step.record?.result)
        assertEquals(1, step.run.contacted)
        val back = AutoApplyEngine.step(step.run, obs(BossScreen.JOB_LIST, noon + 12000, listOf(card)), settings, listOf(step.record!!), zone, newId)
        assertEquals(AutoApplyAction.ScrollList, back.action)
        assertEquals(AutoApplyPhase.SCANNING, back.run.phase)
    }

    @Test fun stopsOnVerificationLoginLimitAndTimeout() {
        val run = AutoApplyEngine.start(AutoApplyMode.LIVE, noon)
        listOf(BossScreen.VERIFY, BossScreen.LOGIN, BossScreen.LIMIT_REACHED).forEach { screen ->
            val step = AutoApplyEngine.step(run, obs(screen), AutoApplySettings(), emptyList(), zone, newId)
            assertEquals(AutoApplyPhase.STOPPED, step.run.phase)
            assertEquals(AutoApplyAction.Wait, step.action)
        }
        val opening = run.copy(phase = AutoApplyPhase.OPENING, target = card)
        assertEquals(AutoApplyPhase.OPENING, AutoApplyEngine.step(opening, obs(BossScreen.OTHER, noon + 5000), AutoApplySettings(), emptyList(), zone, newId).run.phase)
        assertEquals(AutoApplyPhase.STOPPED, AutoApplyEngine.step(opening, obs(BossScreen.OTHER, noon + AutoApplyEngine.STEP_TIMEOUT_MS + 1), AutoApplySettings(), emptyList(), zone, newId).run.phase)
    }

    @Test fun stopsAtDailyLimitAndSkipsAlreadyContacted() {
        val records = (0 until 2).map { record(company = "公司$it") }
        val step = AutoApplyEngine.step(AutoApplyEngine.start(AutoApplyMode.LIVE, noon), obs(BossScreen.JOB_LIST, cards = listOf(card)), AutoApplySettings(dailyLimit = 2), records, zone, newId)
        assertEquals(AutoApplyPhase.STOPPED, step.run.phase)
        val opening = AutoApplyEngine.start(AutoApplyMode.LIVE, noon).copy(phase = AutoApplyPhase.OPENING, target = card)
        val skipped = AutoApplyEngine.step(opening, obs(BossScreen.JOB_DETAIL, contacted = true), AutoApplySettings(), emptyList(), zone, newId)
        assertEquals(AutoApplyAction.Back, skipped.action)
        assertEquals(AutoApplyResult.ALREADY_CONTACTED, skipped.record?.result)
    }

    @Test fun dryRunNeverTapsAndListsDecisions() {
        val step = AutoApplyEngine.step(AutoApplyEngine.start(AutoApplyMode.DRY_RUN, noon), obs(BossScreen.JOB_LIST, cards = listOf(card, card.copy(company = "外包公司"))),
            AutoApplySettings(companyBlacklist = listOf("外包")), emptyList(), zone, newId)
        assertEquals(AutoApplyAction.Wait, step.action)
        assertNull(step.record)
        assertEquals(AutoApplyPhase.STOPPED, step.run.phase)
        assertEquals(listOf(AutoApplyDecision.Apply, AutoApplyDecision.Skip("公司在黑名单「外包」")), step.run.preview.map { it.decision })
    }

    @Test fun stopsWhenListHasNoNewJobs() {
        var run = AutoApplyEngine.start(AutoApplyMode.LIVE, noon)
        val settings = AutoApplySettings(includeKeywords = listOf("iOS"))
        repeat(AutoApplyEngine.MAX_IDLE_SCROLLS + 1) { run = AutoApplyEngine.step(run, obs(BossScreen.JOB_LIST, cards = listOf(card)), settings, emptyList(), zone, newId).run }
        assertEquals(AutoApplyPhase.STOPPED, run.phase)
    }

    private fun text(path: List<Int>, value: String, id: String = "") = UiNode(path, viewId = id, className = "android.widget.TextView", text = value)

    @Test fun readsJobListDetailChatAndVerifyFixtures() {
        val item = UiNode(listOf(0, 0), clickable = true, children = listOf(
            text(listOf(0, 0, 0), "Android 开发工程师", "com.hpbr.bosszhipin:id/tv_position_name"),
            text(listOf(0, 0, 1), "15-25K·13薪", "com.hpbr.bosszhipin:id/tv_salary_statue"),
            text(listOf(0, 0, 2), "示例科技", "com.hpbr.bosszhipin:id/tv_company_name"),
            text(listOf(0, 0, 3), "上海·浦东新区", "com.hpbr.bosszhipin:id/tv_location")))
        val list = UiNode(listOf(0), scrollable = true, children = listOf(item, UiNode(listOf(0, 1), children = listOf(text(listOf(0, 1, 0), "推荐")))))
        val reading = BossScreenReader.read(UiNode(emptyList(), children = listOf(list)), noon)
        assertEquals(BossScreen.JOB_LIST, reading.observation.screen)
        assertEquals(listOf(JobCard("Android 开发工程师", "示例科技", "15-25K·13薪", "上海·浦东新区")), reading.observation.cards)
        assertEquals(listOf(listOf(0, 0)), reading.cardPaths)

        val detail = UiNode(emptyList(), children = listOf(UiNode(listOf(0), clickable = true, children = listOf(text(listOf(0, 0), "立即沟通")))))
        val detailReading = BossScreenReader.read(detail, noon)
        assertEquals(BossScreen.JOB_DETAIL, detailReading.observation.screen)
        assertEquals(listOf(0), detailReading.contactPath)
        assertTrue(BossScreenReader.read(UiNode(emptyList(), children = listOf(text(listOf(0), "继续沟通"))), noon).observation.alreadyContacted)

        val chat = UiNode(emptyList(), children = listOf(UiNode(listOf(0), editable = true), UiNode(listOf(1), clickable = true, text = "发送")))
        assertEquals(BossScreen.CHAT, BossScreenReader.read(chat, noon).observation.screen)
        // 验证页优先于其他特征，即使同屏有“立即沟通”也停。
        val verify = UiNode(emptyList(), children = listOf(text(listOf(0), "请完成安全验证"), text(listOf(1), "立即沟通")))
        assertEquals(BossScreen.VERIFY, BossScreenReader.read(verify, noon).observation.screen)
        assertEquals(BossScreen.LIMIT_REACHED, BossScreenReader.read(UiNode(emptyList(), children = listOf(text(listOf(0), "今日沟通已达上限"))), noon).observation.screen)
        assertEquals(BossScreen.OTHER, BossScreenReader.read(UiNode(emptyList()), noon).observation.screen)
    }

    @Test fun jsonRoundTripAutoContactedStatusAndBackupExclusion() {
        val settings = AutoApplySettings(listOf("Android"), listOf("外包"), 15, "上海", listOf("某公司"), 20, 4_000L, "您好", true, "13.0")
        assertEquals(settings, JsonCodec.readAutoApplySettings(JSONObject(JsonCodec.autoApplySettings(settings).toString())))
        val saved = record()
        assertEquals(saved, JsonCodec.readAutoApplyRecord(JSONObject(JsonCodec.autoApplyRecord(saved).toString())))
        val job = JobOpportunity("j", card.company, card.position, "", "", JobAssistStatus.DRAFT, 1, 1)
        assertEquals(JobAssistStatus.AUTO_CONTACTED, JobAssistRules.upsertFromRecord(listOf(job), saved, "n").single().status)
        assertEquals(JobAssistStatus.DRAFT, JobAssistRules.upsertFromRecord(listOf(job), saved.copy(result = AutoApplyResult.FAILED), "n").single().status)
        assertEquals("自动化已发起沟通", JobAssistRules.statusLabel(JobAssistStatus.AUTO_CONTACTED))
        val backup = BackupCodec.encode(AppState(loading = false, autoApply = AutoApplyBook(settings, listOf(saved))))
        assertFalse(backup.contains(card.company))
        assertFalse(backup.contains("autoApply"))
    }

    private val resumes = listOf(ResumeProfile("a", "张三-Android", listOf("Android", "Kotlin")), ResumeProfile("b", "张三-后端.pdf", listOf("后端")))
    private val resumeSettings = AutoApplySettings(resumes = resumes)

    @Test fun picksResumeByKeywordThenDefault() {
        assertEquals("张三-Android", AutoApplyRules.pickResume("高级 android 开发", resumes))
        assertNull(AutoApplyRules.pickResume("产品经理", resumes))
        val withDefault = resumes + ResumeProfile("c", "张三-通用", isDefault = true)
        assertEquals("张三-通用", AutoApplyRules.pickResume("产品经理", withDefault))
        // 关键词命中优先于默认简历。
        assertEquals("张三-后端.pdf", AutoApplyRules.pickResume("Java 后端", withDefault))
        AutoApplyRules.validateSettings(AutoApplySettings(resumes = withDefault))
        assertTrue(AutoApplyRules.resumeNameMatches("张三-Android.PDF", "张三-Android"))
        assertFalse(AutoApplyRules.resumeNameMatches("张三-Android-旧版.pdf", "张三-Android"))
        fun invalid(list: List<ResumeProfile>) = assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.validateSettings(AutoApplySettings(resumes = list)) }
        invalid(listOf(ResumeProfile("x", "无关键词")))
        invalid(List(11) { ResumeProfile("$it", "简历$it", listOf("a")) })
        invalid(listOf(ResumeProfile("x", "甲", isDefault = true), ResumeProfile("y", "乙", isDefault = true)))
        invalid(listOf(ResumeProfile("x", "同名.pdf", listOf("a")), ResumeProfile("y", "同名", listOf("b"))))
        invalid(listOf(ResumeProfile("x", " ", listOf("a"))))
    }

    @Test fun upsertKeepsSingleDefaultAndMoveSwaps() {
        val withDefault = AutoApplyRules.upsertResume(resumes, ResumeProfile("a", " 张三-Android ", listOf("Android", " ", "Android"), isDefault = true))
        assertEquals(ResumeProfile("a", "张三-Android", listOf("Android"), true), withDefault.first())
        val moved = AutoApplyRules.upsertResume(withDefault, ResumeProfile("c", "通用", isDefault = true))
        assertEquals(listOf(false, false, true), moved.map { it.isDefault })
        assertEquals(listOf("b", "a"), AutoApplyRules.moveResume(resumes, "b", up = true).map { it.id })
        assertEquals(resumes, AutoApplyRules.moveResume(resumes, "a", up = true))
        assertEquals(resumes, AutoApplyRules.moveResume(resumes, "missing", up = false))
    }

    @Test fun companyMatchingIsStrict() {
        assertTrue(AutoApplyRules.sameCompany("示例科技（北京）", "示例 科技"))
        assertTrue(AutoApplyRules.sameCompany("Example Inc", "example inc"))
        assertFalse(AutoApplyRules.sameCompany("华为", "华为云计算"))
        assertFalse(AutoApplyRules.sameCompany("", ""))
        assertTrue(AutoApplyRules.resumeTargets("示例", "", listOf(record())).isEmpty())
    }

    @Test fun resumeAfterPauseReturnsToListAndSkipsUncertainCompany() {
        val scanning = AutoApplyEngine.start(AutoApplyMode.LIVE, noon).copy(idleScrolls = 2)
        assertEquals(AutoApplyRun(AutoApplyMode.LIVE, phaseSince = noon + 5), AutoApplyEngine.resume(scanning, noon + 5))
        val opening = scanning.copy(phase = AutoApplyPhase.OPENING, target = card, backs = 2)
        val resumed = AutoApplyEngine.resume(opening, noon + 5)
        assertEquals(AutoApplyPhase.RETURNING, resumed.phase)
        assertNull(resumed.target)
        assertEquals(0, resumed.backs)
        val stopped = AutoApplyEngine.stop(scanning, "x", noon)
        assertEquals(stopped, AutoApplyEngine.resume(stopped, noon + 5))

        val finding = ResumeEngine.start(AutoApplyMode.LIVE, noon)
        assertEquals(ResumePhase.FINDING, ResumeEngine.resume(finding, noon + 5).run.phase)
        val confirming = finding.copy(phase = ResumePhase.CONFIRMING, company = "示例科技", target = record(), resumeName = "张三-Android", seen = setOf("示例科技"))
        val step = ResumeEngine.resume(confirming, noon + 5)
        assertEquals(ResumePhase.RETURNING, step.run.phase)
        assertTrue(step.log!!.contains("示例科技"))
        // 回到列表后不会再打开这家公司的会话。
        val back = ResumeEngine.step(step.run, messages(noon + 10, Conversation("示例科技", true)), resumeSettings, listOf(record()), zone, newId)
        assertEquals(ResumeAction.ScrollList, back.action)
        val picking = finding.copy(phase = ResumePhase.PICKING, company = "示例科技")
        assertNull(ResumeEngine.resume(picking, noon + 5).log)
    }

    @Test fun awaitingResumeOnlyContactedOnce() {
        val contacted = record()
        assertEquals(listOf(contacted), AutoApplyRules.awaitingResume(listOf(contacted, contacted.copy(id = "dup"), record(company = "失败", result = AutoApplyResult.FAILED))))
        assertTrue(AutoApplyRules.awaitingResume(listOf(contacted, contacted.copy(id = "s", result = AutoApplyResult.RESUME_SENT))).isEmpty())
        assertEquals(listOf(contacted), AutoApplyRules.resumeTargets("示例科技", "", listOf(contacted)))
        assertTrue(AutoApplyRules.resumeTargets("示例科技", "产品经理", listOf(contacted)).isEmpty())
    }

    private fun messages(at: Long, vararg chats: Conversation) = BossObservation(BossScreen.MESSAGE_LIST, now = at, conversations = chats.toList())

    @Test fun resumeRunSendsMatchingResumeAfterReply() {
        val contacted = record()
        val records = listOf(contacted, record(company = "未回复公司"))
        var step = ResumeEngine.step(ResumeEngine.start(AutoApplyMode.LIVE, noon),
            messages(noon, Conversation("陌生公司", true), Conversation("未回复公司", false), Conversation("示例科技", true)), resumeSettings, records, zone, newId)
        assertEquals(ResumeAction.OpenConversation(2), step.action)
        step = ResumeEngine.step(step.run, BossObservation(BossScreen.CHAT, now = noon + 3000, chatPosition = card.position, hasResumeButton = true), resumeSettings, records, zone, newId)
        assertEquals(ResumeAction.TapResumeButton, step.action)
        step = ResumeEngine.step(step.run, BossObservation(BossScreen.RESUME_PICKER, now = noon + 6000, resumeOptions = listOf("张三-后端.pdf", "张三-Android.pdf")), resumeSettings, records, zone, newId)
        assertEquals(ResumeAction.PickResume(1), step.action)
        step = ResumeEngine.step(step.run, BossObservation(BossScreen.RESUME_PICKER, now = noon + 9000, resumeOptions = listOf("张三-Android.pdf"), hasConfirm = true), resumeSettings, records, zone, newId)
        assertEquals(ResumeAction.Confirm, step.action)
        step = ResumeEngine.step(step.run, BossObservation(BossScreen.CHAT, now = noon + 12000), resumeSettings, records, zone, newId)
        assertEquals(ResumeAction.Back, step.action)
        assertEquals(AutoApplyResult.RESUME_SENT, step.record?.result)
        assertEquals(contacted.jobKey, step.record?.jobKey)
        assertEquals(1, step.run.sent)
        // 发过之后同一会话不再处理。
        val again = ResumeEngine.step(step.run, messages(noon + 15000, Conversation("示例科技", true)), resumeSettings, records + step.record!!, zone, newId)
        assertEquals(ResumeAction.ScrollList, again.action)
    }

    @Test fun resumeRunSkipsUnmatchedAndStopsOnMissingFile() {
        val records = listOf(record(position = "产品经理"))
        val skipped = ResumeEngine.step(ResumeEngine.start(AutoApplyMode.LIVE, noon), messages(noon, Conversation("示例科技", true)), resumeSettings, records, zone, newId)
        assertEquals(ResumeAction.ScrollList, skipped.action)
        assertTrue(skipped.log!!.contains("没有匹配的简历"))
        val picking = ResumeEngine.start(AutoApplyMode.LIVE, noon).copy(phase = ResumePhase.PICKING, target = record(), resumeName = "张三-Android")
        val missing = ResumeEngine.step(picking, BossObservation(BossScreen.RESUME_PICKER, now = noon, resumeOptions = listOf("别的简历.pdf")), resumeSettings, listOf(record()), zone, newId)
        assertEquals(ResumePhase.STOPPED, missing.run.phase)
        assertNull(missing.record)
        val wrongConfirm = ResumeEngine.step(picking, BossObservation(BossScreen.RESUME_CONFIRM, now = noon, resumeOptions = listOf("别的简历.pdf"), hasConfirm = true), resumeSettings, listOf(record()), zone, newId)
        assertEquals(ResumePhase.STOPPED, wrongConfirm.run.phase)
        listOf(BossScreen.VERIFY, BossScreen.LOGIN, BossScreen.LIMIT_REACHED).forEach { screen ->
            assertEquals(ResumePhase.STOPPED, ResumeEngine.step(picking, BossObservation(screen, now = noon), resumeSettings, emptyList(), zone, newId).run.phase)
        }
        assertEquals(ResumePhase.STOPPED, ResumeEngine.step(picking, BossObservation(BossScreen.OTHER, now = noon + AutoApplyEngine.STEP_TIMEOUT_MS + 1), resumeSettings, emptyList(), zone, newId).run.phase)
    }

    @Test fun resumeDryRunListsWithoutTappingAndRespectsDailyLimit() {
        val records = listOf(record(), record(company = "乙公司", position = "运营"))
        val dry = ResumeEngine.step(ResumeEngine.start(AutoApplyMode.DRY_RUN, noon), messages(noon, Conversation("示例科技", true), Conversation("乙公司", true)), resumeSettings, records, zone, newId)
        assertEquals(ResumeAction.Wait, dry.action)
        assertTrue(dry.run.listRecognized)
        assertEquals(listOf("张三-Android", null), dry.run.preview.map { it.resumeName })
        val sent = (0 until 2).map { record(company = "已发$it", result = AutoApplyResult.RESUME_SENT) }
        val limited = ResumeEngine.step(ResumeEngine.start(AutoApplyMode.LIVE, noon), messages(noon, Conversation("示例科技", true)), resumeSettings.copy(dailyLimit = 2), records + sent, zone, newId)
        assertEquals(ResumePhase.STOPPED, limited.run.phase)
    }

    @Test fun readsMessageListResumePickerAndConfirmFixtures() {
        val chat = UiNode(listOf(0, 0), clickable = true, children = listOf(
            text(listOf(0, 0, 0), "示例科技", "com.hpbr.bosszhipin:id/tv_company"),
            text(listOf(0, 0, 1), "最后一条消息", "com.hpbr.bosszhipin:id/tv_msg"),
            text(listOf(0, 0, 2), "1", "com.hpbr.bosszhipin:id/tv_unread_count")))
        val quiet = UiNode(listOf(0, 1), clickable = true, children = listOf(text(listOf(0, 1, 0), "乙公司", "com.hpbr.bosszhipin:id/tv_company")))
        val root = UiNode(emptyList(), children = listOf(UiNode(listOf(0), scrollable = true, children = listOf(chat, quiet)), UiNode(listOf(1), clickable = true, text = "消息")))
        val reading = BossScreenReader.read(root, noon)
        assertEquals(BossScreen.MESSAGE_LIST, reading.observation.screen)
        assertEquals(listOf(Conversation("示例科技", true), Conversation("乙公司", false)), reading.observation.conversations)
        assertEquals(listOf(1), reading.messagesTabPath)

        val chatPage = UiNode(emptyList(), children = listOf(text(listOf(0), "Android 开发工程师", "com.hpbr.bosszhipin:id/tv_position_name"),
            UiNode(listOf(1), editable = true), UiNode(listOf(2), clickable = true, text = "发简历")))
        val chatReading = BossScreenReader.read(chatPage, noon)
        assertEquals(BossScreen.CHAT, chatReading.observation.screen)
        assertTrue(chatReading.observation.hasResumeButton)
        assertEquals("Android 开发工程师", chatReading.observation.chatPosition)
        assertEquals(listOf(2), chatReading.resumeButtonPath)

        val picker = UiNode(emptyList(), children = listOf(text(listOf(0), "选择附件简历"),
            UiNode(listOf(1), clickable = true, children = listOf(text(listOf(1, 0), "张三-Android.pdf"))), UiNode(listOf(2), clickable = true, text = "确定")))
        val pickerReading = BossScreenReader.read(picker, noon)
        assertEquals(BossScreen.RESUME_PICKER, pickerReading.observation.screen)
        assertEquals(listOf("张三-Android.pdf"), pickerReading.observation.resumeOptions)
        assertEquals(listOf(listOf(1)), pickerReading.resumeOptionPaths)
        assertEquals(listOf(2), pickerReading.confirmPath)

        val confirm = UiNode(emptyList(), children = listOf(text(listOf(0), "确定向对方发送附件简历吗？"), text(listOf(1), "张三-Android.pdf"), UiNode(listOf(2), clickable = true, text = "确定")))
        assertEquals(BossScreen.RESUME_CONFIRM, BossScreenReader.read(confirm, noon).observation.screen)
    }

    @Test fun resumeSettingsRoundTripAndResumeSentStatus() {
        val settings = AutoApplySettings(resumes = resumes + ResumeProfile("c", "通用", isDefault = true), resumeCalibratedVersion = "13.0")
        assertEquals(settings, JsonCodec.readAutoApplySettings(JSONObject(JsonCodec.autoApplySettings(settings).toString())))
        // 0.9.8 保存的设置没有简历字段，仍能读取。
        val legacy = JsonCodec.autoApplySettings(AutoApplySettings()).apply { remove("resumes"); remove("resumeCalibratedVersion") }
        assertEquals(AutoApplySettings(), JsonCodec.readAutoApplySettings(legacy))
        // 0.9.9 的「关键词=简历名称」规则转换为不设默认的简历。
        val v099 = JsonCodec.autoApplySettings(AutoApplySettings()).apply {
            remove("resumes")
            put("resumeRules", org.json.JSONArray().put(JSONObject().put("keywords", org.json.JSONArray().put("Android")).put("resumeName", "张三-Android")))
        }
        assertEquals(listOf(ResumeProfile("legacy-0", "张三-Android", listOf("Android"))), JsonCodec.readAutoApplySettings(v099).resumes)
        val job = JobOpportunity("j", card.company, card.position, "", "", JobAssistStatus.DRAFT, 1, 1)
        val sent = JobAssistRules.upsertFromRecord(listOf(job), record(result = AutoApplyResult.RESUME_SENT), "n").single()
        assertEquals(JobAssistStatus.AUTO_RESUME_SENT, sent.status)
        assertEquals(JobAssistStatus.AUTO_RESUME_SENT, JobAssistRules.upsertFromRecord(listOf(sent), record(), "n").single().status)
        assertEquals("自动化已发送简历", JobAssistRules.statusLabel(JobAssistStatus.AUTO_RESUME_SENT))
    }

    @Test fun entrySharesLabelsAndCountsWithBrowser() {
        fun entry(running: Boolean = false, resume: Boolean = false, paused: Boolean = false, stop: String? = null) =
            AutoApplyRules.entry(running, resume, paused, stop, 3, 1, 2, 30)
        assertEquals(AutoApplyEntry(AutoApplyEntryState.IDLE, "空闲", "沟通 3/30 · 简历 1/30 · 待回复 2"), entry())
        assertEquals("自动沟通中", entry(running = true).label)
        assertEquals("自动发简历中", entry(running = true, resume = true).label)
        assertEquals(AutoApplyEntryState.PAUSED, entry(running = true, paused = true).state)
        assertEquals(AutoApplyEntryState.STOPPED, entry(stop = "遇到验证页").state)
        assertEquals(AutoApplyEntryState.RUNNING, entry(running = true, stop = "旧原因").state)
    }

    @Test fun presetsSaveApplyDeleteAndKeepGlobalSettings() {
        var settings = AutoApplySettings(includeKeywords = listOf("Android"), city = "北京", companyBlacklist = listOf("外包"), dailyLimit = 5)
        settings = AutoApplyRules.savePreset(settings, "Android·北京", "p1")
        assertEquals("Android·北京", AutoApplyRules.activePresetName(settings))
        settings = AutoApplyRules.savePreset(settings.copy(includeKeywords = listOf("Kotlin"), city = "", minSalaryK = 20), "Kotlin·远程", "p2")
        settings = AutoApplyRules.applyPreset(settings, "p1")
        assertEquals(listOf("Android"), settings.includeKeywords)
        assertEquals("北京", settings.city)
        assertNull(settings.minSalaryK)
        assertEquals("黑名单不随方案切换", listOf("外包"), settings.companyBlacklist)
        assertEquals(5, settings.dailyLimit)
        settings = AutoApplyRules.savePreset(settings.copy(city = "上海"), " android·北京 ", "p3")
        assertEquals(2, settings.presets.size)
        assertEquals("同名覆盖保留原名", "Android·北京", settings.presets.first().name)
        assertEquals("上海", settings.presets.first().city)
        AutoApplyRules.validateSettings(settings)
        assertEquals(settings, JsonCodec.readAutoApplySettings(JSONObject(JsonCodec.autoApplySettings(settings).toString())))
        val removed = AutoApplyRules.deletePreset(settings, "p1")
        assertNull(removed.activePresetId)
        assertEquals("上海", removed.city)
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.applyPreset(removed, "p1") }
        assertNull(AutoApplyRules.savePreset(settings, "  ", "p9").activePresetId)
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.savePreset(settings, "超".repeat(AutoApplyRules.MAX_PRESET_NAME + 1), "p9") }
        var many = AutoApplySettings()
        repeat(AutoApplyRules.MAX_PRESETS) { many = AutoApplyRules.savePreset(many, "方案$it", "m$it") }
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.savePreset(many, "再来一个", "m99") }
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.validateSettings(many.copy(activePresetId = "ghost")) }
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.validateSettings(many.copy(presets = many.presets.dropLast(1) + many.presets.first().copy(id = "dup"))) }
        // 0.9.11 及以前没有方案字段。
        val legacy = JsonCodec.autoApplySettings(AutoApplySettings()).apply { remove("presets"); remove("activePresetId") }
        assertEquals(AutoApplySettings(), JsonCodec.readAutoApplySettings(legacy))
        val legacyRecord = JsonCodec.autoApplyRecord(record()).apply { remove("preset") }
        assertEquals(record(), JsonCodec.readAutoApplyRecord(legacyRecord))
    }

    @Test fun liveContactRecordCarriesActivePresetName() {
        val settings = AutoApplyRules.savePreset(AutoApplySettings(includeKeywords = listOf("Android"), greeting = "您好"), "北京", "p1")
        var step = AutoApplyEngine.step(AutoApplyEngine.start(AutoApplyMode.LIVE, noon), obs(BossScreen.JOB_LIST, cards = listOf(card)), settings, emptyList(), zone, newId)
        step = AutoApplyEngine.step(step.run, obs(BossScreen.JOB_DETAIL, noon + 3000), settings, emptyList(), zone, newId)
        step = AutoApplyEngine.step(step.run, obs(BossScreen.CHAT, noon + 6000), settings, emptyList(), zone, newId)
        step = AutoApplyEngine.step(step.run, obs(BossScreen.CHAT, noon + 9000), settings, emptyList(), zone, newId)
        assertEquals(AutoApplyResult.CONTACTED, step.record?.result)
        assertEquals("北京", step.record?.preset)
    }

    @Test fun reviewFunnelDailyAndByPreset() {
        fun rec(company: String, daysAgo: Long, result: AutoApplyResult = AutoApplyResult.CONTACTED, preset: String = "") =
            AutoApplyRecord("$company$daysAgo$result", "$company|岗位|", company, "岗位", "", noon - daysAgo * 86_400_000L, result, preset = preset)
        val records = listOf(rec("a", 0, preset = "北京"), rec("a", 0, AutoApplyResult.RESUME_SENT), rec("b", 1, preset = "北京"),
            rec("c", 6, preset = "远程"), rec("d", 7, preset = "远程"), rec("e", 0, AutoApplyResult.FAILED))
        var jobs = records.fold(emptyList<JobOpportunity>()) { list, item -> JobAssistRules.upsertFromRecord(list, item, "j${item.id}") }
        jobs = jobs.map {
            when (it.company) {
                "a" -> JobAssistRules.setOutcome(it, JobOutcome.OFFER, "", noon)
                "b" -> JobAssistRules.setOutcome(it, JobOutcome.CLOSED, "", noon)
                else -> it
            }
        }
        val week = JobReview.summary(records, jobs, day, 7, zone)
        assertEquals(7, week.days.size)
        assertEquals(day, week.days.last())
        assertEquals(JobFunnel(3, 1, 1, 1, 1), week.funnel)
        assertEquals(listOf(1, 0, 0, 0, 0, 1, 1), week.dailyContacted)
        assertEquals(listOf(PresetReview("北京", 2, 1), PresetReview("远程", 1, 0)), week.byPreset)
        assertEquals(4, JobReview.summary(records, jobs, day, 30, zone).funnel.contacted)
        assertEquals(30, JobReview.summary(emptyList(), emptyList(), day, 30, zone).dailyContacted.size)
    }
}
