package com.focusassistant.app.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor

internal class MusicReorderState(
    private val count: Int,
    private val onMove: (Int, Int) -> Unit,
) {
    var draggedId by mutableStateOf<String?>(null)
        private set
    var fromIndex by mutableIntStateOf(-1)
        private set
    var targetIndex by mutableIntStateOf(-1)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    private var itemHeight = 0f
    private var lastDraggedId: String? = null
    private var lastDragEndedAt = 0L

    fun measure(height: Int) {
        if (height > itemHeight) itemHeight = height.toFloat()
    }

    fun start(id: String, index: Int) {
        draggedId = id
        fromIndex = index
        targetIndex = index
        offset = 0f
    }

    fun dragBy(delta: Float) {
        if (draggedId == null || itemHeight <= 0f || fromIndex !in 0 until count) return
        offset += delta
        val steps = if (offset >= 0) floor((offset + itemHeight / 2) / itemHeight).toInt()
            else ceil((offset - itemHeight / 2) / itemHeight).toInt()
        targetIndex = (fromIndex + steps).coerceIn(0, count - 1)
    }

    fun translation(id: String, index: Int): Float {
        if (draggedId == null || fromIndex !in 0 until count) return 0f
        if (id == draggedId) return offset
        if (fromIndex < targetIndex && index in (fromIndex + 1)..targetIndex) return -itemHeight
        if (fromIndex > targetIndex && index in targetIndex until fromIndex) return itemHeight
        return 0f
    }

    fun end() {
        val id = draggedId ?: return
        if (targetIndex != fromIndex) onMove(fromIndex, targetIndex)
        lastDraggedId = id
        lastDragEndedAt = SystemClock.uptimeMillis()
        clear()
    }

    fun cancel() = clear()

    fun shouldSuppressClick(id: String): Boolean =
        id == lastDraggedId && SystemClock.uptimeMillis() - lastDragEndedAt < 400

    private fun clear() {
        draggedId = null
        fromIndex = -1
        targetIndex = -1
        offset = 0f
    }
}

@Composable
internal fun rememberMusicReorderState(count: Int, onMove: (Int, Int) -> Unit): MusicReorderState {
    val currentOnMove by rememberUpdatedState(onMove)
    return remember(count) { MusicReorderState(count) { from, to -> currentOnMove(from, to) } }
}

@Composable
internal fun Modifier.musicReorderItem(
    state: MusicReorderState,
    id: String,
    index: Int,
    enabled: Boolean = true,
): Modifier {
    val haptic = LocalHapticFeedback.current
    val dragging = state.draggedId == id
    val insertionTarget = state.draggedId != null && state.targetIndex == index && state.fromIndex != index
    return this
        .onSizeChanged { state.measure(it.height) }
        .zIndex(if (dragging) 2f else 0f)
        .shadow(if (dragging) 10.dp else 0.dp, spotColor = Color(0x24292929))
        .graphicsLayer { translationY = state.translation(id, index) }
        .drawBehind {
            if (insertionTarget) {
                val lineHeight = 2.dp.toPx()
                val top = if (state.targetIndex < state.fromIndex) 0f else size.height - lineHeight
                drawRect(Color(0xFFEF5B24), topLeft = Offset(0f, top), size = Size(size.width, lineHeight))
            }
        }
        .pointerInput(state, id, index, enabled) {
            if (!enabled) return@pointerInput
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    state.start(id, index)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDrag = { change, amount ->
                    change.consume()
                    state.dragBy(amount.y)
                },
                onDragEnd = {
                    state.end()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragCancel = state::cancel,
            )
        }
}

@Composable
internal fun MusicReorderAutoScroll(state: MusicReorderState, listState: LazyListState) {
    LaunchedEffect(listState, state.draggedId) {
        if (state.draggedId == null) return@LaunchedEffect
        while (true) {
            val layout = listState.layoutInfo
            val dragged = layout.visibleItemsInfo.firstOrNull { it.key == state.draggedId }
            if (dragged != null) {
                val top = dragged.offset + state.offset
                val bottom = top + dragged.size
                val edge = 96f
                val delta = when {
                    top < layout.viewportStartOffset + edge -> (top - layout.viewportStartOffset - edge).coerceAtLeast(-18f)
                    bottom > layout.viewportEndOffset - edge -> (bottom - layout.viewportEndOffset + edge).coerceAtMost(18f)
                    else -> 0f
                }
                if (delta != 0f) state.dragBy(listState.scrollBy(delta))
            }
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
    androidx.compose.foundation.layout.Box {
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
