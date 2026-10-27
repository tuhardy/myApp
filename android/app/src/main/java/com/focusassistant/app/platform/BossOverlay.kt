package com.focusassistant.app.platform

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.focusassistant.app.R
import kotlin.math.hypot

/**
 * 运行中的悬浮按钮：无障碍服务自带的 TYPE_ACCESSIBILITY_OVERLAY 窗口，不需要悬浮窗权限。
 * 窗口带 FLAG_KEEP_SCREEN_ON，任务运行期间屏幕不会自动熄灭；点按钮展开暂停 / 继续 / 停止面板，按住可拖动。
 */
internal class BossOverlay(
    private val service: AccessibilityService,
    private val onToggle: () -> Unit,
    private val onStop: () -> Unit,
    private val onCapture: () -> Unit,
    private val onQuickHide: () -> Unit,
    private val onToggleMask: () -> Unit
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val orange = ContextCompat.getColor(service, R.color.focus_orange)
    private val ink = Color.parseColor("#2B2724")
    private val muted = Color.parseColor("#77706A")
    private var root: LinearLayout? = null
    private var mask: View? = null
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var toggle: TextView
    private lateinit var maskButton: TextView
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private val maskParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.OPAQUE
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }

    private fun action(label: String, primary: Boolean, onClick: () -> Unit) = TextView(service).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        minWidth = dp(64)
        minHeight = dp(40)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(if (primary) Color.WHITE else ink)
        background = if (primary) rounded(orange, 20) else rounded(Color.WHITE, 20, Color.parseColor("#DDD8D2"))
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(diagnose: Boolean, masked: Boolean, showControl: Boolean) {
        if (root != null || (!showControl && !masked)) return
        mask = OverlayWallpaperView(service)
        if (masked) runCatching { windowManager.addView(mask, maskParams) }.onFailure { mask = null }
        bubble = TextView(service).apply {
            width = dp(52); height = dp(52)
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(orange); setStroke(dp(3), Color.WHITE) }
            elevation = dp(6).toFloat()
            contentDescription = "任务控制"
        }
        status = TextView(service).apply { textSize = 13f; setTextColor(muted); maxWidth = dp(240) }
        toggle = action("暂停", true) { onToggle() }
        val buttons = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            if (!diagnose) addView(toggle)
            if (!diagnose) addView(action("暂停并返回", false) { onQuickHide() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            if (!diagnose) {
                maskButton = action(if (masked) "取消隐藏" else "隐藏", false) { onToggleMask() }
                addView(maskButton,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            if (diagnose) addView(action("记录本页", true) { onCapture() })
            addView(action(if (diagnose) "结束" else "停止", false) { onStop() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, 14, Color.parseColor("#E8E3DD"))
            elevation = dp(6).toFloat()
            addView(status)
            addView(buttons)
        }
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(bubble)
            addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (moved) { params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); root?.let { windowManager.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); true }
                else -> false
            }
        }
        bubble.setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        val metrics = service.resources.displayMetrics
        params.x = metrics.widthPixels - dp(76)
        params.y = metrics.heightPixels / 3
        if (showControl) runCatching { windowManager.addView(layout, params) }.onSuccess { root = layout }
    }

    fun update(bubbleText: String, statusText: String, paused: Boolean) {
        if (root == null) return
        bubble.text = if (paused) "Ⅱ" else bubbleText
        status.text = statusText
        toggle.text = if (paused) "继续" else "暂停"
    }

    fun setMask(enabled: Boolean) {
        val current = mask
        if (current != null) {
            if (enabled && current.parent == null) runCatching { windowManager.addView(current, maskParams) }
            if (!enabled && current.parent != null) runCatching { windowManager.removeView(current) }
        }
        if (::maskButton.isInitialized) maskButton.text = if (enabled) "取消隐藏" else "隐藏"
    }

    fun hide() {
        mask?.let { runCatching { windowManager.removeView(it) } }
        mask = null
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }
}
