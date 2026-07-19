package com.motionarcade.app.fishing

import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.vision.motion.FishingMotionFrame
import com.motionarcade.vision.motion.FishingMotionConfig
import com.motionarcade.vision.motion.FishingMotionConfigLoader
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingGameRuntimeTest {
    @Test
    fun productionSchedulerOwnsCallbacksOffCallerThread() {
        val callerThread = Thread.currentThread().name
        val callbackThread = AtomicReference<String>()
        val callback = CountDownLatch(1)
        val target = RecordingTarget(
            tickAction = {
                callbackThread.compareAndSet(null, Thread.currentThread().name)
                callback.countDown()
            },
        )
        val runtime = FishingGameRuntime(target)

        try {
            assertTrue("runtime worker did not tick", callback.await(2L, TimeUnit.SECONDS))
            assertNotEquals(callerThread, callbackThread.get())
            assertEquals("fishing-game-runtime", callbackThread.get())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun stalledWorkerCoalescesOneHundredMotionAndCameraUpdatesToNewest() {
        val clock = MutableClock()
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, clock, scheduler)

        repeat(100) { index ->
            val revision = index + 1L
            clock.nowNs = revision
            runtime.onMotionFrame(frame(revision))
            runtime.onCameraState(active = true, poseCount = 1)
        }

        assertEquals(
            FishingGameRuntimePendingCounts(discrete = 0, motion = 1, camera = 1, overflow = 0),
            runtime.pendingCounts(),
        )
        scheduler.runOnce()

        assertEquals(
            listOf("motion:100", "camera:1", "tick:100"),
            target.events,
        )
        assertEquals(100L, target.lastCameraObservedAtNs)
        assertEquals(
            FishingGameRuntimePendingCounts(discrete = 0, motion = 0, camera = 0, overflow = 0),
            runtime.pendingCounts(),
        )
        runtime.close()
    }

    @Test
    fun degradedEdgesSurviveHealthyLatestReplacementWithinOneWorkerCycle() {
        val clock = MutableClock(1L)
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, clock, scheduler)

        runtime.onMotionFrame(frame(revision = 1L, usable = false))
        runtime.onCameraState(active = false, poseCount = null)
        clock.nowNs = 1_300_000_002L
        runtime.onMotionFrame(frame(revision = 2L, usable = true))
        runtime.onCameraState(active = true, poseCount = 1)

        assertEquals(
            FishingGameRuntimePendingCounts(discrete = 0, motion = 2, camera = 2, overflow = 0),
            runtime.pendingCounts(),
        )
        scheduler.runOnce()

        assertEquals(
            listOf("motion:1", "camera:null", "motion:2", "camera:1", "tick:1300000002"),
            target.events,
        )
        runtime.close()
    }

    @Test
    fun discreteFifoAndSurvivingCoalescedCommandsKeepGlobalSequence() {
        val clock = MutableClock(1L)
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, clock, scheduler)

        runtime.onPrimaryAction() // sequence 1
        runtime.onMotionFrame(frame(1L)) // sequence 2, later replaced
        runtime.onBackground() // sequence 3
        runtime.onCameraState(active = true, poseCount = 1) // sequence 4
        runtime.onPauseToggle() // sequence 5
        runtime.onMotionFrame(frame(2L)) // sequence 6 survives
        scheduler.runOnce()

        assertEquals(
            listOf(
                "primary:1",
                "background",
                "camera:1",
                "pause:1",
                "motion:2",
                "tick:1",
            ),
            target.events,
        )
        runtime.close()
    }

    @Test
    fun discreteOverflowIsCompressedToOneFailSafeMarker() {
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, MutableClock(5L), scheduler)

        repeat(FishingGameRuntime.DISCRETE_CAPACITY + 100) {
            runtime.onPrimaryAction()
        }

        assertEquals(
            FishingGameRuntimePendingCounts(
                discrete = FishingGameRuntime.DISCRETE_CAPACITY,
                motion = 0,
                camera = 0,
                overflow = 1,
            ),
            runtime.pendingCounts(),
        )
        scheduler.runOnce()

        assertEquals(FishingGameRuntime.DISCRETE_CAPACITY, target.primaryCount)
        assertEquals(1, target.overflowCount)
        assertEquals(1, target.events.count { it == "overflow" })
        runtime.close()
    }

    @Test
    fun fullGameplayFifoCannotDropLatestCameraRecalibrationBarrier() {
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, MutableClock(5L), scheduler)
        val baseConfig = FishingMotionConfigLoader.load(
            Files.newInputStream(fishingConfigPath()),
        )

        repeat(FishingGameRuntime.DISCRETE_CAPACITY + 1) {
            runtime.onPrimaryAction()
        }
        runtime.onCameraRecalibrated(baseConfig.withCalibrationRevision(baseConfig.calibrationRevision + 1))
        runtime.onCameraRecalibrated(baseConfig.withCalibrationRevision(baseConfig.calibrationRevision + 2))

        assertEquals(
            FishingGameRuntimePendingCounts(
                discrete = FishingGameRuntime.DISCRETE_CAPACITY + 1,
                motion = 0,
                camera = 0,
                overflow = 1,
            ),
            runtime.pendingCounts(),
        )
        scheduler.runOnce()

        assertEquals(1, target.events.count { it.startsWith("recalibrated:") })
        assertTrue(target.events.contains("recalibrated:${baseConfig.calibrationRevision + 2}"))
        assertEquals(1, target.overflowCount)
        runtime.close()
    }

    @Test
    fun oneSecondClockProgressionConvergesToSameFixedSimulationAfterCatchUp() {
        val referenceClock = MutableClock(0L)
        val referenceScheduler = ManualScheduler()
        val referenceTarget = FixedStepTarget()
        val reference = FishingGameRuntime(referenceTarget, referenceClock, referenceScheduler)
        referenceScheduler.runOnce()
        repeat(60) { index ->
            referenceClock.nowNs = min(
                ONE_SECOND_NS,
                (index + 1L) * FishingGameRuntime.FRAME_PERIOD_NS,
            )
            referenceScheduler.runOnce()
        }

        val stalledClock = MutableClock(0L)
        val stalledScheduler = ManualScheduler()
        val stalledTarget = FixedStepTarget()
        val stalled = FishingGameRuntime(stalledTarget, stalledClock, stalledScheduler)
        stalledScheduler.runOnce()
        stalledClock.nowNs = ONE_SECOND_NS
        repeat(12) { stalledScheduler.runOnce() }

        assertEquals(ONE_SECOND_NS, referenceTarget.lastTickTimestampNs)
        assertEquals(ONE_SECOND_NS, stalledTarget.lastTickTimestampNs)
        assertEquals(referenceTarget.simulatedTicks, stalledTarget.simulatedTicks)
        assertEquals(
            ONE_SECOND_NS / FishingGameSession.FIXED_STEP_NS,
            stalledTarget.simulatedTicks,
        )
        reference.close()
        stalled.close()
    }

    @Test
    fun closeFlushesOnlyLatestQueuedBackgroundExactlyOnce() {
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, MutableClock(7L), scheduler)

        runtime.onPrimaryAction()
        runtime.onBackground()
        runtime.onMotionFrame(frame(1L))
        runtime.onCameraState(active = true, poseCount = 1)
        runtime.onBackground()

        assertEquals(
            FishingGameRuntimeCloseResult.CLOSED_AFTER_BACKGROUND_DELIVERY,
            runtime.closeWithResult(),
        )
        assertEquals(listOf("background"), target.events)
        assertEquals(1, scheduler.finalEnqueueCount)
        assertEquals(
            FishingGameRuntimeCloseResult.CLOSED_AFTER_BACKGROUND_DELIVERY,
            runtime.closeWithResult(),
        )
        assertEquals(1, target.events.count { it == "background" })
    }

    @Test
    fun fullGameplayFifoCannotPreventBackgroundCloseFlush() {
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, MutableClock(7L), scheduler)

        repeat(FishingGameRuntime.DISCRETE_CAPACITY + 1) {
            runtime.onPrimaryAction()
        }
        runtime.onBackground()

        assertEquals(
            FishingGameRuntimePendingCounts(
                discrete = FishingGameRuntime.DISCRETE_CAPACITY + 1,
                motion = 0,
                camera = 0,
                overflow = 1,
            ),
            runtime.pendingCounts(),
        )
        assertEquals(
            FishingGameRuntimeCloseResult.CLOSED_AFTER_BACKGROUND_DELIVERY,
            runtime.closeWithResult(),
        )
        assertEquals(listOf("background"), target.events)
    }

    @Test
    fun closeWithoutBackgroundDoesNotScheduleFinalizerOrInvokeCallbacks() {
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, MutableClock(7L), scheduler)

        runtime.onPrimaryAction()
        runtime.onMotionFrame(frame(1L))
        runtime.onCameraState(active = true, poseCount = 1)

        assertEquals(
            FishingGameRuntimeCloseResult.CLOSED_NO_PENDING_BACKGROUND,
            runtime.closeWithResult(),
        )
        assertEquals(0, scheduler.finalEnqueueCount)
        assertTrue(target.events.isEmpty())
    }

    @Test
    fun workerDeliveredBackgroundIsNotDuplicatedByCloseFinalizer() {
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, MutableClock(7L), scheduler)
        runtime.onBackground()

        scheduler.runOnce()
        assertEquals(1, target.events.count { it == "background" })

        assertEquals(
            FishingGameRuntimeCloseResult.CLOSED_NO_PENDING_BACKGROUND,
            runtime.closeWithResult(),
        )
        assertEquals(0, scheduler.finalEnqueueCount)
        assertEquals(1, target.events.count { it == "background" })
    }

    @Test
    fun rejectedFinalizerReturnsExplicitFailureWithoutMainFallback() {
        val scheduler = ManualScheduler(acceptFinal = false)
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, MutableClock(7L), scheduler)
        runtime.onBackground()

        assertEquals(
            FishingGameRuntimeCloseResult.BACKGROUND_DELIVERY_SCHEDULE_REJECTED,
            runtime.closeWithResult(),
        )
        assertTrue(target.events.isEmpty())
    }

    @Test
    fun throwingFinalBackgroundTargetReturnsIncompleteWithoutClaimingDelivery() {
        val scheduler = ManualScheduler()
        val target = RecordingTarget(
            backgroundAction = { throw IllegalStateException("injected background failure") },
        )
        val runtime = FishingGameRuntime(target, MutableClock(7L), scheduler)
        runtime.onBackground()

        assertEquals(
            FishingGameRuntimeCloseResult.BACKGROUND_DELIVERY_INCOMPLETE,
            runtime.closeWithResult(),
        )
        assertTrue(target.events.isEmpty())
        assertEquals(1, scheduler.finalEnqueueCount)
    }

    @Test
    fun closeDropsQueuedAndSubsequentInputsWithoutCallbacks() {
        val scheduler = ManualScheduler()
        val target = RecordingTarget()
        val runtime = FishingGameRuntime(target, MutableClock(7L), scheduler)

        runtime.onPrimaryAction()
        runtime.onMotionFrame(frame(1L))
        runtime.onCameraState(active = true, poseCount = 1)
        runtime.close()
        runtime.onPrimaryAction()
        runtime.onPauseToggle()
        runtime.onBackground()
        runtime.onForeground()
        runtime.onMotionBindingStarted("session", 1L, 1L)
        runtime.onMotionBindingClosed()
        runtime.onMotionFrame(frame(2L))
        runtime.onCameraState(active = false, poseCount = null)
        scheduler.runOnce()

        assertTrue(target.events.isEmpty())
        assertEquals(
            FishingGameRuntimePendingCounts(discrete = 0, motion = 0, camera = 0, overflow = 0),
            runtime.pendingCounts(),
        )
    }

    @Test
    fun targetExceptionFencesWorkerSignalsOnceAndDropsLaterInputs() {
        var failPrimary = true
        val scheduler = ManualScheduler()
        val target = RecordingTarget(
            primaryAction = {
                if (failPrimary) {
                    failPrimary = false
                    throw IllegalStateException("injected target failure")
                }
            },
        )
        val runtime = FishingGameRuntime(target, MutableClock(9L), scheduler)

        runtime.onPrimaryAction()
        scheduler.runOnce()

        assertEquals(listOf("worker-failure"), target.events)
        assertEquals(1, target.workerFailureCount)
        assertEquals(
            FishingGameRuntimePendingCounts(discrete = 0, motion = 0, camera = 0, overflow = 0),
            runtime.pendingCounts(),
        )

        runtime.onPrimaryAction()
        runtime.onMotionFrame(frame(1L))
        runtime.onCameraState(active = true, poseCount = 1)
        scheduler.runOnce()

        assertEquals(listOf("worker-failure"), target.events)
        assertEquals(1, target.workerFailureCount)
        runtime.close()
    }

    private class MutableClock(
        var nowNs: Long = 0L,
    ) : FishingGameRuntimeMonotonicClock {
        override fun nowNs(): Long = nowNs
    }

    private class ManualScheduler(
        private val acceptFinal: Boolean = true,
    ) : FishingGameRuntimeScheduler {
        private var task: (() -> Unit)? = null
        private var finalTask: (() -> Unit)? = null
        private var closed = false
        var finalEnqueueCount = 0
            private set

        override fun scheduleWithFixedDelay(
            initialDelayNs: Long,
            delayNs: Long,
            task: () -> Unit,
        ) {
            assertEquals(0L, initialDelayNs)
            assertEquals(FishingGameRuntime.FRAME_PERIOD_NS, delayNs)
            check(this.task == null)
            this.task = task
        }

        override fun enqueueFinal(task: () -> Unit): Boolean {
            if (closed || !acceptFinal) return false
            check(finalTask == null)
            finalTask = task
            finalEnqueueCount += 1
            return true
        }

        override fun closeAndAwait(timeoutMs: Long) {
            assertTrue(timeoutMs > 0L)
            closed = true
            val task = finalTask
            finalTask = null
            task?.invoke()
        }

        fun runOnce() {
            if (!closed) checkNotNull(task).invoke()
        }
    }

    private open class RecordingTarget(
        private val tickAction: (Long) -> Unit = {},
        private val primaryAction: (Long) -> Unit = {},
        private val backgroundAction: () -> Unit = {},
    ) : FishingGameRuntimeTarget {
        val events = mutableListOf<String>()
        var primaryCount = 0
        var overflowCount = 0
        var workerFailureCount = 0
        var lastCameraObservedAtNs: Long? = null

        override fun onTick(nowNs: Long) {
            events += "tick:$nowNs"
            tickAction(nowNs)
        }

        override fun onPrimaryAction(nowNs: Long) {
            primaryAction(nowNs)
            primaryCount += 1
            events += "primary:$nowNs"
        }

        override fun onMotionFrame(frame: FishingMotionFrame, observedAtNs: Long) {
            events += "motion:${frame.revision}"
        }

        override fun onPauseToggle(nowNs: Long) {
            events += "pause:$nowNs"
        }

        override fun onBackground() {
            backgroundAction()
            events += "background"
        }

        override fun onForeground(nowNs: Long) {
            events += "foreground:$nowNs"
        }

        override fun onCameraState(active: Boolean, poseCount: Int?, observedAtNs: Long) {
            lastCameraObservedAtNs = observedAtNs
            events += "camera:$poseCount"
        }

        override fun onMotionBindingStarted(
            sessionId: String,
            eventTimelineEpoch: Long,
            sessionGeneration: Long,
        ) {
            events += "binding:$sessionGeneration"
        }

        override fun onMotionBindingClosed() {
            events += "binding-closed"
        }

        override fun onCameraRecalibrated(config: FishingMotionConfig) {
            events += "recalibrated:${config.calibrationRevision}"
        }

        override fun onRuntimeQueueOverflow() {
            overflowCount += 1
            events += "overflow"
        }

        override fun onRuntimeWorkerFailure() {
            workerFailureCount += 1
            events += "worker-failure"
        }
    }

    private class FixedStepTarget : FishingGameRuntimeTarget {
        var lastTickTimestampNs = 0L
        var simulatedTicks = 0L
        private var consumedClockNs = 0L

        override fun onTick(nowNs: Long) {
            lastTickTimestampNs = nowNs
            val availableTicks = (nowNs - consumedClockNs) / FishingGameSession.FIXED_STEP_NS
            val consumedTicks = min(
                availableTicks,
                FishingGameSession.MAX_CATCH_UP_TICKS.toLong(),
            )
            simulatedTicks += consumedTicks
            consumedClockNs += consumedTicks * FishingGameSession.FIXED_STEP_NS
        }

        override fun onPrimaryAction(nowNs: Long) = Unit

        override fun onMotionFrame(frame: FishingMotionFrame, observedAtNs: Long) = Unit

        override fun onPauseToggle(nowNs: Long) = Unit

        override fun onBackground() = Unit

        override fun onForeground(nowNs: Long) = Unit

        override fun onCameraState(active: Boolean, poseCount: Int?, observedAtNs: Long) = Unit

        override fun onMotionBindingStarted(
            sessionId: String,
            eventTimelineEpoch: Long,
            sessionGeneration: Long,
        ) = Unit

        override fun onMotionBindingClosed() = Unit

        override fun onCameraRecalibrated(config: FishingMotionConfig) = Unit

        override fun onRuntimeQueueOverflow() = Unit

        override fun onRuntimeWorkerFailure() = Unit
    }

    private companion object {
        const val ONE_SECOND_NS = 1_000_000_000L
        val FISHING_TYPES = listOf(
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
        )

        fun fishingConfigPath(): Path {
            val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
            return listOf(
                workingDirectory.resolve("vision/src/main/assets")
                    .resolve(FishingMotionConfigLoader.ASSET_PATH),
                workingDirectory.resolve("../vision/src/main/assets")
                    .resolve(FishingMotionConfigLoader.ASSET_PATH),
            ).firstOrNull(Files::isRegularFile)
                ?: error("Bundled fishing config source was not found from $workingDirectory")
        }

        fun frame(revision: Long, usable: Boolean = true): FishingMotionFrame = FishingMotionFrame(
            sessionGeneration = 1L,
            revision = revision,
            sourceTimestampNs = revision,
            poseCount = if (usable) 1 else 0,
            usableForSolo = usable,
            continuityBoundary = LivePoseContinuityBoundary.CONTIGUOUS,
            configId = "fishing-test-v1",
            calibrationRevision = 1,
            samples = FISHING_TYPES.map { type ->
                MotionSignalSample(
                    playerId = PlayerId.P1,
                    type = type,
                    timestampNs = revision,
                    activation = 0f,
                    quality = 1f,
                    confidence = 1f,
                    calibrationRevision = 1,
                    source = InputSource.MOTION,
                )
            },
        )
    }
}
