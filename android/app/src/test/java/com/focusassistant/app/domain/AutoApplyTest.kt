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
        assertEquals(AutoApplyDecision.Skip("未识别城市"), decide(AutoApplySettings(city = "上海"), value = card.copy(city = "")))
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

    @Test fun classifiesStopReasonsForNotifications() {
        assertEquals(StopCategory.NO_JOBS, StopNotice.classify("列表没有更多新职位，已停止"))
        assertEquals(StopCategory.NO_JOBS, StopNotice.classify("没有更多待发简历的新回复，已停止"))
        assertEquals(StopCategory.NEED_RETURN_TO_LIST, StopNotice.classify("没有识别到职位列表，已停止"))
        assertEquals(StopCategory.LIMIT_REACHED, StopNotice.classify("已达到今日上限 3 条，已停止"))
        assertEquals(StopCategory.INTERNAL_ERROR, StopNotice.classify("内部错误（IllegalStateException），已停止"))
        assertEquals(StopCategory.USER_STOPPED, StopNotice.classify("已手动停止"))
        assertEquals(StopCategory.NEED_USER_ACTION, StopNotice.classify("出现安全验证，已停止；请手动处理后再开始"))
        StopCategory.entries.forEach { category ->
            val text = StopNotice.friendlyText(category)
            assertFalse(text.contains("BOSS"))
            assertFalse(text.contains("智联"))
        }
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

    private fun zp(screen: BossScreen, at: Long = noon, cards: List<JobCard> = emptyList(), applied: Boolean = false, options: List<String> = emptyList(), confirm: Boolean = false) =
        BossObservation(screen, cards, applied, at, resumeOptions = options, hasConfirm = confirm)

    @Test fun platformsKeepSeparateDedupAndCounts() {
        val boss = record()
        val zhaopin = record(company = "演示网络").copy(result = AutoApplyResult.APPLIED, platform = JobPlatform.ZHAOPIN)
        // BOSS 上沟通过的职位在智联仍可投递，反之亦然。
        assertEquals(AutoApplyDecision.Apply, AutoApplyRules.decide(card, AutoApplySettings(), listOf(boss), day, zone, JobPlatform.ZHAOPIN))
        assertEquals(AutoApplyDecision.Skip("已投递过"),
            AutoApplyRules.decide(card, AutoApplySettings(), listOf(boss.copy(platform = JobPlatform.ZHAOPIN, result = AutoApplyResult.APPLIED)), day, zone, JobPlatform.ZHAOPIN))
        assertEquals(1, AutoApplyRules.contactedOn(listOf(boss, zhaopin), day, zone))
        assertEquals(1, AutoApplyRules.contactedOn(listOf(boss, zhaopin), day, zone, JobPlatform.ZHAOPIN))
        // 智联投递不进入 BOSS 的待发简历队列。
        assertEquals(listOf(boss), AutoApplyRules.awaitingResume(listOf(boss, zhaopin.copy(company = card.company))))
        val settings = AutoApplySettings(dailyLimit = 10, zhaopinDailyLimit = 5)
        assertEquals(5, AutoApplyRules.limitFor(settings, JobPlatform.ZHAOPIN))
        assertEquals(10, AutoApplyRules.limitFor(settings, JobPlatform.BOSS))
        assertThrows(IllegalArgumentException::class.java) { AutoApplyRules.validateSettings(AutoApplySettings(zhaopinDailyLimit = 101)) }
        assertEquals("智联 · 投递 2/5", AutoApplyRules.entry(true, false, false, null, 2, 0, 0, 5, JobPlatform.ZHAOPIN).summary)
        assertEquals("智联投递中", AutoApplyRules.entry(true, false, false, null, 2, 0, 0, 5, JobPlatform.ZHAOPIN).label)
    }

    @Test fun platformFieldsRoundTripAndLegacyJsonDefaultsToBoss() {
        val settings = AutoApplySettings(platform = JobPlatform.ZHAOPIN, zhaopinDailyLimit = 12, zhaopinCalibratedVersion = "8.1")
        assertEquals(settings, JsonCodec.readAutoApplySettings(JSONObject(JsonCodec.autoApplySettings(settings).toString())))
        val saved = record().copy(result = AutoApplyResult.APPLIED, platform = JobPlatform.ZHAOPIN)
        assertEquals(saved, JsonCodec.readAutoApplyRecord(JSONObject(JsonCodec.autoApplyRecord(saved).toString())))
        val legacySettings = JsonCodec.autoApplySettings(AutoApplySettings()).apply { remove("platform"); remove("zhaopinDailyLimit"); remove("zhaopinCalibratedVersion") }
        assertEquals(AutoApplySettings(), JsonCodec.readAutoApplySettings(JSONObject(legacySettings.toString())))
        val legacyRecord = JsonCodec.autoApplyRecord(record()).apply { remove("platform") }
        assertEquals(JobPlatform.BOSS, JsonCodec.readAutoApplyRecord(JSONObject(legacyRecord.toString())).platform)
        val job = JobAssistRules.upsertFromRecord(emptyList(), saved, "j").single()
        assertEquals(JobPlatform.ZHAOPIN, job.platform)
        assertEquals(JobStage.RESUME, JobAssistRules.stage(job))
        assertEquals(job, JsonCodec.readJobOpportunity(JSONObject(JsonCodec.jobOpportunity(job).toString())))
        // 智联已投递不会被之后的 BOSS 沟通记录退回「已沟通」。
        assertEquals(JobAssistStatus.AUTO_APPLIED, JobAssistRules.upsertFromRecord(listOf(job), record(), "n").single().status)
    }

    @Test fun readsZhaopinFixtures() {
        val item = UiNode(listOf(0, 0), clickable = true, children = listOf(
            text(listOf(0, 0, 0), "Android 开发工程师", "com.zhaopin.social:id/tv_job_name"),
            text(listOf(0, 0, 1), "1.5-2.5万", "com.zhaopin.social:id/tv_salary"),
            text(listOf(0, 0, 2), "示例科技", "com.zhaopin.social:id/tv_company_name")))
        val list = UiNode(emptyList(), children = listOf(UiNode(listOf(0), scrollable = true, children = listOf(item))))
        val reading = ZhaopinScreenReader.read(list, noon)
        assertEquals(BossScreen.JOB_LIST, reading.observation.screen)
        assertEquals(listOf(JobCard("Android 开发工程师", "示例科技", "1.5-2.5万")), reading.observation.cards)

        val detail = UiNode(emptyList(), children = listOf(UiNode(listOf(0), clickable = true, children = listOf(text(listOf(0, 0), "申请职位")))))
        val detailReading = ZhaopinScreenReader.read(detail, noon)
        assertEquals(BossScreen.JOB_DETAIL, detailReading.observation.screen)
        assertFalse(detailReading.observation.alreadyContacted)
        assertEquals(listOf(0), detailReading.contactPath)
        assertTrue(ZhaopinScreenReader.read(UiNode(emptyList(), children = listOf(text(listOf(0), "已申请"))), noon).observation.alreadyContacted)

        val picker = UiNode(emptyList(), children = listOf(text(listOf(0), "选择简历"),
            UiNode(listOf(1), clickable = true, children = listOf(text(listOf(1, 0), "张三-Android"))),
            UiNode(listOf(2), clickable = true, text = "确认投递")))
        val pickerReading = ZhaopinScreenReader.read(picker, noon)
        assertEquals(BossScreen.RESUME_PICKER, pickerReading.observation.screen)
        assertEquals(listOf("张三-Android"), pickerReading.observation.resumeOptions)
        assertEquals(listOf(listOf(1)), pickerReading.resumeOptionPaths)
        assertEquals(listOf(2), pickerReading.confirmPath)

        assertEquals(BossScreen.APPLY_DONE, ZhaopinScreenReader.read(UiNode(emptyList(), children = listOf(text(listOf(0), "投递成功"))), noon).observation.screen)
        val verify = UiNode(emptyList(), children = listOf(text(listOf(0), "请完成安全验证"), text(listOf(1), "申请职位")))
        assertEquals(BossScreen.VERIFY, ZhaopinScreenReader.read(verify, noon).observation.screen)
        assertEquals(BossScreen.LIMIT_REACHED, ZhaopinScreenReader.read(UiNode(emptyList(), children = listOf(text(listOf(0), "今日投递已达上限"))), noon).observation.screen)
        assertEquals(BossScreen.LOGIN, ZhaopinScreenReader.read(UiNode(emptyList(), children = listOf(text(listOf(0), "验证码登录"))), noon).observation.screen)
        assertEquals(BossScreen.OTHER, ZhaopinScreenReader.read(UiNode(emptyList()), noon).observation.screen)
    }

    @Test fun zhaopinApplyPathSurvivesSkippedChildrenAndPrefersClickable() {
        // 下标 0 的子树在屏幕外被跳过；按钮的可点击容器原始下标为 2，children 里排第 1。
        val detail = UiNode(emptyList(), children = listOf(
            UiNode(listOf(1), children = listOf(text(listOf(1, 0), "申请职位"))),
            UiNode(listOf(2), clickable = true, children = listOf(text(listOf(2, 0), "申请职位")))))
        assertEquals(listOf(2), ZhaopinScreenReader.read(detail, noon).contactPath)
        assertTrue(ZhaopinApplyEngine.failReason(ZhaopinAction.TapApply).contains("申请职位"))
    }

    @Test fun zhaopinCardPathPointsAtClickableContainer() {
        fun card(index: Int, inner: Boolean) = UiNode(listOf(0, index), children = listOf(
            UiNode(listOf(0, index, 0), clickable = inner, children = listOf(
                text(listOf(0, index, 0, 0), "Android 开发工程师", "com.zhaopin.social:id/tv_job_name"),
                text(listOf(0, index, 0, 1), "1.5-2.5万", "com.zhaopin.social:id/tv_salary"),
                text(listOf(0, index, 0, 2), "示例科技", "com.zhaopin.social:id/tv_company_name")))))
        // 卡片容器不可点、内层唯一可点：取内层。
        val inner = UiNode(emptyList(), children = listOf(UiNode(listOf(0), scrollable = true, children = listOf(card(0, true)))))
        assertEquals(listOf(listOf(0, 0, 0)), ZhaopinScreenReader.read(inner, noon).cardPaths)
        // 整条都不可点：退回可点击祖先（这里是可点的列表项之外没有，保持原路径）。
        val none = UiNode(emptyList(), children = listOf(UiNode(listOf(0), scrollable = true, children = listOf(card(0, false)))))
        assertEquals(listOf(listOf(0, 0)), ZhaopinScreenReader.read(none, noon).cardPaths)
    }

    @Test fun zhaopinPickerMatchesRealDialog() {
        // 真机弹窗：标题带「请」，提交按钮只写「投递」，行里有「预览」和更新时间。
        fun row(index: Int, name: String, date: String) = UiNode(listOf(index), children = listOf(
            UiNode(listOf(index, 0), clickable = true, children = listOf(
                text(listOf(index, 0, 0), name), text(listOf(index, 0, 1), date))),
            UiNode(listOf(index, 1), clickable = true, text = "预览")))
        val picker = UiNode(emptyList(), children = listOf(
            text(listOf(0), "请选择要投递的简历"),
            row(1, "在线简历", "2026.10.13更新"),
            row(2, "涂林俊-简历.pdf", "2026.10.13上传"),
            UiNode(listOf(3), clickable = true, text = "投递"),
            text(listOf(4), "每次投递默认发送该简历")))
        val reading = ZhaopinScreenReader.read(picker, noon)
        assertEquals(BossScreen.RESUME_PICKER, reading.observation.screen)
        assertEquals(listOf("在线简历", "涂林俊-简历.pdf"), reading.observation.resumeOptions)
        assertTrue(reading.observation.hasConfirm)
        assertEquals(listOf(3), reading.confirmPath)
        // 选中第二份：点的是行内可点容器，不是「预览」。
        assertEquals(listOf(2, 0), reading.resumeOptionPaths[1])
    }

    @Test fun zhaopinResumeOptionPathPointsAtClickableRow() {
        // 简历名文字在不可点的 TextView 里，可点的是外层整行；行内只有这一个可点节点。
        val picker = UiNode(emptyList(), children = listOf(text(listOf(0), "选择要投递的简历"),
            UiNode(listOf(1), children = listOf(UiNode(listOf(1, 0), clickable = true, children = listOf(
                text(listOf(1, 0, 0), "张三-Android.pdf"))))),
            UiNode(listOf(2), clickable = true, text = "确认投递")))
        val reading = ZhaopinScreenReader.read(picker, noon)
        assertEquals(BossScreen.RESUME_PICKER, reading.observation.screen)
        assertEquals(listOf("张三-Android.pdf"), reading.observation.resumeOptions)
        assertEquals(listOf(listOf(1, 0)), reading.resumeOptionPaths)
    }

    @Test fun zhaopinLiveRunAppliesWithResumeAndReturns() {
        val settings = resumeSettings
        var run = ZhaopinApplyEngine.start(AutoApplyMode.LIVE, noon)
        var step = ZhaopinApplyEngine.step(run, zp(BossScreen.JOB_LIST, cards = listOf(card)), settings, emptyList(), zone, newId)
        assertEquals(ZhaopinAction.OpenCard(0), step.action)
        run = step.run
        step = ZhaopinApplyEngine.step(run, zp(BossScreen.JOB_DETAIL), settings, emptyList(), zone, newId)
        assertEquals(ZhaopinAction.TapApply, step.action)
        assertEquals("张三-Android", step.run.resumeName)
        run = step.run
        step = ZhaopinApplyEngine.step(run, zp(BossScreen.RESUME_PICKER, options = listOf("张三-后端.pdf", "张三-Android.pdf"), confirm = true), settings, emptyList(), zone, newId)
        assertEquals(ZhaopinAction.PickResume(1), step.action)
        run = step.run
        step = ZhaopinApplyEngine.step(run, zp(BossScreen.RESUME_PICKER, confirm = true), settings, emptyList(), zone, newId)
        assertEquals(ZhaopinAction.Confirm, step.action)
        run = step.run
        step = ZhaopinApplyEngine.step(run, zp(BossScreen.APPLY_DONE), settings, emptyList(), zone, newId)
        assertEquals(ZhaopinAction.Back, step.action)
        assertEquals(AutoApplyResult.APPLIED, step.record?.result)
        assertEquals(JobPlatform.ZHAOPIN, step.record?.platform)
        assertEquals("简历：张三-Android", step.record?.note)
        assertEquals(1, step.run.applied)
        assertEquals(ZhaopinPhase.SCANNING, ZhaopinApplyEngine.step(step.run, zp(BossScreen.JOB_LIST), settings, listOfNotNull(step.record), zone, newId).run.phase)
    }

    @Test fun zhaopinAppliesWithoutPickerAndStopsSafely() {
        val settings = AutoApplySettings()
        val opened = ZhaopinApplyEngine.step(ZhaopinApplyEngine.start(AutoApplyMode.LIVE, noon), zp(BossScreen.JOB_LIST, cards = listOf(card)), settings, emptyList(), zone, newId).run
        val applying = ZhaopinApplyEngine.step(opened, zp(BossScreen.JOB_DETAIL), settings, emptyList(), zone, newId).run
        // 没有弹简历选择、按钮直接变为「已申请」也算投递完成。
        val done = ZhaopinApplyEngine.step(applying, zp(BossScreen.JOB_DETAIL, applied = true), settings, emptyList(), zone, newId)
        assertEquals("智联默认简历", done.record?.note)
        // 弹出简历选择但没有可用简历：停止且不记录投递。
        val noResume = ZhaopinApplyEngine.step(applying, zp(BossScreen.RESUME_PICKER, options = listOf("某简历")), settings, emptyList(), zone, newId)
        assertEquals(ZhaopinPhase.STOPPED, noResume.run.phase)
        assertNull(noResume.record)
        val mismatch = ZhaopinApplyEngine.step(applying.copy(resumeName = "张三-Android"), zp(BossScreen.RESUME_PICKER, options = listOf("李四")), settings, emptyList(), zone, newId)
        assertEquals(ZhaopinPhase.STOPPED, mismatch.run.phase)
        // 详情页已申请：记为之前投递过并返回。
        val already = ZhaopinApplyEngine.step(opened, zp(BossScreen.JOB_DETAIL, applied = true), settings, emptyList(), zone, newId)
        assertEquals(AutoApplyResult.ALREADY_CONTACTED, already.record?.result)
        assertEquals(ZhaopinPhase.STOPPED, ZhaopinApplyEngine.step(applying, zp(BossScreen.VERIFY), settings, emptyList(), zone, newId).run.phase)
        assertEquals(ZhaopinPhase.STOPPED,
            ZhaopinApplyEngine.step(applying, zp(BossScreen.OTHER, at = noon + AutoApplyEngine.STEP_TIMEOUT_MS + 1), settings, emptyList(), zone, newId).run.phase)
        // 点过投递后暂停：回列表，该职位已在 seen 中不再处理。
        val resumed = ZhaopinApplyEngine.resume(applying, noon + 1)
        assertEquals(ZhaopinPhase.RETURNING, resumed.run.phase)
        assertTrue(resumed.log!!.contains("请到智联核对"))
        assertTrue(AutoApplyRules.jobKey(card) in resumed.run.seen)
        assertEquals(JobPlatform.ZHAOPIN, ZhaopinApplyEngine.fail(applying, "找不到", noon, newId).record?.platform)
    }

    @Test fun zhaopinDryRunAndLimit() {
        val dry = ZhaopinApplyEngine.step(ZhaopinApplyEngine.start(AutoApplyMode.DRY_RUN, noon), zp(BossScreen.JOB_LIST, cards = listOf(card)), AutoApplySettings(), emptyList(), zone, newId)
        assertEquals(ZhaopinPhase.STOPPED, dry.run.phase)
        assertEquals(1, dry.run.preview.size)
        val used = listOf(record(company = "别家").copy(result = AutoApplyResult.APPLIED, platform = JobPlatform.ZHAOPIN))
        val limited = ZhaopinApplyEngine.step(ZhaopinApplyEngine.start(AutoApplyMode.LIVE, noon), zp(BossScreen.JOB_LIST, cards = listOf(card)),
            AutoApplySettings(zhaopinDailyLimit = 1), used, zone, newId)
        assertTrue(limited.run.stopReason!!.contains("智联今日上限"))
        // BOSS 的记录不占智联上限。
        assertEquals(ZhaopinPhase.OPENING, ZhaopinApplyEngine.step(ZhaopinApplyEngine.start(AutoApplyMode.LIVE, noon), zp(BossScreen.JOB_LIST, cards = listOf(card)),
            AutoApplySettings(zhaopinDailyLimit = 1), listOf(record(company = "别家")), zone, newId).run.phase)
    }

    @Test fun reviewCountsZhaopinApplyAsResume() {
        val summary = JobReview.summary(listOf(record(), record().copy(result = AutoApplyResult.APPLIED, platform = JobPlatform.ZHAOPIN)), emptyList(), day, 7, zone)
        // 同一职位在两个平台各算一次；智联投递直接计入已发简历。
        assertEquals(2, summary.funnel.contacted)
        assertEquals(1, summary.funnel.resumes)
    }
}
