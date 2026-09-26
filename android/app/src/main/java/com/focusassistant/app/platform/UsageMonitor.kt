package com.focusassistant.app.platform

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import com.focusassistant.app.domain.TimeSegment
import com.focusassistant.app.domain.UsageAggregation
import com.focusassistant.app.domain.UsageOverviewRange
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class AppUsage(val packageName: String, val label: String, val durationMillis: Long)
data class UsageBucket(val label: String, val durationMillis: Long, val available: Boolean)
data class UsageReport(
    val apps: List<AppUsage>,
    val totalMillis: Long,
    val warning: String?,
    val buckets: List<UsageBucket> = emptyList(),
    val todayMillis: Long? = null,
    val hasData: Boolean = false
)

class UsageMonitor(context: Context) {
    private val context = context.applicationContext

    fun hasPermission(): Boolean {
        val manager = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT &&
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED)
    }

    fun permissionIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))

    suspend fun query(start: Long, end: Long): UsageReport = queryRange(start, end)

    suspend fun queryOverview(today: LocalDate, week: Boolean, end: Long): UsageReport {
        val overview = UsageOverviewRange(today, week, ZoneId.systemDefault())
        return queryRange(overview.start, overview.end, overview, end)
    }

    private suspend fun queryRange(
        start: Long,
        end: Long,
        overview: UsageOverviewRange? = null,
        cutoff: Long = end
    ): UsageReport = withContext(Dispatchers.IO) {
        require(start >= 0 && end > start && cutoff >= 0) { "请选择有效的用时统计时间范围" }
        check(hasPermission()) { "尚未授予使用情况访问权限，无法读取应用用时" }
        check(context.getSystemService(UserManager::class.java).isUserUnlocked) { "请先解锁设备后读取应用用时" }
        val rangeEnd = minOf(end, cutoff, System.currentTimeMillis())
        val aggregation = UsageAggregation(start, rangeEnd, overview)
        if (rangeEnd <= start) return@withContext report(aggregation, "所选时间尚未开始。$LIMITATION")
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val events = manager.queryEvents((start - CARRYOVER_MS).coerceAtLeast(0), rangeEnd)
            ?: return@withContext report(aggregation, "系统暂未提供使用事件，无法确认实际用时。$LIMITATION")
        val active = mutableMapOf<String, MutableSet<String>>()
        val openedAt = mutableMapOf<String, Long>()
        var eventCount = 0
        var rangeEventCount = 0
        var screenInteractive = true
        var keyguardHidden = true

        fun close(packageName: String, time: Long) {
            val opened = openedAt.remove(packageName) ?: return
            if (packageName != context.packageName) aggregation.addSegment(packageName, TimeSegment(opened, time))
        }

        fun closeAll(time: Long) {
            openedAt.keys.toList().forEach { close(it, time) }
            active.clear()
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            coroutineContext.ensureActive()
            events.getNextEvent(event)
            eventCount++
            if (event.timeStamp >= start && event.timeStamp < rangeEnd) rangeEventCount++
            aggregation.recordEvent(event.timeStamp)
            val time = event.timeStamp
            val packageName = event.packageName
            if (Build.VERSION.SDK_INT >= 28) {
                val visibilityChanged = when (event.eventType) {
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { screenInteractive = false; true }
                    UsageEvents.Event.SCREEN_INTERACTIVE -> { screenInteractive = true; true }
                    UsageEvents.Event.KEYGUARD_SHOWN -> { keyguardHidden = false; true }
                    UsageEvents.Event.KEYGUARD_HIDDEN -> { keyguardHidden = true; true }
                    else -> false
                }
                if (visibilityChanged) {
                    if (screenInteractive && keyguardHidden) {
                        active.keys.forEach { openedAt.putIfAbsent(it, time) }
                    } else openedAt.keys.toList().forEach { close(it, time) }
                    continue
                }
            }
            if (Build.VERSION.SDK_INT >= 29 && (event.eventType == UsageEvents.Event.DEVICE_SHUTDOWN || event.eventType == UsageEvents.Event.DEVICE_STARTUP)) {
                closeAll(time)
                continue
            }
            if (packageName.isNullOrBlank()) continue
            val activityKey = event.className ?: packageName
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val activities = active.getOrPut(packageName) { mutableSetOf() }
                    if (activities.isEmpty() && screenInteractive && keyguardHidden) openedAt[packageName] = time
                    activities.add(activityKey)
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val activities = active[packageName]
                    activities?.remove(activityKey)
                    if (activities == null || activities.isEmpty()) {
                        close(packageName, time)
                        active.remove(packageName)
                    }
                }
                else -> if (Build.VERSION.SDK_INT >= 29 && event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                    val activities = active[packageName]
                    activities?.remove(activityKey)
                    if (activities == null || activities.isEmpty()) {
                        close(packageName, time)
                        active.remove(packageName)
                    }
                }
            }
        }
        closeAll(rangeEnd)
        check(hasPermission()) { "使用情况访问权限已关闭，请重新授权" }
        val prefix = when {
            eventCount == 0 -> "系统未返回使用事件；没有记录不代表实际用时为零。"
            rangeEventCount == 0 && !aggregation.hasData -> "所选时段没有可重建的前台事件，可能已超过系统保留期。"
            else -> ""
        }
        report(aggregation, prefix + LIMITATION)
    }

    private fun report(aggregation: UsageAggregation, warning: String): UsageReport {
        val apps = aggregation.totals.map { (packageName, duration) -> AppUsage(packageName, label(packageName), duration) }
            .sortedWith(compareByDescending<AppUsage> { it.durationMillis }.thenBy { it.packageName })
        return UsageReport(apps, apps.sumOf { it.durationMillis }, warning,
            aggregation.buckets.map { UsageBucket(it.label, it.durationMillis, it.available) },
            aggregation.todayMillis, aggregation.hasData)
    }

    private fun label(packageName: String): String = try {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        else manager.getApplicationInfo(packageName, 0)
        manager.getApplicationLabel(info).toString().ifBlank { packageName }
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    } catch (_: SecurityException) {
        packageName
    }

    companion object {
        private const val CARRYOVER_MS = 24 * 60 * 60 * 1000L
        private const val LIMITATION = "根据 Android 前台事件按所选时段裁剪，向前追溯 24 小时补全跨界活动，排除专注助手自身。系统保留期、延迟、缺失事件及旧版锁屏事件限制可能导致偏差；多窗口应用用时之和不等于严格的亮屏时长。"
    }
}
