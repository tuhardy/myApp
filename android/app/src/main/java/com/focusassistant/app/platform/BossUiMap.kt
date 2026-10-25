package com.focusassistant.app.platform

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.focusassistant.app.domain.AutoApplyRules
import com.focusassistant.app.domain.JobPlatform
import com.focusassistant.app.domain.PlatformReader
import com.focusassistant.app.domain.UiNode
import org.json.JSONArray
import org.json.JSONObject

/** 招聘 App 界面的读取与诊断导出。识别规则在 domain/BossScreenReader、ZhaopinScreenReader，这里只做节点转换和按路径找回节点。 */
internal object BossUiMap {
    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 3000
    private val screen get() = Resources.getSystem().displayMetrics

    /** 诊断导出只保留这些界面固定文字的命中情况，不记录职位、公司、聊天等正文。 */
    private val knownLabels = listOf("立即沟通", "继续沟通", "发送", "常用语", "发简历", "选择附件简历", "确定", "消息", "安全验证", "滑动验证", "拖动滑块", "请完成验证", "手机号登录", "验证码登录", "上限", "推荐", "附近", "最新",
        "申请职位", "投递简历", "立即申请", "已申请", "已投递", "投递成功", "申请成功", "选择简历", "确认投递")

    fun installedVersion(context: Context, platform: JobPlatform): String? = try {
        context.packageManager.getPackageInfo(platform.packageName, 0).versionName ?: "unknown"
    } catch (_: PackageManager.NameNotFoundException) { null }

    /** masked 为 true 时目标页被本应用遮罩盖住，部分系统会把节点都标成不可见，改按屏幕坐标判断是否在屏内。 */
    fun snapshot(root: AccessibilityNodeInfo, masked: Boolean = false): UiNode {
        var count = 0
        val bounds = Rect()
        fun onScreen(node: AccessibilityNodeInfo): Boolean {
            if (!masked) return node.isVisibleToUser
            node.getBoundsInScreen(bounds)
            return bounds.width() > 0 && bounds.height() > 0 && bounds.right > 0 && bounds.bottom > 0 &&
                bounds.left < screen.widthPixels && bounds.top < screen.heightPixels
        }
        fun convert(node: AccessibilityNodeInfo, path: List<Int>): UiNode {
            count++
            val children = if (path.size >= MAX_DEPTH) emptyList() else (0 until node.childCount).mapNotNull { index ->
                // 跳过屏幕外的子树（列表预加载项等），避免点到看不见的节点；路径仍按原始下标记录。
                if (count >= MAX_NODES) null else node.getChild(index)?.takeIf(::onScreen)?.let { convert(it, path + index) }
            }
            return UiNode(path, node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(), node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty(), node.isClickable, node.isEditable, node.isScrollable, children)
        }
        return convert(root, emptyList())
    }

    fun find(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo = root
        for (index in path) node = node.getChild(index) ?: return null
        return node
    }

    /** 一页界面的结构摘要：viewId、类名、文字长度与是否像薪资，不含正文。 */
    fun diagnostic(root: UiNode, platform: JobPlatform, version: String?, at: Long): JSONObject {
        val nodes = JSONArray()
        root.walk().forEach { node ->
            nodes.put(JSONObject().put("path", JSONArray(node.path)).put("id", node.viewId).put("cls", node.className.substringAfterLast('.'))
                .put("textLen", node.text.length).put("descLen", node.description.length)
                .put("label", knownLabels.firstOrNull { node.label.contains(it) } ?: JSONObject.NULL)
                .put("salaryLike", node.text.isNotBlank() && AutoApplyRules.isSalaryText(node.text))
                .put("click", node.clickable).put("edit", node.editable).put("scroll", node.scrollable).put("children", node.children.size))
        }
        val reading = PlatformReader.read(platform, root, at)
        return JSONObject().put("platform", platform.name).put("appVersion", version ?: JSONObject.NULL).put("at", at)
            .put("recognized", reading.observation.screen.name).put("cards", reading.observation.cards.size).put("nodes", nodes)
    }
}
