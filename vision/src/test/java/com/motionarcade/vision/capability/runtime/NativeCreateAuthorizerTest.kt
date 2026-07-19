package com.motionarcade.vision.capability.runtime

import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCreateAuthorizerTest {
    private val callbacks = object : RuntimeCallbackPort {
        override fun onResult(callback: RuntimeResultCallback) = Unit

        override fun onError(callback: RuntimeErrorCallback) = Unit
    }

    @Test
    fun productionDefaultFailsClosedAndMakesZeroFactoryCalls() {
        val command = command()
        val authorizationRequest = RuntimeTestDeepFixture.nativeGrant(command).request
        val factory = RecordingFactory()

        val execution = RuntimeOwnerBoundary.open(
            command,
            authorizationRequest,
            FailClosedNativeCreateAuthorizer,
            callbacks,
            factory,
            ProbeClock { 0L },
        )

        assertTrue(execution.result is RuntimeOpenResult.Failed)
        val failure = (execution.result as RuntimeOpenResult.Failed).reason
        assertEquals(
            NativeCreateDenialReason.NATIVE_CLOSE_FENCE_PROOF_UNAVAILABLE,
            (failure as RuntimeOpenFailure.AuthorizationDenied).reason,
        )
        assertFalse(execution.ownsRuntime)
        assertEquals(0, factory.calls)
    }

    @Test
    fun oneShotPermitReplayCannotInvokeFactoryTwice() {
        val command = command(attemptEpoch = 91uL)
        val grant = RuntimeTestDeepFixture.nativeGrant(command)
        val authorizationRequest = grant.request
        val authorizer = NativeCreateAuthorizer { grant.authorization }
        val factory = RecordingFactory()

        val first = RuntimeOwnerBoundary.open(
            command,
            authorizationRequest,
            authorizer,
            callbacks,
            factory,
            ProbeClock { 0L },
        )
        val replay = RuntimeOwnerBoundary.open(
            command,
            authorizationRequest,
            authorizer,
            callbacks,
            factory,
            ProbeClock { 0L },
        )

        assertTrue(first.result is RuntimeOpenResult.Opened)
        assertTrue(replay.result is RuntimeOpenResult.Failed)
        val replayReason = ((replay.result as RuntimeOpenResult.Failed).reason as
            RuntimeOpenFailure.AuthorizationDenied).reason
        assertEquals(NativeCreateDenialReason.AUTHORIZATION_ALREADY_CONSUMED, replayReason)
        assertEquals(1, factory.calls)
        val machine = RuntimeTestDeepFixture.stateMachine(first)
        machine.abortIncomplete()
        val claim = requireNotNull(machine.beginClose())
        val closeExecution = requireNotNull(RuntimeOwnerBoundary.close(claim, first))
        val completion = requireNotNull(machine.completeClose(claim, closeExecution))
        assertTrue(machine.consumeCloseCompletion(completion, claim) != null)
    }

    @Test
    fun permitRejectsDifferentRouteAndProofSnapshotBindings() {
        val first = command(routeOrdinal = 1, runtimeGeneration = 1L, attemptEpoch = 92uL)
        val second = command(routeOrdinal = 2, runtimeGeneration = 2L, attemptEpoch = 92uL)
        val grant = RuntimeTestDeepFixture.nativeGrant(first, proofSeed = 7)

        val wrongRoute = NativeCreateAuthorizationRequest(
            request = second.request,
            proofSnapshot = grant.request.proofSnapshot,
        )
        val factory = RecordingFactory()
        val denied = RuntimeOwnerBoundary.open(
            second,
            wrongRoute,
            NativeCreateAuthorizer { grant.authorization },
            callbacks,
            factory,
            ProbeClock { 0L },
        )

        assertTrue(denied.result is RuntimeOpenResult.Failed)
        assertEquals(
            NativeCreateDenialReason.AUTHORIZATION_BINDING_MISMATCH,
            ((denied.result as RuntimeOpenResult.Failed).reason as
                RuntimeOpenFailure.AuthorizationDenied).reason,
        )
        val accepted = RuntimeOwnerBoundary.open(
            first,
            grant.request,
            NativeCreateAuthorizer { grant.authorization },
            callbacks,
            factory,
            ProbeClock { 0L },
        )
        assertTrue(accepted.result is RuntimeOpenResult.Opened)
        assertEquals(1, factory.calls)
        closeCleanly(accepted)
    }

    @Test
    fun permitBindsTheExactUnsignedHighBitAttemptEpoch() {
        val bound = command(attemptEpoch = 0x8000000000000000uL)
        val different = command(attemptEpoch = ULong.MAX_VALUE)
        val grant = RuntimeTestDeepFixture.nativeGrant(bound, proofSeed = 8)
        val factory = RecordingFactory()

        val denied = RuntimeOwnerBoundary.open(
            different,
            NativeCreateAuthorizationRequest(
                request = different.request,
                proofSnapshot = grant.request.proofSnapshot,
            ),
            NativeCreateAuthorizer { grant.authorization },
            callbacks,
            factory,
            ProbeClock { 0L },
        )

        assertTrue(denied.result is RuntimeOpenResult.Failed)
        assertEquals(
            NativeCreateDenialReason.AUTHORIZATION_BINDING_MISMATCH,
            ((denied.result as RuntimeOpenResult.Failed).reason as
                RuntimeOpenFailure.AuthorizationDenied).reason,
        )
        val accepted = RuntimeOwnerBoundary.open(
            bound,
            grant.request,
            NativeCreateAuthorizer { grant.authorization },
            callbacks,
            factory,
            ProbeClock { 0L },
        )
        assertTrue(accepted.result is RuntimeOpenResult.Opened)
        assertEquals(1, factory.calls)
        closeCleanly(accepted)
    }

    @Test
    fun equalRequestFromAnotherCommandIsOneShotAcrossCleanClose() {
        val firstCommand = command(attemptEpoch = 93uL)
        val equalCommand = OpenRuntimeCommand(firstCommand.request.copy())
        val factory = RecordingFactory()
        val firstGrant = RuntimeTestDeepFixture.nativeGrant(firstCommand, proofSeed = 31)
        val first = RuntimeOwnerBoundary.open(
            firstCommand,
            firstGrant.request,
            NativeCreateAuthorizer { firstGrant.authorization },
            callbacks,
            factory,
            ProbeClock { 0L },
        )
        assertTrue(first.result is RuntimeOpenResult.Opened)

        val liveReplayGrant = RuntimeTestDeepFixture.nativeGrant(equalCommand, proofSeed = 32)
        val liveReplay = RuntimeOwnerBoundary.open(
            equalCommand,
            liveReplayGrant.request,
            NativeCreateAuthorizer { liveReplayGrant.authorization },
            callbacks,
            factory,
            ProbeClock { 0L },
        )
        assertEquals(
            RuntimeOpenFailure.DUPLICATE_RUNTIME_CREATE_REQUEST,
            (liveReplay.result as RuntimeOpenResult.Failed).reason,
        )
        assertEquals(1, factory.calls)

        closeCleanly(first)

        val closedReplayGrant = RuntimeTestDeepFixture.nativeGrant(equalCommand, proofSeed = 33)
        val closedReplay = RuntimeOwnerBoundary.open(
            equalCommand,
            closedReplayGrant.request,
            NativeCreateAuthorizer { closedReplayGrant.authorization },
            callbacks,
            factory,
            ProbeClock { 0L },
        )
        assertEquals(
            RuntimeOpenFailure.DUPLICATE_RUNTIME_CREATE_REQUEST,
            (closedReplay.result as RuntimeOpenResult.Failed).reason,
        )

        val nextCommand = command(
            runtimeGeneration = 2L,
            attemptEpoch = 93uL,
        )
        val nextGrant = RuntimeTestDeepFixture.nativeGrant(nextCommand, proofSeed = 34)
        val next = RuntimeOwnerBoundary.open(
            nextCommand,
            nextGrant.request,
            NativeCreateAuthorizer { nextGrant.authorization },
            callbacks,
            factory,
            ProbeClock { 0L },
        )
        assertTrue(next.result is RuntimeOpenResult.Opened)
        assertEquals(2, factory.calls)
        closeCleanly(next)
    }

    @Test
    fun concurrentEqualRequestsInvokeFactoryExactlyOnce() {
        val firstCommand = command(attemptEpoch = 94uL)
        val equalCommand = OpenRuntimeCommand(firstCommand.request.copy())
        val firstGrant = RuntimeTestDeepFixture.nativeGrant(firstCommand, proofSeed = 41)
        val secondGrant = RuntimeTestDeepFixture.nativeGrant(equalCommand, proofSeed = 42)
        val factory = BlockingFactory()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstFuture = executor.submit<RuntimeOpenExecution> {
                RuntimeOwnerBoundary.open(
                    firstCommand,
                    firstGrant.request,
                    NativeCreateAuthorizer { firstGrant.authorization },
                    callbacks,
                    factory,
                    ProbeClock { 0L },
                )
            }
            assertTrue(factory.entered.await(5, TimeUnit.SECONDS))

            val duplicate = RuntimeOwnerBoundary.open(
                equalCommand,
                secondGrant.request,
                NativeCreateAuthorizer { secondGrant.authorization },
                callbacks,
                factory,
                ProbeClock { 0L },
            )
            assertEquals(
                RuntimeOpenFailure.DUPLICATE_RUNTIME_CREATE_REQUEST,
                (duplicate.result as RuntimeOpenResult.Failed).reason,
            )
            assertEquals(1, factory.calls.get())

            factory.release.countDown()
            val first = firstFuture.get(5, TimeUnit.SECONDS)
            assertTrue(first.result is RuntimeOpenResult.Opened)
            closeCleanly(first)
        } finally {
            factory.release.countDown()
            executor.shutdownNow()
        }
    }

    private fun command(
        routeOrdinal: Int = 1,
        runtimeGeneration: Long = 1L,
        attemptEpoch: ULong = 9uL,
    ): OpenRuntimeCommand {
        val attempt = ProbeAttemptContext(
            mode = GameMode.SOLO,
            attemptEpoch = ProbeAttemptEpoch(attemptEpoch),
            runtimeArtifactId = RuntimeArtifactId(digest(1)),
            probeBaseScopeId = ProbeBaseScopeId(digest(2)),
        )
        val route = RuntimeRoute(
            RuntimeRouteKey(
                attempt.attemptEpoch,
                routeOrdinal,
                RuntimeGeneration(runtimeGeneration),
            ),
            if (routeOrdinal == 1) {
                RuntimeRouteKind.CPU_CANDIDATE
            } else {
                RuntimeRouteKind.GPU_CANDIDATE
            },
        )
        return OpenRuntimeCommand(RuntimeCreateRequest(attempt, route))
    }

    private fun closeCleanly(execution: RuntimeOpenExecution) {
        val machine = RuntimeTestDeepFixture.stateMachine(execution)
        machine.abortIncomplete()
        val claim = requireNotNull(machine.beginClose())
        val closeExecution = requireNotNull(RuntimeOwnerBoundary.close(claim, execution))
        val completion = requireNotNull(machine.completeClose(claim, closeExecution))
        assertTrue(machine.consumeCloseCompletion(completion, claim) != null)
    }

    private fun digest(seed: Int): Sha256Digest =
        valid(Sha256Digest.fromBytes(ByteArray(32) { index -> (seed + index).toByte() }))

    private fun <T> valid(result: com.motionarcade.vision.capability.domain.CapabilityDomainResult<T>): T =
        (result as com.motionarcade.vision.capability.domain.CapabilityDomainResult.Valid).value

    private class RecordingFactory : FreshPoseRuntimeFactory {
        var calls = 0

        override fun create(
            request: RuntimeCreateRequest,
            callbacks: RuntimeCallbackPort,
            owner: RuntimeCreationOwner,
        ): PoseRuntime {
            calls += 1
            val runtime = FakeRuntime(RuntimeIdentity(calls.toLong()))
            check(owner.bind(runtime, PoseRuntimeSubmissionPort {
                PoseRuntimeSubmitEvidence.RETURNED
            }))
            return runtime
        }
    }

    private class BlockingFactory : FreshPoseRuntimeFactory {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun create(
            request: RuntimeCreateRequest,
            callbacks: RuntimeCallbackPort,
            owner: RuntimeCreationOwner,
        ): PoseRuntime {
            val call = calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            val runtime = FakeRuntime(RuntimeIdentity(call.toLong()))
            check(owner.bind(runtime, PoseRuntimeSubmissionPort {
                PoseRuntimeSubmitEvidence.RETURNED
            }))
            return runtime
        }
    }

    private class FakeRuntime(override val identity: RuntimeIdentity) : PoseRuntime() {
        override fun close(): RuntimeCloseEvidence = RuntimeCloseEvidence.CLEAN
    }
}
