package com.focusassistant.app.domain

data class TimerTick(val timer: ActiveTimer?, val session: FocusSession? = null, val event: TimerEvent? = null)

object TimerEngine {
    const val CHECKPOINT_INTERVAL_MS = 5000L
    const val MIN_SESSION_MS = 1000L

    fun start(project: Project, sessionId: String, wall: Long, elapsed: Long, bootCount: Int): ActiveTimer {
        Validation.project(project)
        return ActiveTimer(sessionId, project.id, project.title, project.category, project.timerMode,
            project.targetMinutes, TimerPhase.FOCUS, TimerStatus.RUNNING, wall,
            anchorElapsed = elapsed, anchorWall = wall, bootCount = bootCount)
    }

    fun checkpoint(timer: ActiveTimer, elapsed: Long): ActiveTimer {
        if (timer.status == TimerStatus.PAUSED) return timer
        val total = timer.elapsedMs(elapsed)
        val delta = total - timer.accumulatedMs
        val end = timer.anchorWall + delta
        val pieces = if (delta > 0) append(timer.segments, TimeSegment(timer.anchorWall, end)) else timer.segments
        return timer.copy(accumulatedMs = total, anchorElapsed = elapsed, anchorWall = end, segments = pieces)
    }

    private fun append(segments: List<TimeSegment>, next: TimeSegment): List<TimeSegment> {
        val last = segments.lastOrNull()
        return if (last != null && last.endedAt == next.startedAt) segments.dropLast(1) + last.copy(endedAt = next.endedAt)
        else segments + next
    }

    fun pause(timer: ActiveTimer, elapsed: Long): ActiveTimer = checkpoint(timer, elapsed).copy(status = TimerStatus.PAUSED)

    fun resume(timer: ActiveTimer, wall: Long, elapsed: Long, bootCount: Int): ActiveTimer {
        require(timer.status == TimerStatus.PAUSED) { "计时已在运行" }
        val resumeWall = if (bootCount >= 0 && timer.bootCount == bootCount && elapsed >= timer.anchorElapsed)
            timer.anchorWall + (elapsed - timer.anchorElapsed)
        else maxOf(timer.anchorWall, wall)
        return timer.copy(status = TimerStatus.RUNNING, anchorElapsed = elapsed, anchorWall = resumeWall,
            bootCount = bootCount, recoveryPending = false)
    }

    fun recover(timer: ActiveTimer, bootCount: Int, elapsed: Long): ActiveTimer {
        if (bootCount >= 0 && timer.bootCount == bootCount && elapsed >= timer.anchorElapsed) return timer
        return timer.copy(status = TimerStatus.PAUSED, recoveryPending = true)
    }

    fun tick(timer: ActiveTimer?, elapsed: Long): TimerTick {
        if (timer == null) return TimerTick(null)
        if (timer.recoveryPending) return TimerTick(timer.copy(recoveryPending = false),
            event = TimerEvent(TimerEventKind.RECOVERED, timer.projectTitle, timer.sessionId))
        if (timer.timerMode == TimerMode.COUNTDOWN && timer.targetReached(elapsed)) {
            val session = finish(timer, elapsed)
            return TimerTick(null, session, TimerEvent(TimerEventKind.COMPLETED, timer.projectTitle, session?.id))
        }
        if (timer.status != TimerStatus.RUNNING) return TimerTick(timer)
        if (timer.timerMode == TimerMode.COUNTUP && timer.targetReached(elapsed) && !timer.targetNotified) {
            return TimerTick(checkpoint(timer, elapsed).copy(targetNotified = true),
                event = TimerEvent(TimerEventKind.TARGET_REACHED, timer.projectTitle, timer.sessionId))
        }
        return TimerTick(if (elapsed - timer.anchorElapsed >= CHECKPOINT_INTERVAL_MS) checkpoint(timer, elapsed) else timer)
    }

    /** [early] 为用户明确确认的提前结束，倒计时未到零也按实际时长保存；自动结束仍要求到零。 */
    fun finish(timer: ActiveTimer, elapsed: Long, wall: Long = timer.anchorWall, bootCount: Int = timer.bootCount, early: Boolean = false): FocusSession? {
        if (timer.phase != TimerPhase.FOCUS) return null
        val stopped = checkpoint(timer, elapsed)
        val limit = timer.targetMs
        if (timer.timerMode == TimerMode.COUNTDOWN && limit != null && stopped.accumulatedMs < limit && !early) {
            throw IllegalArgumentException("倒计时未完成")
        }
        require(stopped.accumulatedMs >= MIN_SESSION_MS) { "至少专注 1 秒后才能记录" }
        val seconds = stopped.accumulatedMs / 1000
        var remaining = seconds * 1000
        val segments = stopped.segments.mapNotNull { segment ->
            val length = minOf(segment.endedAt - segment.startedAt, remaining)
            remaining -= length
            if (length > 0) TimeSegment(segment.startedAt, segment.startedAt + length) else null
        }
        val endedAt = if (timer.timerMode == TimerMode.COUNTDOWN && limit != null && stopped.accumulatedMs >= limit) {
            stopped.segments.last().endedAt
        } else if (timer.status == TimerStatus.PAUSED) {
            if (bootCount >= 0 && timer.bootCount == bootCount && elapsed >= timer.anchorElapsed)
                timer.anchorWall + elapsed - timer.anchorElapsed
            else maxOf(timer.anchorWall, wall)
        } else stopped.anchorWall
        return FocusSession(timer.sessionId, timer.projectId, timer.projectTitle, timer.category,
            timer.timerMode, timer.targetMinutes, timer.startedAt,
            endedAt, seconds, segments)
    }
}
