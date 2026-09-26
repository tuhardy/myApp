package com.focusassistant.app.platform

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TimerServiceCommands {
    const val START = "com.focusassistant.app.timer.START"
    const val PAUSE = "com.focusassistant.app.timer.PAUSE"
    const val RESUME = "com.focusassistant.app.timer.RESUME"
    const val FINISH = "com.focusassistant.app.timer.FINISH"
    const val DISCARD = "com.focusassistant.app.timer.DISCARD"
    const val SHORT_BREAK = "com.focusassistant.app.timer.SHORT_BREAK"
    const val LONG_BREAK = "com.focusassistant.app.timer.LONG_BREAK"
    const val RESTORE = "com.focusassistant.app.timer.RESTORE"
    internal const val PROJECT_ID = "projectId"
    internal const val TASK_ID = "taskId"
    private val mutableErrors = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<String> = mutableErrors.asSharedFlow()
    private val actions = setOf(START, PAUSE, RESUME, FINISH, DISCARD, SHORT_BREAK, LONG_BREAK, RESTORE)

    fun send(context: Context, action: String, projectId: String? = null, taskId: String? = null): Boolean {
        if (action !in actions) {
            report(context, "无法识别计时操作")
            return false
        }
        return try {
            val intent = intent(context, action).putExtra(PROJECT_ID, projectId).putExtra(TASK_ID, taskId)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (error: RuntimeException) {
            report(context, "系统未允许启动计时服务，请打开应用后重试", error)
            false
        }
    }

    internal fun intent(context: Context, action: String) = Intent(context, FocusTimerService::class.java).setAction(action)

    internal fun report(context: Context, message: String, error: Throwable? = null) {
        Log.e("FocusTimer", "$message (${error?.javaClass?.simpleName ?: "operation"})")
        mutableErrors.tryEmit(message)
        try {
            TimerNotifications.error(context, message)
        } catch (notificationError: RuntimeException) {
            Log.e("FocusTimer", "无法显示异常通知 (${notificationError.javaClass.simpleName})")
        }
    }
}
