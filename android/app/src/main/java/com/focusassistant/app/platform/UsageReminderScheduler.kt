package com.focusassistant.app.platform

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focusassistant.app.FocusApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object UsageReminderScheduler {
    private const val WORK_NAME = "daily_usage_reminder"
    private const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<UsageReminderWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES).build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else manager.cancelUniqueWork(WORK_NAME)
    }
}

class UsageReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val application = applicationContext as FocusApplication
            val state = application.repository.state.first { !it.loading }
            if (!state.settings.usageReminders || !TimerNotifications.allowed(application)) return Result.success()
            val monitor = UsageMonitor(application)
            if (!monitor.hasPermission()) return Result.success()
            val zone = ZoneId.systemDefault()
            val date = LocalDate.now(zone)
            val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (preferences.getString(LAST_DATE, null) == date.toString()) return Result.success()
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val report = monitor.query(start, System.currentTimeMillis().coerceAtLeast(start + 1))
            val currentSettings = application.repository.state.value.settings
            if (!currentSettings.usageReminders || LocalDate.now(zone) != date) return Result.success()
            if (report.totalMillis >= currentSettings.dailyGoalMinutes * MILLIS_PER_MINUTE) {
                if (TimerNotifications.reminder(application, report.totalMillis / MILLIS_PER_MINUTE)) {
                    if (!preferences.edit().putString(LAST_DATE, date.toString()).commit()) {
                        Log.e("UsageReminder", "无法保存每日提醒标记")
                        return Result.failure()
                    }
                }
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("UsageReminder", "用时提醒暂不可用 (${error.javaClass.simpleName})")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PREFERENCES = "usage_reminder_state"
        private const val LAST_DATE = "last_notified_local_date"
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MAX_RETRIES = 3
    }
}
