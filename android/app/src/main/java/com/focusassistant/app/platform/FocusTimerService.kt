package com.focusassistant.app.platform

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.focusassistant.app.FocusApplication
import com.focusassistant.app.domain.TimerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FocusTimerService : Service() {
    private data class Command(val action: String, val projectId: String? = null, val taskId: String? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val repository get() = (applicationContext as FocusApplication).repository
    private var pendingCommands = 0
    private var latestStartId = 0
    private var lastNotificationAt = 0L
    private var tickQueued = false
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        TimerNotifications.channels(this)
        scope.launch {
            for (command in commands) {
                val isTick = command.action == TICK
                if (isTick) tickQueued = false
                try {
                    withTimeout(LOAD_TIMEOUT_MS) { repository.state.first { !it.loading } }
                    val event = when (command.action) {
                        TimerServiceCommands.START -> {
                            require(!command.projectId.isNullOrBlank()) { "请选择专注项目" }
                            repository.startTimer(command.projectId, command.taskId)
                            null
                        }
                        TimerServiceCommands.PAUSE -> { repository.pauseTimer(); null }
                        TimerServiceCommands.RESUME -> { repository.resumeTimer(); null }
                        TimerServiceCommands.FINISH -> repository.finishTimer()
                        TimerServiceCommands.DISCARD -> { repository.discardTimer(); null }
                        TimerServiceCommands.SHORT_BREAK -> { repository.startBreak(false); null }
                        TimerServiceCommands.LONG_BREAK -> { repository.startBreak(true); null }
                        TimerServiceCommands.RESTORE, TICK -> repository.tickTimer()
                        else -> throw IllegalArgumentException("无法识别计时操作")
                    }
                    if (event != null) TimerNotifications.event(this@FocusTimerService, event)
                } catch (error: TimeoutCancellationException) {
                    TimerServiceCommands.report(this@FocusTimerService, "计时数据加载超时，请重新打开应用后重试", error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = if (error is IllegalArgumentException || error is IllegalStateException) error.message ?: "计时操作失败" else "计时数据暂时无法保存，请打开应用检查后重试"
                    if (!isTick || SystemClock.elapsedRealtime() - lastErrorAt >= ERROR_INTERVAL_MS) {
                        lastErrorAt = SystemClock.elapsedRealtime()
                        TimerServiceCommands.report(this@FocusTimerService, message, error)
                    }
                } finally {
                    if (!isTick) pendingCommands--
                }
                try {
                    reconcile(forceNotification = !isTick)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    TimerServiceCommands.report(this@FocusTimerService, "无法更新系统计时状态，请重新打开应用", error)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                if (!tickQueued) {
                    tickQueued = commands.trySend(Command(TICK)).isSuccess
                }
            }
        }
    }

    private var lastErrorAt = -ERROR_INTERVAL_MS

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        try {
            if (!foreground) {
                val notification = TimerNotifications.restoring(this)
                if (Build.VERSION.SDK_INT >= 34) startForeground(TimerNotifications.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(TimerNotifications.ONGOING_ID, notification)
                foreground = true
            }
            pendingCommands++
            val command = Command(intent?.action ?: TimerServiceCommands.RESTORE,
                intent?.getStringExtra(TimerServiceCommands.PROJECT_ID), intent?.getStringExtra(TimerServiceCommands.TASK_ID))
            if (!commands.trySend(command).isSuccess) {
                pendingCommands--
                throw IllegalStateException("计时服务正在关闭，请重试")
            }
        } catch (error: RuntimeException) {
            TimerServiceCommands.report(this, "系统无法启动前台计时，请打开应用后重试", error)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun reconcile(forceNotification: Boolean) {
        val timer = repository.state.value.timer
        TimerAlarmScheduler.schedule(this, timer)
        if (timer?.status == TimerStatus.RUNNING) {
            val now = SystemClock.elapsedRealtime()
            if (forceNotification || now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
                TimerNotifications.update(this, timer)
                lastNotificationAt = now
            }
            return
        }
        if (pendingCommands != 0) return
        if (timer == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            TimerNotifications.removeTimer(this)
        } else {
            TimerNotifications.update(this, timer)
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        foreground = false
        stopSelfResult(latestStartId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        commands.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TICK = "internal.tick"
        private const val LOAD_TIMEOUT_MS = 15_000L
        private const val TICK_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_INTERVAL_MS = 5_000L
        private const val ERROR_INTERVAL_MS = 30_000L
    }
}
