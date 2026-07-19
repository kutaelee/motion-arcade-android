package com.motionarcade.vision.pose

import com.motionarcade.vision.camera.CameraSessionRequestGate
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LivePoseObservationPipelineTest {
    @Test
    fun zeroOneAndTwoPoseFramesKeepExactCorrelationAndAllThirtyThreeValues() {
        val dispatcher = CapturingObservationDispatcher(TEST_GENERATION)
        val fixture = Fixture(dispatcher)
        val pipeline = fixture.pipeline()
        val sourceTimestamps = listOf(7_000_000_000L, 7_000_000_001L, 7_000_000_002L)

        sourceTimestamps.forEachIndexed { index, sourceTimestampNs ->
            assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(sourceTimestampNs)))
            fixture.callbacks.onResult(validLivePoseSessionResult(index, index.toLong()))
        }

        assertEquals(listOf(0, 1, 2), dispatcher.frames.map { it.poses.size })
        assertTrue(dispatcher.frames.flatMap { it.poses }.all { it.landmarks.size == 33 })
        assertEquals(sourceTimestamps, dispatcher.frames.map { it.sourceTimestampNs })
        assertEquals(listOf(0L, 1L, 2L), dispatcher.frames.map { it.taskTimestampMs })
        assertEquals(listOf(1L, 2L, 3L), dispatcher.frames.map { it.revision })
        assertTrue(dispatcher.frames.all { it.sessionGeneration == TEST_GENERATION })
        assertTrue(
            dispatcher.frames.all {
                it.coordinateSpace == LivePoseCoordinateSpace.ROTATED_ANALYSIS_NORMALIZED_V1
            },
        )
        assertEquals(
            List(33) { index -> (index + 1) / 40.0f },
            dispatcher.frames.last().poses.first().landmarks.map { it.x },
        )
        assertEquals(
            List(33) { 0.5f },
            dispatcher.frames.last().poses.last().landmarks.map { it.y },
        )
        assertTrue(
            dispatcher.frames.last().poses.flatMap { it.landmarks }.all {
                it.visibility == 0.9f && it.presence == 0.8f
            },
        )
    }

    @Test
    fun callbackDtoAndPublishedFrameAreDeeplyImmutableAndOptionalAbsenceIsPreserved() {
        val mutableLandmarks =
            MutableList(33) { index ->
                LivePoseSessionLandmark(
                    if (index == 0) -0.125f else 0.25f,
                    1.125f,
                    -0.5f,
                    if (index == 0) null else 0.75f,
                    if (index == 0) null else 0.5f,
                )
            }
        val mutablePoses = mutableListOf<List<LivePoseSessionLandmark>>(mutableLandmarks)
        val copiedCallback = LivePoseSessionResult(0L, mutablePoses)
        mutableLandmarks[0] = LivePoseSessionLandmark(0.875f, 0f, 0f, 1f, 1f)
        mutableLandmarks.clear()
        mutablePoses.clear()
        val dispatcher = CapturingObservationDispatcher(TEST_GENERATION)
        val fixture = Fixture(dispatcher)
        val pipeline = fixture.pipeline()

        assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(99L)))
        fixture.callbacks.onResult(copiedCallback)

        val observation = dispatcher.frames.single()
        val first = observation.poses.single().landmarks.first()
        assertEquals(-0.125f, first.x)
        assertEquals(1.125f, first.y)
        assertEquals(-0.5f, first.z)
        assertNull(first.visibility)
        assertNull(first.presence)
        assertFalse(first.toString().contains("-0.125"))
        assertFalse(observation.toString().contains("sourceTimestamp"))
        assertFalse(copiedCallback.toString().contains("taskTimestamp"))
        assertUnsupportedMutation { (observation.poses as MutableList).clear() }
        assertUnsupportedMutation { (observation.poses.single().landmarks as MutableList).clear() }
    }

    @Test
    fun correlatedInvalidCountShapeCoordinatesDepthAndScoresFailWholeResultClosed() {
        val validPose = validPose()
        val invalidResults =
            listOf(
                LivePoseSessionResult(0L, List(3) { validPose }),
                LivePoseSessionResult(0L, listOf(validPose.dropLast(1))),
                resultWithFirst(LivePoseSessionLandmark(Float.NaN, 0.5f, 0f, 1f, 1f)),
                resultWithFirst(
                    LivePoseSessionLandmark(
                        LIVE_POSE_IMAGE_COORDINATE_MAX + 0.001f,
                        0.5f,
                        0f,
                        1f,
                        1f,
                    ),
                ),
                resultWithFirst(
                    LivePoseSessionLandmark(
                        0.5f,
                        LIVE_POSE_IMAGE_COORDINATE_MIN - 0.001f,
                        0f,
                        1f,
                        1f,
                    ),
                ),
                resultWithFirst(
                    LivePoseSessionLandmark(0.5f, 0.5f, LIVE_POSE_DEPTH_MAX + 0.01f, 1f, 1f),
                ),
                resultWithFirst(LivePoseSessionLandmark(0.5f, 0.5f, 0f, 1.01f, 1f)),
                resultWithFirst(LivePoseSessionLandmark(0.5f, 0.5f, 0f, 1f, -0.01f)),
            )

        invalidResults.forEach { invalid ->
            val dispatcher = CapturingObservationDispatcher(TEST_GENERATION)
            val fixture = Fixture(dispatcher)
            val pipeline = fixture.pipeline()
            assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(1L)))
            fixture.callbacks.onResult(invalid)
            assertEquals(LivePoseInferencePhase.FAILED, fixture.snapshots.last().phase)
            assertEquals(0L, fixture.snapshots.last().callbackCount)
            assertTrue(dispatcher.frames.isEmpty())
            assertFalse(pipeline.isAccepting())
        }
    }

    @Test
    fun explicitOverscanDepthAndScoreEndpointsAreInclusiveAndNeverClamped() {
        val pose = validPose().toMutableList()
        pose[0] =
            LivePoseSessionLandmark(
                LIVE_POSE_IMAGE_COORDINATE_MIN,
                LIVE_POSE_IMAGE_COORDINATE_MAX,
                LIVE_POSE_DEPTH_MIN,
                0f,
                1f,
            )
        pose[1] =
            LivePoseSessionLandmark(
                LIVE_POSE_IMAGE_COORDINATE_MAX,
                LIVE_POSE_IMAGE_COORDINATE_MIN,
                LIVE_POSE_DEPTH_MAX,
                1f,
                0f,
            )
        val dispatcher = CapturingObservationDispatcher(TEST_GENERATION)
        val fixture = Fixture(dispatcher)
        val pipeline = fixture.pipeline()
        pipeline.submit(frame(1L))
        fixture.callbacks.onResult(LivePoseSessionResult(0L, listOf(pose)))

        val landmarks = dispatcher.frames.single().poses.single().landmarks
        assertEquals(LIVE_POSE_IMAGE_COORDINATE_MIN, landmarks[0].x)
        assertEquals(LIVE_POSE_IMAGE_COORDINATE_MAX, landmarks[0].y)
        assertEquals(LIVE_POSE_DEPTH_MIN, landmarks[0].z)
        assertEquals(LIVE_POSE_IMAGE_COORDINATE_MAX, landmarks[1].x)
        assertEquals(LIVE_POSE_IMAGE_COORDINATE_MIN, landmarks[1].y)
        assertEquals(LIVE_POSE_DEPTH_MAX, landmarks[1].z)
    }

    @Test
    fun mediaPipeCallbackNeverRunsBlockingConsumerAndOneHundredResultsStayBounded() {
        val consumerEntered = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)
        val latestApplied = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread?>()
        val consumerThread = AtomicReference<Thread?>()
        val summaries = Collections.synchronizedList(mutableListOf<LivePoseSemanticSummary>())
        val async =
            asyncDispatcher(TEST_GENERATION) { summary ->
                consumerThread.compareAndSet(null, Thread.currentThread())
                summaries += summary
                if (summary.revision == 1L) {
                    consumerEntered.countDown()
                    releaseConsumer.await(5L, TimeUnit.SECONDS)
                }
                if (summary.revision == 100L) latestApplied.countDown()
            }
        val fixture = Fixture(async.dispatcher)
        val pipeline = fixture.pipeline()
        assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(10_000L)))
        val callback =
            Thread {
                callbackThread.set(Thread.currentThread())
                fixture.callbacks.onResult(validLivePoseSessionResult(1, 0L))
            }
        callback.start()
        callback.join(2_000L)

        assertFalse("MediaPipe callback blocked on consumer", callback.isAlive)
        assertTrue(consumerEntered.await(2L, TimeUnit.SECONDS))
        for (index in 1 until 100) {
            assertEquals(
                LivePoseSubmissionResult.SUBMITTED,
                pipeline.submit(frame(10_000L + index)),
            )
            fixture.callbacks.onResult(validLivePoseSessionResult(index % 3, index.toLong()))
        }
        val blocked = async.dispatcher.snapshot()
        assertEquals(1, blocked.maximumObservedPendingCount)
        assertEquals(1, blocked.pendingCount)
        assertEquals(1, blocked.inFlightCount)
        assertEquals(100L, blocked.acceptedCount)
        assertTrue(blocked.droppedCount >= 98L)
        assertTrue(callbackThread.get() !== consumerThread.get())

        releaseConsumer.countDown()
        assertTrue(latestApplied.await(2L, TimeUnit.SECONDS))
        async.dispatcher.closeAndAwait(LivePoseObservationTerminalReason.RELEASED)

        assertEquals(listOf(1L, 100L), summaries.map { it.revision })
        assertEquals(LivePoseContinuityBoundary.RESET_GENERATION, summaries[0].continuityBoundary)
        assertEquals(LivePoseContinuityBoundary.RESET_REVISION_GAP, summaries[1].continuityBoundary)
        val closed = async.dispatcher.snapshot()
        assertEquals(0, closed.pendingCount)
        assertEquals(0, closed.inFlightCount)
        assertFalse(closed.accepting)
        assertEquals(2L, closed.appliedCount)
    }

    @Test
    fun closeWaitsForExecutingConsumerThenOldGenerationCanNeverDeliverAgain() {
        val consumerEntered = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)
        val summaries = Collections.synchronizedList(mutableListOf<LivePoseSemanticSummary>())
        val async =
            asyncDispatcher(TEST_GENERATION) { summary ->
                consumerEntered.countDown()
                releaseConsumer.await(5L, TimeUnit.SECONDS)
                summaries += summary
            }
        val fixture = Fixture(async.dispatcher)
        val pipeline = fixture.pipeline()
        pipeline.submit(frame(1L))
        val callback = Thread { fixture.callbacks.onResult(validLivePoseSessionResult(1, 0L)) }
        callback.start()
        callback.join(2_000L)
        assertFalse(callback.isAlive)
        assertTrue(consumerEntered.await(2L, TimeUnit.SECONDS))

        val close = Thread(pipeline::close)
        close.start()
        close.join(100L)
        assertTrue("close returned with a consumer still executing", close.isAlive)
        fixture.callbacks.onResult(validLivePoseSessionResult(2, 0L))
        releaseConsumer.countDown()
        close.join(2_000L)

        assertFalse(close.isAlive)
        assertEquals(1, summaries.size)
        assertFalse(async.dispatcher.offer(observationFrame(TEST_GENERATION, 2L)))
        val terminal = async.dispatcher.snapshot()
        assertEquals(0, terminal.pendingCount)
        assertEquals(0, terminal.inFlightCount)
        assertFalse(terminal.accepting)
    }

    @Test
    fun continuityGateAlwaysResetsOnRevisionGapAndGenerationChange() {
        val gate = LivePoseTemporalContinuityGate()
        val summaries = mutableListOf<LivePoseSemanticSummary>()
        val firstCoordinator =
            LivePoseObservationCoordinator(TEST_GENERATION, gate, LivePoseSemanticSink(summaries::add))
        assertTrue(firstCoordinator.apply(observationFrame(TEST_GENERATION, 1L)))
        assertTrue(firstCoordinator.apply(observationFrame(TEST_GENERATION, 3L)))
        firstCoordinator.terminate()
        val nextCoordinator =
            LivePoseObservationCoordinator(
                TEST_GENERATION + 1L,
                gate,
                LivePoseSemanticSink(summaries::add),
            )
        assertTrue(nextCoordinator.apply(observationFrame(TEST_GENERATION + 1L, 1L)))

        assertEquals(
            listOf(
                LivePoseContinuityBoundary.RESET_GENERATION,
                LivePoseContinuityBoundary.RESET_REVISION_GAP,
                LivePoseContinuityBoundary.RESET_GENERATION,
            ),
            summaries.map { it.continuityBoundary },
        )
        assertEquals(listOf(1L, 2L, 3L), summaries.map { it.temporalEpoch })
        assertTrue(
            summaries.all {
                it.coordinateSpace == LivePoseCoordinateSpace.ROTATED_ANALYSIS_NORMALIZED_V1 &&
                    !it.directGestureApprovalAllowed
            },
        )
    }

    @Test
    fun observationRecognizerFailureReportsCurrentGeneration() {
        val failures = mutableListOf<Long>()
        val coordinator = LivePoseObservationCoordinator(
            sessionGeneration = TEST_GENERATION,
            continuityGate = LivePoseTemporalContinuityGate(),
            semanticSink = LivePoseSemanticSink { },
            onObservationError = failures::add,
            observationSink = LivePoseObservationSink { _, _ -> error("recognizer failed") },
        )

        assertTrue(coordinator.apply(observationFrame(TEST_GENERATION, 1L)))
        assertEquals(listOf(TEST_GENERATION), failures)
    }

    @Test
    fun revisionRewindIsRejectedByBothDispatcherAndContinuityGate() {
        val directSummaries = mutableListOf<LivePoseSemanticSummary>()
        val directCoordinator =
            LivePoseObservationCoordinator(
                TEST_GENERATION,
                LivePoseTemporalContinuityGate(),
                LivePoseSemanticSink(directSummaries::add),
            )
        assertTrue(directCoordinator.apply(observationFrame(TEST_GENERATION, 2L)))
        assertFalse(directCoordinator.apply(observationFrame(TEST_GENERATION, 1L)))
        assertEquals(listOf(2L), directSummaries.map { it.revision })

        val asyncSummaries = Collections.synchronizedList(mutableListOf<LivePoseSemanticSummary>())
        val delivered = CountDownLatch(1)
        val async =
            asyncDispatcher(TEST_GENERATION) {
                asyncSummaries += it
                delivered.countDown()
            }
        assertTrue(async.dispatcher.offer(observationFrame(TEST_GENERATION, 2L)))
        assertTrue(delivered.await(2L, TimeUnit.SECONDS))
        assertFalse(async.dispatcher.offer(observationFrame(TEST_GENERATION, 1L)))
        async.dispatcher.closeAndAwait(LivePoseObservationTerminalReason.RELEASED)

        assertEquals(listOf(2L), asyncSummaries.map { it.revision })
        assertEquals(1L, async.dispatcher.snapshot().droppedCount)
    }

    @Test
    fun dequeuedOldGenerationFrameCannotDeliverAfterRebindCompletes() {
        val requestGate = CameraSessionRequestGate()
        val oldToken = requestGate.begin()
        val beforeDelivery = CountDownLatch(1)
        val releaseDelivery = CountDownLatch(1)
        val summaries = Collections.synchronizedList(mutableListOf<LivePoseSemanticSummary>())
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "old-generation-delivery-test").apply { isDaemon = true }
            }
        val coordinator =
            LivePoseObservationCoordinator(
                oldToken.generation,
                LivePoseTemporalContinuityGate(),
                LivePoseSemanticSink(summaries::add),
                deliveryGate =
                    LivePoseObservationDeliveryGate { delivery ->
                        requestGate.deliverIfCurrent(oldToken, delivery)
                    },
            )
        val dispatcher =
            BoundedLivePoseObservationDispatcher(
                oldToken.generation,
                coordinator,
                executor,
                beforeDeliveryCheck = {
                    beforeDelivery.countDown()
                    releaseDelivery.await(5L, TimeUnit.SECONDS)
                },
            )

        assertTrue(dispatcher.offer(observationFrame(oldToken.generation, 1L)))
        assertTrue(beforeDelivery.await(2L, TimeUnit.SECONDS))
        requestGate.cancel()
        val newToken = requestGate.begin()
        assertTrue(newToken.generation > oldToken.generation)
        releaseDelivery.countDown()
        dispatcher.closeAndAwait(LivePoseObservationTerminalReason.REBOUND)

        assertTrue(summaries.isEmpty())
        assertEquals(0L, dispatcher.snapshot().appliedCount)
    }

    @Test
    fun rebindCannotCompleteInsideTheCurrentCheckToSemanticCallbackWindow() {
        val requestGate = CameraSessionRequestGate()
        val oldToken = requestGate.begin()
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val rebindCompleted = CountDownLatch(1)
        val async =
            asyncDispatcher(
                generation = oldToken.generation,
                semanticConsumer = {
                    sinkEntered.countDown()
                    releaseSink.await(5L, TimeUnit.SECONDS)
                },
                deliveryGate =
                    LivePoseObservationDeliveryGate { delivery ->
                        requestGate.deliverIfCurrent(oldToken, delivery)
                    },
            )
        assertTrue(async.dispatcher.offer(observationFrame(oldToken.generation, 1L)))
        assertTrue(sinkEntered.await(2L, TimeUnit.SECONDS))

        val rebind =
            Thread {
                requestGate.cancel()
                requestGate.begin()
                rebindCompleted.countDown()
            }
        rebind.start()
        assertFalse("rebind crossed an executing old callback", rebindCompleted.await(100L, TimeUnit.MILLISECONDS))
        releaseSink.countDown()
        assertTrue(rebindCompleted.await(2L, TimeUnit.SECONDS))
        rebind.join(2_000L)
        async.dispatcher.closeAndAwait(LivePoseObservationTerminalReason.REBOUND)
        assertFalse(rebind.isAlive)
    }

    @Test
    fun consumerCanCloseItsOwnDispatcherWithoutSelfDeadlock() {
        val returned = CountDownLatch(1)
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "self-close-observation-test").apply { isDaemon = true }
            }
        lateinit var dispatcher: BoundedLivePoseObservationDispatcher
        val coordinator =
            LivePoseObservationCoordinator(
                TEST_GENERATION,
                LivePoseTemporalContinuityGate(),
                LivePoseSemanticSink {
                    dispatcher.closeAndAwait(LivePoseObservationTerminalReason.RELEASED)
                    returned.countDown()
                },
            )
        dispatcher = BoundedLivePoseObservationDispatcher(TEST_GENERATION, coordinator, executor)

        assertTrue(dispatcher.offer(observationFrame(TEST_GENERATION, 1L)))
        assertTrue("consumer self-close deadlocked", returned.await(2L, TimeUnit.SECONDS))
        assertEventually { dispatcher.snapshot().inFlightCount == 0 }
        assertDispatcherEmpty(dispatcher)
        assertTrue(executor.isShutdown)
    }

    @Test
    fun permanentlyBlockedExecuteRetainsOnlyOneProcessWorkerAndOneLatestRequest() {
        val bridge = BoundedLivePoseExecutorSubmissionBridge("bounded-bridge-test")
        val blockedExecutor = BlockingExecuteExecutorService()
        val dispatchers = mutableListOf<BoundedLivePoseObservationDispatcher>()
        try {
            val first = dispatcherWithExecutor(blockedExecutor, bridge)
            dispatchers += first
            assertTrue(first.offer(observationFrame(TEST_GENERATION, 1L)))
            assertTrue(blockedExecutor.executeEntered.await(2L, TimeUnit.SECONDS))

            repeat(24) { index ->
                val generation = TEST_GENERATION + index + 1L
                val executor = Executors.newSingleThreadExecutor()
                val dispatcher = dispatcherWithExecutor(executor, bridge, generation)
                dispatchers += dispatcher
                dispatcher.offer(observationFrame(generation, 1L))
            }
            val saturated = bridge.snapshot()
            assertEquals(1, saturated.workerThreadCount)
            assertEquals(1, saturated.executingSubmissionCount)
            assertTrue(saturated.pendingCount <= 1)
            assertEquals(1, saturated.maximumObservedPendingCount)

            dispatchers.forEach {
                it.closeAndAwait(LivePoseObservationTerminalReason.REBOUND)
            }
            val closed = bridge.snapshot()
            assertEquals(1, closed.workerThreadCount)
            assertEquals(1, closed.executingSubmissionCount)
            assertEquals(0, closed.pendingCount)
            dispatchers.forEach(::assertDispatcherEmpty)
        } finally {
            blockedExecutor.releaseExecute.countDown()
            bridge.closeForTest()
        }
    }

    @Test
    fun rejectedAndThrowingExecutorsFailClosedWithoutPendingRawData() {
        val rejectedExecutor = Executors.newSingleThreadExecutor().apply { shutdownNow() }
        val rejected = dispatcherWithExecutor(rejectedExecutor)
        assertTrue(rejected.offer(observationFrame(TEST_GENERATION, 1L)))
        assertEventually { !rejected.snapshot().accepting }
        assertFalse(rejected.offer(observationFrame(TEST_GENERATION, 2L)))
        rejected.closeAndAwait(LivePoseObservationTerminalReason.FAILED)
        assertDispatcherEmpty(rejected)

        val throwing = dispatcherWithExecutor(ThrowingExecutorService())
        assertTrue(throwing.offer(observationFrame(TEST_GENERATION, 1L)))
        assertEventually { !throwing.snapshot().accepting }
        assertFalse(throwing.offer(observationFrame(TEST_GENERATION, 2L)))
        throwing.closeAndAwait(LivePoseObservationTerminalReason.FAILED)
        assertDispatcherEmpty(throwing)
    }

    @Test
    fun synchronousSubmissionFailureCannotRetroactivelyRejectAcceptedOffer() {
        val dispatcher = dispatcherWithExecutor(
            executor = ThrowingExecutorService(),
            bridge = ImmediateFailureSubmissionBridge,
        )

        assertTrue(dispatcher.offer(observationFrame(TEST_GENERATION, 1L)))
        assertFalse(dispatcher.snapshot().accepting)
        assertFalse(dispatcher.offer(observationFrame(TEST_GENERATION, 2L)))
        dispatcher.closeAndAwait(LivePoseObservationTerminalReason.FAILED)
        assertDispatcherEmpty(dispatcher)
    }

    @Test
    fun synchronousNullAndThrowingSubmissionBridgesPreserveAcceptedOfferResult() {
        listOf(NullSubmissionBridge, ThrowingSubmissionBridge).forEach { bridge ->
            val executor = Executors.newSingleThreadExecutor()
            val dispatcher = dispatcherWithExecutor(executor = executor, bridge = bridge)

            assertTrue(dispatcher.offer(observationFrame(TEST_GENERATION, 1L)))
            assertFalse(dispatcher.snapshot().accepting)
            assertFalse(dispatcher.offer(observationFrame(TEST_GENERATION, 2L)))
            dispatcher.closeAndAwait(LivePoseObservationTerminalReason.FAILED)
            assertDispatcherEmpty(dispatcher)
            assertTrue(executor.isShutdown)
        }
    }

    @Test
    fun pipelineRecordsAdmittedCallbackBeforeClosedDispatcherFailsNextCallback() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = dispatcherWithExecutor(executor = executor, bridge = NullSubmissionBridge)
        val fixture = Fixture(dispatcher)
        val pipeline = fixture.pipeline()

        assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(1L)))
        fixture.callbacks.onResult(validLivePoseSessionResult(1, 0L))
        assertEquals(LivePoseInferencePhase.ACTIVE, fixture.snapshots.last().phase)
        assertEquals(1L, fixture.snapshots.last().callbackCount)

        assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(2L)))
        fixture.callbacks.onResult(validLivePoseSessionResult(1, 1L))
        assertEquals(LivePoseInferencePhase.FAILED, fixture.snapshots.last().phase)
        assertEquals(1L, fixture.snapshots.last().callbackCount)
        assertFalse(pipeline.isAccepting())

        pipeline.close()
        assertDispatcherEmpty(dispatcher)
        assertTrue(executor.isShutdown)
    }

    @Test
    fun executorWhoseExecuteBlocksCannotBlockCallbackOrTerminalClose() {
        val executor = BlockingExecuteExecutorService()
        val dispatcher = dispatcherWithExecutor(executor)
        val fixture = Fixture(dispatcher)
        val pipeline = fixture.pipeline()
        pipeline.submit(frame(1L))

        val callback = Thread { fixture.callbacks.onResult(validLivePoseSessionResult(1, 0L)) }
        callback.start()
        callback.join(2_000L)

        assertFalse("callback waited for ExecutorService.execute", callback.isAlive)
        assertTrue(executor.executeEntered.await(2L, TimeUnit.SECONDS))
        val close = Thread(pipeline::close)
        close.start()
        close.join(2_000L)
        assertFalse("close waited for blocked ExecutorService.execute", close.isAlive)
        assertDispatcherEmpty(dispatcher)
        executor.releaseExecute.countDown()
    }

    @Test
    fun earlyStaleDuplicateAndFutureCallbacksPreserveCorrelationAndTerminalRules() {
        val dispatcher = CapturingObservationDispatcher(TEST_GENERATION)
        lateinit var callbacks: LivePoseSessionCallbacks
        val pipeline =
            LivePosePipeline(
                sessionGeneration = TEST_GENERATION,
                sessionFactory =
                    LivePoseSessionFactory { installed ->
                        callbacks = installed
                        object : LivePoseSession {
                            override fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long) {
                                callbacks.onResult(validLivePoseSessionResult(1, taskTimestampMs))
                            }

                            override fun close() = Unit
                        }
                    },
                inferenceSink = LivePoseInferenceSink.NONE,
                observationDispatcher = dispatcher,
                timeoutScheduler = NoOpTimeoutScheduler,
            )
        assertEquals(LivePoseSubmissionResult.SUBMITTED, pipeline.submit(frame(123_456_789L)))
        assertEquals(123_456_789L, dispatcher.frames.single().sourceTimestampNs)

        val regularDispatcher = CapturingObservationDispatcher(TEST_GENERATION)
        val fixture = Fixture(regularDispatcher)
        val regular = fixture.pipeline()
        regular.submit(frame(5_000L))
        fixture.callbacks.onResult(validLivePoseSessionResult(1, 0L))
        regular.submit(frame(5_001L))
        fixture.callbacks.onResult(LivePoseSessionResult(0L, listOf(emptyList())))
        fixture.callbacks.onResult(validLivePoseSessionResult(2, 1L))
        fixture.callbacks.onResult(validLivePoseSessionResult(1, 1L))
        assertEquals(2, regularDispatcher.frames.size)
        fixture.callbacks.onResult(validLivePoseSessionResult(1, 999L))
        assertEquals(LivePoseInferencePhase.FAILED, fixture.snapshots.last().phase)
        assertEquals(2, regularDispatcher.frames.size)
    }

    @Test
    fun semanticSinkExceptionCannotBreakDispatcherLifecycle() {
        val calls = AtomicInteger()
        val firstAttempted = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val async =
            asyncDispatcher(TEST_GENERATION) {
                if (calls.incrementAndGet() == 1) {
                    firstAttempted.countDown()
                    throw IllegalStateException("consumer failed")
                }
                completed.countDown()
            }
        assertTrue(async.dispatcher.offer(observationFrame(TEST_GENERATION, 1L)))
        assertTrue(firstAttempted.await(2L, TimeUnit.SECONDS))
        assertTrue(async.dispatcher.offer(observationFrame(TEST_GENERATION, 2L)))
        assertTrue(completed.await(2L, TimeUnit.SECONDS))
        async.dispatcher.closeAndAwait(LivePoseObservationTerminalReason.RELEASED)
        assertEquals(2, calls.get())
        assertDispatcherEmpty(async.dispatcher)
    }

    @Test
    fun rawAndSemanticBoundariesExposeNoMediaPipeImageCameraOrBufferTypes() {
        val boundaryClasses =
            listOf(
                LivePoseLandmark::class.java,
                LivePoseObservation::class.java,
                LivePoseObservationFrame::class.java,
                LivePoseSessionLandmark::class.java,
                LivePoseSessionResult::class.java,
                LivePoseSessionCallbacks::class.java,
                LivePoseSemanticSummary::class.java,
                LivePoseSemanticSink::class.java,
            )
        val forbiddenPrefixes = listOf("com.google.mediapipe", "androidx.camera", "java.nio")
        boundaryClasses.forEach { boundary ->
            val exposedTypes =
                boundary.declaredFields.map { it.genericType.typeName } +
                    boundary.declaredMethods.flatMap { method ->
                        listOf(method.genericReturnType.typeName) +
                            method.genericParameterTypes.map { it.typeName }
                    }
            assertTrue(
                "${boundary.simpleName} exposed $exposedTypes",
                exposedTypes.none { type -> forbiddenPrefixes.any(type::contains) },
            )
        }
    }

    private class Fixture(
        private val observationDispatcher: LivePoseObservationDispatcher,
    ) {
        val snapshots = Collections.synchronizedList(mutableListOf<LivePoseInferenceSnapshot>())
        val session = CapturingSession()
        lateinit var callbacks: LivePoseSessionCallbacks

        fun pipeline(): LivePosePipeline =
            LivePosePipeline(
                sessionGeneration = TEST_GENERATION,
                sessionFactory =
                    LivePoseSessionFactory {
                        callbacks = it
                        session
                    },
                inferenceSink = LivePoseInferenceSink(snapshots::add),
                observationDispatcher = observationDispatcher,
                timeoutScheduler = NoOpTimeoutScheduler,
            )
    }

    private class CapturingSession : LivePoseSession {
        var closeCount = 0

        override fun detectAsync(frame: LivePoseInputFrame, taskTimestampMs: Long) = Unit

        override fun close() {
            closeCount += 1
        }
    }

    private data class AsyncDispatcherFixture(
        val dispatcher: BoundedLivePoseObservationDispatcher,
        val executor: ExecutorService,
    )

    private fun asyncDispatcher(
        generation: Long,
        deliveryGate: LivePoseObservationDeliveryGate =
            LivePoseObservationDeliveryGate.UNCONDITIONAL,
        semanticConsumer: (LivePoseSemanticSummary) -> Unit,
    ): AsyncDispatcherFixture {
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-pose-observation").apply { isDaemon = true }
            }
        val coordinator =
            LivePoseObservationCoordinator(
                generation,
                LivePoseTemporalContinuityGate(),
                LivePoseSemanticSink(semanticConsumer),
                deliveryGate,
            )
        return AsyncDispatcherFixture(
            BoundedLivePoseObservationDispatcher(generation, coordinator, executor),
            executor,
        )
    }

    private fun dispatcherWithExecutor(
        executor: ExecutorService,
        bridge: LivePoseExecutorSubmissionBridge = LivePoseExecutorSubmissionBridge.SYSTEM,
        generation: Long = TEST_GENERATION,
    ): BoundedLivePoseObservationDispatcher =
        BoundedLivePoseObservationDispatcher(
            generation,
            LivePoseObservationCoordinator(
                generation,
                LivePoseTemporalContinuityGate(),
                LivePoseSemanticSink { },
            ),
            executor,
            bridge,
        )

    private fun observationFrame(generation: Long, revision: Long): LivePoseObservationFrame =
        requireNotNull(
            validLivePoseSessionResult(1, revision - 1L).toObservationFrameOrNull(
                sessionGeneration = generation,
                frameRevision = revision,
                sourceTimestampNs = revision,
            ),
        )

    private fun validPose(): List<LivePoseSessionLandmark> =
        validLivePoseSessionResult(1, 0L).poses.single()

    private fun resultWithFirst(first: LivePoseSessionLandmark): LivePoseSessionResult =
        LivePoseSessionResult(0L, listOf(listOf(first) + validPose().drop(1)))

    private fun frame(sourceTimestampNs: Long): LivePoseInputFrame =
        LivePoseInputFrame(
            rgba = ByteBuffer.allocateDirect(4).apply { limit(4) },
            width = 1,
            height = 1,
            clockwiseRotationDegrees = 0,
            sourceTimestampNs = sourceTimestampNs,
        )

    private fun assertDispatcherEmpty(dispatcher: LivePoseObservationDispatcher) {
        val snapshot = dispatcher.snapshot()
        assertFalse(snapshot.accepting)
        assertEquals(0, snapshot.pendingCount)
        assertEquals(0, snapshot.inFlightCount)
    }

    private fun assertUnsupportedMutation(action: () -> Unit) {
        try {
            action()
            fail("Expected immutable collection mutation to fail")
        } catch (_: UnsupportedOperationException) {
            // Expected.
        }
    }

    private fun assertEventually(predicate: () -> Boolean) {
        repeat(200) {
            if (predicate()) return
            Thread.sleep(10L)
        }
        assertTrue("condition did not become true", predicate())
    }

    private object ImmediateFailureSubmissionBridge : LivePoseExecutorSubmissionBridge {
        override fun submit(
            executor: ExecutorService,
            task: Runnable,
            onFailure: () -> Unit,
        ): LivePoseExecutorSubmissionTicket {
            onFailure()
            return LivePoseExecutorSubmissionTicket {}
        }

        override fun snapshot(): LivePoseExecutorSubmissionBridgeSnapshot =
            LivePoseExecutorSubmissionBridgeSnapshot(
                pendingCount = 0,
                executingSubmissionCount = 0,
                maximumObservedPendingCount = 0,
                workerThreadCount = 0,
            )
    }

    private object NullSubmissionBridge : LivePoseExecutorSubmissionBridge {
        override fun submit(
            executor: ExecutorService,
            task: Runnable,
            onFailure: () -> Unit,
        ): LivePoseExecutorSubmissionTicket? = null

        override fun snapshot(): LivePoseExecutorSubmissionBridgeSnapshot =
            LivePoseExecutorSubmissionBridgeSnapshot(0, 0, 0, 0)
    }

    private object ThrowingSubmissionBridge : LivePoseExecutorSubmissionBridge {
        override fun submit(
            executor: ExecutorService,
            task: Runnable,
            onFailure: () -> Unit,
        ): LivePoseExecutorSubmissionTicket = throw IllegalStateException("submission failed")

        override fun snapshot(): LivePoseExecutorSubmissionBridgeSnapshot =
            LivePoseExecutorSubmissionBridgeSnapshot(0, 0, 0, 0)
    }

    private object NoOpTimeoutScheduler : LivePoseTimeoutScheduler {
        override fun schedule(delayMillis: Long, action: () -> Unit): LivePoseTimeoutHandle =
            LivePoseTimeoutHandle { }
    }

    private class ThrowingExecutorService : AbstractExecutorService() {
        @Volatile private var shutdown = false

        override fun execute(command: Runnable) {
            throw IllegalStateException("executor failed")
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
    }

    private class BlockingExecuteExecutorService : AbstractExecutorService() {
        val executeEntered = CountDownLatch(1)
        val releaseExecute = CountDownLatch(1)
        @Volatile private var shutdown = false

        override fun execute(command: Runnable) {
            executeEntered.countDown()
            while (releaseExecute.count != 0L) {
                try {
                    releaseExecute.await()
                } catch (_: InterruptedException) {
                    // Deliberately model an executor submission that ignores interruption.
                }
            }
            if (!shutdown) command.run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && releaseExecute.count == 0L

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }

    private companion object {
        const val TEST_GENERATION = 17L
    }
}
