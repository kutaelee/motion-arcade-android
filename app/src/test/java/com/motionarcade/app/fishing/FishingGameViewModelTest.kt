package com.motionarcade.app.fishing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.SavedStateHandle
import com.motionarcade.app.FishingViewModelRuntimeTarget
import com.motionarcade.app.checkpoint.TypedCheckpointEnvelopeCodec
import com.motionarcade.core.contract.InputSource
import com.motionarcade.core.contract.MotionType
import com.motionarcade.core.contract.PauseReason
import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.SessionStatus
import com.motionarcade.core.motion.MotionSignalSample
import com.motionarcade.games.fishing.FishingGameSession
import com.motionarcade.games.fishing.FishingPhase
import com.motionarcade.vision.motion.FishingMotionConfig
import com.motionarcade.vision.motion.FishingMotionConfigLoader
import com.motionarcade.vision.motion.FishingMotionFrame
import com.motionarcade.vision.pose.LivePoseContinuityBoundary
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FishingGameViewModelTest {
    private val motionConfig: FishingMotionConfig by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.assets.open(FishingMotionConfigLoader.ASSET_PATH).use(
            FishingMotionConfigLoader::load,
        )
    }

    @Test
    fun primaryActionQueuesCastAndTickAdvancesIntoBiteWait() {
        val viewModel = newViewModel()
        val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs

        viewModel.onPrimaryAction(originNs)

        assertEquals("accepted_FISH_CAST", viewModel.uiState.value.lastInputDetail)
        assertEquals(FishingPhase.READY, viewModel.uiState.value.snapshot.phase)

        viewModel.onTick(originNs + FishingGameSession.FIXED_STEP_NS)

        assertEquals(FishingPhase.BITE_WAIT, viewModel.uiState.value.snapshot.phase)
    }

    @Test
    fun fixedSimulationTicksAreConflatedUntilHudObservableStateChanges() {
        val viewModel = newViewModel()
        val initial = viewModel.uiState.value
        val originNs = initial.snapshot.eventTimelineBaseNs

        repeat(60) { index ->
            viewModel.onTick(originNs + (index + 1L) * FishingGameSession.FIXED_STEP_NS)
        }

        assertSame(initial, viewModel.uiState.value)
    }

    @Test
    fun semanticMotionFrameUsesSameControllerPathAsTouchCast() {
        val viewModel = newViewModel()
        val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs
        val (trackingAtNs, trackingRevision) = establishMotionTracking(viewModel, originNs)
        val (armedAtNs, armedRevision) =
            armMotionReady(viewModel, trackingAtNs + 1L, trackingRevision + 1L)
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = armedAtNs + 1L,
                revision = armedRevision + 1L,
                activeType = MotionType.FISH_CAST,
                usable = true,
            ),
            observedAtNs = armedAtNs + 1L,
        )

        assertEquals("accepted_FISH_CAST", viewModel.uiState.value.lastInputDetail)
        viewModel.onTick(armedAtNs + 1L + FishingGameSession.FIXED_STEP_NS)
        assertEquals(FishingPhase.BITE_WAIT, viewModel.uiState.value.snapshot.phase)
    }

    @Test
    fun userPauseOnlyResumesThroughUserToggle() {
        val viewModel = newViewModel()
        val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs

        viewModel.onPauseToggle(originNs)
        val paused = viewModel.uiState.value.snapshot
        assertEquals(SessionStatus.PAUSED, paused.status)
        assertEquals(PauseReason.USER, paused.pauseReason)

        viewModel.onForeground(originNs + 1L)
        assertEquals(SessionStatus.PAUSED, viewModel.uiState.value.snapshot.status)

        viewModel.onPauseToggle(originNs + 1L)
        assertEquals(SessionStatus.RUNNING, viewModel.uiState.value.snapshot.status)
        assertNull(viewModel.uiState.value.snapshot.pauseReason)
    }

    @Test
    fun runtimeWorkerFailurePausesIntoExplicitCameraRecovery() {
        val viewModel = newViewModel()

        viewModel.onRuntimeWorkerFailure()

        val state = viewModel.uiState.value
        assertEquals(SessionStatus.PAUSED, state.snapshot.status)
        assertEquals(PauseReason.EVENT_QUEUE_OVERFLOW, state.snapshot.pauseReason)
        assertEquals(FishingRecoveryStage.WAITING_FOR_CAMERA, state.recoveryStage)
        assertEquals("runtime_worker_failure_paused", state.lastInputDetail)
        assertTrue(state.runtimeFailed)

        assertTrue(bindMotionGeneration(viewModel, 2L))
        assertFalse(viewModel.uiState.value.runtimeFailed)
        assertEquals(
            "runtime_restarted_camera_recovery_required",
            viewModel.uiState.value.lastInputDetail,
        )
    }

    @Test
    fun backgroundPauseRequiresCameraConfirmationAndCountdownWithoutCatchUp() {
        val viewModel = newViewModel()
        val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs
        viewModel.onTick(originNs + FishingGameSession.FIXED_STEP_NS)
        val foregroundNs = originNs + 500L * FishingGameSession.FIXED_STEP_NS

        viewModel.onBackground()
        val pausedTick = viewModel.uiState.value.snapshot.simulationTick
        viewModel.onForeground(foregroundNs)

        assertEquals(SessionStatus.PAUSED, viewModel.uiState.value.snapshot.status)
        assertEquals(pausedTick, viewModel.uiState.value.snapshot.simulationTick)
        assertEquals(
            FishingRecoveryStage.WAITING_FOR_CAMERA,
            viewModel.uiState.value.recoveryStage,
        )

        deliverRecoveryPose(viewModel, foregroundNs)
        viewModel.onCameraState(active = true, poseCount = 1, observedAtNs = foregroundNs + 1L)
        assertEquals(
            FishingRecoveryStage.AWAITING_CONFIRMATION,
            viewModel.uiState.value.recoveryStage,
        )
        viewModel.onPauseToggle(foregroundNs + 1L)
        assertEquals(FishingRecoveryStage.COUNTDOWN, viewModel.uiState.value.recoveryStage)
        assertEquals(3, viewModel.uiState.value.recoveryCountdownSeconds)

        var revision = 3L
        for (offsetNs in 300_000_000L..2_100_000_000L step 300_000_000L) {
            deliverUsableHeartbeat(
                viewModel,
                timestampNs = foregroundNs + offsetNs,
                revision = revision++,
                sessionGeneration = 2L,
            )
            viewModel.onTick(foregroundNs + offsetNs + 1L)
        }
        assertEquals(SessionStatus.PAUSED, viewModel.uiState.value.snapshot.status)
        assertEquals(pausedTick, viewModel.uiState.value.snapshot.simulationTick)
        assertEquals(1, viewModel.uiState.value.recoveryCountdownSeconds)

        for (offsetNs in 2_400_000_000L..3_000_000_000L step 300_000_000L) {
            deliverUsableHeartbeat(
                viewModel,
                timestampNs = foregroundNs + offsetNs,
                revision = revision++,
                sessionGeneration = 2L,
            )
            viewModel.onTick(foregroundNs + offsetNs + 1L)
        }
        assertEquals(SessionStatus.RUNNING, viewModel.uiState.value.snapshot.status)
        assertEquals(FishingRecoveryStage.NONE, viewModel.uiState.value.recoveryStage)
        assertEquals(pausedTick, viewModel.uiState.value.snapshot.simulationTick)

        viewModel.onTick(
            foregroundNs + 3_000_000_000L + FishingGameSession.FIXED_STEP_NS,
        )
        assertEquals(pausedTick, viewModel.uiState.value.snapshot.simulationTick)
    }

    @Test
    fun cameraStatusIsProjectedWithoutMutatingGameSnapshot() {
        val viewModel = newViewModel()
        val before = viewModel.uiState.value.snapshot

        viewModel.onCameraState(active = true, poseCount = 2)

        val state = viewModel.uiState.value
        assertTrue(state.cameraActive)
        assertEquals(2, state.detectedPoseCount)
        assertEquals(before, state.snapshot)

        viewModel.onCameraState(active = false, poseCount = null)
        assertFalse(viewModel.uiState.value.cameraActive)
        assertNull(viewModel.uiState.value.detectedPoseCount)
    }

    @Test
    fun processRecreationRestoresSafeProgressPausedAndRequiresRearm() {
        val handle = SavedStateHandle()
        val first = newViewModel(handle)
        val originNs = first.uiState.value.snapshot.eventTimelineBaseNs
        first.onPrimaryAction(originNs)
        first.onTick(originNs + FishingGameSession.FIXED_STEP_NS)
        val firstSnapshot = first.uiState.value.snapshot

        val recreated = newViewModel(handle)
        val recreatedSnapshot = recreated.uiState.value.snapshot

        assertEquals(firstSnapshot.sessionId, recreatedSnapshot.sessionId)
        assertEquals(firstSnapshot.seed, recreatedSnapshot.seed)
        assertEquals(firstSnapshot.phase, recreatedSnapshot.phase)
        assertEquals(firstSnapshot.simulationTick, recreatedSnapshot.simulationTick)
        assertEquals(SessionStatus.PAUSED, recreatedSnapshot.status)
        assertEquals(PauseReason.APP_BACKGROUND, recreatedSnapshot.pauseReason)
        assertEquals(
            FishingRecoveryStage.WAITING_FOR_CAMERA,
            recreated.uiState.value.recoveryStage,
        )

        val resumeAtNs = recreatedSnapshot.acceptedEventTimestampWatermarkNs + 1_000_000_000L
        deliverRecoveryPose(recreated, resumeAtNs)
        recreated.onCameraState(active = true, poseCount = 1, observedAtNs = resumeAtNs + 1L)
        recreated.onPauseToggle(resumeAtNs + 1L)
        var revision = 3L
        for (offsetNs in 300_000_000L..3_000_000_000L step 300_000_000L) {
            deliverUsableHeartbeat(
                recreated,
                timestampNs = resumeAtNs + offsetNs,
                revision = revision++,
                sessionGeneration = 2L,
            )
            recreated.onTick(resumeAtNs + offsetNs + 1L)
        }

        assertEquals(SessionStatus.RUNNING, recreated.uiState.value.snapshot.status)
        assertEquals(firstSnapshot.phase, recreated.uiState.value.snapshot.phase)
        assertEquals(firstSnapshot.simulationTick, recreated.uiState.value.snapshot.simulationTick)
    }

    @Test
    fun poseLossUnder400MillisecondsKeepsRunningAndAllowsReturnedFrame() {
        val viewModel = newViewModel()
        val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs
        val (trackingAtNs, trackingRevision) = establishMotionTracking(viewModel, originNs)
        val (armedAtNs, armedRevision) =
            armMotionReady(viewModel, trackingAtNs + 1L, trackingRevision + 1L)
        val lossStartedNs = armedAtNs + 1L

        viewModel.onMotionFrame(
            motionFrame(lossStartedNs, armedRevision + 1L, null, usable = false),
            observedAtNs = lossStartedNs,
        )
        val returnedAtNs = lossStartedNs + 399_999_999L
        repeat(4) { viewModel.onTick(returnedAtNs) }
        viewModel.onMotionFrame(
            motionFrame(
                returnedAtNs,
                armedRevision + 2L,
                MotionType.FISH_CAST,
                usable = true,
            ),
            observedAtNs = returnedAtNs,
        )

        assertEquals(SessionStatus.RUNNING, viewModel.uiState.value.snapshot.status)
        assertEquals("accepted_FISH_CAST", viewModel.uiState.value.lastInputDetail)
    }

    @Test
    fun poseLossAt400MillisecondsCancelsReturnedFrameAndAfter1p2SecondsPauses() {
        val viewModel = newViewModel()
        val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs
        val (trackingAtNs, trackingRevision) = establishMotionTracking(viewModel, originNs)
        val (armedAtNs, armedRevision) =
            armMotionReady(viewModel, trackingAtNs + 1L, trackingRevision + 1L)
        val lossStartedNs = armedAtNs + 1L
        val detailBeforeLoss = viewModel.uiState.value.lastInputDetail

        viewModel.onMotionFrame(
            motionFrame(lossStartedNs, armedRevision + 1L, null, usable = false),
            observedAtNs = lossStartedNs,
        )
        viewModel.onMotionFrame(
            motionFrame(
                lossStartedNs + 400_000_000L,
                armedRevision + 2L,
                MotionType.FISH_CAST,
                usable = true,
            ),
            observedAtNs = lossStartedNs + 400_000_000L,
        )
        assertEquals(detailBeforeLoss, viewModel.uiState.value.lastInputDetail)
        assertEquals(SessionStatus.RUNNING, viewModel.uiState.value.snapshot.status)

        viewModel.onMotionFrame(
            motionFrame(
                lossStartedNs + 400_000_001L,
                armedRevision + 3L,
                MotionType.FISH_CAST,
                usable = true,
            ),
            observedAtNs = lossStartedNs + 400_000_001L,
        )
        viewModel.onMotionFrame(
            motionFrame(
                lossStartedNs + 700_000_000L,
                armedRevision + 4L,
                MotionType.FISH_CAST,
                usable = true,
            ),
            observedAtNs = lossStartedNs + 700_000_000L,
        )
        assertEquals(detailBeforeLoss, viewModel.uiState.value.lastInputDetail)

        viewModel.onMotionFrame(
            motionFrame(
                lossStartedNs + 700_000_001L,
                armedRevision + 5L,
                null,
                usable = false,
            ),
            observedAtNs = lossStartedNs + 700_000_001L,
        )
        viewModel.onTick(lossStartedNs + 1_900_000_001L)

        assertEquals(SessionStatus.RUNNING, viewModel.uiState.value.snapshot.status)

        viewModel.onTick(lossStartedNs + 1_900_000_002L)

        assertEquals(SessionStatus.PAUSED, viewModel.uiState.value.snapshot.status)
        assertEquals(PauseReason.POSE_LOST, viewModel.uiState.value.snapshot.pauseReason)
        assertEquals(FishingRecoveryStage.WAITING_FOR_CAMERA, viewModel.uiState.value.recoveryStage)
    }

    @Test
    fun recoveryCountdownRequiresFreshUsableFramesAtThe400MillisecondBoundary() {
        val viewModel = newViewModel()
        val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs
        viewModel.onBackground()
        deliverRecoveryPose(viewModel, originNs + 1_000_000_000L)
        val usableAtNs = originNs + 1_000_000_001L
        viewModel.onCameraState(active = true, poseCount = 1, observedAtNs = usableAtNs)
        assertEquals(FishingRecoveryStage.AWAITING_CONFIRMATION, viewModel.uiState.value.recoveryStage)
        viewModel.onPauseToggle(usableAtNs)

        viewModel.onTick(usableAtNs + 399_999_999L)
        assertEquals(FishingRecoveryStage.COUNTDOWN, viewModel.uiState.value.recoveryStage)

        viewModel.onTick(usableAtNs + 400_000_000L)
        assertEquals(FishingRecoveryStage.WAITING_FOR_CAMERA, viewModel.uiState.value.recoveryStage)
        assertEquals(SessionStatus.PAUSED, viewModel.uiState.value.snapshot.status)
    }

    @Test
    fun usableReturnCannotBypassTheObservedPoseLossPauseBoundary() {
        listOf(1_200_000_000L to false, 1_200_000_001L to true).forEach { (gapNs, pauses) ->
            val viewModel = newViewModel()
            val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs
            val (trackingAtNs, trackingRevision) = establishMotionTracking(viewModel, originNs)
            val lossAtNs = trackingAtNs + 1L
            viewModel.onMotionFrame(
                motionFrame(lossAtNs, trackingRevision + 1L, null, usable = false),
                observedAtNs = lossAtNs,
            )
            viewModel.onMotionFrame(
                motionFrame(lossAtNs + gapNs, trackingRevision + 2L, null, usable = true),
                observedAtNs = lossAtNs + gapNs,
            )

            assertEquals(
                "gap=$gapNs",
                pauses,
                viewModel.uiState.value.snapshot.pauseReason == PauseReason.POSE_LOST,
            )
        }
    }

    @Test
    fun missingMotionHeartbeatUsesExactCancelAndPauseBoundaries() {
        listOf(1_200_000_000L to false, 1_200_000_001L to true).forEach { (gapNs, pauses) ->
            val viewModel = newViewModel()
            val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs
            val (lastHeartbeatNs, _) = establishMotionTracking(viewModel, originNs)

            viewModel.onTick(lastHeartbeatNs + 399_999_999L)
            assertEquals(SessionStatus.RUNNING, viewModel.uiState.value.snapshot.status)

            viewModel.onTick(lastHeartbeatNs + 400_000_000L)
            assertEquals(SessionStatus.RUNNING, viewModel.uiState.value.snapshot.status)

            viewModel.onTick(lastHeartbeatNs + gapNs)
            assertEquals(
                "gap=$gapNs",
                pauses,
                viewModel.uiState.value.snapshot.pauseReason == PauseReason.POSE_LOST,
            )
        }
    }

    @Test
    fun stalledRuntimePreservesMotionAndCameraLossEdgesBeforeHealthyLatestValues() {
        listOf("motion", "camera").forEach { lane ->
            val viewModel = newViewModel()
            val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs
            val (trackingAtNs, trackingRevision) = establishMotionTracking(viewModel, originNs)
            var runtimeClockNs = trackingAtNs + 1L
            val scheduler = ManualFishingRuntimeScheduler()
            val runtime = FishingGameRuntime(
                target = FishingViewModelRuntimeTarget(viewModel),
                clock = FishingGameRuntimeMonotonicClock { runtimeClockNs },
                scheduler = scheduler,
            )

            if (lane == "motion") {
                runtime.onMotionFrame(
                    motionFrame(runtimeClockNs, trackingRevision + 1L, null, usable = false),
                )
            } else {
                runtime.onCameraState(active = false, poseCount = null)
            }
            runtimeClockNs += 1_200_000_001L
            if (lane == "motion") {
                runtime.onMotionFrame(
                    motionFrame(runtimeClockNs, trackingRevision + 2L, null, usable = true),
                )
            } else {
                runtime.onCameraState(active = true, poseCount = 1)
            }

            scheduler.runOnce()

            assertEquals("lane=$lane", SessionStatus.PAUSED, viewModel.uiState.value.snapshot.status)
            assertEquals(PauseReason.POSE_LOST, viewModel.uiState.value.snapshot.pauseReason)
            runtime.close()
        }
    }

    @Test
    fun staleGameIdentityCannotOpenMotionAdmissionAfterResume() {
        val viewModel = newViewModel()
        val originNs = viewModel.uiState.value.snapshot.eventTimelineBaseNs
        val beforePause = viewModel.uiState.value.snapshot
        viewModel.onPauseToggle(originNs)
        viewModel.onPauseToggle(originNs + 1L)
        val resumed = viewModel.uiState.value.snapshot
        assertTrue(resumed.eventTimelineEpoch > beforePause.eventTimelineEpoch)

        assertFalse(
            viewModel.onMotionBindingStarted(
                sessionId = beforePause.sessionId,
                eventTimelineEpoch = beforePause.eventTimelineEpoch,
                sessionGeneration = 2L,
            ),
        )
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = originNs + 2L,
                revision = 1L,
                activeType = MotionType.FISH_CAST,
                usable = true,
                sessionGeneration = 1L,
            ),
            observedAtNs = originNs + 2L,
        )
        assertNull(viewModel.uiState.value.lastInputDetail)

        assertTrue(bindMotionGeneration(viewModel, 2L))
        assertTrue(bindMotionGeneration(viewModel, 3L))
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = originNs + 3L,
                revision = 1L,
                activeType = null,
                usable = false,
                boundary = LivePoseContinuityBoundary.RESET_GENERATION,
                sessionGeneration = 2L,
            ),
            observedAtNs = originNs + 3L,
        )
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = originNs + 4L,
                revision = 1L,
                activeType = null,
                usable = false,
                boundary = LivePoseContinuityBoundary.RESET_GENERATION,
                sessionGeneration = 3L,
            ),
            observedAtNs = originNs + 4L,
        )
        assertNull(viewModel.uiState.value.lastInputDetail)
    }

    @Test
    fun corruptCheckpointIsQuarantinedBeforeFreshSessionReplacesIt() {
        val rejected = byteArrayOf(1, 2, 3, 4, 5)
        val handle = SavedStateHandle(mapOf("fishing.checkpoint.v1" to rejected))

        val viewModel = newViewModel(handle)

        assertEquals(
            "checkpoint_rejected_new_session",
            viewModel.uiState.value.lastInputDetail,
        )
        assertArrayEquals(
            rejected,
            handle.get<ByteArray>("fishing.checkpoint.rejected.v1"),
        )
        assertTrue(requireNotNull(handle.get<ByteArray>("fishing.checkpoint.v1")).size > 5)
    }

    @Test
    fun exactLegacyCheckpointRestoresThenIsAtomicallyRewrittenAsTypedV2() {
        val legacyPayload = FishingLegacyCheckpointFixtures.pausedV1
        val original = requireNotNull(FishingCheckpointCodec.decode(legacyPayload))
        val legacyConfig = motionConfig.withCalibrationRevision(original.calibrationRevision)
        val handle = SavedStateHandle(
            mapOf(
                "fishing.checkpoint.v1" to legacyPayload,
                "fishing.motion-config-id.v1" to legacyConfig.configId,
                "fishing.motion-config-revision.v1" to legacyConfig.calibrationRevision,
            ),
        )

        val recreated = FishingGameViewModel(handle, legacyConfig)
        assertTrue(bindMotionGeneration(recreated, 1L))

        assertEquals(original.sessionId, recreated.uiState.value.snapshot.sessionId)
        assertEquals(
            "checkpoint_restored_waiting_for_rearm",
            recreated.uiState.value.lastInputDetail,
        )
        val rewritten = requireNotNull(handle.get<ByteArray>("fishing.checkpoint.v1"))
        assertTrue(TypedCheckpointEnvelopeCodec.hasEnvelopeMagic(rewritten))
        assertArrayEquals(
            legacyPayload,
            requireNotNull(TypedCheckpointEnvelopeCodec.decode(rewritten)).payload,
        )
        assertNull(handle.get<ByteArray>("fishing.checkpoint.rejected.v1"))
    }

    @Test
    fun oversizedCheckpointIsRejectedWithoutCopyingItIntoSavedStateAgain() {
        val rejected = ByteArray(FishingCheckpointCodec.MAX_ENCODED_BYTES + 1)
        val handle = SavedStateHandle(mapOf("fishing.checkpoint.v1" to rejected))

        val viewModel = newViewModel(handle)

        assertEquals(
            "checkpoint_rejected_new_session",
            viewModel.uiState.value.lastInputDetail,
        )
        assertNull(handle.get<ByteArray>("fishing.checkpoint.rejected.v1"))
    }

    @Test
    fun configIdChangeQuarantinesOldCheckpointAndStartsNewSession() {
        val handle = SavedStateHandle()
        val first = newViewModel(handle)
        val oldSessionId = first.uiState.value.snapshot.sessionId
        val context = ApplicationProvider.getApplicationContext<Context>()
        val changedText = context.assets.open(FishingMotionConfigLoader.ASSET_PATH)
            .bufferedReader()
            .use { it.readText() }
            .replace(
                "configId=fishing-qa-candidate-v2",
                "configId=fishing-qa-candidate-v3",
            )
        val changedConfig = FishingMotionConfigLoader.load(
            ByteArrayInputStream(changedText.toByteArray(StandardCharsets.UTF_8)),
        )

        val recreated = FishingGameViewModel(handle, changedConfig)

        assertEquals(
            "checkpoint_config_changed_new_session",
            recreated.uiState.value.lastInputDetail,
        )
        assertTrue(recreated.uiState.value.snapshot.sessionId != oldSessionId)
        assertEquals(
            changedConfig.configId,
            handle.get<String>("fishing.motion-config-id.v1"),
        )
        assertTrue(requireNotNull(handle.get<ByteArray>("fishing.checkpoint.rejected.v1")).isNotEmpty())
    }

    @Test
    fun cameraRecalibrationPersistsSameSessionAndRestoresUnderNewRevision() {
        val handle = SavedStateHandle()
        val viewModel = newViewModel(handle)
        val before = viewModel.uiState.value.snapshot
        val nextConfig = motionConfig.withCalibrationRevision(motionConfig.calibrationRevision + 1)

        viewModel.onCameraRecalibrated(nextConfig)

        val migrated = viewModel.uiState.value.snapshot
        assertEquals(before.sessionId, migrated.sessionId)
        assertEquals(nextConfig.calibrationRevision, migrated.calibrationRevision)
        assertEquals(before.simulationTick, migrated.simulationTick)
        assertEquals(before.score, migrated.score)
        assertEquals(SessionStatus.PAUSED, migrated.status)
        assertEquals(PauseReason.CAMERA_SWITCH, migrated.pauseReason)
        assertEquals(FishingRecoveryStage.WAITING_FOR_CAMERA, viewModel.uiState.value.recoveryStage)
        assertEquals(
            nextConfig.calibrationRevision,
            handle.get<Int>("fishing.motion-config-revision.v1"),
        )

        val afterMigration = viewModel.uiState.value
        viewModel.onCameraRecalibrated(nextConfig)
        viewModel.onCameraRecalibrated(motionConfig)
        assertSame(afterMigration, viewModel.uiState.value)

        val recreated = FishingGameViewModel(handle, nextConfig)
        assertEquals(before.sessionId, recreated.uiState.value.snapshot.sessionId)
        assertEquals(nextConfig.calibrationRevision, recreated.uiState.value.snapshot.calibrationRevision)
        assertEquals(FishingRecoveryStage.WAITING_FOR_CAMERA, recreated.uiState.value.recoveryStage)
    }

    @Test
    fun calibrationRevisionChangeWithSameIdQuarantinesOldCheckpoint() {
        val handle = SavedStateHandle()
        val first = newViewModel(handle)
        val oldSessionId = first.uiState.value.snapshot.sessionId
        val context = ApplicationProvider.getApplicationContext<Context>()
        val changedText = context.assets.open(FishingMotionConfigLoader.ASSET_PATH)
            .bufferedReader()
            .use { it.readText() }
            .replace("calibrationRevision=2", "calibrationRevision=3")
        val changedConfig = FishingMotionConfigLoader.load(
            ByteArrayInputStream(changedText.toByteArray(StandardCharsets.UTF_8)),
        )

        val recreated = FishingGameViewModel(handle, changedConfig)

        assertEquals(
            "checkpoint_config_changed_new_session",
            recreated.uiState.value.lastInputDetail,
        )
        assertTrue(recreated.uiState.value.snapshot.sessionId != oldSessionId)
        assertEquals(
            3,
            handle.get<Int>("fishing.motion-config-revision.v1"),
        )
    }

    private fun newViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
    ): FishingGameViewModel = FishingGameViewModel(handle, motionConfig).also { viewModel ->
        bindMotionGeneration(viewModel, 1L)
    }

    private fun bindMotionGeneration(
        viewModel: FishingGameViewModel,
        sessionGeneration: Long,
    ): Boolean {
        val snapshot = viewModel.uiState.value.snapshot
        return viewModel.onMotionBindingStarted(
            sessionId = snapshot.sessionId,
            eventTimelineEpoch = snapshot.eventTimelineEpoch,
            sessionGeneration = sessionGeneration,
        )
    }

    private class ManualFishingRuntimeScheduler : FishingGameRuntimeScheduler {
        private var task: (() -> Unit)? = null
        private var closed = false

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

        override fun closeAndAwait(timeoutMs: Long) {
            assertTrue(timeoutMs > 0L)
            closed = true
        }

        fun runOnce() {
            if (!closed) checkNotNull(task).invoke()
        }
    }

    private fun establishMotionTracking(
        viewModel: FishingGameViewModel,
        originNs: Long,
    ): Pair<Long, Long> {
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = originNs,
                revision = 1L,
                activeType = null,
                usable = false,
                boundary = LivePoseContinuityBoundary.RESET_GENERATION,
            ),
            observedAtNs = originNs,
        )
        viewModel.onMotionFrame(
            motionFrame(originNs + 1L, 2L, null, usable = true),
            observedAtNs = originNs + 1L,
        )
        val neutralAtNs = originNs + 120_000_001L
        viewModel.onMotionFrame(
            motionFrame(neutralAtNs, 3L, null, usable = true),
            observedAtNs = neutralAtNs,
        )
        return neutralAtNs to 3L
    }

    private fun deliverRecoveryPose(viewModel: FishingGameViewModel, timestampNs: Long) {
        assertTrue(bindMotionGeneration(viewModel, 2L))
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = timestampNs,
                revision = 1L,
                activeType = null,
                usable = false,
                boundary = LivePoseContinuityBoundary.RESET_GENERATION,
                sessionGeneration = 2L,
            ),
            observedAtNs = timestampNs,
        )
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = timestampNs + 1L,
                revision = 2L,
                activeType = null,
                usable = true,
                sessionGeneration = 2L,
            ),
            observedAtNs = timestampNs + 1L,
        )
    }

    private fun deliverUsableHeartbeat(
        viewModel: FishingGameViewModel,
        timestampNs: Long,
        revision: Long,
        sessionGeneration: Long,
    ) {
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = timestampNs,
                revision = revision,
                activeType = null,
                usable = true,
                sessionGeneration = sessionGeneration,
            ),
            observedAtNs = timestampNs,
        )
    }

    private fun armMotionReady(
        viewModel: FishingGameViewModel,
        startedAtNs: Long,
        startedRevision: Long,
    ): Pair<Long, Long> {
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = startedAtNs,
                revision = startedRevision,
                activeType = MotionType.FISH_READY,
                usable = true,
            ),
            observedAtNs = startedAtNs,
        )
        val armedAtNs = startedAtNs + 500_000_000L
        val armedRevision = startedRevision + 1L
        viewModel.onMotionFrame(
            motionFrame(
                timestampNs = armedAtNs,
                revision = armedRevision,
                activeType = MotionType.FISH_READY,
                usable = true,
            ),
            observedAtNs = armedAtNs,
        )
        repeat(4) { viewModel.onTick(armedAtNs) }
        assertEquals("motion_ready_armed", viewModel.uiState.value.lastInputDetail)
        return armedAtNs to armedRevision
    }

    private fun motionFrame(
        timestampNs: Long,
        revision: Long,
        activeType: MotionType?,
        usable: Boolean,
        boundary: LivePoseContinuityBoundary = LivePoseContinuityBoundary.CONTIGUOUS,
        sessionGeneration: Long = 1L,
    ): FishingMotionFrame = FishingMotionFrame(
        sessionGeneration = sessionGeneration,
        revision = revision,
        sourceTimestampNs = timestampNs,
        poseCount = if (usable) 1 else 0,
        usableForSolo = usable,
        continuityBoundary = boundary,
        configId = motionConfig.configId,
        calibrationRevision = motionConfig.calibrationRevision,
        samples = motionConfig.gestureDefinitions.map { definition ->
            MotionSignalSample(
                playerId = PlayerId.P1,
                type = definition.type,
                timestampNs = timestampNs,
                activation = if (definition.type == activeType) 0.9f else 0.1f,
                quality = 0.9f,
                confidence = 0.9f,
                calibrationRevision = motionConfig.calibrationRevision,
                source = InputSource.MOTION,
            )
        },
    )
}
