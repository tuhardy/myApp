package com.focusassistant.app.domain

import org.junit.Assert.*
import org.junit.Test

class TimerEngineTest {
    private val project = Project("p", "真实项目", "学习", TimerMode.COUNTUP, 1)
    private fun start(mode: TimerMode = TimerMode.COUNTUP) = TimerEngine.start(project.copy(timerMode = mode), "s", 100_000, 10_000, 7)

    @Test fun initialStateContainsNoFakeData() {
        val state = AppState()
        assertTrue(state.projects.isEmpty() && state.todos.isEmpty() && state.sessions.isEmpty() && state.progress.isEmpty())
        assertNull(state.timer)
        assertTrue(state.loading)
    }
    @Test fun countdownClampsAndCompletesAtPlannedEndAfterLateWake() {
        val timer = start(TimerMode.COUNTDOWN)
        assertEquals(60_000L, timer.elapsedMs(180_000))
        assertEquals(0L, timer.remainingSeconds(180_000))
        val session = TimerEngine.finish(timer, 180_000)!!
        assertEquals(160_000L, session.endedAt)
        assertEquals(60L, session.durationSeconds)
        assertEquals(listOf(TimeSegment(100_000, 160_000)), session.segments)
    }
    @Test(expected = IllegalArgumentException::class) fun unfinishedCountdownCannotBeRecorded() {
        TimerEngine.finish(start(TimerMode.COUNTDOWN), 20_000)
    }
    @Test fun countupContinuesBeyondTargetAndUsesWholeSeconds() {
        val timer = start()
        assertTrue(timer.targetReached(80_321))
        assertEquals(70_321L, timer.elapsedMs(80_321))
        val session = TimerEngine.finish(timer, 80_321)!!
        assertEquals(70L, session.durationSeconds)
        assertEquals(70_000L, session.segments.sumOf { it.endedAt - it.startedAt })
    }
    @Test fun pauseExcludesGapAndIgnoresWallClockCorrectionOnResume() {
        val paused = TimerEngine.pause(start(), 20_000)
        assertEquals(10_000L, paused.elapsedMs(999_000))
        val resumed = TimerEngine.resume(paused, 99_999_999, 80_000, 7)
        val session = TimerEngine.finish(resumed, 90_000)!!
        assertEquals(20L, session.durationSeconds)
        assertEquals(listOf(TimeSegment(100_000, 110_000), TimeSegment(170_000, 180_000)), session.segments)
        assertEquals(180_000L, session.endedAt)
    }
    @Test fun checkpointsMergeContinuousSegmentsWithoutDoubleCounting() {
        val first = TimerEngine.checkpoint(start(), 14_000)
        val second = TimerEngine.checkpoint(first, 18_000)
        assertEquals(8000L, second.accumulatedMs)
        assertEquals(1, second.segments.size)
        assertEquals(12L, TimerEngine.finish(second, 22_000)!!.durationSeconds)
    }
    @Test fun sameBootProcessRestartRetainsMonotonicTime() {
        val saved = TimerEngine.checkpoint(start(), 14_000)
        val recovered = TimerEngine.recover(saved, 7, 40_000)
        assertEquals(TimerStatus.RUNNING, recovered.status)
        assertEquals(30_000L, recovered.elapsedMs(40_000))
        assertFalse(recovered.recoveryPending)
    }
    @Test fun rebootRecoversPausedAtCheckpointWithoutPhantomTime() {
        val saved = TimerEngine.checkpoint(start(), 14_000)
        val recovered = TimerEngine.recover(saved, 8, 100_000)
        assertEquals(TimerStatus.PAUSED, recovered.status)
        assertEquals(4000L, recovered.elapsedMs(500_000))
        assertTrue(recovered.recoveryPending)
        val resumed = TimerEngine.resume(recovered, 1_000_000, 200_000, 8)
        assertEquals(5000L, resumed.elapsedMs(201_000))
        assertFalse(resumed.recoveryPending)
    }
    @Test fun elapsedClockRegressionAlsoRecoversSafely() {
        assertEquals(TimerStatus.PAUSED, TimerEngine.recover(TimerEngine.checkpoint(start(), 14_000), 7, 3000).status)
    }
    @Test fun finishWhilePausedDoesNotIncludePause() {
        val paused = TimerEngine.pause(start(), 15_000)
        assertEquals(5L, TimerEngine.finish(paused, 999_000)!!.durationSeconds)
    }
    @Test(expected = IllegalArgumentException::class) fun lessThanOneSecondCannotBeRecorded() {
        TimerEngine.finish(start(), 10_999)
    }
    @Test fun breaksNeverProduceFocusHistory() {
        assertNull(TimerEngine.finish(start(TimerMode.COUNTDOWN).copy(phase = TimerPhase.SHORT_BREAK), 80_000))
    }
    @Test fun projectSnapshotIsRetained() {
        val timer = TimerEngine.start(project, "s", 100_000, 10_000, 7)
        val session = TimerEngine.finish(timer, 12_000)!!
        assertEquals(project.id, session.projectId)
        assertEquals(project.title, session.projectTitle)
        assertEquals(project.category, session.category)
        assertEquals(project.timerMode, session.timerMode)
        assertEquals(project.targetMinutes, session.targetMinutes)
    }
    @Test fun pausedCountdownRetainsExactPlannedEndAfterResumeAndCheckpoint() {
        val paused = TimerEngine.pause(start(TimerMode.COUNTDOWN), 30_000)
        val resumed = TimerEngine.resume(paused, 999, 50_000, 7)
        val checkpoint = TimerEngine.checkpoint(resumed, 200_000)
        val session = TimerEngine.finish(checkpoint, 300_000)!!
        assertEquals(180_000L, session.endedAt)
        assertEquals(60L, session.durationSeconds)
        assertEquals(listOf(TimeSegment(100_000, 120_000), TimeSegment(140_000, 180_000)), session.segments)
    }
    @Test fun countupTargetEventOccursOnceAndDoesNotStopTimer() {
        val first = TimerEngine.tick(start(), 70_000)
        assertEquals(TimerEventKind.TARGET_REACHED, first.event!!.kind)
        assertEquals(TimerStatus.RUNNING, first.timer!!.status)
        assertNull(first.session)
        val second = TimerEngine.tick(TimerEngine.recover(first.timer!!, 7, 90_000), 90_000)
        assertNull(second.event)
        assertEquals(80_000L, second.timer!!.elapsedMs(90_000))
    }
    @Test fun completedTickCannotRecordTwice() {
        val first = TimerEngine.tick(start(TimerMode.COUNTDOWN), 90_000)
        assertEquals(TimerEventKind.COMPLETED, first.event!!.kind)
        assertNotNull(first.session); assertNull(first.timer)
        val second = TimerEngine.tick(first.timer, 100_000)
        assertNull(second.session); assertNull(second.event)
    }
    @Test fun recoveryTickGivesExplicitEventAndKeepsTimerPaused() {
        val recovered = TimerEngine.recover(TimerEngine.checkpoint(start(), 15_000), 8, 1000)
        val first = TimerEngine.tick(recovered, 5000)
        assertEquals(TimerEventKind.RECOVERED, first.event!!.kind)
        assertEquals(TimerStatus.PAUSED, first.timer!!.status)
        assertNull(TimerEngine.tick(first.timer, 10_000).event)
        assertEquals(5000L, first.timer!!.elapsedMs(10_000))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidProjectTargetRejected() {
        TimerEngine.start(project.copy(targetMinutes = 0), "s", 0, 0, 1)
    }
    @Test(expected = IllegalArgumentException::class) fun targetBeyondTwentyThreeHoursRejected() {
        TimerEngine.start(project.copy(targetMinutes = 24 * 60), "s", 0, 0, 1)
    }
    @Test(expected = IllegalArgumentException::class) fun countdownWithoutTargetRejected() {
        TimerEngine.start(project.copy(timerMode = TimerMode.COUNTDOWN, targetMinutes = null), "s", 0, 0, 1)
    }
    @Test fun unlimitedCountupNeverReachesTargetAndNeverAutoCompletes() {
        val timer = TimerEngine.start(project.copy(targetMinutes = null), "s", 100_000, 10_000, 7)
        assertNull(timer.targetMs)
        assertFalse(timer.targetReached(10_000_000))
        assertEquals(0L, timer.remainingSeconds(10_000_000))
        // 不限时不应触发到点完成，也不应发出到达目标提醒。
        val tick = TimerEngine.tick(timer, 10_000_000)
        assertNull(tick.session)
        assertNull(tick.event)
        assertNotNull(tick.timer)
    }
    @Test fun unlimitedCountupFinishesManuallyWithActualSeconds() {
        val timer = TimerEngine.start(project.copy(targetMinutes = null), "s", 100_000, 10_000, 7)
        val session = TimerEngine.finish(timer, 70_000)!!
        assertEquals(60L, session.durationSeconds)
        assertNull(session.targetMinutes)
    }
    @Test fun unlimitedTargetAcceptedOnlyForCountup() {
        Validation.target(TimerMode.COUNTUP, null)
        Validation.target(TimerMode.COUNTDOWN, 23 * 60 + 59)
    }
    @Test fun countdownFinishedEarlyRecordsActualSeconds() {
        // 用户确认提前结束：按已专注时长保存，不补足到目标。
        val timer = start(TimerMode.COUNTDOWN)
        val session = TimerEngine.finish(timer, 32_400, early = true)!!
        assertEquals(22L, session.durationSeconds)
        assertEquals(1, session.targetMinutes)
        assertEquals(22_000L, session.segments.sumOf { it.endedAt - it.startedAt })
        // endedAt 是实际停止时刻，有效时长按整秒截断。
        assertEquals(122_400L, session.endedAt)
    }
    @Test fun countdownFinishedEarlyExcludesPausedTime() {
        val paused = TimerEngine.pause(start(TimerMode.COUNTDOWN), 20_000)
        val resumed = TimerEngine.resume(paused, 999_000, 500_000, 7)
        val session = TimerEngine.finish(resumed, 505_000, early = true)!!
        assertEquals(15L, session.durationSeconds)
    }
    @Test(expected = IllegalArgumentException::class) fun earlyFinishStillNeedsOneSecond() {
        TimerEngine.finish(start(TimerMode.COUNTDOWN), 10_999, early = true)
    }
    @Test(expected = IllegalArgumentException::class) fun blankProgressRejected() { Validation.progress("  ", null) }
    @Test(expected = IllegalArgumentException::class) fun invalidPercentRejected() { Validation.progress("记录", 101) }
}
