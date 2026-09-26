package com.focusassistant.app.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.focusassistant.app.MainActivity
import com.focusassistant.app.R
import com.focusassistant.app.domain.ActiveTimer
import com.focusassistant.app.domain.TimerEvent
import com.focusassistant.app.domain.TimerEventKind
import com.focusassistant.app.domain.TimerMode
import com.focusassistant.app.domain.TimerPhase
import com.focusassistant.app.domain.TimerStatus
import java.util.Locale

internal object TimerNotifications {
    const val ONGOING_ID = 1001
    private const val EVENT_ID = 1002
    private const val ERROR_ID = 1003
    private const val REMINDER_ID = 1004
    private const val TIMER_CHANNEL = "focus_timer"
    private const val EVENT_CHANNEL = "focus_events"
    private const val REMINDER_CHANNEL = "usage_reminders"

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(TIMER_CHANNEL, "进行中的计时", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示计时状态与暂停、继续和结束操作"
                setSound(null, null)
                enableVibration(false)
            },
            NotificationChannel(EVENT_CHANNEL, "计时完成与异常", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(REMINDER_CHANNEL, "每日用时提醒", NotificationManager.IMPORTANCE_DEFAULT)
        ))
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun base(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(open(context))
        .setColor(ContextCompat.getColor(context, R.color.focus_orange))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    fun restoring(context: Context): Notification {
        channels(context)
        return base(context, TIMER_CHANNEL).setContentTitle("专注助手")
            .setContentText("正在恢复计时").setOngoing(true).setOnlyAlertOnce(true).build()
    }

    fun timer(context: Context, timer: ActiveTimer): Notification {
        val running = timer.status == TimerStatus.RUNNING
        val now = SystemClock.elapsedRealtime()
        val seconds = if (timer.timerMode == TimerMode.COUNTDOWN) timer.remainingSeconds(now) else timer.elapsedMs(now) / 1000
        val duration = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        val prefix = if (!running) "已暂停" else if (timer.timerMode == TimerMode.COUNTDOWN) "剩余" else "已专注"
        val builder = base(context, TIMER_CHANNEL)
            .setContentTitle(timer.projectTitle)
            .setContentText("$prefix $duration")
            .setOngoing(running).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (running) {
            val countdown = timer.timerMode == TimerMode.COUNTDOWN
            val whenMillis = System.currentTimeMillis() + if (countdown) seconds * 1000 else -timer.elapsedMs(now)
            builder.setWhen(whenMillis).setUsesChronometer(true).setChronometerCountDown(countdown)
        } else builder.setShowWhen(false)
        val toggle = if (running) TimerServiceCommands.PAUSE else TimerServiceCommands.RESUME
        builder.addAction(0, if (running) "暂停" else "继续", command(context, toggle))
        val end = if (timer.phase == TimerPhase.FOCUS) TimerServiceCommands.FINISH else TimerServiceCommands.DISCARD
        builder.addAction(0, if (timer.phase == TimerPhase.FOCUS) "结束并保存" else "结束休息", command(context, end))
        return builder.build()
    }

    private fun command(context: Context, action: String): PendingIntent = PendingIntent.getForegroundService(
        context, action.hashCode(), TimerServiceCommands.intent(context, action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun post(context: Context, id: Int, notification: Notification): Boolean {
        if (!allowed(context)) return false
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (error: SecurityException) {
            Log.w("FocusNotification", "通知权限已变更 (${error.javaClass.simpleName})")
            false
        }
    }

    fun update(context: Context, timer: ActiveTimer) {
        post(context, ONGOING_ID, timer(context, timer))
    }

    fun removeTimer(context: Context) = NotificationManagerCompat.from(context).cancel(ONGOING_ID)

    fun event(context: Context, event: TimerEvent) {
        channels(context)
        val text = when (event.kind) {
            TimerEventKind.COMPLETED -> if (event.sessionId == null) "休息已结束，休息不计入专注统计" else "专注已完成，记录已保存"
            TimerEventKind.TARGET_REACHED -> "已达到目标，正计时仍在继续"
            TimerEventKind.RECOVERED -> "计时已恢复为暂停，请确认后继续"
        }
        post(context, EVENT_ID, base(context, EVENT_CHANNEL).setContentTitle(event.projectTitle)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }

    fun error(context: Context, message: String) {
        channels(context)
        post(context, ERROR_ID, base(context, EVENT_CHANNEL).setContentTitle("计时操作未完成")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setAutoCancel(true).build())
    }

    fun reminder(context: Context, minutes: Long): Boolean {
        channels(context)
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(REMINDER_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val text = "今天已累计使用应用约 $minutes 分钟，可以休息一下。后台定期检查，非实时提醒。"
        return post(context, REMINDER_ID, base(context, REMINDER_CHANNEL).setContentTitle("每日用时提醒")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }
}
