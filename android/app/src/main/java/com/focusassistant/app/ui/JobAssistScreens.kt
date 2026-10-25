package com.focusassistant.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import com.focusassistant.app.domain.JobOutcome
import com.focusassistant.app.domain.JobPlatform
import com.focusassistant.app.domain.JobReviewSummary
import com.focusassistant.app.domain.JobSource
import com.focusassistant.app.domain.JobStage
import com.focusassistant.app.domain.ResumeProfile
import com.focusassistant.app.platform.BossAutomationStatus
import com.focusassistant.app.platform.BossTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val JobLine = Color(0xFFF0E6DF)
private val JobDanger = Color(0xFFB42318)

/** 自动投递分区的只读状态；无障碍开关和所选平台的 App 版本在页面回到前台时重新读取。todayCount 是所选平台的今日联系数。 */
internal data class AutoApplyPanel(
    val settings: AutoApplySettings,
    val todayCount: Int,
    val status: BossAutomationStatus,
    val serviceEnabled: Boolean,
    val appVersion: String?,
    val resumeTodayCount: Int = 0,
    val awaitingResume: Int = 0,
    /** 投递记录，新到旧。 */
    val records: List<AutoApplyRecord> = emptyList(),
    /** 数据复盘：近 7 天与近 30 天，由 JobReview.summary 计算。 */
    val reviewWeek: JobReviewSummary? = null,
    val reviewMonth: JobReviewSummary? = null,
    /** 已用 adb 授予 WRITE_SECURE_SETTINGS，无障碍被系统关掉后会自动恢复。 */
    val canReEnable: Boolean = false
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
    val clearRecords: () -> Unit,
    val applyPreset: (String) -> Unit = {},
    val managePresets: () -> Unit = {},
    val editStage: (String) -> Unit = {},
    val selectPlatform: (JobPlatform) -> Unit = {},
    val setHideTargetPage: (Boolean) -> Unit = {},
    val setShowOverlay: (Boolean) -> Unit = {},
    val keepEnabledHelp: () -> Unit = {}
)

private enum class JobTab(val label: String) { CONTACT("沟通"), RESUME("简历"), PROGRESS("进度"), DATA("数据") }

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
    onDelete: (String) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(JobTab.CONTACT) }
    var stageFilter by rememberSaveable { mutableStateOf<JobStage?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回我的") }
            Column(Modifier.weight(1f)) {
                Text("求职辅助", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("BOSS / 智联自动投递 · 求职进度", color = Muted, fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)) {
            item(key = "status") { AutoStatusCard(auto, autoActions) }
            item(key = "ready") { ReadinessCard(auto, autoActions, busy) }
            item(key = "tabs") { JobTabs(tab) { tab = it } }
            when (tab) {
                JobTab.CONTACT -> item(key = "contact") { ContactSection(auto, autoActions, busy) }
                JobTab.RESUME -> item(key = "resume") { ResumeSection(auto, autoActions, busy) }
                JobTab.DATA -> {
                    item(key = "review") { ReviewSection(auto) }
                    item(key = "records") { RecordsSection(auto, autoActions, busy) }
                }
                JobTab.PROGRESS -> {
                    item(key = "progress-title") {
                        // 与浏览器 #job-panel-progress 同一布局：标题行右侧唯一的“手动添加”，下方一行说明与单行阶段筛选。
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("求职进度", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                                OutlinedButton(onClick = onAdd, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp)); Text("手动添加", fontSize = 12.sp, color = AccentDark)
                                }
                            }
                            Text("自动沟通（BOSS）和自动投递（智联）的职位会自动加入；面试、Offer、不合适由你标记。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            StageFilter(jobs, stageFilter) { stageFilter = it }
                        }
                    }
                    val shown = jobs.filter { stageFilter == null || JobAssistRules.stage(it) == stageFilter }
                    if (jobs.isEmpty()) item(key = "empty") {
                        Column(cardModifier().padding(20.dp)) {
                            Text("还没有职位", fontWeight = FontWeight.SemiBold)
                            Text("开始自动沟通或投递后，职位会出现在这里；自己联系或内推的职位，可点右上角“手动添加”。", color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    } else if (shown.isEmpty()) item(key = "stage-empty") { Text("这个阶段还没有职位。", color = Muted, fontSize = 12.sp) }
                    items(shown, key = { it.id }) { job ->
                        val stage = JobAssistRules.stage(job)
                        val autoJob = job.source == JobSource.AUTO
                        Column(cardModifier().background(if (stage == JobStage.CLOSED) SoftSurface else Color.White).padding(14.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(job.position, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(listOf(job.company, job.salary).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                                }
                                Text(JobAssistRules.displayLabel(job), color = if (stage == JobStage.CLOSED) Muted else AccentDark, fontSize = 11.sp,
                                    fontWeight = if (job.outcome != null) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 8.dp))
                            }
                            Text(listOfNotNull(if (autoJob) "${(job.platform ?: JobPlatform.BOSS).short} 自动加入" else "手动添加", job.preset.takeIf { it.isNotBlank() }?.let { "方案 $it" }).joinToString(" · "),
                                color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                            if (job.note.isNotBlank()) Text(job.note, fontSize = 12.sp, lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SoftSurface).padding(horizontal = 10.dp, vertical = 6.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { autoActions.editStage(job.id) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("阶段与备注", maxLines = 1) }
                                // 自动建档的职位按公司+岗位与记录对应，不允许改名。
                                if (!autoJob) TextButton(onClick = { onEdit(job.id) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(16.dp)); Text("编辑", maxLines = 1) }
                                TextButton(onClick = { onDelete(job.id) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(16.dp)); Text("删除", maxLines = 1) }
                            }
                        }
                    }
                }
            }
            item(key = "advanced") { AdvancedSection(auto, autoActions, busy) }
            item(key = "footer") {
                Text("职位、方案、规则、简历名称和投递记录保存在本机，不包含在完整备份中。手动添加的职位是本人记录，未经平台核验。", color = Muted,
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
    var note by remember(existing?.id) { mutableStateOf(existing?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text(if (existing == null) "手动添加职位" else "编辑职位", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(company, { company = it.take(JobAssistRules.MAX_COMPANY); error = null }, label = { Text("公司") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(position, { position = it.take(JobAssistRules.MAX_POSITION); error = null }, label = { Text("岗位") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it.take(JobAssistRules.MAX_NOTE) }, label = { Text("备注（可选，最多 ${JobAssistRules.MAX_NOTE} 字）") }, enabled = !busy, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                Text("用来跟踪你自己在招聘平台上联系或被内推的职位，记为“已沟通 · 本人记录”，本应用无法核验。之后可在“阶段与备注”里标面试、Offer 或不合适。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                error?.let { Text(it, color = Color(0xFFB42318), fontSize = 12.sp) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val now = System.currentTimeMillis()
                val draft = (existing ?: JobOpportunity(UUID.randomUUID().toString(), "", "", "", "", JobAssistStatus.APPLIED_SELF_REPORTED, now, now))
                    .copy(company = company.trim(), position = position.trim(), note = note.trim(), updatedAt = now)
                try { JobAssistRules.validate(draft); onSave(draft) } catch (exception: IllegalArgumentException) { error = exception.message }
            }) { Text(if (busy) "保存中" else "保存") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
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

private fun taskLabel(task: BossTask, platform: JobPlatform): String = when (task) {
    BossTask.DIAGNOSE -> "${platform.short} 诊断中"
    BossTask.DRY_RUN -> if (platform == JobPlatform.ZHAOPIN) "智联试运行中" else "沟通试运行中"
    BossTask.RESUME_DRY_RUN -> "发简历试运行中"
    BossTask.LIVE -> if (platform == JobPlatform.ZHAOPIN) "智联投递中" else "自动沟通中"
    BossTask.RESUME_LIVE -> "自动发简历中"
}

/** 每次运行只操作一个平台；运行中不能切换。 */
@Composable
private fun PlatformSwitch(auto: AutoApplyPanel, actions: AutoApplyActions) {
    val running = auto.status.task != null
    val current = auto.status.platform.takeIf { running } ?: auto.settings.platform
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SoftSurface).padding(3.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        JobPlatform.entries.forEach { platform ->
            val active = platform == current
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(9.dp)).background(if (active) Color.White else Color.Transparent)
                .selectable(selected = active, enabled = !running, role = Role.RadioButton) { if (!active) actions.selectPlatform(platform) }, contentAlignment = Alignment.Center) {
                Text(platform.label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) AccentDark else Muted)
            }
        }
    }
    if (running) DisabledHint("运行中不能切换平台")
}

@Composable
private fun AutoStatusCard(auto: AutoApplyPanel, actions: AutoApplyActions) {
    val status = auto.status
    val task = status.task
    val platform = status.platform.takeIf { task != null } ?: auto.settings.platform
    val zhaopin = platform == JobPlatform.ZHAOPIN
    val limit = AutoApplyRules.limitFor(auto.settings, platform)
    Column(cardModifier().background(if (task != null) AccentSoft else Color.White).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlatformSwitch(auto, actions)
        val headline = when {
            task != null && status.paused != null -> "已暂停"
            task != null -> taskLabel(task, platform)
            else -> "空闲"
        }
        Text(headline, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = if (task != null) AccentDark else Color.Unspecified)
        val detail = when {
            task != null && status.paused != null -> status.paused
            task != null && task != BossTask.DIAGNOSE -> "运行中保持亮屏，可放下手机；${platform.short} 上的悬浮按钮可暂停或停止。"
            task != null -> "在${platform.short}里打开要记录的页面，点悬浮按钮里的「记录本页」。"
            else -> status.stopReason?.let { "最近停止：$it" }
        }
        detail?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 18.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (zhaopin) listOf("投递" to "${auto.todayCount}/$limit") else listOf(
                "沟通" to "${auto.todayCount}/$limit",
                "简历" to "${auto.resumeTodayCount}/$limit",
                "待回复" to auto.awaitingResume.toString()
            )).forEach { (label, value) ->
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
    val platform = auto.settings.platform
    val calibrated = auto.appVersion != null && AutoApplyRules.calibratedFor(auto.settings, platform) == auto.appVersion
    val steps = listOf(
        Triple(auto.appVersion != null, auto.appVersion?.let { "已安装 ${platform.label} $it" } ?: "安装并登录 ${platform.label}", null),
        Triple(auto.serviceEnabled, if (auto.serviceEnabled) "无障碍服务已开启" else "开启「专注助手 · 求职自动化」无障碍服务", ("去开启" to actions.openAccessibility)),
        Triple(auto.settings.riskAccepted, if (auto.settings.riskAccepted) "已确认风险" else "阅读并确认自动化风险", ("确认" to actions.acceptRisk)),
        Triple(calibrated, if (calibrated) "已在当前版本试运行" else "在${platform.short}职位列表页做一次试运行", ("试运行" to actions.dryRun))
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
        if (!ready || expanded) TextButton(onClick = actions.keepEnabledHelp, contentPadding = PaddingValues(0.dp)) {
            Text(if (auto.canReEnable) "✓ 已开启自动恢复无障碍" else "总要重新开启无障碍？设置自动恢复", fontSize = 12.sp, color = AccentDark)
        }
    }
}

@Composable
internal fun KeepEnabledDialog(command: String, granted: Boolean, onDismiss: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White,
        title = { Text("免重复开启无障碍", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (granted) "已授权：服务被系统关掉后，回到本应用会自动重新开启。" else "系统会在应用更新、崩溃或被清理后关闭无障碍。电脑连接手机（开启 USB 调试）执行一次下面的命令，之后回到本应用即可自动恢复开启：",
                    color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                Text(command, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SoftSurface).padding(10.dp))
                Text("同时建议在系统设置里把专注助手设为「省电无限制 / 允许自启动」，并在最近任务里锁定，减少被系统清理。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text("授权只在本机生效，卸载重装需重新执行；本应用不联网，也不会用它修改其他设置。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("复制命令") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
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
    val platform = auto.settings.platform
    val zhaopin = platform == JobPlatform.ZHAOPIN
    val calibrated = auto.appVersion != null && AutoApplyRules.calibratedFor(auto.settings, platform) == auto.appVersion
    Column(cardModifier().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(if (zhaopin) "智联自动投递" else "自动沟通", "今日 ${auto.todayCount}/${AutoApplyRules.limitFor(auto.settings, platform)}")
        Text(if (zhaopin) "在智联职位列表里按规则打开职位、点「申请职位」；需要选简历时按「我的简历」选择同名简历后确认。"
            else "在职位推荐列表里按规则打开职位、点「立即沟通」并发送招呼语。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        PresetChips(auto.settings, enabled = !busy && !running, actions)
        if (running && auto.settings.presets.isNotEmpty()) DisabledHint("运行中不能切换方案")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SoftSurface).clickable(enabled = !busy && !running, onClick = actions.editRules).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (zhaopin) "投递规则" else "沟通规则", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Edit, if (zhaopin) "编辑投递规则" else "编辑沟通规则", tint = Muted, modifier = Modifier.size(16.dp))
            }
            Text(rulesSummary(auto.settings), color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.dryRun, enabled = !busy && !running, modifier = Modifier.weight(1f)) { Text("试运行", maxLines = 1) }
            Button(onClick = actions.start, enabled = !busy && !running && calibrated, colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.weight(1f)) { Text(if (zhaopin) "开始投递" else "开始沟通", maxLines = 1) }
        }
        DisabledHint(when {
            running -> "有任务在运行，先暂停或停止"
            !calibrated -> "先在${platform.short}职位列表页试运行一次，核对清单后才能开始"
            else -> null
        })
        if (status.preview.isNotEmpty()) {
            Text("试运行清单（未点击）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            status.preview.take(20).forEach { item ->
                val decision = item.decision
                Text("${if (decision != AutoApplyDecision.Apply) "跳过" else if (zhaopin) "会投递" else "会沟通"} · ${item.card.position} · ${item.card.company} · ${item.card.salary}" +
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
    val zhaopin = auto.settings.platform == JobPlatform.ZHAOPIN
    val resumeCalibrated = auto.appVersion != null && auto.settings.resumeCalibratedVersion == auto.appVersion
    Column(cardModifier().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (zhaopin) {
            SectionTitle("投递用简历", "两个平台共用")
            Text("智联投递时直接附带简历，不需要等回复。弹出简历选择时按岗位关键词选简历，没命中用默认简历；找不到同名简历会停止，不投递。",
                color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        } else {
            SectionTitle("自动发简历", "今日 ${auto.resumeTodayCount}/${auto.settings.dailyLimit} · 待回复 ${auto.awaitingResume}")
            Text("对方回复后才能发附件简历。只处理本应用自动沟通过、会话有新消息的公司，不读消息内容；按岗位关键词选简历，没命中用默认简历。",
                color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的简历", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            Text("${resumes.size}/${AutoApplyRules.MAX_RESUMES}", color = Muted, fontSize = 12.sp)
        }
        if (resumes.isEmpty()) Text("还没有简历。名称要和招聘 App 里的简历名称一致，例如 BOSS 附件「张三-Android.pdf」。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
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
        if (resumes.isNotEmpty() && resumes.none { it.isDefault }) Text(if (zhaopin) "没有默认简历：关键词都没命中、且智联要求选简历时会停止。" else "没有默认简历：关键词都没命中的会话会跳过。",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        // 智联没有回复后发简历，只管理共用的「我的简历」。
        if (!zhaopin) {
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
        }
        if (!zhaopin) status.resumePreview?.let { preview ->
            Text("发简历试运行清单（未点击）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (preview.isEmpty()) Text("没有找到自动沟通过且有新消息的会话", color = Muted, fontSize = 11.sp)
            preview.take(20).forEach { item ->
                Text(item.resumeName?.let { "会发送「$it」 · ${item.position} · ${item.company}" } ?: "跳过 · ${item.position} · ${item.company}（${item.reason}）",
                    color = if (item.resumeName != null) AccentDark else Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean, dashed: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.heightIn(min = 40.dp).clip(shape).background(if (selected) AccentSoft else Color.White)
        .border(1.dp, if (selected) Accent else JobLine, shape)
        .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, color = if (selected) AccentDark else if (dashed) AccentDark else Muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** 筛选方案：点一下切换，与浏览器 #auto-presets 同一交互；最后一个按钮打开方案管理。 */
@Composable
private fun PresetChips(settings: AutoApplySettings, enabled: Boolean, actions: AutoApplyActions) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        settings.presets.forEach { preset ->
            Chip(preset.name, preset.id == settings.activePresetId, enabled) { if (preset.id != settings.activePresetId) actions.applyPreset(preset.id) }
        }
        Chip(if (settings.presets.isEmpty()) "＋ 存为方案" else "管理方案", false, enabled, dashed = true, onClick = actions.managePresets)
    }
}

@Composable
private fun StageFilter(jobs: List<JobOpportunity>, selected: JobStage?, onSelect: (JobStage?) -> Unit) {
    val counts = jobs.groupingBy(JobAssistRules::stage).eachCount()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("全部 ${jobs.size}", selected == null, true) { onSelect(null) }
        JobStage.entries.forEach { stage -> Chip("${stage.label} ${counts[stage] ?: 0}", selected == stage, true) { onSelect(stage) } }
    }
}

/** 数据复盘：与浏览器 #job-panel-data 同口径，漏斗以职位计；“已发简历”只说明对方回复过。 */
@Composable
private fun ReviewSection(auto: AutoApplyPanel) {
    var month by rememberSaveable { mutableStateOf(false) }
    val review = (if (month) auto.reviewMonth else auto.reviewWeek) ?: return
    Column(cardModifier().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("数据复盘", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(SoftSurface).padding(3.dp)) {
                listOf(false to "近 7 天", true to "近 30 天").forEach { (value, label) ->
                    Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(9.dp)).background(if (month == value) Color.White else Color.Transparent)
                        .selectable(selected = month == value, role = Role.Tab) { month = value }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.sp, color = if (month == value) AccentDark else Muted, fontWeight = if (month == value) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        val funnel = review.funnel
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("联系" to funnel.contacted, "已发简历" to funnel.resumes, "面试" to funnel.interviews, "Offer" to funnel.offers, "不合适" to funnel.closed).forEachIndexed { index, (label, count) ->
                val closed = index == 4
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (closed) SoftSurface else AccentSoft)
                    .semantics(mergeDescendants = true) {}.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$count", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (closed) Muted else AccentDark, style = NumeralStyle)
                    Text(label, fontSize = 10.sp, color = Muted, maxLines = 1)
                }
            }
        }
        Text("以职位计：BOSS 自动沟通和智联自动投递过的职位里，有多少已发简历（BOSS 需对方回复后才能发，智联投递即附带）、被你标为面试或 Offer。不代表 HR 已读。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        val dayLabel = DateTimeFormatter.ofPattern("MM-dd")
        ScrubBars(review.dailyContacted.map { it.toLong() }, { review.days[it].format(dayLabel) }, "最多", "这段时间没有联系", "每日联系",
            if (month) 2.dp else 6.dp, valueLabel = { "$it 次" }, chartHeight = 80.dp) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(review.days.first().format(dayLabel), color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text("今天", color = Muted, fontSize = 10.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("按方案", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("联系 · 已发简历", color = Muted, fontSize = 11.sp)
        }
        if (review.byPreset.isEmpty()) Text("还没有自动沟通或投递记录。", color = Muted, fontSize = 12.sp)
        review.byPreset.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(row.preset.ifBlank { "未用方案" }, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${row.contacted} · ${row.resumes}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = NumeralStyle)
            }
            HorizontalDivider(color = JobLine)
        }
    }
}

/** 阶段与备注：面试 / Offer / 不合适由用户标记；选“自动阶段”即清除手动结果。 */
@Composable
internal fun JobStageDialog(job: JobOpportunity, busy: Boolean, onDismiss: () -> Unit, onSave: (JobOutcome?, String) -> Unit) {
    var outcome by remember(job.id) { mutableStateOf(job.outcome) }
    var note by remember(job.id) { mutableStateOf(job.note) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text("${job.position} · ${job.company}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("结果（由你标记，自动记录不会覆盖）", color = Muted, fontSize = 12.sp)
                (listOf<JobOutcome?>(null) + JobOutcome.entries).forEach { value ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = outcome == value, role = Role.RadioButton) { outcome = value },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = outcome == value, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Accent))
                        Text(value?.label ?: "自动阶段：${JobAssistRules.statusLabel(job.status)}", fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                OutlinedTextField(note, { note = it.take(JobAssistRules.MAX_NOTE) }, label = { Text("备注（最多 ${JobAssistRules.MAX_NOTE} 字）") }, enabled = !busy,
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { onSave(outcome, note) }) { Text(if (busy) "保存中" else "保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

/** 方案管理：把当前规则存为方案（同名覆盖），或删除已有方案；与浏览器 autoPresetDialog 一致。 */
@Composable
internal fun FilterPresetDialog(settings: AutoApplySettings, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: (String) -> Unit) {
    var name by remember { mutableStateOf(AutoApplyRules.activePresetName(settings)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text("筛选方案", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(AutoApplyRules.MAX_PRESET_NAME); error = null }, label = { Text("方案名称（最多 ${AutoApplyRules.MAX_PRESET_NAME} 字，同名覆盖）") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("方案保存岗位关键词、排除词、最低月薪、城市和招呼语；黑名单、每日上限、间隔和简历对所有方案通用。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                settings.presets.forEach { preset ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(preset.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onDelete(preset.id) }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "删除方案「${preset.name}」", tint = JobDanger) }
                    }
                    HorizontalDivider(color = JobLine)
                }
                error?.let { Text(it, color = JobDanger, fontSize = 12.sp) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                try {
                    require(name.isNotBlank()) { "请输入方案名称" }
                    AutoApplyRules.validateSettings(AutoApplyRules.savePreset(settings, name, "check"))
                    onSave(name)
                } catch (exception: IllegalArgumentException) { error = exception.message }
            }) { Text(if (busy) "保存中" else "保存当前规则") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

private val recordTime = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun resultLabel(result: AutoApplyResult): String = when (result) {
    AutoApplyResult.CONTACTED -> "已沟通"
    AutoApplyResult.ALREADY_CONTACTED -> "之前沟通过"
    AutoApplyResult.FAILED -> "失败"
    AutoApplyResult.RESUME_SENT -> "已发简历"
    AutoApplyResult.APPLIED -> "已投递"
}

@Composable
private fun RecordsSection(auto: AutoApplyPanel, actions: AutoApplyActions, busy: Boolean) {
    val status = auto.status
    var showLog by rememberSaveable { mutableStateOf(false) }
    Column(cardModifier().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("投递记录", "共 ${auto.records.size} 条")
        if (auto.records.isEmpty()) Text("还没有记录。自动沟通、发简历和智联投递的结果会出现在这里。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        auto.records.take(MAX_SHOWN_RECORDS).forEach { record ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${record.position} · ${record.company}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(resultLabel(record.result), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (record.result == AutoApplyResult.FAILED) JobDanger else AccentDark)
                }
                val time = Instant.ofEpochMilli(record.at).atZone(ZoneId.systemDefault()).format(recordTime)
                Text(listOf(time, record.platform.short, record.salary, record.note).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
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
            Text("识别不了${auto.settings.platform.short}界面时，用诊断导出控件结构给开发者核对。只记录控件结构和文字长度，不含职位与聊天正文；App 更新后需要重新诊断和试运行。",
                color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            SettingSwitch("隐藏招聘页面", auto.settings.hideTargetPage, !running) { actions.setHideTargetPage(it) }
            Text("真实执行时用专注助手遮罩覆盖招聘页面；诊断和试运行不隐藏。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            SettingSwitch("显示悬浮控制按钮", auto.settings.showOverlay, !running) { actions.setShowOverlay(it) }
            Text("关闭后仍可从通知暂停、继续或停止。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.diagnose, enabled = !busy && !running, modifier = Modifier.weight(1f)) { Text("界面诊断", maxLines = 1) }
                OutlinedButton(onClick = actions.exportDiagnostics, enabled = !busy && auto.status.diagnostics > 0, modifier = Modifier.weight(1f)) { Text("导出 ${auto.status.diagnostics} 页", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}

private const val MAX_SHOWN_RECORDS = 30

private fun rulesSummary(value: AutoApplySettings): String = listOfNotNull(
    if (value.includeKeywords.isEmpty()) "关键词：不限" else "关键词：${value.includeKeywords.joinToString("、")}",
    value.excludeKeywords.takeIf { it.isNotEmpty() }?.let { "排除：${it.joinToString("、")}" },
    value.minSalaryK?.let { "最低 ${it}K" },
    value.city.takeIf { it.isNotBlank() }?.let { "城市：$it" },
    value.companyBlacklist.takeIf { it.isNotEmpty() }?.let { "黑名单 ${it.size} 家" },
    "每日 ${AutoApplyRules.limitFor(value, value.platform)} 条",
    "间隔 ${value.stepDelayMs / 1000} 秒",
    // 智联投递不发招呼语。
    if (value.platform == JobPlatform.ZHAOPIN) null else if (value.greeting.isBlank()) "BOSS 默认招呼语" else "自定义招呼语"
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
                Text("要和 BOSS「附件简历」的文件名或智联里的简历名称完全一致，扩展名可以省略。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
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
                    "自动操作招聘 App 可能违反 BOSS 直聘、智联招聘等平台的用户协议，账号可能被限制沟通、投递或封禁，后果由你自行承担。",
                    "本功能不会识别或绕过验证码、不伪装设备、不随机化节奏；平台出现验证、登录或次数上限时会立即停止。",
                    "运行中会保持亮屏，建议插上充电器；触碰招聘 App 界面、离开它或熄屏会暂停，可从悬浮按钮继续。",
                    "只操作你手机上已登录的 BOSS 直聘或智联招聘，不联网、不读取或保存账号与 Cookie。",
                    "“自动化已发起沟通”“自动化已投递”只表示界面上已发出，不代表对方已读。"
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
    val zhaopin = settings.platform == JobPlatform.ZHAOPIN
    var limit by remember { mutableStateOf(AutoApplyRules.limitFor(settings, settings.platform).toString()) }
    var delaySeconds by remember { mutableStateOf((settings.stepDelayMs / 1000).toString()) }
    var greeting by remember { mutableStateOf(settings.greeting) }
    var error by remember { mutableStateOf<String?>(null) }
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color.White,
        title = { Text(if (zhaopin) "投递规则" else "沟通规则", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(include, { include = it; error = null }, label = { Text("岗位关键词（逗号分隔，留空不限）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(exclude, { exclude = it; error = null }, label = { Text("排除词") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(salary, { salary = it.filter(Char::isDigit).take(4); error = null }, label = { Text("最低月薪（K，留空不限）") }, singleLine = true, enabled = !busy, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(city, { city = it.take(AutoApplyRules.MAX_CITY); error = null }, label = { Text("城市（留空不限）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(blacklist, { blacklist = it; error = null }, label = { Text("公司黑名单") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(3); error = null }, label = { Text("${settings.platform.short} 每日上限（1–${AutoApplyRules.MAX_DAILY_LIMIT}）") }, singleLine = true, enabled = !busy, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(delaySeconds, { delaySeconds = it.filter(Char::isDigit).take(2); error = null }, label = { Text("步骤最短间隔（${AutoApplyRules.MIN_STEP_DELAY_MS / 1000}–${AutoApplyRules.MAX_STEP_DELAY_MS / 1000} 秒，固定不随机）") }, singleLine = true, enabled = !busy, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
                if (!zhaopin) OutlinedTextField(greeting, { greeting = it.take(AutoApplyRules.MAX_GREETING); error = null }, label = { Text("自定义招呼语（留空用 BOSS 默认）") }, enabled = !busy, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Text("界面一变化就进入下一步，两步之间至少间隔上面的秒数。关键词、黑名单和间隔两个平台共用，每日上限按平台分开。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                Text(if (zhaopin) "同一职位只投递一次，同一公司每天只投一次；智联记录不影响 BOSS 的去重和计数。"
                    else "同一职位只沟通一次、只发一次简历，同一公司每天只联系一次；每日上限对沟通和发简历分别计算。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                error?.let { Text(it, color = JobDanger, fontSize = 12.sp) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                try {
                    val next = settings.copy(includeKeywords = AutoApplyRules.parseList(include), excludeKeywords = AutoApplyRules.parseList(exclude),
                        minSalaryK = salary.toIntOrNull(), city = city.trim(), companyBlacklist = AutoApplyRules.parseList(blacklist),
                        stepDelayMs = (delaySeconds.toLongOrNull() ?: 0) * 1000, greeting = greeting.trim())
                        .let { if (zhaopin) it.copy(zhaopinDailyLimit = limit.toIntOrNull() ?: 0) else it.copy(dailyLimit = limit.toIntOrNull() ?: 0) }
                    AutoApplyRules.validateSettings(next); onSave(next)
                } catch (exception: IllegalArgumentException) { error = exception.message }
            }) { Text(if (busy) "保存中" else "保存") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
