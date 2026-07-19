package com.motionarcade.vision.pose

import com.motionarcade.vision.capability.domain.ProbeTimeContract
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePosePipelineTest {
    @Test
    fun liveSessionMovesFromInitializationToCorrelatedAggregateTwoPoseCallback() {
        val fixture = Fixture()
        val pipeline = fixture.pipeline()

        assertEquals(
            LivePoseSubmissionResult.SUBMITTED,
            pipeline.submit(frame(sourceTimestampNs = 9_000_000_000L)),
        )
        fixture.callbacks.onResult(2, fixture.session.taskTimestamps.single())

        assertEquals(
            listOf(
                LivePoseInferencePhase.INITIALIZING,
                LivePoseInferencePhase.WAITING_FOR_RESULT,
                LivePoseInferencePhase.ACTIVE,
            ),
            fixture.snapshots.map(LivePoseInferenceSnapshot::phase),
        )
        assertEquals(listOf(1L, 2L, 3L), fixture.snapshots.map { it.revision })
        assertTrue(fixture.snapshots.all { it.sessionGeneration == TEST_GENERATION })
        assertEquals(2, fixture.snapshots.last().poseCount)
        assertEquals(1L, fixture.snapshots.last().callbackCount)
        assertEquals(0L, fixture.snapshots.last().resultTimestampMs)
        assertEquals(listOf(0L), fixture.session.taskTimestamps)
    }

    @Test
    fun runtimeFailureIsTerminalAndLateCallbackCannotReviveIt() {
        val fixture = Fixture(detectFailure = IllegalStateException("dependency failed"))
        val pipeline = fixture.pipeline()

        assertEquals(LivePoseSubmissionResult.TERMINAL, pipeline.submit(frame(10L)))
        val snapshotCountAtFailure = fixture.snapshots.size
        pipeline.close()
        fixture.callbacks.onResult(1, 0L)

        assertEquals(LivePoseInferencePhase.FAILED, fixture.snapshots.last().phase)
        assertEquals(snapshotCountAtFailure, fixture.snapshots.size)
        assertEquals(1, fixture.session.closeCount)
        assertEquals(0L, fixture.snapshots.last().callbackCount)
        assertEquals(null, fixture.snapshots.last().resultTimestampMs)
        assertFalse(pipeline.isAccepting())
    }

    @Test
    fun dependencyErrorAndCorrelatedOutOfContractPoseCountFailClosed() {
        val errorFixture = Fixture()
        val errorPipeline = errorFixture.pipeline()
        assertEquals(LivePoseSubmissionResult.SUBMITTED, errorPipeline.submit(frame(1L)))
        errorFixture.callbacks.onError()
        errorFixture.callbacks.onResult(1, 0L)
        assertEquals(LivePoseInferencePhase.FAILED, errorFixture.snapshots.last().phase)

        val countFixture = Fixture()
        val countPipeline = countFixture.pipeline()
        assertEquals(LivePoseSubmissionResult.SUBMITTED, countPipeline.submit(frame(1L)))
        countFixture.callbacks.onResult(3, 0L)
        assertEquals(LivePoseInferencePhase.FAILED, countFixture.snapshots.last().phase)
        assertEquals(0L, countFixture.snapshots.last().callbackCount)
    }

    @Test
    fun closeIsIdempotentAndDropsLateCallbacks() {
        val fixture = Fixture()
        val pipeline = fixture.pipeline()
        assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(1L)))

        pipeline.close()
        pipeline.close()
        fixture.callbacks.onResult(2, 0L)

        assertEquals(1, fixture.session.closeCount)
        assertEquals(LivePoseInferencePhase.RELEASED, fixture.snapshots.last().phase)
        assertEquals(0L, fixture.snapshots.last().callbackCount)
    }

    @Test
    fun failureWhileNativeCloseIsInFlightRemainsTheOnlyTerminalState() {
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val nativeCloseReleased = AtomicBoolean(false)
        val snapshots = Collections.synchronizedList(mutableListOf<LivePoseInferenceSnapshot>())
        val session =
            object : LivePoseSession {
                override fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long) = Unit

                override fun close() {
                    closeEntered.countDown()
                    nativeCloseReleased.set(releaseClose.await(5, TimeUnit.SECONDS))
                }
            }
        val pipeline =
            LivePosePipeline(
                sessionGeneration = TEST_GENERATION,
                sessionFactory = LivePoseSessionFactory { session },
                inferenceSink = LivePoseInferenceSink(snapshots::add),
                observationDispatcher = CapturingObservationDispatcher(TEST_GENERATION),
                timeoutScheduler = ManualTimeoutScheduler(),
            )
        assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(1L)))
        val closeThread = Thread(pipeline::close)

        closeThread.start()
        assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
        pipeline.fail()
        releaseClose.countDown()
        closeThread.join(5_000L)

        assertFalse(closeThread.isAlive)
        assertTrue(nativeCloseReleased.get())
        assertEquals(LivePoseInferencePhase.FAILED, snapshots.last().phase)
        assertEquals(
            1,
            snapshots.count {
                it.phase == LivePoseInferencePhase.FAILED ||
                    it.phase == LivePoseInferencePhase.RELEASED
            },
        )
        pipeline.close()
        assertEquals(LivePoseInferencePhase.FAILED, snapshots.last().phase)
    }

    @Test
    fun creationFailureIsObservableWithoutAFalseActiveState() {
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val pipeline =
            LivePosePipeline(
                sessionGeneration = TEST_GENERATION,
                sessionFactory = LivePoseSessionFactory {
                    throw IllegalStateException("model unavailable")
                },
                inferenceSink = LivePoseInferenceSink(snapshots::add),
                observationDispatcher = CapturingObservationDispatcher(TEST_GENERATION),
                timeoutScheduler = ManualTimeoutScheduler(),
            )

        assertEquals(LivePoseSubmissionResult.TERMINAL, pipeline.submit(frame(1L)))

        assertEquals(
            listOf(LivePoseInferencePhase.INITIALIZING, LivePoseInferencePhase.FAILED),
            snapshots.map(LivePoseInferenceSnapshot::phase),
        )
        assertTrue(snapshots.zipWithNext().all { (left, right) -> left.revision < right.revision })
    }

    @Test
    fun callbackBeforeDetectAsyncReturnsIsBufferedUntilReturnWithoutArmingTheWatchdog() {
        val snapshots = mutableListOf<LivePoseInferenceSnapshot>()
        val scheduler = ManualTimeoutScheduler()
        lateinit var callbacks: LivePoseSessionCallbacks
        val pipeline =
            LivePosePipeline(
                sessionGeneration = TEST_GENERATION,
                sessionFactory = LivePoseSessionFactory { installedCallbacks ->
                    callbacks = installedCallbacks
                    object : LivePoseSession {
                        override fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long) {
                            assertEquals(0, scheduler.taskCount)
                            callbacks.onResult(1, taskTimestampMs)
                        }

                        override fun close() = Unit
                    }
                },
                inferenceSink = LivePoseInferenceSink(snapshots::add),
                observationDispatcher = CapturingObservationDispatcher(TEST_GENERATION),
                timeoutScheduler = scheduler,
            )

        assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(1L)))

        assertEquals(0, scheduler.taskCount)
        assertEquals(LivePoseInferencePhase.ACTIVE, snapshots.last().phase)
        assertEquals(0L, snapshots.last().resultTimestampMs)
        assertEquals(1, snapshots.last().poseCount)
    }

    @Test
    fun oldResultsAreInertAndUnsubmittedFutureResultFailsClosed() {
        val fixture = Fixture()
        val pipeline = fixture.pipeline()
        assertEquals(
            LivePoseSubmissionResult.SUBMITTED,
            pipeline.submit(frame(1_000_000_000L)),
        )
        assertEquals(LivePoseSubmissionResult.BUSY, pipeline.submit(frame(1_000_000_001L)))
        assertEquals(listOf(0L), fixture.session.taskTimestamps)
        fixture.callbacks.onResult(1, 0L)
        assertEquals(
            LivePoseSubmissionResult.SUBMITTED,
            pipeline.submit(frame(1_000_000_001L)),
        )
        fixture.callbacks.onResult(1, 0L)
        fixture.callbacks.onResult(2, 1L)
        val accepted = fixture.snapshots.last()
        fixture.callbacks.onResult(1, 0L)
        fixture.callbacks.onResult(1, 1L)
        assertEquals(accepted, fixture.snapshots.last())
        fixture.callbacks.onResult(1, 999L)

        assertEquals(2, fixture.snapshots.count { it.phase == LivePoseInferencePhase.ACTIVE })
        assertEquals(1L, accepted.resultTimestampMs)
        assertEquals(2, accepted.poseCount)
        assertEquals(LivePoseInferencePhase.FAILED, fixture.snapshots.last().phase)
        assertTrue(fixture.snapshots.last().revision > accepted.revision)
    }

    @Test
    fun resultDeliveryIsSerializedWhenMediaPipeCallbacksRace() {
        val firstDeliveryEntered = CountDownLatch(1)
        val releaseFirstDelivery = CountDownLatch(1)
        val delivered = Collections.synchronizedList(mutableListOf<LivePoseInferenceSnapshot>())
        val fixture = Fixture()
        val pipeline =
            fixture.pipeline(
                LivePoseInferenceSink { snapshot ->
                    if (
                        snapshot.phase == LivePoseInferencePhase.ACTIVE &&
                        snapshot.resultTimestampMs == 0L
                    ) {
                        firstDeliveryEntered.countDown()
                        assertTrue(releaseFirstDelivery.await(2, TimeUnit.SECONDS))
                    }
                    delivered += snapshot
                },
            )
        assertEquals(
            LivePoseSubmissionResult.SUBMITTED,
            pipeline.submit(frame(1_000_000_000L)),
        )

        val first = Thread { fixture.callbacks.onResult(1, 0L) }
        first.start()
        assertTrue(firstDeliveryEntered.await(2, TimeUnit.SECONDS))
        assertEquals(
            LivePoseSubmissionResult.SUBMITTED,
            pipeline.submit(frame(1_000_000_001L)),
        )
        val second = Thread { fixture.callbacks.onResult(2, 1L) }
        second.start()
        second.join(2_000L)
        assertFalse(second.isAlive)
        assertEquals(0, delivered.count { it.resultTimestampMs == 1L })

        releaseFirstDelivery.countDown()
        first.join(2_000L)
        assertFalse(first.isAlive)
        val active = delivered.filter { it.phase == LivePoseInferencePhase.ACTIVE }
        assertEquals(listOf(0L, 1L), active.map { it.resultTimestampMs })
        assertTrue(active.zipWithNext().all { (left, right) -> left.revision < right.revision })
    }

    @Test
    fun noResultTimeoutClearsStaleActiveAndLateResultCannotReviveIt() {
        val fixture = Fixture()
        val pipeline = fixture.pipeline()
        assertEquals(
            LivePoseSubmissionResult.SUBMITTED,
            pipeline.submit(frame(1_000_000_000L)),
        )
        fixture.callbacks.onResult(2, 0L)
        assertEquals(LivePoseInferencePhase.ACTIVE, fixture.snapshots.last().phase)
        assertEquals(
            LivePoseSubmissionResult.SUBMITTED,
            pipeline.submit(frame(1_000_000_001L)),
        )

        fixture.scheduler.runTask(1)
        val terminal = fixture.snapshots.last()
        fixture.callbacks.onResult(1, 1L)

        assertEquals(LivePoseInferencePhase.FAILED, terminal.phase)
        assertEquals(null, terminal.poseCount)
        assertEquals(null, terminal.resultTimestampMs)
        assertEquals(terminal, fixture.snapshots.last())
        assertFalse(pipeline.isAccepting())
    }

    @Test
    fun canceledWatchdogRaceCannotFailTheReplacementResultWindow() {
        val fixture = Fixture()
        val pipeline = fixture.pipeline()
        assertEquals(
            LivePoseSubmissionResult.SUBMITTED,
            pipeline.submit(frame(1_000_000_000L)),
        )
        fixture.callbacks.onResult(1, 0L)
        assertEquals(
            LivePoseSubmissionResult.SUBMITTED,
            pipeline.submit(frame(1_000_000_001L)),
        )

        fixture.scheduler.runTask(index = 0, evenIfCanceled = true)
        assertEquals(LivePoseInferencePhase.ACTIVE, fixture.snapshots.last().phase)
        fixture.callbacks.onResult(2, 1L)
        fixture.scheduler.runTask(index = 1, evenIfCanceled = true)

        assertEquals(LivePoseInferencePhase.ACTIVE, fixture.snapshots.last().phase)
        assertEquals(2, fixture.snapshots.last().poseCount)
        assertEquals(1L, fixture.snapshots.last().resultTimestampMs)
    }

    @Test
    fun timestampEpochStartsAtZeroAndAdvancesAcrossSubMillisecondFrames() {
        val epoch = LivePoseTimestampEpoch()

        assertEquals(0L, epoch.reserve(5_000_000_000L))
        assertEquals(1L, epoch.reserve(5_000_000_001L))
        assertEquals(5L, epoch.reserve(5_005_000_000L))
        assertEquals(null, epoch.reserve(5_005_000_000L))
        assertEquals(null, epoch.reserve(5_004_000_000L))
    }

    @Test
    fun timestampEpochRejectsMediaPipeMicrosecondOverflowBoundary() {
        val epoch =
            LivePoseTimestampEpoch(
                sourceAnchorNs = 0L,
                previousSourceTimestampNs = 1L,
                previousTaskTimestampMs = ProbeTimeContract.MAX_TASK_TIMESTAMP_MS,
            )

        assertEquals(null, epoch.reserve(2L))
    }

    private class Fixture(
        detectFailure: RuntimeException? = null,
    ) {
        val snapshots = Collections.synchronizedList(mutableListOf<LivePoseInferenceSnapshot>())
        val session = FakeSession(detectFailure)
        val scheduler = ManualTimeoutScheduler()
        lateinit var callbacks: LivePoseSessionCallbacks

        fun pipeline(
            sink: LivePoseInferenceSink = LivePoseInferenceSink(snapshots::add),
        ): LivePosePipeline =
            LivePosePipeline(
                sessionGeneration = TEST_GENERATION,
                sessionFactory = LivePoseSessionFactory { installedCallbacks ->
                    callbacks = installedCallbacks
                    session
                },
                inferenceSink = sink,
                observationDispatcher = CapturingObservationDispatcher(TEST_GENERATION),
                timeoutScheduler = scheduler,
            )
    }

    private class FakeSession(
        private val detectFailure: RuntimeException?,
    ) : LivePoseSession {
        val taskTimestamps = mutableListOf<Long>()
        var closeCount = 0

        override fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long) {
            detectFailure?.let { throw it }
            taskTimestamps += taskTimestampMs
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class ManualTimeoutScheduler : LivePoseTimeoutScheduler {
        private data class Task(
            val action: () -> Unit,
            var canceled: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()

        val taskCount: Int
            @Synchronized get() = tasks.size

        @Synchronized
        override fun schedule(delayMillis: Long, action: () -> Unit): LivePoseTimeoutHandle {
            assertEquals(LIVE_POSE_RESULT_TIMEOUT_MILLIS, delayMillis)
            val task = Task(action)
            tasks += task
            return LivePoseTimeoutHandle { synchronized(this) { task.canceled = true } }
        }

        fun runTask(index: Int, evenIfCanceled: Boolean = false) {
            val task = synchronized(this) { tasks[index] }
            if (evenIfCanceled || !task.canceled) task.action()
        }
    }

    private fun frame(sourceTimestampNs: Long): LivePoseInputFrame =
        LivePoseInputFrame(
            rgba = ByteBuffer.allocateDirect(4).apply { limit(4) },
            width = 1,
            height = 1,
            clockwiseRotationDegrees = 0,
            sourceTimestampNs = sourceTimestampNs,
        )

    private companion object {
        const val TEST_GENERATION = 7L
    }
}

private fun LivePoseSessionCallbacks.onResult(poseCount: Int, taskTimestampMs: Long) {
    onResult(validLivePoseSessionResult(poseCount, taskTimestampMs))
}
