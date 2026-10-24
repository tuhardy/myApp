package com.focusassistant.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusassistant.app.domain.AutoApplyDecision
import com.focusassistant.app.domain.AutoApplyRecord
import com.focusassistant.app.domain.AutoApplyResult
import com.focusassistant.app.domain.AutoApplyRules
import com.focusassistant.app.domain.AutoApplySettings
import com.focusassistant.app.domain.JobAssistRules
import com.focusassistant.app.domain.JobAssistStatus
import com.focusassistant.app.domain.JobOpportunity
import com.focusassistant.app.domain.ResumeProfile
import com.focusassistant.app.platform.BossAutomationStatus
import com.focusassistant.app.platform.BossTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val JobLine = Color(0xFFF0E6DF)
private val JobDanger = Color(0xFFB42318)

/** 自动投递分区的只读状态；无障碍开关和 BOSS 版本在页面回到前台时重新读取。 */
internal data class AutoApplyPanel(
    val settings: AutoApplySettings,
    val todayCount: Int,
    val status: BossAutomationStatus,
    val serviceEnabled: Boolean,
    val bossVersion: String?,
    val resumeTodayCount: Int = 0,
    val awaitingResume: Int = 0,
    /** 投递记录，新到旧。 */
    val records: List<AutoApplyRecord> = emptyList()
)

internal class AutoApplyActions(
    val openAccessibility: () -> Unit,
    val acceptRisk: () -> Unit,
    val editRules: () -> Unit,
    val diagnose: () -> Unit,
    val exportDiagnostics: () -> Unit,
    val dryRun: () -> Unit,
    val start: () -> Unit,
    val stop: () -> Unit,
    val togglePause: () -> Unit,
    val resumeDryRun: () -> Unit,
    val resumeStart: () -> Unit,
    val addResume: () -> Unit,
    val editResume: (String) -> Unit,
    val deleteResume: (String) -> Unit,
    val moveResumeUp: (String) -> Unit,
    val clearRecords: () -> Unit
)

private enum class JobTab(val label: String) { CONTACT("沟通"), RESUME("简历"), RECORDS("记录"), DRAFTS("草稿") }

private fun cardModifier() = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, JobLine, RoundedCornerShape(16.dp))

@Composable
internal fun JobAssistScreen(
    jobs: List<JobOpportunity>,
    busy: Boolean,
    auto: AutoApplyPanel,
    autoActions: AutoApplyActions,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onReview: (String) -> Unit,
    onRecordApplied: (String) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(JobTab.CONTACT) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回我的") }
            Column(Modifier.weight(1f)) {
                Text("求职辅助", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("BOSS 自动沟通与发简历 · 本机职位草稿", color = Muted, fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)) {
            item(key = "status") { AutoStatusCard(auto, autoActions) }
            item(key = "ready") { ReadinessCard(auto, autoActions, busy) }
            item(key = "tabs") { JobTabs(tab) { tab = it } }
            when (tab) {
                JobTab.CONTACT -> item(key = "contact") { ContactSection(auto, autoActions, busy) }
                JobTab.RESUME -> item(key = "resume") { ResumeSection(auto, autoActions, busy) }
                JobTab.RECORDS -> item(key = "records") { RecordsSection(auto, autoActions, busy) }
                JobTab.DRAFTS -> {
                    item(key = "drafts-title") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("自动沟通成功时，同公司同岗位的草稿会标为“自动化已发起沟通”。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                            FilledIconButton(onClick = onAdd, enabled = !busy, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent)) {
                                Icon(Icons.Outlined.Add, "新增职位", tint = Color.White)
                            }
                        }
                    }
                    if (jobs.isEmpty()) item(key = "empty") {
                        Column(cardModifier().padding(20.dp)) {
                            Text("还没有职位草稿", fontWeight = FontWeight.SemiBold)
                            Text("可以先在招聘平台查看职位，再把公司、岗位和描述手动粘贴到这里。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                            Button(onClick = onAdd, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.padding(top = 12.dp)) { Text("新增职位") }
                        }
                    }
                    items(jobs, key = { it.id }) { job ->
                        Column(cardModifier().padding(14.dp)) {
                            Text(job.position, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(job.company, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            Text(JobAssistRules.statusLabel(job.status), color = AccentDark, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                            if (job.message.isNotBlank()) Text(job.message, color = Muted, fontSize = 12.sp, maxLines = 3, modifier = Modifier.padding(top = 8.dp))
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onReview(job.id) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("复核文案", maxLines = 1) }
                                    TextButton(onClick = { onEdit(job.id) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(16.dp)); Text("编辑", maxLines = 1) }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onRecordApplied(job.id) }, enabled = !busy && job.status != JobAssistStatus.APPLIED_SELF_REPORTED, modifier = Modifier.weight(1f)) { Text("本人已投递", maxLines = 1) }
                                    TextButton(onClick = { onDelete(job.id) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(16.dp)); Text("删除", maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "advanced") { AdvancedSection(auto, autoActions, busy) }
            item(key = "footer") {
                Text("职位、规则、简历名称和投递记录保存在本机，不包含在完整备份中。复制后剪贴板可能被系统暂存；“本人记录已投递”未经平台核验。", color = Muted,
                    fontSize = 10.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
internal fun JobOpportunityEditorDialog(
    existing: JobOpportunity?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (JobOpportunity) -> Unit
) {
    var company by remember(existing?.id) { mutableStateOf(existing?.company.orEmpty()) }
    var position by remember(existing?.id) { mutableStateOf(existing?.position.orEmpty()) }
    var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    var message by remember(existing?.id) { mutableStateOf(existing?.message.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text(if (existing == null) "新增职位" else "编辑职位", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(company, { company = it.take(JobAssistRules.MAX_COMPANY); error = null }, label = { Text("公司") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(position, { position = it.take(JobAssistRules.MAX_POSITION); error = null }, label = { Text("岗位") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(JobAssistRules.MAX_DESCRIPTION) }, label = { Text("职位描述（可粘贴）") }, enabled = !busy, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(message, { message = it.take(JobAssistRules.MAX_MESSAGE) }, label = { Text("沟通文案（自行编辑）") }, enabled = !busy, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                Text("首版只保存你输入的内容，不联网生成、不读取其他应用。", color = Muted, fontSize = 11.sp)
                error?.let { Text(it, color = Color(0xFFB42318), fontSize = 12.sp) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val now = System.currentTimeMillis()
                val draft = JobOpportunity(existing?.id ?: UUID.randomUUID().toString(), company.trim(), position.trim(), description.trim(), message.trim(),
                    existing?.status ?: JobAssistStatus.DRAFT, existing?.createdAt ?: now, now)
                try { JobAssistRules.validate(draft); onSave(draft) } catch (exception: IllegalArgumentException) { error = exception.message }
            }) { Text(if (busy) "保存中" else "保存") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun JobOpportunityReviewDialog(
    job: JobOpportunity,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text("复核后手动交接", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${job.company} · ${job.position}", fontWeight = FontWeight.SemiBold)
                Text(job.message.ifBlank { "还没有沟通文案，请先编辑并保存。" }, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                Text("确认后只会复制到剪贴板，不会发送消息、打开或控制招聘平台。剪贴板可能被系统暂存。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }, confirmButton = {
            TextButton(enabled = !busy && job.message.isNotBlank(), onClick = onCopy) { Text(if (busy) "处理中" else "确认并复制") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

internal fun copyJobMessage(context: Context, job: JobOpportunity) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("${job.company} · ${job.position}", job.message))
}

@Composable
private fun SectionTitle(text: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.let { Text(it, color = AccentDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** 按钮不可用时的原因，写在按钮下方，避免只看到灰掉的按钮。 */
@Composable
private fun DisabledHint(reason: String?) {
    reason?.let { Text(it, color = Muted, fontSize = 11.sp, lineHeight = 16.sp) }
}

private fun taskLabel(task: BossTask): String = when (task) {
    BossTask.DIAGNOSE -> "诊断中"
    BossTask.DRY_RUN -> "沟通试运行中"
    BossTask.RESUME_DRY_RUN -> "发简历试运行中"
    BossTask.LIVE -> "自动沟通中"
    BossTask.RESUME_LIVE -> "自动发简历中"
}

@Composable
private fun AutoStatusCard(auto: AutoApplyPanel, actions: AutoApplyActions) {
    val status = auto.status
    val task = status.task
    Column(cardModifier().background(if (task != null) AccentSoft else Color.White).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val headline = when {
            task != null && status.paused != null -> "已暂停"
            task != null -> taskLabel(task)
            else -> "空闲"
        }
        Text(headline, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = if (task != null) AccentDark else Color.Unspecified)
        val detail = when {
            task != null && status.paused != null -> status.paused
            task != null && task != BossTask.DIAGNOSE -> "运行中保持亮屏，可放下手机；BOSS 上的悬浮按钮可暂停或停止。"
            task != null -> "在 BOSS 里打开要记录的页面，点悬浮按钮里的「记录本页」。"
            else -> status.stopReason?.let { "最近停止：$it" }
        }
        detail?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 18.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "沟通" to "${auto.todayCount}/${auto.settings.dailyLimit}",
                "简历" to "${auto.resumeTodayCount}/${auto.settings.dailyLimit}",
                "待回复" to auto.awaitingResume.toString()
            ).forEach { (label, value) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(SoftSurface).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = NumeralStyle)
                    Text(if (label == "待回复") label else "今日$label", color = Muted, fontSize = 11.sp)
                }
            }
        }
        if (task != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (task != BossTask.DIAGNOSE) OutlinedButton(onClick = actions.togglePause, modifier = Modifier.weight(1f)) { Text(if (status.paused != null) "继续" else "暂停", maxLines = 1) }
            Button(onClick = actions.stop, colors = ButtonDefaults.buttonColors(containerColor = JobDanger), modifier = Modifier.weight(1f)) {
                Text(if (task == BossTask.DIAGNOSE) "结束诊断" else "停止", maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReadinessCard(auto: AutoApplyPanel, actions: AutoApplyActions, busy: Boolean) {
    val running = auto.status.task != null
    val calibrated = auto.bossVersion != null && auto.settings.calibratedVersion == auto.bossVersion
    val steps = listOf(
        Triple(auto.bossVersion != null, auto.bossVersion?.let { "已安装 BOSS 直聘 $it" } ?: "安装并登录 BOSS 直聘", null),
        Triple(auto.serviceEnabled, if (auto.serviceEnabled) "无障碍服务已开启" else "开启「专注助手 · BOSS 自动投递」无障碍服务", ("去开启" to actions.openAccessibility)),
        Triple(auto.settings.riskAccepted, if (auto.settings.riskAccepted) "已确认风险" else "阅读并确认自动化风险", ("确认" to actions.acceptRisk)),
        Triple(calibrated, if (calibrated) "已在当前版本试运行" else "在职位列表页做一次沟通试运行", ("试运行" to actions.dryRun))
    )
    val ready = steps.all { it.first }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(cardModifier().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ready) "✓ 准备就绪" else "准备 ${steps.count { it.first }}/${steps.size}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (ready) AccentDark else Color.Unspecified, modifier = Modifier.weight(1f).semantics { heading() })
            if (ready) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看") }
        }
        if (!ready || expanded) steps.forEachIndexed { index, (ok, text, action) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(if (ok) Accent else SoftSurface), contentAlignment = Alignment.Center) {
                    Text(if (ok) "✓" else "${index + 1}", color = if (ok) Color.White else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(text, color = if (ok) AccentDark else Color.Unspecified, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                if (!ok && action != null) {
                    // 前一步没完成时后面的按钮不可用，引导按顺序进行。
                    val previousDone = steps.take(index).all { it.first }
                    OutlinedButton(onClick = action.second, enabled = !busy && !running && previousDone, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(action.first, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun JobTabs(selected: JobTab, onSelect: (JobTab) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SoftSurface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        JobTab.entries.forEach { tab ->
            val active = tab == selected
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(9.dp)).background(if (active) Color.White else Color.Transparent)
                .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }), contentAlignment = Alignment.Center) {
                Text(tab.label, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) AccentDark else Muted)
            }
        }
    }
}

@Composable
private fun ContactSection(auto: AutoApplyPanel, actions: AutoApplyActions, busy: Boolean) {
    val status = auto.status
    val running = status.task != null
    val calibrated = auto.bossVersion != null && auto.settings.calibratedVersion == auto.bossVersion
    Column(cardModifier().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("自动沟通", "今日 ${auto.todayCount}/${auto.settings.dailyLimit}")
        Text("在职位推荐列表里按规则打开职位、点「立即沟通」并发送招呼语。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SoftSurface).clickable(enabled = !busy && !running, onClick = actions.editRules).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("沟通规则", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Edit, "编辑沟通规则", tint = Muted, modifier = Modifier.size(16.dp))
            }
            Text(rulesSummary(auto.settings), color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.dryRun, enabled = !busy && !running, modifier = Modifier.weight(1f)) { Text("试运行", maxLines = 1) }
            Button(onClick = actions.start, enabled = !busy && !running && calibrated, colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.weight(1f)) { Text("开始沟通", maxLines = 1) }
        }
        DisabledHint(when {
            running -> "有任务在运行，先暂停或停止"
            !calibrated -> "先在 BOSS 职位列表页试运行一次，核对清单后才能开始"
            else -> null
        })
        if (status.preview.isNotEmpty()) {
            Text("试运行清单（未点击）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            status.preview.take(20).forEach { item ->
                val decision = item.decision
                Text("${if (decision == AutoApplyDecision.Apply) "会沟通" else "跳过"} · ${item.card.position} · ${item.card.company} · ${item.card.salary}" +
                    (if (decision is AutoApplyDecision.Skip) "（${decision.reason}）" else ""),
                    color = if (decision == AutoApplyDecision.Apply) AccentDark else Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun ResumeSection(auto: AutoApplyPanel, actions: AutoApplyActions, busy: Boolean) {
    val status = auto.status
    val running = status.task != null
    val resumes = auto.settings.resumes
    val resumeCalibrated = auto.bossVersion != null && auto.settings.resumeCalibratedVersion == auto.bossVersion
    Column(cardModifier().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("自动发简历", "今日 ${auto.resumeTodayCount}/${auto.settings.dailyLimit} · 待回复 ${auto.awaitingResume}")
        Text("对方回复后才能发附件简历。只处理本应用自动沟通过、会话有新消息的公司，不读消息内容；按岗位关键词选简历，没命中用默认简历。",
            color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的简历", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            Text("${resumes.size}/${AutoApplyRules.MAX_RESUMES}", color = Muted, fontSize = 12.sp)
        }
        if (resumes.isEmpty()) Text("还没有简历。名称要和 BOSS「附件简历」里的文件名一致，例如「张三-Android.pdf」。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        resumes.forEachIndexed { index, resume ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, JobLine, RoundedCornerShape(12.dp)).padding(start = 12.dp, top = 10.dp, bottom = 4.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(resume.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    if (resume.isDefault) Text("默认", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(Accent).padding(horizontal = 6.dp, vertical = 1.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { actions.moveResumeUp(resume.id) }, enabled = !busy && !running && index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "提高「${resume.name}」的优先级") }
                    IconButton(onClick = { actions.editResume(resume.id) }, enabled = !busy && !running) { Icon(Icons.Outlined.Edit, "编辑「${resume.name}」", modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { actions.deleteResume(resume.id) }, enabled = !busy && !running) { Icon(Icons.Outlined.DeleteOutline, "删除「${resume.name}」", modifier = Modifier.size(18.dp)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (resume.keywords.isEmpty()) Text("无关键词 · 只在其他简历都不命中时使用", color = Muted, fontSize = 11.sp)
                    resume.keywords.forEach { word ->
                        Text(word, color = AccentDark, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AccentSoft).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
        }
        OutlinedButton(onClick = actions.addResume, enabled = !busy && !running && resumes.size < AutoApplyRules.MAX_RESUMES, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp)); Text("添加简历")
        }
        if (resumes.isNotEmpty() && resumes.none { it.isDefault }) Text("没有默认简历：关键词都没命中的会话会跳过。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.resumeDryRun, enabled = !busy && !running && resumes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("发简历试运行", maxLines = 1) }
            Button(onClick = actions.resumeStart, enabled = !busy && !running && resumes.isNotEmpty() && resumeCalibrated, colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.weight(1f)) { Text("开始发简历", maxLines = 1) }
        }
        DisabledHint(when {
            running -> "有任务在运行，先暂停或停止"
            resumes.isEmpty() -> "先添加至少一份简历"
            !resumeCalibrated -> "先在 BOSS 消息页做一次发简历试运行，核对清单后才能开始"
            else -> null
        })
        status.resumePreview?.let { preview ->
            Text("发简历试运行清单（未点击）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (preview.isEmpty()) Text("没有找到自动沟通过且有新消息的会话", color = Muted, fontSize = 11.sp)
            preview.take(20).forEach { item ->
                Text(item.resumeName?.let { "会发送「$it」 · ${item.position} · ${item.company}" } ?: "跳过 · ${item.position} · ${item.company}（${item.reason}）",
                    color = if (item.resumeName != null) AccentDark else Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

private val recordTime = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun resultLabel(result: AutoApplyResult): String = when (result) {
    AutoApplyResult.CONTACTED -> "已沟通"
    AutoApplyResult.ALREADY_CONTACTED -> "之前沟通过"
    AutoApplyResult.FAILED -> "失败"
    AutoApplyResult.RESUME_SENT -> "已发简历"
}

@Composable
private fun RecordsSection(auto: AutoApplyPanel, actions: AutoApplyActions, busy: Boolean) {
    val status = auto.status
    var showLog by rememberSaveable { mutableStateOf(false) }
    Column(cardModifier().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("投递记录", "共 ${auto.records.size} 条")
        if (auto.records.isEmpty()) Text("还没有记录。自动沟通和发简历的结果会出现在这里。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        auto.records.take(MAX_SHOWN_RECORDS).forEach { record ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${record.position} · ${record.company}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(resultLabel(record.result), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (record.result == AutoApplyResult.FAILED) JobDanger else AccentDark)
                }
                val time = Instant.ofEpochMilli(record.at).atZone(ZoneId.systemDefault()).format(recordTime)
                Text(listOf(time, record.salary, record.note).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        if (auto.records.size > MAX_SHOWN_RECORDS) Text("只显示最近 $MAX_SHOWN_RECORDS 条", color = Muted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showLog = !showLog }, modifier = Modifier.weight(1f)) { Text(if (showLog) "收起运行日志" else "运行日志", maxLines = 1) }
            TextButton(onClick = actions.clearRecords, enabled = !busy && status.task == null && auto.records.isNotEmpty(), modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(contentColor = JobDanger)) { Text("清空记录", maxLines = 1) }
        }
        if (showLog) {
            if (status.log.isEmpty()) Text("还没有运行日志", color = Muted, fontSize = 11.sp)
            status.log.takeLast(30).asReversed().forEach { Text(it, color = Muted, fontSize = 11.sp, lineHeight = 16.sp) }
        }
    }
}

@Composable
private fun AdvancedSection(auto: AutoApplyPanel, actions: AutoApplyActions, busy: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val running = auto.status.task != null
    Column(cardModifier().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("高级 · 界面诊断", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", color = Muted, fontSize = 12.sp)
        }
        if (expanded) Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("识别不了 BOSS 界面时，用诊断导出控件结构给开发者核对。只记录控件结构和文字长度，不含职位与聊天正文；BOSS 更新后需要重新诊断和试运行。",
                color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.diagnose, enabled = !busy && !running, modifier = Modifier.weight(1f)) { Text("界面诊断", maxLines = 1) }
                OutlinedButton(onClick = actions.exportDiagnostics, enabled = !busy && auto.status.diagnostics > 0, modifier = Modifier.weight(1f)) { Text("导出 ${auto.status.diagnostics} 页", maxLines = 1) }
            }
        }
    }
}

private const val MAX_SHOWN_RECORDS = 30

private fun rulesSummary(value: AutoApplySettings): String = listOfNotNull(
    if (value.includeKeywords.isEmpty()) "关键词：不限" else "关键词：${value.includeKeywords.joinToString("、")}",
    value.excludeKeywords.takeIf { it.isNotEmpty() }?.let { "排除：${it.joinToString("、")}" },
    value.minSalaryK?.let { "最低 ${it}K" },
    value.city.takeIf { it.isNotBlank() }?.let { "城市：$it" },
    value.companyBlacklist.takeIf { it.isNotEmpty() }?.let { "黑名单 ${it.size} 家" },
    "每日 ${value.dailyLimit} 条",
    "间隔 ${value.stepDelayMs / 1000} 秒",
    if (value.greeting.isBlank()) "BOSS 默认招呼语" else "自定义招呼语"
).joinToString(" · ")

@Composable
internal fun ResumeEditDialog(existing: ResumeProfile?, busy: Boolean, onDismiss: () -> Unit, onSave: (ResumeProfile) -> Unit, validate: (ResumeProfile) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var keywords by remember(existing?.id) { mutableStateOf(existing?.keywords?.joinToString("，").orEmpty()) }
    var isDefault by remember(existing?.id) { mutableStateOf(existing?.isDefault ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text(if (existing == null) "添加简历" else "编辑简历", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it.take(AutoApplyRules.MAX_RESUME_NAME); error = null }, label = { Text("简历名称") }, singleLine = true, enabled = !busy,
                    placeholder = { Text("张三-Android.pdf") }, modifier = Modifier.fillMaxWidth())
                Text("要和 BOSS「附件简历」里的文件名完全一致，扩展名可以省略。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                OutlinedTextField(keywords, { keywords = it; error = null }, label = { Text("岗位关键词（逗号分隔）") }, enabled = !busy, minLines = 1, maxLines = 3,
                    placeholder = { Text("Android，Kotlin") }, modifier = Modifier.fillMaxWidth())
                Text("岗位名称包含任一关键词时发这份简历；多份都命中时取列表里靠前的。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { isDefault = !isDefault; error = null }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("设为默认简历", fontSize = 14.sp)
                        Text("关键词都没命中时发这份；只能有一份默认", color = Muted, fontSize = 11.sp)
                    }
                    Switch(isDefault, { isDefault = it; error = null }, enabled = !busy, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                }
                error?.let { Text(it, color = JobDanger, fontSize = 12.sp) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val value = ResumeProfile(existing?.id ?: UUID.randomUUID().toString(), name.trim(), AutoApplyRules.parseList(keywords), isDefault)
                try { validate(value); onSave(value) } catch (exception: IllegalArgumentException) { error = exception.message }
            }) { Text(if (busy) "保存中" else "保存") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun AutoApplyRiskDialog(busy: Boolean, onDismiss: () -> Unit, onAccept: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text("自动投递风险", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "自动操作招聘 App 可能违反 BOSS 直聘用户协议，账号可能被限制沟通或封禁，后果由你自行承担。",
                    "本功能不会识别或绕过验证码、不伪装设备、不随机化节奏；平台出现验证、登录或次数上限时会立即停止。",
                    "运行中会保持亮屏，建议插上充电器；触碰 BOSS 界面、离开 BOSS 或熄屏会暂停，可从悬浮按钮继续。",
                    "只操作你手机上已登录的 BOSS 直聘，不联网、不读取或保存账号与 Cookie。",
                    "“自动化已发起沟通”只表示界面上已发出，不代表对方已读或投递成功。"
                ).forEach { Text("· $it", color = Muted, fontSize = 13.sp, lineHeight = 20.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { checked = it }, enabled = !busy)
                    Text("我已了解并自行承担风险", fontSize = 13.sp)
                }
            }
        }, confirmButton = {
            TextButton(enabled = !busy && checked, onClick = onAccept) { Text("确认") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun AutoApplyRulesDialog(settings: AutoApplySettings, busy: Boolean, onDismiss: () -> Unit, onSave: (AutoApplySettings) -> Unit) {
    var include by remember { mutableStateOf(settings.includeKeywords.joinToString("，")) }
    var exclude by remember { mutableStateOf(settings.excludeKeywords.joinToString("，")) }
    var salary by remember { mutableStateOf(settings.minSalaryK?.toString().orEmpty()) }
    var city by remember { mutableStateOf(settings.city) }
    var blacklist by remember { mutableStateOf(settings.companyBlacklist.joinToString("，")) }
    var limit by remember { mutableStateOf(settings.dailyLimit.toString()) }
    var delaySeconds by remember { mutableStateOf((settings.stepDelayMs / 1000).toString()) }
    var greeting by remember { mutableStateOf(settings.greeting) }
    var error by remember { mutableStateOf<String?>(null) }
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text("沟通规则", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(include, { include = it; error = null }, label = { Text("岗位关键词（逗号分隔，留空不限）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(exclude, { exclude = it; error = null }, label = { Text("排除词") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(salary, { salary = it.filter(Char::isDigit).take(4); error = null }, label = { Text("最低月薪（K，留空不限）") }, singleLine = true, enabled = !busy, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(city, { city = it.take(AutoApplyRules.MAX_CITY); error = null }, label = { Text("城市（留空不限）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(blacklist, { blacklist = it; error = null }, label = { Text("公司黑名单") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(3); error = null }, label = { Text("每日上限（1–${AutoApplyRules.MAX_DAILY_LIMIT}）") }, singleLine = true, enabled = !busy, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(delaySeconds, { delaySeconds = it.filter(Char::isDigit).take(2); error = null }, label = { Text("步骤最短间隔（${AutoApplyRules.MIN_STEP_DELAY_MS / 1000}–${AutoApplyRules.MAX_STEP_DELAY_MS / 1000} 秒，固定不随机）") }, singleLine = true, enabled = !busy, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(greeting, { greeting = it.take(AutoApplyRules.MAX_GREETING); error = null }, label = { Text("自定义招呼语（留空用 BOSS 默认）") }, enabled = !busy, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Text("BOSS 界面一变化就进入下一步，两步之间至少间隔上面的秒数。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                Text("同一职位只沟通一次、只发一次简历，同一公司每天只联系一次；每日上限对沟通和发简历分别计算。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                error?.let { Text(it, color = JobDanger, fontSize = 12.sp) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                try {
                    val next = settings.copy(includeKeywords = AutoApplyRules.parseList(include), excludeKeywords = AutoApplyRules.parseList(exclude),
                        minSalaryK = salary.toIntOrNull(), city = city.trim(), companyBlacklist = AutoApplyRules.parseList(blacklist),
                        dailyLimit = limit.toIntOrNull() ?: 0, stepDelayMs = (delaySeconds.toLongOrNull() ?: 0) * 1000, greeting = greeting.trim())
                    AutoApplyRules.validateSettings(next); onSave(next)
                } catch (exception: IllegalArgumentException) { error = exception.message }
            }) { Text(if (busy) "保存中" else "保存") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
