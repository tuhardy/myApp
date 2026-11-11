package com.focusassistant.app.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 拖拽浮层由列表外层持有，普通 LazyColumn item 只负责占位和让位。
 * 这样即使原 item 被回收，手势、吸附动画和拖拽内容也不会随之中断。
 */
internal class MusicReorderState(
    private val listState: LazyListState,
    private val onMove: (Int, Int) -> Unit,
) {
    var itemIds by mutableStateOf<List<String>>(emptyList())
        private set
    var draggedId by mutableStateOf<String?>(null)
        private set
    var fromIndex by mutableIntStateOf(-1)
        private set
    var targetIndex by mutableIntStateOf(-1)
        private set
    var draggedTop by mutableFloatStateOf(0f)
        private set
    var draggedSize by mutableFloatStateOf(0f)
        private set
    var settling by mutableStateOf(false)
        private set
    var committed by mutableStateOf(false)
        private set
    internal var motionScale by mutableFloatStateOf(1f)
        internal set

    private val scrollMutex = Mutex()
    private var session = 0L
    private var lastDraggedId: String? = null
    private var lastDragEndedAt = 0L

    fun updateItems(ids: List<String>) {
        if (ids == itemIds) return
        if (draggedId != null && !settling) clear()
        itemIds = ids
        if (draggedId !in ids && draggedId != null) clear()
    }

    fun start(positionY: Float): Boolean {
        if (draggedId != null || settling || itemIds.size < 2) return false
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            val index = itemIds.indexOf(info.key)
            index >= 0 && positionY >= info.offset && positionY < info.offset + info.size
        } ?: return false
        val id = item.key as? String ?: return false
        val index = itemIds.indexOf(id)
        if (index < 0) return false

        session += 1
        draggedTop = item.offset.toFloat()
        draggedSize = item.size.toFloat()
        fromIndex = index
        targetIndex = index
        committed = false
        draggedId = id
        return true
    }

    fun dragBy(delta: Float) {
        if (draggedId == null || settling) return
        draggedTop += delta
        updateTarget()
    }

    private fun updateTarget() {
        if (draggedId == null || settling || draggedSize <= 0f) return
        var target = fromIndex
        listState.layoutInfo.visibleItemsInfo.forEach { info ->
            val index = itemIds.indexOf(info.key)
            if (index < 0) return@forEach
            val middle = info.offset + info.size / 2f
            if (index > fromIndex && draggedTop + draggedSize > middle) {
                target = maxOf(target, index)
            } else if (index < fromIndex && draggedTop < middle) {
                target = minOf(target, index)
            }
        }
        targetIndex = target.coerceIn(0, itemIds.lastIndex)
    }

    /** 使用相邻可见行的真实布局距离，不再假设所有行等高。 */
    fun translation(id: String, index: Int): Float {
        if (draggedId == null || committed || id == draggedId) return 0f
        if (fromIndex !in itemIds.indices || targetIndex !in itemIds.indices) return 0f
        val current = visibleItemAt(index) ?: return 0f
        if (fromIndex < targetIndex && index in (fromIndex + 1)..targetIndex) {
            val previous = visibleItemAt(index - 1)
            return previous?.let { (it.offset - current.offset).toFloat() } ?: -draggedSize
        }
        if (fromIndex > targetIndex && index in targetIndex until fromIndex) {
            val next = visibleItemAt(index + 1)
            return next?.let { (it.offset - current.offset).toFloat() } ?: draggedSize
        }
        return 0f
    }

    private fun visibleItemAt(index: Int) = itemIds.getOrNull(index)?.let { id ->
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
    }

    private fun destination(from: Int, target: Int): Float {
        val targetItem = visibleItemAt(target)
        val originItem = visibleItemAt(from)
        return when {
            target == from -> originItem?.offset?.toFloat() ?: draggedTop
            from < target && targetItem != null ->
                targetItem.offset + targetItem.size - draggedSize
            targetItem != null -> targetItem.offset.toFloat()
            else -> draggedTop
        }
    }

    fun end(scope: CoroutineScope): Boolean = finish(scope, commit = true)

    fun cancel(scope: CoroutineScope): Boolean = finish(scope, commit = false)

    /** settling 在手势回调内同步设置，避免松手和下一次按压之间的竞态。 */
    private fun finish(scope: CoroutineScope, commit: Boolean): Boolean {
        val id = draggedId ?: return false
        if (settling) return false
        val ticket = session
        val from = fromIndex
        val target = targetIndex
        settling = true
        lastDraggedId = id
        lastDragEndedAt = SystemClock.uptimeMillis()
        if (!commit) targetIndex = from

        scope.launch {
            try {
                scrollMutex.withLock {
                    // 若最后一帧自动滚动尚未完成，先等它退出并应用新布局。
                    withFrameNanos { }
                    val destination = destination(from, if (commit) target else from)
                    if (motionScale == 0f || draggedTop == destination) {
                        draggedTop = destination
                    } else {
                        animate(draggedTop, destination, animationSpec = tween(160)) { value, _ ->
                            draggedTop = value
                        }
                    }
                }
                if (commit && target != from) {
                    onMove(from, target)
                    // 新顺序使用新布局，旧的让位位移必须立即归零，不能再动画一次。
                    committed = true
                    withFrameNanos { }
                    withFrameNanos { }
                }
            } finally {
                if (ticket == session) clear()
            }
        }
        return true
    }

    internal fun autoScrollDelta(elapsedSeconds: Float, edge: Float, maxSpeed: Float): Float {
        if (draggedId == null || settling || draggedSize <= 0f) return 0f
        val layout = listState.layoutInfo
        val start = layout.viewportStartOffset.toFloat()
        val end = layout.viewportEndOffset.toFloat()
        val speed = when {
            draggedTop < start + edge -> {
                val pressure = ((start + edge - draggedTop) / edge).coerceIn(0.12f, 1f)
                -maxSpeed * pressure
            }
            draggedTop + draggedSize > end - edge -> {
                val pressure = ((draggedTop + draggedSize - end + edge) / edge).coerceIn(0.12f, 1f)
                maxSpeed * pressure
            }
            else -> 0f
        }
        return speed * elapsedSeconds.coerceAtMost(0.05f)
    }

    internal suspend fun autoScrollBy(delta: Float) {
        scrollMutex.withLock {
            if (!settling && draggedId != null) {
                listState.scrollBy(delta)
                updateTarget()
            }
        }
    }

    fun shouldSuppressClick(id: String): Boolean =
        id == lastDraggedId && SystemClock.uptimeMillis() - lastDragEndedAt < 400

    private fun clear() {
        session += 1
        draggedId = null
        fromIndex = -1
        targetIndex = -1
        draggedTop = 0f
        draggedSize = 0f
        settling = false
        committed = false
    }
}

@Composable
internal fun rememberMusicReorderState(
    listState: LazyListState,
    ids: List<String>,
    onMove: (Int, Int) -> Unit,
): MusicReorderState {
    val currentOnMove by rememberUpdatedState(onMove)
    val view = LocalView.current
    val motionScale = remember(view) { if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f }
    val state = remember(listState) { MusicReorderState(listState) { from, to -> currentOnMove(from, to) } }
    SideEffect {
        state.motionScale = motionScale
        state.updateItems(ids)
    }
    return state
}

/** 手势挂在列表外层，不随被拖拽 item 的回收而取消。 */
@Composable
internal fun Modifier.musicReorderContainer(
    state: MusicReorderState,
    enabled: Boolean = true,
): Modifier {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    return this
        .clipToBounds()
        .pointerInput(state, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@withTimeoutOrNull false
                        val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        if (!change.pressed || moved) return@withTimeoutOrNull false
                    }
                    false
                }
                if (cancelled != null || !state.start(down.position.y)) return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            val delta = change.position - change.previousPosition
                            if (delta != Offset.Zero) {
                                // Initial 在内部 LazyColumn 的 Main 通道之前消费，避免拖滚动而非拖拽。
                                change.consume()
                                state.dragBy(delta.y)
                            }
                        } else {
                            change.consume()
                            released = true
                            if (state.end(scope)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            break
                        }
                    }
                } finally {
                    if (!released) state.cancel(scope)
                }
            }
        }
}

@Composable
internal fun Modifier.musicReorderItem(
    state: MusicReorderState,
    id: String,
    index: Int,
    enabled: Boolean = true,
): Modifier {
    val dragging = state.draggedId == id
    val insertionTarget = state.draggedId != null && !state.committed &&
        state.targetIndex == index && state.fromIndex != index
    val targetTranslation = state.translation(id, index)
    val siblingTranslation by animateFloatAsState(
        targetValue = if (dragging) 0f else targetTranslation,
        animationSpec = if (state.draggedId == null || state.committed || state.motionScale == 0f)
            snap() else tween(120),
        label = "musicReorderSibling",
    )
    return this
        .alpha(if (dragging) 0f else 1f)
        .then(if (dragging) Modifier.clearAndSetSemantics { } else Modifier)
        .drawBehind {
            if (insertionTarget) {
                val lineHeight = 2.dp.toPx()
                val top = if (state.targetIndex < state.fromIndex) 0f else size.height - lineHeight
                drawRect(Color(0xFFEF5B24), topLeft = Offset(0f, top), size = Size(size.width, lineHeight))
            }
        }
        .graphicsLayer {
            // 新顺序布局生效时必须同步清除旧让位位移，不能等 snap 动画下一帧归零。
            translationY = if (dragging || !enabled || state.committed) 0f else siblingTranslation
        }
}

/** 拖拽内容独立绘制，不使用 zIndex 覆盖普通列表项。 */
@Composable
internal fun MusicReorderDragOverlay(
    state: MusicReorderState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    rowContent: @Composable (String, Int) -> Unit,
) {
    val id = state.draggedId ?: return
    val index = state.fromIndex
    if (index !in state.itemIds.indices) return
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val horizontalPadding = PaddingValues(
        start = contentPadding.calculateLeftPadding(layoutDirection),
        end = contentPadding.calculateRightPadding(layoutDirection),
    )
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontalPadding)
                .offset { IntOffset(0, state.draggedTop.roundToInt()) }
                .height(with(density) { state.draggedSize.toDp() }),
            shape = RoundedCornerShape(10.dp),
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            rowContent(id, index)
        }
    }
}

@Composable
internal fun MusicReorderAutoScroll(state: MusicReorderState) {
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val maxSpeed = with(density) { 720.dp.toPx() }
    LaunchedEffect(state, state.draggedId, edge, maxSpeed) {
        if (state.draggedId == null) return@LaunchedEffect
        var previousCheck = 0L
        while (!state.settling) {
            val now = SystemClock.uptimeMillis()
            val elapsed = if (previousCheck == 0L) 0f else (now - previousCheck) / 1000f
            previousCheck = now
            val delta = state.autoScrollDelta(elapsed, edge, maxSpeed)
            if (delta != 0f) state.autoScrollBy(delta)
            delay(16)
        }
    }
}

internal data class MusicRowAction(
    val label: String,
    val enabled: Boolean = true,
    val action: () -> Unit,
)

@Composable
internal fun MusicRowMenu(trackTitle: String, actions: List<MusicRowAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, "$trackTitle 的更多操作", tint = MusicMuted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Color.White) {
            actions.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, fontSize = 12.sp) },
                    enabled = item.enabled,
                    onClick = { expanded = false; item.action() },
                )
            }
        }
    }
}
