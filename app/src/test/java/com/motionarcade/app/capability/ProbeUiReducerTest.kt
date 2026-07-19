package com.motionarcade.app.capability

import com.motionarcade.core.capability.EffectCapability
import com.motionarcade.core.capability.MlCapability
import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.CapabilityDerivation
import com.motionarcade.vision.capability.domain.CapabilityModeOutcome
import com.motionarcade.vision.capability.domain.DerivedMlCapability
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.ProbeTimeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeUiReducerTest {
    @Test
    fun canonicalProgressAtSelectedDurationDoesNotAuthorizeResult() {
        val snapshot = selectedSnapshot(
            identity = identity(1L),
            revision = 3L,
            mode = GameMode.SOLO,
            delegate = ProbeDelegate.CPU,
            elapsedNs = ProbeTimeContract.SELECTED_STEADY_DURATION_NS,
        )

        val transition = ProbeUiReducer.reduce(
            awaiting(GameMode.SOLO),
            ProbeUiAction.EngineSnapshotReceived(snapshot),
        )

        val projecting = transition.state as ProbeUiState.Projecting
        assertEquals(ProbeTimeContract.SELECTED_STEADY_DURATION_NS, projecting.snapshot.elapsedNs)
        assertTrue(transition.commands.isEmpty())
    }

    @Test
    fun staleSnapshotFromOldAttemptCannotAdvanceNewDualAttempt() {
        val soloResult = verifiedSoloResult(identity(10L))
        val awaitingDual = ProbeUiReducer.reduce(soloResult, ProbeUiAction.OptInDual)
        assertEquals(
            listOf(ProbeUiCommand.StartProbe(GameMode.DUAL, requestId(2L))),
            awaitingDual.commands,
        )

        val stale = preparingSnapshot(
            identity = soloResult.lastAttempt.identity,
            revision = soloResult.lastAttempt.revision + 1L,
            mode = GameMode.SOLO,
        )
        val ignored = ProbeUiReducer.reduce(
            awaitingDual.state,
            ProbeUiAction.EngineSnapshotReceived(stale),
        )

        assertEquals(awaitingDual.state, ignored.state)
        assertTrue(ignored.commands.isEmpty())
    }

    @Test
    fun duplicateOutOfOrderAndWrongRequestActiveSnapshotsAreIgnored() {
        val current = preparingSnapshot(identity(20L), revision = 3L, mode = GameMode.SOLO)
        val state = ProbeUiState.Projecting(current, CapabilitySummaryUi())

        listOf(3L, 2L).forEach { staleRevision ->
            val transition = ProbeUiReducer.reduce(
                state,
                ProbeUiAction.EngineSnapshotReceived(current.copy(revision = staleRevision)),
            )
            assertEquals(state, transition.state)
            assertTrue(transition.commands.isEmpty())
        }

        val wrongRequest = ProbeUiReducer.reduce(
            state,
            ProbeUiAction.EngineSnapshotReceived(current.copy(
                startRequestId = requestId(2L),
                revision = 4L,
            )),
        )
        assertEquals(state, wrongRequest.state)
        assertTrue(wrongRequest.commands.isEmpty())
    }

    @Test
    fun malformedForwardSnapshotRetainsActiveIdentityAndRequestsCleanup() {
        val current = selectedSnapshot(
            identity = identity(30L),
            revision = 3L,
            mode = GameMode.SOLO,
            delegate = ProbeDelegate.GPU,
        )
        val state = ProbeUiState.Projecting(current, CapabilitySummaryUi())
        val malformed = current.copy(
            revision = 4L,
            stage = ProbeUiStage.CANDIDATE_MEASUREMENT,
            durationNs = ProbeTimeContract.SELECTED_STEADY_DURATION_NS,
        )

        val transition = ProbeUiReducer.reduce(
            state,
            ProbeUiAction.EngineSnapshotReceived(malformed),
        )

        val restart = transition.state as ProbeUiState.RestartRequired
        assertEquals(current.cursor, restart.cursor)
        assertEquals(ProbeUiReason.MALFORMED_ENGINE_TRANSITION, restart.reason)
        assertTrue(restart.cleanupRequested)
        assertEquals(
            listOf(ProbeUiCommand.CancelCurrentAttempt(current.identity)),
            transition.commands,
        )
    }

    @Test
    fun engineOwnedGpuFailureAndCpuFallbackProjectionEmitsNoFallbackCommand() {
        val gpu = selectedSnapshot(
            identity = identity(40L),
            revision = 3L,
            mode = GameMode.SOLO,
            delegate = ProbeDelegate.GPU,
        )
        val state = ProbeUiState.Projecting(gpu, CapabilitySummaryUi())
        val engineOwnedFallback = warmupSnapshot(
            identity = gpu.identity,
            revision = 4L,
            mode = GameMode.SOLO,
            delegate = ProbeDelegate.CPU,
            successfulWarmups = 0,
        )

        val transition = ProbeUiReducer.reduce(
            state,
            ProbeUiAction.EngineSnapshotReceived(engineOwnedFallback),
        )

        val projected = transition.state as ProbeUiState.Projecting
        assertEquals(ProbeDelegate.CPU, projected.snapshot.delegate)
        assertTrue(transition.commands.isEmpty())
    }

    @Test
    fun measuredAndFinalizingSnapshotsNeverIssueSaveAndOnlyVerifiedExposesResult() {
        val active = selectedSnapshot(
            identity = identity(50L),
            revision = 3L,
            mode = GameMode.SOLO,
            delegate = ProbeDelegate.CPU,
        )
        val measurement = soloSupportMeasurement()
        val finalizing = resolutionSnapshot(
            identity = active.identity,
            revision = 4L,
            mode = GameMode.SOLO,
            stage = ProbeUiStage.FINALIZING,
            measurement = measurement,
        )

        val measured = ProbeUiReducer.reduce(
            ProbeUiState.Projecting(active, CapabilitySummaryUi()),
            ProbeUiAction.EngineSnapshotReceived(finalizing),
        )
        assertTrue(measured.state is ProbeUiState.Projecting)
        assertTrue(measured.commands.isEmpty())

        val verified = ProbeUiReducer.reduce(
            measured.state,
            ProbeUiAction.EngineSnapshotReceived(finalizing.copy(
                revision = 5L,
                stage = ProbeUiStage.VERIFIED,
            )),
        )
        assertTrue(verified.state is ProbeUiState.Result)
        assertTrue(verified.commands.isEmpty())
    }

    @Test
    fun soloMustBeEngineVerifiedBeforeDualIntentCanBeEmitted() {
        val measurement = soloSupportMeasurement()
        val finalizing = resolutionSnapshot(
            identity = identity(60L),
            revision = 4L,
            mode = GameMode.SOLO,
            stage = ProbeUiStage.FINALIZING,
            measurement = measurement,
        )
        val pending = ProbeUiState.Projecting(finalizing, CapabilitySummaryUi())

        val premature = ProbeUiReducer.reduce(pending, ProbeUiAction.OptInDual)
        assertEquals(pending, premature.state)
        assertTrue(premature.commands.isEmpty())

        val verified = ProbeUiReducer.reduce(
            pending,
            ProbeUiAction.EngineSnapshotReceived(finalizing.copy(
                revision = 5L,
                stage = ProbeUiStage.VERIFIED,
            )),
        ).state as ProbeUiState.Result
        val dualIntent = ProbeUiReducer.reduce(verified, ProbeUiAction.OptInDual)
        assertEquals(
            listOf(ProbeUiCommand.StartProbe(GameMode.DUAL, requestId(2L))),
            dualIntent.commands,
        )
    }

    @Test
    fun dualCancellationIsIdempotentAndPreservesVerifiedSolo() {
        val soloResult = verifiedSoloResult(identity(70L))
        val awaitingDual = ProbeUiReducer.reduce(soloResult, ProbeUiAction.OptInDual).state
        val dualPreparing = preparingSnapshot(
            identity = identity(71L),
            revision = 1L,
            mode = GameMode.DUAL,
            startRequestId = requestId(2L),
        )
        val dualActive = ProbeUiReducer.reduce(
            awaitingDual,
            ProbeUiAction.EngineSnapshotReceived(dualPreparing),
        ).state

        val firstCancel = ProbeUiReducer.reduce(dualActive, ProbeUiAction.Cancel)
        assertEquals(
            listOf(ProbeUiCommand.CancelCurrentAttempt(dualPreparing.identity)),
            firstCancel.commands,
        )
        val duplicateCancel = ProbeUiReducer.reduce(firstCancel.state, ProbeUiAction.Cancel)
        assertEquals(firstCancel.state, duplicateCancel.state)
        assertTrue(duplicateCancel.commands.isEmpty())

        val wrongRequestCancellation = ProbeUiReducer.reduce(
            firstCancel.state,
            ProbeUiAction.EngineSnapshotReceived(terminalSnapshot(
                identity = dualPreparing.identity,
                revision = 2L,
                mode = GameMode.DUAL,
                stage = ProbeUiStage.CANCELLED,
                reason = ProbeUiReason.USER_CANCELLED,
                startRequestId = requestId(3L),
            )),
        )
        assertEquals(firstCancel.state, wrongRequestCancellation.state)
        assertTrue(wrongRequestCancellation.commands.isEmpty())

        val cancelled = ProbeUiReducer.reduce(
            firstCancel.state,
            ProbeUiAction.EngineSnapshotReceived(terminalSnapshot(
                identity = dualPreparing.identity,
                revision = 2L,
                mode = GameMode.DUAL,
                stage = ProbeUiStage.CANCELLED,
                reason = ProbeUiReason.USER_CANCELLED,
                startRequestId = requestId(2L),
            )),
        ).state as ProbeUiState.Result
        assertEquals(soloResult.summary.solo, cancelled.summary.solo)
        assertTrue(cancelled.summary.dual is ModeCapabilityUi.Incomplete)
        val derivation = cancelled.summary.derivation as CapabilityDerivation.Derived
        assertEquals(DerivedMlCapability.SOLO_VALID_DUAL_INCOMPLETE, derivation.capability.mlOutcome)
    }

    @Test
    fun errorListenerSnapshotRequiresRestartAndTerminationIntentIsIdempotent() {
        val active = warmupSnapshot(
            identity = identity(80L),
            revision = 2L,
            mode = GameMode.SOLO,
            delegate = ProbeDelegate.GPU,
            successfulWarmups = 2,
        )
        val restartSnapshot = terminalSnapshot(
            identity = active.identity,
            revision = 3L,
            mode = GameMode.SOLO,
            stage = ProbeUiStage.RESTART_REQUIRED,
            reason = ProbeUiReason.ERROR_LISTENER,
        )
        val transition = ProbeUiReducer.reduce(
            ProbeUiState.Projecting(active, CapabilitySummaryUi()),
            ProbeUiAction.EngineSnapshotReceived(restartSnapshot),
        )

        val restart = transition.state as ProbeUiState.RestartRequired
        assertEquals(ProbeUiReason.ERROR_LISTENER, restart.reason)
        assertTrue(transition.commands.isEmpty())

        val terminate = ProbeUiReducer.reduce(restart, ProbeUiAction.TerminateForCleanRestart)
        assertEquals(
            listOf(ProbeUiCommand.RequestCleanProcessTermination(active.identity)),
            terminate.commands,
        )
        val duplicate = ProbeUiReducer.reduce(
            terminate.state,
            ProbeUiAction.TerminateForCleanRestart,
        )
        assertTrue(duplicate.commands.isEmpty())
    }

    @Test
    fun cancellationBeforeFirstSnapshotWaitsForIdentityThenRequestsExactlyOnce() {
        val awaiting = awaiting(GameMode.SOLO)
        val first = ProbeUiReducer.reduce(awaiting, ProbeUiAction.Cancel)
        val duplicate = ProbeUiReducer.reduce(first.state, ProbeUiAction.Cancel)
        assertEquals(first.state, duplicate.state)
        assertTrue(first.commands.isEmpty())
        assertTrue(duplicate.commands.isEmpty())

        val snapshot = preparingSnapshot(identity(90L), 1L, GameMode.SOLO)
        val identified = ProbeUiReducer.reduce(
            duplicate.state,
            ProbeUiAction.EngineSnapshotReceived(snapshot),
        )
        assertTrue(identified.state is ProbeUiState.Cancelling)
        assertEquals(
            listOf(ProbeUiCommand.CancelCurrentAttempt(snapshot.identity)),
            identified.commands,
        )

        val repeatedSnapshot = ProbeUiReducer.reduce(
            identified.state,
            ProbeUiAction.EngineSnapshotReceived(snapshot),
        )
        assertEquals(identified.state, repeatedSnapshot.state)
        assertTrue(repeatedSnapshot.commands.isEmpty())
    }

    @Test
    fun cancelBeforeFirstSnapshotProjectsEveryAuthoritativeTerminalWithoutCancel() {
        val cancelPending = ProbeUiReducer.reduce(
            awaiting(GameMode.SOLO),
            ProbeUiAction.Cancel,
        ).state
        val measurement = soloSupportMeasurement()
        val terminalSnapshots = listOf(
            resolutionSnapshot(
                identity = identity(101L),
                revision = 1L,
                mode = GameMode.SOLO,
                stage = ProbeUiStage.VERIFIED,
                measurement = measurement,
            ),
            terminalSnapshot(
                identity = identity(102L),
                revision = 1L,
                mode = GameMode.SOLO,
                stage = ProbeUiStage.RESTART_REQUIRED,
                reason = ProbeUiReason.ERROR_LISTENER,
            ),
            terminalSnapshot(
                identity = identity(103L),
                revision = 1L,
                mode = GameMode.SOLO,
                stage = ProbeUiStage.CANCELLED,
                reason = ProbeUiReason.USER_CANCELLED,
            ),
            terminalSnapshot(
                identity = identity(104L),
                revision = 1L,
                mode = GameMode.SOLO,
                stage = ProbeUiStage.REPOSITION_REQUIRED,
                reason = ProbeUiReason.WORKLOAD_NOT_PRESENT_OR_UNSTABLE,
            ),
            terminalSnapshot(
                identity = identity(105L),
                revision = 1L,
                mode = GameMode.SOLO,
                stage = ProbeUiStage.INCOMPLETE,
                reason = ProbeUiReason.LIFECYCLE_INTERRUPTED,
            ),
        )

        terminalSnapshots.forEach { snapshot ->
            val first = ProbeUiReducer.reduce(
                cancelPending,
                ProbeUiAction.EngineSnapshotReceived(snapshot),
            )
            assertTrue("terminal ${snapshot.stage} emitted cancel", first.commands.isEmpty())
            when (snapshot.stage) {
                ProbeUiStage.VERIFIED -> assertTrue(first.state is ProbeUiState.Result)
                ProbeUiStage.RESTART_REQUIRED -> {
                    val state = first.state as ProbeUiState.RestartRequired
                    assertEquals(snapshot.reason, state.reason)
                }
                ProbeUiStage.CANCELLED,
                ProbeUiStage.REPOSITION_REQUIRED,
                -> {
                    val state = first.state as ProbeUiState.Reposition
                    assertEquals(snapshot.reason, state.reason)
                }
                ProbeUiStage.INCOMPLETE -> {
                    val state = first.state as ProbeUiState.Incomplete
                    assertEquals(snapshot.reason, state.reason)
                }
                else -> error("Not a terminal fixture: ${snapshot.stage}")
            }

            val duplicate = ProbeUiReducer.reduce(
                first.state,
                ProbeUiAction.EngineSnapshotReceived(snapshot),
            )
            assertEquals(first.state, duplicate.state)
            assertTrue(duplicate.commands.isEmpty())
        }
    }

    @Test
    fun finiteReasonStageMatrixPreservesEveryCleanIncompleteAndRestartReason() {
        cleanIncompleteReasons().forEachIndexed { index, reason ->
            val snapshot = terminalSnapshot(
                identity = identity(200L + index),
                revision = 1L,
                mode = GameMode.SOLO,
                stage = ProbeUiStage.INCOMPLETE,
                reason = reason,
            )
            val transition = ProbeUiReducer.reduce(
                awaiting(GameMode.SOLO),
                ProbeUiAction.EngineSnapshotReceived(snapshot),
            )
            val state = transition.state as ProbeUiState.Incomplete
            assertEquals(reason, state.reason)
            assertEquals(
                reason,
                (state.summary.solo as ModeCapabilityUi.Incomplete).reason,
            )
            assertEquals(
                if (reason == ProbeUiReason.CAMERA_PERMISSION_REVOKED) {
                    ProbeIncompleteRecovery.REQUEST_PERMISSION
                } else {
                    ProbeIncompleteRecovery.RETRY
                },
                state.recovery,
            )
            assertTrue(transition.commands.isEmpty())
        }

        restartReasons().forEachIndexed { index, reason ->
            val snapshot = terminalSnapshot(
                identity = identity(230L + index),
                revision = 1L,
                mode = GameMode.SOLO,
                stage = ProbeUiStage.RESTART_REQUIRED,
                reason = reason,
            )
            val transition = ProbeUiReducer.reduce(
                awaiting(GameMode.SOLO),
                ProbeUiAction.EngineSnapshotReceived(snapshot),
            )
            val state = transition.state as ProbeUiState.RestartRequired
            assertEquals(reason, state.reason)
            assertTrue(transition.commands.isEmpty())
        }
    }

    @Test
    fun crossedReasonStagePairsAreMalformedAndRetainActiveIdentity() {
        val current = preparingSnapshot(identity(250L), revision = 1L, mode = GameMode.SOLO)
        val invalidPairs = listOf(
            ProbeUiStage.INCOMPLETE to ProbeUiReason.ERROR_LISTENER,
            ProbeUiStage.RESTART_REQUIRED to ProbeUiReason.LIFECYCLE_INTERRUPTED,
            ProbeUiStage.REPOSITION_REQUIRED to ProbeUiReason.USER_CANCELLED,
            ProbeUiStage.CANCELLED to ProbeUiReason.WORKLOAD_NOT_PRESENT_OR_UNSTABLE,
            ProbeUiStage.INCOMPLETE to ProbeUiReason.START_REQUEST_ID_EXHAUSTED,
            ProbeUiStage.RESTART_REQUIRED to ProbeUiReason.MALFORMED_ENGINE_TRANSITION,
        )

        invalidPairs.forEach { (stage, reason) ->
            val invalid = terminalSnapshot(
                identity = current.identity,
                revision = 2L,
                mode = GameMode.SOLO,
                stage = stage,
                reason = reason,
            )
            val transition = ProbeUiReducer.reduce(
                ProbeUiState.Projecting(current, CapabilitySummaryUi()),
                ProbeUiAction.EngineSnapshotReceived(invalid),
            )
            val restart = transition.state as ProbeUiState.RestartRequired
            assertEquals(current.cursor, restart.cursor)
            assertEquals(ProbeUiReason.MALFORMED_ENGINE_TRANSITION, restart.reason)
            assertEquals(
                listOf(ProbeUiCommand.CancelCurrentAttempt(current.identity)),
                transition.commands,
            )
        }
    }

    @Test
    fun cleanIncompleteRetryIsExplicitSingleShotAndPreservesReasonUntilStart() {
        val snapshot = terminalSnapshot(
            identity = identity(260L),
            revision = 1L,
            mode = GameMode.SOLO,
            stage = ProbeUiStage.INCOMPLETE,
            reason = ProbeUiReason.CALLBACK_CORRELATION_INVALID,
        )
        val terminal = ProbeUiReducer.reduce(
            awaiting(GameMode.SOLO),
            ProbeUiAction.EngineSnapshotReceived(snapshot),
        ).state as ProbeUiState.Incomplete

        val irrelevantPermission = ProbeUiReducer.reduce(terminal, ProbeUiAction.RequestPermission)
        assertEquals(terminal, irrelevantPermission.state)
        assertTrue(irrelevantPermission.commands.isEmpty())

        val retry = ProbeUiReducer.reduce(terminal, ProbeUiAction.RetryAfterIncomplete)
        assertEquals(
            listOf(ProbeUiCommand.StartProbe(GameMode.SOLO, requestId(2L))),
            retry.commands,
        )
        val awaitingRetry = retry.state as ProbeUiState.AwaitingEngineStart
        assertEquals(snapshot.cursor, awaitingRetry.previousAttempt)

        val duplicate = ProbeUiReducer.reduce(retry.state, ProbeUiAction.RetryAfterIncomplete)
        assertEquals(retry.state, duplicate.state)
        assertTrue(duplicate.commands.isEmpty())
    }

    @Test
    fun permissionRevocationRequiresPermissionResultBeforeExplicitRetry() {
        val snapshot = terminalSnapshot(
            identity = identity(270L),
            revision = 1L,
            mode = GameMode.SOLO,
            stage = ProbeUiStage.INCOMPLETE,
            reason = ProbeUiReason.CAMERA_PERMISSION_REVOKED,
        )
        val terminal = ProbeUiReducer.reduce(
            awaiting(GameMode.SOLO),
            ProbeUiAction.EngineSnapshotReceived(snapshot),
        ).state as ProbeUiState.Incomplete

        val prematureRetry = ProbeUiReducer.reduce(terminal, ProbeUiAction.RetryAfterIncomplete)
        assertEquals(terminal, prematureRetry.state)
        assertTrue(prematureRetry.commands.isEmpty())

        val request = ProbeUiReducer.reduce(terminal, ProbeUiAction.RequestPermission)
        assertEquals(listOf(ProbeUiCommand.RequestCameraPermission), request.commands)
        val pending = request.state as ProbeUiState.Incomplete
        assertTrue(pending.permissionRequestPending)
        assertEquals(ProbeUiReason.CAMERA_PERMISSION_REVOKED, pending.reason)

        val duplicateRequest = ProbeUiReducer.reduce(pending, ProbeUiAction.RequestPermission)
        assertEquals(pending, duplicateRequest.state)
        assertTrue(duplicateRequest.commands.isEmpty())

        val granted = ProbeUiReducer.reduce(pending, ProbeUiAction.PermissionResult(true))
        val retryAvailable = granted.state as ProbeUiState.Incomplete
        assertEquals(ProbeIncompleteRecovery.RETRY, retryAvailable.recovery)
        assertFalse(retryAvailable.permissionRequestPending)
        assertEquals(ProbeUiReason.CAMERA_PERMISSION_REVOKED, retryAvailable.reason)
        assertTrue(granted.commands.isEmpty())

        val retry = ProbeUiReducer.reduce(retryAvailable, ProbeUiAction.RetryAfterIncomplete)
        assertEquals(
            listOf(ProbeUiCommand.StartProbe(GameMode.SOLO, requestId(2L))),
            retry.commands,
        )
    }

    @Test
    fun skippedDualUsesCanonicalOutcomePolicyAndEffectsStayConservative() {
        val soloResult = verifiedSoloResult(identity(100L))
        val skipped = ProbeUiReducer.reduce(soloResult, ProbeUiAction.SkipDual)
        val result = skipped.state as ProbeUiState.Result
        val derivation = result.summary.derivation as CapabilityDerivation.Derived

        assertEquals(MlCapability.C, derivation.capability.mlCapability)
        assertEquals(
            EffectCapability.CONSERVATIVE_UNVERIFIED,
            result.summary.effectCapability,
        )
        assertTrue(result.summary.dual is ModeCapabilityUi.Unverified)
        assertTrue(skipped.commands.isEmpty())
    }

    @Test
    fun awaitingIgnoresTwoOlderSameModeSnapshotsBeyondRecordedPreviousAttempt() {
        val previous = preparingSnapshot(
            identity = identity(110L),
            revision = 8L,
            mode = GameMode.SOLO,
            startRequestId = requestId(2L),
        ).cursor
        val ready = ProbeUiState.Ready(lastAttempt = previous)
        val started = ProbeUiReducer.reduce(ready, ProbeUiAction.StartSolo)
        assertEquals(
            listOf(ProbeUiCommand.StartProbe(GameMode.SOLO, requestId(3L))),
            started.commands,
        )

        val delayedSnapshots = listOf(
            preparingSnapshot(
                identity = identity(108L),
                revision = 100L,
                mode = GameMode.SOLO,
                startRequestId = requestId(1L),
            ),
            preparingSnapshot(
                identity = identity(109L),
                revision = 100L,
                mode = GameMode.SOLO,
                startRequestId = requestId(2L),
            ),
        )

        delayedSnapshots.forEach { delayed ->
            val ignored = ProbeUiReducer.reduce(
                started.state,
                ProbeUiAction.EngineSnapshotReceived(delayed),
            )
            assertEquals(started.state, ignored.state)
            assertTrue(ignored.commands.isEmpty())
        }
    }

    @Test
    fun awaitingIgnoresWrongRequestIdEvenWhenAttemptIdentityIsFresh() {
        val started = ProbeUiReducer.reduce(ProbeUiState.Ready(), ProbeUiAction.StartSolo)
        val wrongRequest = preparingSnapshot(
            identity = identity(120L),
            revision = 1L,
            mode = GameMode.SOLO,
            startRequestId = requestId(2L),
        )

        val ignored = ProbeUiReducer.reduce(
            started.state,
            ProbeUiAction.EngineSnapshotReceived(wrongRequest),
        )

        assertEquals(started.state, ignored.state)
        assertTrue(ignored.commands.isEmpty())
    }

    @Test
    fun requestIdsAreMonotonicCannotBeReusedAndSoloOverflowIsExplicitAndIdempotent() {
        val previous = preparingSnapshot(
            identity = identity(130L),
            revision = 5L,
            mode = GameMode.SOLO,
            startRequestId = requestId(41L),
        ).cursor
        val ready = ProbeUiState.Ready(lastAttempt = previous)
        val started = ProbeUiReducer.reduce(ready, ProbeUiAction.StartSolo)
        assertEquals(
            listOf(ProbeUiCommand.StartProbe(GameMode.SOLO, requestId(42L))),
            started.commands,
        )

        val duplicateIntent = ProbeUiReducer.reduce(started.state, ProbeUiAction.StartSolo)
        assertEquals(started.state, duplicateIntent.state)
        assertTrue(duplicateIntent.commands.isEmpty())

        val reusedIdConstruction = runCatching {
            ProbeUiState.AwaitingEngineStart(
                startRequestId = requestId(41L),
                mode = GameMode.SOLO,
                summary = CapabilitySummaryUi(),
                previousAttempt = previous,
            )
        }
        assertTrue(reusedIdConstruction.isFailure)

        val maximum = preparingSnapshot(
            identity = identity(131L),
            revision = 5L,
            mode = GameMode.SOLO,
            startRequestId = requestId(Long.MAX_VALUE),
        ).cursor
        val exhausted = ProbeUiState.Ready(lastAttempt = maximum)
        val overflow = ProbeUiReducer.reduce(exhausted, ProbeUiAction.StartSolo)
        val terminal = overflow.state as ProbeUiState.StartRequestIdExhausted
        assertEquals(GameMode.SOLO, terminal.mode)
        assertEquals(maximum, terminal.lastAttempt)
        assertEquals(ProbeUiReason.START_REQUEST_ID_EXHAUSTED, terminal.reason)
        assertEquals(
            ProbeUiReason.START_REQUEST_ID_EXHAUSTED,
            (terminal.summary.solo as ModeCapabilityUi.Incomplete).reason,
        )
        assertTrue(overflow.commands.isEmpty())

        val repeated = ProbeUiReducer.reduce(terminal, ProbeUiAction.StartSolo)
        assertEquals(terminal, repeated.state)
        assertTrue(repeated.commands.isEmpty())
    }

    @Test
    fun requestIdOverflowIsExplicitForDualAndBothRetryPaths() {
        val maximumSolo = resolutionSnapshot(
            identity = identity(132L),
            revision = 5L,
            mode = GameMode.SOLO,
            stage = ProbeUiStage.VERIFIED,
            measurement = soloSupportMeasurement(),
            startRequestId = requestId(Long.MAX_VALUE),
        ).cursor
        val verifiedSummary = CapabilitySummaryUi().withVerified(soloSupportMeasurement())
        val result = ProbeUiState.Result(
            summary = verifiedSummary,
            lastAttempt = maximumSolo,
            dualOptInAvailable = true,
        )
        val dualOverflow = ProbeUiReducer.reduce(result, ProbeUiAction.OptInDual)
        val dualTerminal = dualOverflow.state as ProbeUiState.StartRequestIdExhausted
        assertEquals(GameMode.DUAL, dualTerminal.mode)
        assertEquals(maximumSolo, dualTerminal.lastAttempt)
        assertEquals(
            ProbeUiReason.START_REQUEST_ID_EXHAUSTED,
            (dualTerminal.summary.dual as ModeCapabilityUi.Incomplete).reason,
        )
        assertTrue(dualOverflow.commands.isEmpty())
        val repeatedDual = ProbeUiReducer.reduce(dualTerminal, ProbeUiAction.OptInDual)
        assertEquals(dualTerminal, repeatedDual.state)
        assertTrue(repeatedDual.commands.isEmpty())

        val reposition = ProbeUiState.Reposition(
            cursor = maximumSolo,
            summary = CapabilitySummaryUi().withIncomplete(
                GameMode.SOLO,
                ProbeUiReason.WORKLOAD_NOT_PRESENT_OR_UNSTABLE,
            ),
            reason = ProbeUiReason.WORKLOAD_NOT_PRESENT_OR_UNSTABLE,
        )
        val repositionOverflow = ProbeUiReducer.reduce(
            reposition,
            ProbeUiAction.RetryAfterReposition,
        )
        val repositionTerminal =
            repositionOverflow.state as ProbeUiState.StartRequestIdExhausted
        assertEquals(GameMode.SOLO, repositionTerminal.mode)
        assertTrue(repositionOverflow.commands.isEmpty())
        val repeatedReposition = ProbeUiReducer.reduce(
            repositionTerminal,
            ProbeUiAction.RetryAfterReposition,
        )
        assertEquals(repositionTerminal, repeatedReposition.state)
        assertTrue(repeatedReposition.commands.isEmpty())

        val incomplete = ProbeUiState.Incomplete(
            cursor = maximumSolo,
            summary = CapabilitySummaryUi().withIncomplete(
                GameMode.SOLO,
                ProbeUiReason.MEASUREMENT_INCOMPLETE,
            ),
            reason = ProbeUiReason.MEASUREMENT_INCOMPLETE,
            recovery = ProbeIncompleteRecovery.RETRY,
        )
        val incompleteOverflow = ProbeUiReducer.reduce(
            incomplete,
            ProbeUiAction.RetryAfterIncomplete,
        )
        val incompleteTerminal =
            incompleteOverflow.state as ProbeUiState.StartRequestIdExhausted
        assertEquals(GameMode.SOLO, incompleteTerminal.mode)
        assertTrue(incompleteOverflow.commands.isEmpty())
        val repeatedIncomplete = ProbeUiReducer.reduce(
            incompleteTerminal,
            ProbeUiAction.RetryAfterIncomplete,
        )
        assertEquals(incompleteTerminal, repeatedIncomplete.state)
        assertTrue(repeatedIncomplete.commands.isEmpty())
    }

    @Test
    fun matchingRequestIdBindsFirstEngineIdentityAndRevision() {
        val started = ProbeUiReducer.reduce(ProbeUiState.Ready(), ProbeUiAction.StartSolo)
        assertEquals(
            listOf(ProbeUiCommand.StartProbe(GameMode.SOLO, requestId(1L))),
            started.commands,
        )
        val matching = preparingSnapshot(
            identity = identity(140L),
            revision = 1L,
            mode = GameMode.SOLO,
            startRequestId = requestId(1L),
        )

        val accepted = ProbeUiReducer.reduce(
            started.state,
            ProbeUiAction.EngineSnapshotReceived(matching),
        )

        val projecting = accepted.state as ProbeUiState.Projecting
        assertEquals(requestId(1L), projecting.snapshot.startRequestId)
        assertEquals(matching.cursor, projecting.snapshot.cursor)
        assertTrue(accepted.commands.isEmpty())
    }

    @Test
    fun projectionDtosContainNoSensitiveObservationFields() {
        val declaredNames = listOf(
            ProbeStartRequestId::class.java,
            ProbeAttemptIdentity::class.java,
            ProbeAttemptCursor::class.java,
            ModeMeasurementSummary::class.java,
            ProbeEngineSnapshot::class.java,
            CapabilitySummaryUi::class.java,
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }

        listOf(
            "devicename",
            "manufacturer",
            "serial",
            "rawframe",
            "bitmap",
            "landmark",
            "cameraid",
            "filepath",
        ).forEach { forbidden ->
            assertFalse("forbidden field: $forbidden", declaredNames.any { forbidden in it })
        }
    }

    private fun awaiting(
        mode: GameMode,
        startRequestId: ProbeStartRequestId = requestId(1L),
    ): ProbeUiState.AwaitingEngineStart =
        ProbeUiState.AwaitingEngineStart(
            startRequestId = startRequestId,
            mode = mode,
            summary = CapabilitySummaryUi(),
            previousAttempt = null,
        )

    private fun verifiedSoloResult(identity: ProbeAttemptIdentity): ProbeUiState.Result {
        val measurement = soloSupportMeasurement()
        val verified = resolutionSnapshot(
            identity = identity,
            revision = 5L,
            mode = GameMode.SOLO,
            stage = ProbeUiStage.VERIFIED,
            measurement = measurement,
        )
        return ProbeUiReducer.reduce(
            awaiting(GameMode.SOLO),
            ProbeUiAction.EngineSnapshotReceived(verified),
        ).state as ProbeUiState.Result
    }

    private fun soloSupportMeasurement(): ModeMeasurementSummary = ModeMeasurementSummary(
        mode = GameMode.SOLO,
        outcome = CapabilityModeOutcome.SOLO_SUPPORT,
        selectedDelegate = ProbeDelegate.CPU,
        representativeCompletionCount = 200L,
        durationNs = ProbeTimeContract.SELECTED_STEADY_DURATION_NS,
        representativeInferenceP95Ns = 40_000_000L,
        maximumRepresentativeFrameAgeNs = 200_000_000L,
    )

    private fun preparingSnapshot(
        identity: ProbeAttemptIdentity,
        revision: Long,
        mode: GameMode,
        startRequestId: ProbeStartRequestId = requestId(1L),
    ): ProbeEngineSnapshot = ProbeEngineSnapshot(
        startRequestId = startRequestId,
        identity = identity,
        revision = revision,
        mode = mode,
        stage = ProbeUiStage.PREPARING,
    )

    private fun warmupSnapshot(
        identity: ProbeAttemptIdentity,
        revision: Long,
        mode: GameMode,
        delegate: ProbeDelegate,
        successfulWarmups: Int,
        startRequestId: ProbeStartRequestId = requestId(1L),
    ): ProbeEngineSnapshot = ProbeEngineSnapshot(
        startRequestId = startRequestId,
        identity = identity,
        revision = revision,
        mode = mode,
        stage = ProbeUiStage.WARMUP,
        delegate = delegate,
        successfulWarmups = successfulWarmups,
    )

    private fun selectedSnapshot(
        identity: ProbeAttemptIdentity,
        revision: Long,
        mode: GameMode,
        delegate: ProbeDelegate,
        elapsedNs: Long = 0L,
        startRequestId: ProbeStartRequestId = requestId(1L),
    ): ProbeEngineSnapshot = ProbeEngineSnapshot(
        startRequestId = startRequestId,
        identity = identity,
        revision = revision,
        mode = mode,
        stage = ProbeUiStage.SELECTED_MEASUREMENT,
        delegate = delegate,
        successfulWarmups = ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS,
        elapsedNs = elapsedNs,
        durationNs = ProbeTimeContract.SELECTED_STEADY_DURATION_NS,
    )

    private fun resolutionSnapshot(
        identity: ProbeAttemptIdentity,
        revision: Long,
        mode: GameMode,
        stage: ProbeUiStage,
        measurement: ModeMeasurementSummary,
        startRequestId: ProbeStartRequestId = requestId(1L),
    ): ProbeEngineSnapshot = ProbeEngineSnapshot(
        startRequestId = startRequestId,
        identity = identity,
        revision = revision,
        mode = mode,
        stage = stage,
        delegate = measurement.selectedDelegate,
        measurement = measurement,
    )

    private fun terminalSnapshot(
        identity: ProbeAttemptIdentity,
        revision: Long,
        mode: GameMode,
        stage: ProbeUiStage,
        reason: ProbeUiReason,
        startRequestId: ProbeStartRequestId = requestId(1L),
    ): ProbeEngineSnapshot = ProbeEngineSnapshot(
        startRequestId = startRequestId,
        identity = identity,
        revision = revision,
        mode = mode,
        stage = stage,
        reason = reason,
    )

    private fun cleanIncompleteReasons(): List<ProbeUiReason> = listOf(
        ProbeUiReason.ARTIFACT_OR_BUILD_INVALID,
        ProbeUiReason.PROFILE_INVALID,
        ProbeUiReason.CAMERA_PERMISSION_REVOKED,
        ProbeUiReason.LIFECYCLE_INTERRUPTED,
        ProbeUiReason.CAMERA_BIND_FAILED,
        ProbeUiReason.CAMERA_STALLED,
        ProbeUiReason.SCOPE_CHANGED,
        ProbeUiReason.TIMEBASE_INVALID,
        ProbeUiReason.SOURCE_SEQUENCE_INVALID,
        ProbeUiReason.CLOCK_INVALID,
        ProbeUiReason.CALLBACK_CORRELATION_INVALID,
        ProbeUiReason.MEASUREMENT_INCOMPLETE,
    )

    private fun restartReasons(): List<ProbeUiReason> = listOf(
        ProbeUiReason.ERROR_LISTENER,
        ProbeUiReason.RESOURCE_UNCERTAIN,
        ProbeUiReason.PERSISTENCE_NOT_COMMITTED,
        ProbeUiReason.PERSISTENCE_OUTCOME_UNKNOWN,
    )

    private fun identity(value: Long): ProbeAttemptIdentity = ProbeAttemptIdentity(
        sessionId = value,
        attemptId = value,
    )

    private fun requestId(value: Long): ProbeStartRequestId = ProbeStartRequestId(value)
}
