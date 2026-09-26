package com.focusassistant.app.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.focusassistant.app.FocusApplication
import com.focusassistant.app.domain.ActiveTimer
import com.focusassistant.app.domain.TimerMode
import com.focusassistant.app.domain.TimerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

object TimerAlarmScheduler {
    const val TIMING_LIMITATION = "精确闹钟是可选授权；未授权时使用非精确闹钟，锁屏、省电和系统调度可能延迟提醒。强行停止应用会取消系统提醒；重启设备后不会自动启动计时服务，请打开应用确认恢复状态。"
    private const val REQUEST_CODE = 1201
    private const val ACTION = "com.focusassistant.app.timer.ALARM"
    private var scheduledKey: String? = null

    fun canScheduleExactAlarms(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun permissionIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= 31) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE,
        Intent(context, TimerAlarmReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Synchronized
    internal fun schedule(context: Context, timer: ActiveTimer?) {
        val manager = context.getSystemService(AlarmManager::class.java)
        if (timer == null || timer.status != TimerStatus.RUNNING || (timer.timerMode == TimerMode.COUNTUP && timer.targetNotified)) {
            manager.cancel(pending(context))
            scheduledKey = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        val remaining = (timer.targetMinutes * 60_000L - timer.elapsedMs(now)).coerceAtLeast(0)
        val deadline = now + remaining
        val exact = canScheduleExactAlarms(context)
        val key = "${timer.sessionId}:${timer.anchorElapsed}:${timer.accumulatedMs}:$exact"
        if (scheduledKey == key) return
        if (exact) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending(context))
                scheduledKey = key
                return
            } catch (error: SecurityException) {
                Log.w("FocusAlarm", "精确闹钟授权已变更，使用非精确提醒")
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending(context))
        scheduledKey = key
    }

    @Synchronized
    internal fun delivered() {
        scheduledKey = null
    }
}

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val application = context.applicationContext as FocusApplication
        application.appScope.launch {
            try {
                withTimeout(RECEIVER_TIMEOUT_MS) {
                    TimerAlarmScheduler.delivered()
                    application.repository.state.first { !it.loading }
                    val event = application.repository.tickTimer()
                    if (event != null) TimerNotifications.event(application, event)
                    val timer = application.repository.state.value.timer
                    TimerAlarmScheduler.schedule(application, timer)
                    if (timer == null) TimerNotifications.removeTimer(application)
                    else TimerNotifications.update(application, timer)
                }
            } catch (error: TimeoutCancellationException) {
                TimerServiceCommands.report(application, "计时提醒处理超时，请打开应用确认计时结果", error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                TimerServiceCommands.report(application, "计时提醒处理失败，请打开应用确认计时结果", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val RECEIVER_TIMEOUT_MS = 8_000L
    }
}
