package com.focusassistant.app.platform

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.focusassistant.app.FocusApplication
import com.focusassistant.app.MainActivity
import com.focusassistant.app.R
import com.focusassistant.app.domain.AutoApplyAction
import com.focusassistant.app.domain.AutoApplyEngine
import com.focusassistant.app.domain.AutoApplyMode
import com.focusassistant.app.domain.AutoApplyPhase
import com.focusassistant.app.domain.AutoApplyPreview
import com.focusassistant.app.domain.AutoApplyRules
import com.focusassistant.app.domain.AutoApplyRun
import com.focusassistant.app.domain.BossReading
import com.focusassistant.app.domain.BossScreen
import com.focusassistant.app.domain.StopNotice
import com.focusassistant.app.domain.JobPlatform
import com.focusassistant.app.domain.PlatformReader
import com.focusassistant.app.domain.ResumeAction
import com.focusassistant.app.domain.ResumeEngine
import com.focusassistant.app.domain.ResumePhase
import com.focusassistant.app.domain.ResumePreview
import com.focusassistant.app.domain.ResumeRun
import com.focusassistant.app.domain.ZhaopinAction
import com.focusassistant.app.domain.ZhaopinApplyEngine
import com.focusassistant.app.domain.ZhaopinPhase
import com.focusassistant.app.domain.ZhaopinRun
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

enum class BossTask {
    DIAGNOSE, DRY_RUN, LIVE, RESUME_DRY_RUN, RESUME_LIVE;
    val resume: Boolean get() = this == RESUME_DRY_RUN || this == RESUME_LIVE
    val live: Boolean get() = this == LIVE || this == RESUME_LIVE
}

data class BossAutomationStatus(
    val connected: Boolean = false,
    val task: BossTask? = null,
    val log: List<String> = emptyList(),
    val preview: List<AutoApplyPreview> = emptyList(),
    /** null 表示还没做过发简历试运行；空列表表示试运行没有找到要发的会话。 */
    val resumePreview: List<ResumePreview>? = null,
    val diagnostics: Int = 0,
    val stopReason: String? = null,
    /** 非空表示任务已暂停（保留进度），内容是暂停原因。 */
    val paused: String? = null,
    /** 当前或最近一次任务操作的平台。 */
    val platform: JobPlatform? = null
)

/** 应用界面与无障碍服务之间的进程内通道；服务未开启时只返回错误，不做任何事。 */
object BossAutomation {
    private const val MAX_LOG = 80
    private val mutable = MutableStateFlow(BossAutomationStatus())
    val status: StateFlow<BossAutomationStatus> = mutable.asStateFlow()
    @Volatile internal var service: BossAutomationService? = null
    internal val diagnosticPages = mutableListOf<String>()

    fun enabled(context: Context): Boolean {
        val component = ComponentName(context, BossAutomationService::class.java).flattenToString()
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { it.equals(component, ignoreCase = true) }
    }

    fun canReEnable(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 用户已用 adb 授予 WRITE_SECURE_SETTINGS 时，把本服务写回已开启列表；没有授权就什么都不做。 */
    fun tryReEnable(context: Context): Boolean {
        if (enabled(context) || !canReEnable(context)) return false
        return runCatching {
            val component = ComponentName(context, BossAutomationService::class.java).flattenToString()
            val resolver = context.contentResolver
            val list = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && !it.equals(component, ignoreCase = true) }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + component).joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.isSuccess
    }

    fun grantCommand(context: Context) = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun settingsIntent(): Intent =Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 开始任务并切到所选平台。返回 null 表示已开始，否则是不能开始的原因。 */
    fun start(context: Context, task: BossTask, platform: JobPlatform): String? {
        val running = service ?: return "请先在系统设置中开启「专注助手 · 求职自动化」无障碍服务"
        val launch = context.packageManager.getLaunchIntentForPackage(platform.packageName) ?: return "未找到${platform.label} App"
        running.begin(task, platform)?.let { return it }
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    fun stop(reason: String = "已手动停止") { service?.halt(reason) }

    /** 暂停或继续当前任务；继续时如果不在目标 App 会先切回。 */
    fun togglePause() { service?.togglePause() }

    fun diagnosticExport(): String = synchronized(diagnosticPages) { JSONArray(diagnosticPages.map { org.json.JSONObject(it) }).toString(2) }

    fun clearDiagnostics() { synchronized(diagnosticPages) { diagnosticPages.clear() }; update { it.copy(diagnostics = 0) } }

    internal fun update(change: (BossAutomationStatus) -> BossAutomationStatus) { mutable.value = change(mutable.value) }

    internal fun log(line: String) {
        val stamp = java.time.LocalTime.now().withNano(0).toString()
        update { it.copy(log = (it.log + "$stamp  $line").takeLast(MAX_LOG)) }
    }
}

/**
 * 只在用户手动开启后、用户点「开始」时操作所选平台（BOSS 直聘或智联招聘）的界面。界面变化并稳定后执行下一步，
 * 两步之间至少间隔设定的秒数（固定，不随机）。用户触碰、离开目标 App 或熄屏时暂停，可从悬浮按钮继续；
 * 遇到验证、登录、上限或识别不了的界面即停止。不识别验证码、不联网。
 */
class BossAutomationService : AccessibilityService() {
    // 无障碍服务一旦崩溃，系统会把它关掉，用户只能重新去设置里开启；所以任何异常都转为停止任务。
    private val crashGuard = CoroutineExceptionHandler { _, error -> fail(error) }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
    private var lastScreen: String? = null
    private var waitingForListNotice = false
    private var masked = false
    private var loop: Job? = null
    private var task: BossTask? = null
    private var run: AutoApplyRun? = null
    private var resumeRun: ResumeRun? = null
    private var zhaopinRun: ZhaopinRun? = null
    private var platform = JobPlatform.BOSS
    private var paused: String? = null
    private var overlay: BossOverlay? = null
    /** 界面变化事件唤醒步进循环；CONFLATED 让连续事件只触发一次读取。 */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastEventAt = 0L
    private var lastActionAt = 0L
    private var seenTarget = false
    /** 已经认出过列表页；之后用户再点界面就算手动操作，不再当成「还在自己找页面」。 */
    private var sawList = false
    private var startedAt = 0L
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val repository get() = (application as FocusApplication).repository

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = guarded {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pause("屏幕已熄灭，已暂停；亮屏后点悬浮按钮继续")
                ACTION_STOP -> halt("已从通知停止")
                ACTION_TOGGLE -> togglePause()
                ACTION_CAPTURE -> capture()
            }
        }
    }

    override fun onServiceConnected() {
        BossAutomation.service = this
        BossAutomation.update { it.copy(connected = true) }
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(ACTION_STOP); addAction(ACTION_CAPTURE); addAction(ACTION_TOGGLE)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = guarded { handleEvent(event) }

    private fun guarded(block: () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Throwable) { fail(error) }
    }

    private fun fail(error: Throwable) {
        android.util.Log.e("BossAutomation", "automation error", error)
        val reason = "内部错误（${error.javaClass.simpleName}），已停止"
        if (task != null) runCatching { halt(reason) }.onFailure { task = null } else BossAutomation.log(reason)
    }

    /**
     * 遮罩窗口会成为活动窗口，所以先在全部窗口里找目标 App 的根节点。
     * 简历选择这类弹窗是目标 App 的另一个窗口，和详情页同时存在，必须取层级最高的那个，
     * 否则只读到底下的详情页，弹窗一直识别不出来。
     */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val target = platform.packageName
        val top = runCatching {
            windows.filter { it.root?.packageName?.toString() == target }.maxByOrNull { it.layer }?.root
        }.getOrNull()
        return top ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == target }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        // 悬浮按钮属于本应用，点击它不会走到这里，因此不会被当成手动操作。
        if (task == null || task == BossTask.DIAGNOSE || paused != null || event.packageName?.toString() != platform.packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastEventAt = SystemClock.elapsedRealtime()
            wake.trySend(Unit)
            return
        }
        // 本服务点击后的界面回调会落在动作后的短时间内；之外的点击和输入视为用户在操作，暂停任务。
        val manual = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        // 第一次自动动作之前允许用户自己切到列表页；但列表已经认出来之后再点（切换职位分类等）就算手动操作，
        // 否则界面离开列表又没人暂停，扫描阶段会在 20 秒后直接停止任务。
        val navigating = lastActionAt == startedAt && !sawList
        if (manual && !navigating && SystemClock.elapsedRealtime() - lastActionAt > OWN_EVENT_WINDOW_MS) pause("检测到手动操作，已暂停；处理完点悬浮按钮继续")
    }

    override fun onInterrupt() = halt("系统中断了无障碍服务，已停止")

    override fun onDestroy() {
        halt("无障碍服务已关闭")
        runCatching { unregisterReceiver(receiver) }
        scope.cancel()
        if (BossAutomation.service === this) BossAutomation.service = null
        BossAutomation.update { it.copy(connected = false) }
        super.onDestroy()
    }

    internal fun begin(next: BossTask, target: JobPlatform): String? {
        if (task != null) return "已有任务在运行"
        val state = repository.state.value
        val settings = state.autoApply.settings
        val version = BossUiMap.installedVersion(this, target) ?: return "未找到${target.label} App"
        // 智联只做投递，没有回复后发简历。
        if (target == JobPlatform.ZHAOPIN && next.resume) return "智联招聘投递时直接附带简历，不需要回复后发简历"
        if (next != BossTask.DIAGNOSE && !settings.riskAccepted) return "请先阅读并确认自动投递风险"
        if (next == BossTask.LIVE && AutoApplyRules.calibratedFor(settings, target) != version) return "${target.short} 版本（$version）尚未试运行校准，请先试运行并核对清单"
        if (next.resume && settings.resumes.isEmpty()) return "请先在「我的简历」里添加简历"
        if (next == BossTask.RESUME_LIVE && settings.resumeCalibratedVersion != version) return "BOSS 版本（$version）尚未做发简历试运行，请先试运行并核对清单"
        task = next
        platform = target
        paused = null
        seenTarget = false
        sawList = false
        startedAt = SystemClock.elapsedRealtime()
        lastActionAt = startedAt
        waitingForListNotice = false
        val mode = if (next.live) AutoApplyMode.LIVE else AutoApplyMode.DRY_RUN
        val applyTask = next == BossTask.DRY_RUN || next == BossTask.LIVE
        run = if (applyTask && target == JobPlatform.BOSS) AutoApplyEngine.start(mode, System.currentTimeMillis()) else null
        zhaopinRun = if (applyTask && target == JobPlatform.ZHAOPIN) ZhaopinApplyEngine.start(mode, System.currentTimeMillis()) else null
        resumeRun = if (next.resume) ResumeEngine.start(mode, System.currentTimeMillis()) else null
        BossAutomation.update {
            when {
                next == BossTask.DIAGNOSE -> it.copy(task = next, stopReason = null, paused = null, platform = target)
                next.resume -> it.copy(task = next, stopReason = null, paused = null, resumePreview = null, platform = target)
                else -> it.copy(task = next, stopReason = null, paused = null, preview = emptyList(), platform = target)
            }
        }
        BossAutomation.log(when (next) {
            BossTask.DIAGNOSE -> if (target == JobPlatform.ZHAOPIN) "诊断：在智联中打开职位列表、详情、简历选择和投递成功页，分别点悬浮按钮里的「记录本页」"
                else "诊断：在 BOSS 中打开职位列表、详情和聊天页，分别点悬浮按钮里的「记录本页」"
            BossTask.DRY_RUN -> "${target.short} 试运行：只读取当前职位列表，不点击任何按钮"
            BossTask.LIVE -> if (target == JobPlatform.ZHAOPIN) "开始智联自动投递，每日上限 ${settings.zhaopinDailyLimit} 条"
                else "开始 BOSS 自动沟通，每日上限 ${settings.dailyLimit} 条"
            BossTask.RESUME_DRY_RUN -> "发简历试运行：只读取当前消息列表，不点击任何按钮"
            BossTask.RESUME_LIVE -> "开始自动发简历：只处理已自动沟通且有新回复的会话，每日上限 ${settings.dailyLimit} 份"
        })
        notify(next, when {
            next == BossTask.DIAGNOSE -> "诊断中 · 已记录 ${BossAutomation.status.value.diagnostics} 页"
            next.resume -> "正在等待下一项"
            else -> "正在等待下一项"
        })
        masked = next.live && settings.hideTargetPage
        lastScreen = null
        overlay = BossOverlay(this, onToggle = { guarded(::togglePause) }, onStop = { guarded { halt(if (task == BossTask.DIAGNOSE) "诊断已结束" else "已手动停止") } },
            onCapture = { guarded(::capture) }, onQuickHide = { guarded(::quickHide) }, onToggleMask = { guarded(::toggleMask) })
        runCatching { overlay?.show(next == BossTask.DIAGNOSE, masked, settings.showOverlay) }.onFailure { BossAutomation.log("悬浮窗未能显示，可从通知控制") }
        refreshOverlay()
        if (next != BossTask.DIAGNOSE) loop = scope.launch { tickLoop(settings.stepDelayMs) }
        return null
    }

    private fun toggleMask() {
        if (task == null) return
        masked = !masked
        overlay?.setMask(masked)
    }

    private fun quickHide() {
        if (task == null) return
        pause("已暂停")
        masked = false
        overlay?.setMask(false)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_QUICK_HIDE, true)
        })
    }

    internal fun halt(reason: String) {
        val current = task ?: return
        task = null
        loop?.cancel()
        loop = null
        run = null
        resumeRun = null
        zhaopinRun = null
        paused = null
        waitingForListNotice = false
        overlay?.hide()
        overlay = null
        BossAutomation.update { it.copy(task = null, stopReason = reason, paused = null) }
        BossAutomation.log(reason)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        if (current != BossTask.DIAGNOSE) notifyStopped(reason)
    }

    internal fun pause(reason: String) {
        val current = task ?: return
        if (current == BossTask.DIAGNOSE || paused != null) return
        paused = reason
        BossAutomation.update { it.copy(paused = reason) }
        BossAutomation.log(reason)
        notify(current, reason)
        refreshOverlay()
    }

    internal fun togglePause() {
        val current = task ?: return
        if (current == BossTask.DIAGNOSE) return
        if (paused == null) return pause("已暂停，点悬浮按钮继续")
        val now = System.currentTimeMillis()
        run = run?.let { AutoApplyEngine.resume(it, now) }
        resumeRun?.let { ResumeEngine.resume(it, now) }?.let { step -> resumeRun = step.run; step.log?.let(BossAutomation::log) }
        zhaopinRun?.let { ZhaopinApplyEngine.resume(it, now) }?.let { step -> zhaopinRun = step.run; step.log?.let(BossAutomation::log) }
        paused = null
        // 继续后重新给出切回目标 App 的时间，第一步动作前也允许用户自己调整页面。
        seenTarget = false
        sawList = false
        startedAt = SystemClock.elapsedRealtime()
        lastActionAt = startedAt
        BossAutomation.update { it.copy(paused = null) }
        BossAutomation.log("继续执行")
        if (rootInActiveWindow?.packageName?.toString() != platform.packageName && targetRoot() == null) {
            packageManager.getLaunchIntentForPackage(platform.packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        notify(current, "继续执行")
        refreshOverlay()
        wake.trySend(Unit)
    }

    /**
     * 事件驱动：目标 App 界面有变化就唤醒，等界面稳定 SETTLE_MS 后读取；距上一步不足 stepDelayMs 时补足。
     * 没有事件时每 FALLBACK_MS 也检查一次，保证超时、离开目标 App 能被发现。只有这一个协程在执行步骤。
     */
    private suspend fun tickLoop(stepDelayMs: Long) {
        while (scope.isActive && task != null) {
            withTimeoutOrNull(FALLBACK_MS) { wake.receive() }
            val settleStart = SystemClock.elapsedRealtime()
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val quiet = now - lastEventAt
                if (quiet >= SETTLE_MS || now - settleStart >= MAX_SETTLE_MS) break
                delay(SETTLE_MS - quiet)
            }
            val gap = lastActionAt + stepDelayMs - SystemClock.elapsedRealtime()
            if (gap > 0) delay(gap)
            if (task != null && paused == null) {
                try { tick() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Throwable) { fail(error) }
            }
        }
    }

    private fun refreshOverlay() {
        val current = task ?: return
        val state = repository.state.value
        val day = AutoApplyRules.localDay(System.currentTimeMillis(), zone)
        val limit = AutoApplyRules.limitFor(state.autoApply.settings, platform)
        val records = state.autoApply.records
        val diagnostics = BossAutomation.status.value.diagnostics
        val (count, text) = when {
            current == BossTask.DIAGNOSE -> diagnostics to "诊断中 · 已记录 $diagnostics 页"
            platform == JobPlatform.ZHAOPIN -> (zhaopinRun?.applied ?: 0).let {
                it to "${if (current.live) "进行中" else "准备中"} · 本次 $it · 今日 ${AutoApplyRules.contactedOn(records, day, zone, platform)}/$limit"
            }
            current.resume -> (resumeRun?.sent ?: 0).let { it to "${if (current.live) "进行中" else "准备中"} · 本次 $it · 今日 ${AutoApplyRules.resumesSentOn(records, day, zone)}/$limit" }
            else -> (run?.contacted ?: 0).let { it to "${if (current.live) "进行中" else "准备中"} · 本次 $it · 今日 ${AutoApplyRules.contactedOn(records, day, zone)}/$limit" }
        }
        overlay?.update(count.toString(), if (paused != null) "已暂停" else if (waitingForListNotice) "请返回列表页" else text, paused != null)
    }

    private fun updateWaitingForList(onEntryScreen: Boolean) {
        if (!onEntryScreen && !waitingForListNotice) {
            waitingForListNotice = true
            BossAutomation.log("请返回列表页")
            task?.let { notify(it, "请返回列表页") }
        } else if (onEntryScreen && waitingForListNotice) {
            waitingForListNotice = false
            task?.let { notify(it, if (it.resume) "正在等待下一项" else "任务进行中") }
        }
    }

    private suspend fun tick() {
        if (run == null && resumeRun == null && zhaopinRun == null) return
        if (!getSystemService(PowerManager::class.java).isInteractive) return pause("屏幕已熄灭，已暂停；亮屏后点悬浮按钮继续")
        val root = targetRoot()
        val inTarget = root != null
        if (!inTarget) {
            if (seenTarget) return pause("已离开当前页面，已暂停；点悬浮按钮继续")
            if (SystemClock.elapsedRealtime() - startedAt > AutoApplyEngine.STEP_TIMEOUT_MS) halt("目标页面未打开，已停止")
            return
        }
        seenTarget = true
        val reading = PlatformReader.read(platform, BossUiMap.snapshot(root!!, masked), System.currentTimeMillis())
        val seen = reading.observation.let { "${it.screen.name}${if (it.cards.isNotEmpty()) " · ${it.cards.size} 个职位" else ""}" }
        if (seen != lastScreen) { lastScreen = seen; BossAutomation.log("识别：$seen") }
        if (reading.observation.screen == BossScreen.JOB_LIST || reading.observation.screen == BossScreen.MESSAGE_LIST) sawList = true
        resumeRun?.let { return resumeTick(it, root, reading) }
        zhaopinRun?.let { return zhaopinTick(it, root, reading) }
        val current = run ?: return
        if (current.phase == AutoApplyPhase.SCANNING) updateWaitingForList(reading.observation.screen == BossScreen.JOB_LIST)
        val state = repository.state.value
        val step = AutoApplyEngine.step(current, reading.observation, state.autoApply.settings, state.autoApply.records, zone) { UUID.randomUUID().toString() }
        run = step.run
        step.log?.let(BossAutomation::log)
        step.record?.let { record ->
            repository.addAutoApplyRecord(record)
            notify(BossTask.LIVE, "已处理 ${step.run.contacted} 项")
        }
        refreshOverlay()
        if (step.run.phase == AutoApplyPhase.STOPPED) {
            if (current.mode == AutoApplyMode.DRY_RUN && step.run.preview.isNotEmpty()) {
                BossAutomation.update { it.copy(preview = step.run.preview) }
                BossUiMap.installedVersion(this, JobPlatform.BOSS)?.let { repository.saveAutoApplySettings(state.autoApply.settings.copy(calibratedVersion = it)) }
            }
            return halt(step.run.stopReason ?: "已停止")
        }
        if (!perform(root, reading, step.action)) {
            val failed = AutoApplyEngine.fail(step.run, "找不到要点击的界面元素，已停止；BOSS 界面可能已更新", System.currentTimeMillis()) { UUID.randomUUID().toString() }
            failed.record?.let { repository.addAutoApplyRecord(it) }
            halt(failed.run.stopReason ?: "已停止")
        }
    }

    private suspend fun resumeTick(current: ResumeRun, root: AccessibilityNodeInfo, reading: BossReading) {
        if (current.phase == ResumePhase.FINDING) updateWaitingForList(reading.observation.screen == BossScreen.MESSAGE_LIST)
        val state = repository.state.value
        val step = ResumeEngine.step(current, reading.observation, state.autoApply.settings, state.autoApply.records, zone) { UUID.randomUUID().toString() }
        resumeRun = step.run
        step.log?.let(BossAutomation::log)
        step.record?.let { record ->
            repository.addAutoApplyRecord(record)
            notify(BossTask.RESUME_LIVE, "已处理 ${step.run.sent} 项")
        }
        refreshOverlay()
        if (step.run.phase == ResumePhase.STOPPED) {
            if (current.mode == AutoApplyMode.DRY_RUN && step.run.listRecognized) {
                BossAutomation.update { it.copy(resumePreview = step.run.preview) }
                BossUiMap.installedVersion(this, JobPlatform.BOSS)?.let { repository.saveAutoApplySettings(state.autoApply.settings.copy(resumeCalibratedVersion = it)) }
            }
            return halt(step.run.stopReason ?: "已停止")
        }
        if (!performResume(root, reading, step.action)) {
            halt(ResumeEngine.fail(step.run, "找不到要点击的界面元素，已停止；BOSS 界面可能已更新", System.currentTimeMillis()).run.stopReason ?: "已停止")
        }
    }

    private suspend fun zhaopinTick(current: ZhaopinRun, root: AccessibilityNodeInfo, reading: BossReading) {
        if (current.phase == ZhaopinPhase.SCANNING) updateWaitingForList(reading.observation.screen == BossScreen.JOB_LIST)
        val state = repository.state.value
        val step = ZhaopinApplyEngine.step(current, reading.observation, state.autoApply.settings, state.autoApply.records, zone) { UUID.randomUUID().toString() }
        zhaopinRun = step.run
        step.log?.let(BossAutomation::log)
        step.record?.let { record ->
            repository.addAutoApplyRecord(record)
            notify(BossTask.LIVE, "已处理 ${step.run.applied} 项")
        }
        refreshOverlay()
        if (step.run.phase == ZhaopinPhase.STOPPED) {
            if (current.mode == AutoApplyMode.DRY_RUN && step.run.preview.isNotEmpty()) {
                BossAutomation.update { it.copy(preview = step.run.preview) }
                BossUiMap.installedVersion(this, JobPlatform.ZHAOPIN)?.let { repository.saveAutoApplySettings(state.autoApply.settings.copy(zhaopinCalibratedVersion = it)) }
            }
            return halt(step.run.stopReason ?: "已停止")
        }
        if (!performZhaopin(root, reading, step.action)) {
            val failed = ZhaopinApplyEngine.fail(step.run, ZhaopinApplyEngine.failReason(step.action), System.currentTimeMillis()) { UUID.randomUUID().toString() }
            failed.record?.let { repository.addAutoApplyRecord(it) }
            halt(failed.run.stopReason ?: "已停止")
        }
    }

    private fun performZhaopin(root: AccessibilityNodeInfo, reading: BossReading, action: ZhaopinAction): Boolean = when (action) {
        ZhaopinAction.Wait -> true
        is ZhaopinAction.OpenCard -> clickPath(root, reading.cardPaths.getOrNull(action.index))
        ZhaopinAction.TapApply -> clickPath(root, reading.contactPath)
        is ZhaopinAction.PickResume -> clickPath(root, reading.resumeOptionPaths.getOrNull(action.index))
        ZhaopinAction.Confirm -> clickPath(root, reading.confirmPath)
        ZhaopinAction.Back -> { lastActionAt = SystemClock.elapsedRealtime(); performGlobalAction(GLOBAL_ACTION_BACK) }
        ZhaopinAction.ScrollList -> scrollList(root, reading)
    }

    private fun clickPath(root: AccessibilityNodeInfo, path: List<Int>?): Boolean {
        var node: AccessibilityNodeInfo? = path?.let { BossUiMap.find(root, it) } ?: return false
        lastActionAt = SystemClock.elapsedRealtime()
        // 部分控件 isClickable 为 false 但声明了点击动作，也可以点；点击被拒时继续向上试可点击的祖先。
        while (node != null) {
            val clickable = node.isClickable || AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK in node.actionList
            if (clickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
        }
        return false
    }

    private fun performResume(root: AccessibilityNodeInfo, reading: BossReading, action: ResumeAction): Boolean = when (action) {
        ResumeAction.Wait -> true
        ResumeAction.OpenMessages -> clickPath(root, reading.messagesTabPath)
        is ResumeAction.OpenConversation -> clickPath(root, reading.conversationPaths.getOrNull(action.index))
        ResumeAction.TapResumeButton -> clickPath(root, reading.resumeButtonPath)
        is ResumeAction.PickResume -> clickPath(root, reading.resumeOptionPaths.getOrNull(action.index))
        ResumeAction.Confirm -> clickPath(root, reading.confirmPath)
        ResumeAction.Back -> { lastActionAt = SystemClock.elapsedRealtime(); performGlobalAction(GLOBAL_ACTION_BACK) }
        ResumeAction.ScrollList -> scrollList(root, reading)
    }

    /** 列表到底时滚动会返回 false，这不算找不到元素；没有新内容由状态机的空滚计数停止。 */
    private fun scrollList(root: AccessibilityNodeInfo, reading: BossReading): Boolean {
        val list = reading.listPath?.let { BossUiMap.find(root, it) } ?: return false
        lastActionAt = SystemClock.elapsedRealtime()
        list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return true
    }

    private suspend fun perform(root: AccessibilityNodeInfo, reading: BossReading, action: AutoApplyAction): Boolean {
        fun click(path: List<Int>?): Boolean = clickPath(root, path)
        return when (action) {
            AutoApplyAction.Wait -> true
            is AutoApplyAction.OpenCard -> click(reading.cardPaths.getOrNull(action.index))
            AutoApplyAction.TapContact -> click(reading.contactPath)
            AutoApplyAction.Back -> { lastActionAt = SystemClock.elapsedRealtime(); performGlobalAction(GLOBAL_ACTION_BACK) }
            AutoApplyAction.ScrollList -> scrollList(root, reading)
            is AutoApplyAction.SendGreeting -> {
                val input = reading.inputPath?.let { BossUiMap.find(root, it) } ?: return false
                lastActionAt = SystemClock.elapsedRealtime()
                val typed = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) })
                if (!typed) return false
                // 输入后发送按钮才出现，等界面刷新再重新读取一次。
                delay(SEND_SETTLE_MS)
                val fresh = targetRoot() ?: return false
                val again = PlatformReader.read(JobPlatform.BOSS, BossUiMap.snapshot(fresh, masked), System.currentTimeMillis())
                val send = again.sendPath?.let { BossUiMap.find(fresh, it) } ?: return false
                var node: AccessibilityNodeInfo = send
                while (!node.isClickable) node = node.parent ?: return false
                lastActionAt = SystemClock.elapsedRealtime()
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    private fun capture() {
        if (task != BossTask.DIAGNOSE) return
        val root = targetRoot() ?: run { BossAutomation.log("当前不是${platform.short}界面，未记录"); return }
        val page = BossUiMap.diagnostic(BossUiMap.snapshot(root, false), platform, BossUiMap.installedVersion(this, platform), System.currentTimeMillis())
        val count = synchronized(BossAutomation.diagnosticPages) {
            if (BossAutomation.diagnosticPages.size >= MAX_DIAGNOSTIC_PAGES) BossAutomation.diagnosticPages.removeAt(0)
            BossAutomation.diagnosticPages += page.toString()
            BossAutomation.diagnosticPages.size
        }
        BossAutomation.update { it.copy(diagnostics = count) }
        BossAutomation.log("已记录第 $count 页（识别为 ${page.getString("recognized")}，不含正文）")
        notify(BossTask.DIAGNOSE, "诊断中 · 已记录 $count 页")
        refreshOverlay()
    }

    private fun allowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "后台任务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示任务进度与暂停、停止按钮"; setSound(null, null); enableVibration(false)
            })
    }

    private fun broadcast(action: String, code: Int) = PendingIntent.getBroadcast(this, code, Intent(action).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun builder() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(ContextCompat.getColor(this, R.color.focus_orange))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(current: BossTask, text: String) {
        if (!allowed()) return
        channel()
        val builder = builder().setContentTitle("专注助手 · 后台任务进行中")
            .setContentText(if (paused != null) "已暂停" else text).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        if (current == BossTask.DIAGNOSE) builder.addAction(0, "记录本页", broadcast(ACTION_CAPTURE, 1))
        else builder.addAction(0, if (paused != null) "继续" else "暂停", broadcast(ACTION_TOGGLE, 3))
        builder.addAction(0, if (current == BossTask.DIAGNOSE) "结束诊断" else "停止", broadcast(ACTION_STOP, 2))
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyStopped(reason: String) {
        if (!allowed()) return
        channel()
        val text = StopNotice.friendlyText(StopNotice.classify(reason))
        NotificationManagerCompat.from(this).notify(STOPPED_ID, builder().setContentTitle("专注助手 · 任务已停止").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }

    private companion object {
        const val CHANNEL = "background_task_v2"
        const val NOTIFICATION_ID = 1101
        const val STOPPED_ID = 1102
        const val ACTION_STOP = "com.focusassistant.app.BOSS_STOP"
        const val ACTION_CAPTURE = "com.focusassistant.app.BOSS_CAPTURE"
        const val ACTION_TOGGLE = "com.focusassistant.app.BOSS_TOGGLE"
        const val SETTLE_MS = 350L
        const val MAX_SETTLE_MS = 1_500L
        const val FALLBACK_MS = 1_000L
        const val OWN_EVENT_WINDOW_MS = 2_500L
        const val SEND_SETTLE_MS = 500L
        const val MAX_DIAGNOSTIC_PAGES = 20
    }
}
