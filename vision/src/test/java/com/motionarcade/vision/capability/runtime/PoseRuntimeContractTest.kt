package com.motionarcade.vision.capability.runtime

import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.ProbeTimeContract
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import com.motionarcade.vision.capability.domain.SourceTimestampNs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseRuntimeContractTest {
    @Test
    fun identitiesKeysAndRoutesRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { RuntimeIdentity(0L) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeGeneration(0L) }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeRouteKey(ProbeAttemptEpoch(1uL), 0, RuntimeGeneration(1L))
        }
    }

    @Test
    fun attemptEpochUsesTheFullNonzeroUnsigned64BitDomain() {
        assertThrows(IllegalArgumentException::class.java) { ProbeAttemptEpoch(0uL) }

        val values = listOf(
            1uL,
            Long.MAX_VALUE.toULong(),
            0x8000000000000000uL,
            ULong.MAX_VALUE,
        )
        values.forEach { value ->
            assertEquals(value, ProbeAttemptEpoch(value).value)
        }
        assertTrue(values.zipWithNext().all { (lower, upper) -> lower < upper })
    }

    @Test
    fun highBitAttemptEpochBindsRouteAndContextExactly() {
        val epoch = ProbeAttemptEpoch(0x8000000000000000uL)
        val attempt = ProbeAttemptContext(
            GameMode.DUAL,
            epoch,
            RuntimeArtifactId(digest(101)),
            ProbeBaseScopeId(digest(102)),
        )
        val route = RuntimeRoute(
            RuntimeRouteKey(epoch, 1, RuntimeGeneration(1L)),
            RuntimeRouteKind.CPU_CANDIDATE,
        )

        val request = RuntimeCreateRequest(attempt, route)

        assertEquals(epoch, request.attempt.attemptEpoch)
        assertEquals(epoch, request.route.key.attemptEpoch)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCreateRequest(
                attempt,
                route.copy(key = route.key.copy(attemptEpoch = ProbeAttemptEpoch(ULong.MAX_VALUE))),
            )
        }
    }

    @Test
    fun routeKindsCarryOnlyExactContractDurations() {
        RuntimeRouteKind.entries.forEach { kind ->
            val expected = when (kind.role) {
                RuntimeRole.CANDIDATE -> ProbeTimeContract.CANDIDATE_MEASUREMENT_DURATION_NS
                RuntimeRole.SELECTED_STEADY -> ProbeTimeContract.SELECTED_STEADY_DURATION_NS
            }
            assertEquals(expected, kind.measurementDurationNs)
        }
    }

    @Test
    fun factoryThrowsBeforeAndAfterBindingBecomeCategoricalAndCleanupIsExactOnce() {
        val beforeCommand = command()
        val beforeGrant = RuntimeTestDeepFixture.nativeGrant(beforeCommand, 10)
        val before = RuntimeOwnerBoundary.open(
            beforeCommand,
            beforeGrant.request,
            NativeCreateAuthorizer { beforeGrant.authorization },
            RuntimeTestDeepFixture.noOpCallbacks(),
            FreshPoseRuntimeFactory { _, _, _ -> throw IllegalStateException("before") },
            ProbeClock { 0L },
        )
        assertEquals(RuntimeOpenFailure.CREATE_THREW, (before.result as RuntimeOpenResult.Failed).reason)

        val runtime = RecordingRuntime(RuntimeIdentity(2L))
        val afterCommand = command()
        val afterGrant = RuntimeTestDeepFixture.nativeGrant(afterCommand, 11)
        val after = RuntimeOwnerBoundary.open(
            afterCommand,
            afterGrant.request,
            NativeCreateAuthorizer { afterGrant.authorization },
            RuntimeTestDeepFixture.noOpCallbacks(),
            FreshPoseRuntimeFactory { _, _, owner ->
                check(owner.bind(runtime, PoseRuntimeSubmissionPort {
                    PoseRuntimeSubmitEvidence.RETURNED
                }))
                throw IllegalStateException("after")
            },
            ProbeClock { 0L },
        )
        assertEquals(
            RuntimeOpenFailure.CreateThrewAfterBinding(RuntimeCreateCleanupOutcome.CLEAN),
            (after.result as RuntimeOpenResult.Failed).reason,
        )
        assertEquals(1, runtime.closeCalls)
    }

    @Test
    fun assertionAndLinkageErrorsCannotCrossRuntimeOwnerBoundary() {
        listOf(AssertionError("assert"), LinkageError("link")).forEachIndexed { index, delivered ->
            val command = command()
            val grant = RuntimeTestDeepFixture.nativeGrant(command, 20 + index)
            val result = RuntimeOwnerBoundary.open(
                command,
                grant.request,
                NativeCreateAuthorizer { grant.authorization },
                RuntimeTestDeepFixture.noOpCallbacks(),
                FreshPoseRuntimeFactory { _, _, _ -> throw delivered },
                ProbeClock { 0L },
            )
            assertEquals(RuntimeOpenFailure.CREATE_THREW, (result.result as RuntimeOpenResult.Failed).reason)
        }
    }

    @Test
    fun closeRequiresStateClaimOwnerExecutionAndNativeReturnBeforeSeal() {
        val runtime = RecordingRuntime(RuntimeIdentity(31L))
        val opened = RuntimeTestDeepFixture.open(command(), runtime, proofSeed = 31)
        val machine = RuntimeTestDeepFixture.stateMachine(opened)
        machine.abortIncomplete()
        val claim = requireNotNull(machine.beginClose())

        val forgedRaw = boundaryCloseClean(claim.command)
        assertEquals(0, runtime.closeCalls)
        assertTrue(machine.callbackAdmissionIsOpen())
        assertFalse(machine.callbackGateIsSealed())
        assertFalse(RuntimeCloseExecution::class.java.isInstance(forgedRaw))

        val closeExecution = requireNotNull(RuntimeOwnerBoundary.close(claim, opened))
        assertEquals(1, runtime.closeCalls)
        val completion = requireNotNull(machine.completeClose(claim, closeExecution))
        val record = requireNotNull(machine.consumeCloseCompletion(completion, claim))
        assertEquals(ProbeCloseCompletionKind.SEALED, record.kind)
        assertFalse(machine.callbackAdmissionIsOpen())
        assertTrue(machine.callbackGateIsSealed())
    }

    @Test
    fun closeExecutionAndCompletionCannotCrossMachineClaimOrReplay() {
        val firstRuntime = RecordingRuntime(RuntimeIdentity(41L))
        val secondRuntime = RecordingRuntime(RuntimeIdentity(42L))
        val firstOpen = RuntimeTestDeepFixture.open(command(), firstRuntime, proofSeed = 41)
        val secondOpen = RuntimeTestDeepFixture.open(command(), secondRuntime, proofSeed = 42)
        val first = RuntimeTestDeepFixture.stateMachine(firstOpen)
        val second = RuntimeTestDeepFixture.stateMachine(secondOpen)
        first.abortIncomplete()
        second.abortIncomplete()
        val firstClaim = requireNotNull(first.beginClose())
        val secondClaim = requireNotNull(second.beginClose())

        assertNull(RuntimeOwnerBoundary.close(firstClaim, secondOpen))
        assertEquals(0, secondRuntime.closeCalls)
        val firstExecution = requireNotNull(RuntimeOwnerBoundary.close(firstClaim, firstOpen))
        val secondExecution = requireNotNull(RuntimeOwnerBoundary.close(secondClaim, secondOpen))
        assertNull(second.completeClose(secondClaim, firstExecution))
        val secondCompletion = requireNotNull(second.completeClose(secondClaim, secondExecution))
        val firstCompletion = requireNotNull(first.completeClose(firstClaim, firstExecution))

        assertNull(first.consumeCloseCompletion(firstCompletion, secondClaim))
        assertEquals(
            ProbeCloseCompletionKind.SEALED,
            requireNotNull(first.consumeCloseCompletion(firstCompletion, firstClaim)).kind,
        )
        assertNull(first.consumeCloseCompletion(firstCompletion, firstClaim))
        assertEquals(
            ProbeCloseCompletionKind.SEALED,
            requireNotNull(second.consumeCloseCompletion(secondCompletion, secondClaim)).kind,
        )
    }

    @Test
    fun identityGetterThrowableAlwaysAttemptsCloseOnceAndPreservesFiveOutcomes() {
        val cases = listOf(
            Triple(RuntimeException("identity"), RuntimeCloseEvidence.CLEAN,
                RuntimeCreateCleanupOutcome.CLEAN),
            Triple(AssertionError("identity"), RuntimeCloseEvidence.THREW,
                RuntimeCreateCleanupOutcome.REPORTED_FAILURE),
            Triple(LinkageError("identity"), RuntimeCloseEvidence.TIMED_OUT,
                RuntimeCreateCleanupOutcome.TIMED_OUT),
            Triple(RuntimeException("identity"), RuntimeCloseEvidence.RESOURCE_UNCERTAIN,
                RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN),
        )
        cases.forEachIndexed { index, (identityThrowable, closeEvidence, expected) ->
            assertIdentityFailureCleanup(index, identityThrowable, expected) { closeEvidence }
        }
        assertIdentityFailureCleanup(
            90,
            AssertionError("identity close call throws"),
            RuntimeCreateCleanupOutcome.CALL_THREW,
        ) { throw LinkageError("native close call") }
    }

    @Test
    fun missingCreationBindingClosesReturnedRuntimeAndQuarantinesOwnership() {
        val command = command()
        val runtime = RecordingRuntime(RuntimeIdentity(55L))
        val grant = RuntimeTestDeepFixture.nativeGrant(command, 55)
        val result = RuntimeOwnerBoundary.open(
            command,
            grant.request,
            NativeCreateAuthorizer { grant.authorization },
            RuntimeTestDeepFixture.noOpCallbacks(),
            FreshPoseRuntimeFactory { _, _, _ -> runtime },
            ProbeClock { 0L },
        )
        assertEquals(
            RuntimeOpenFailure.CreationBindingInvalid(RuntimeCreateCleanupOutcome.CLEAN),
            (result.result as RuntimeOpenResult.Failed).reason,
        )
        assertFalse(result.ownsRuntime)
        assertEquals(1, runtime.closeCalls)
    }

    @Test
    fun submitBoundaryCategorizesReturnFailureDeadlineAndThrow() {
        val cases = listOf(
            PoseRuntimeSubmitEvidence.RETURNED to RuntimeSubmitResult.Returned::class.java,
            PoseRuntimeSubmitEvidence.FAILED to RuntimeSubmitResult.Failed::class.java,
            PoseRuntimeSubmitEvidence.DEADLINE_EXPIRED to RuntimeSubmitResult.DeadlineExpired::class.java,
        )
        cases.forEachIndexed { index, (evidence, expectedType) ->
            val harness = submitHarness(RuntimeIdentity(60L + index)) { evidence }
            val execution = requireNotNull(RuntimeOwnerBoundary.submit(
                harness.machine,
                harness.authorization,
                harness.opened,
            ))
            assertTrue(expectedType.isInstance(execution.result))
        }
        listOf(RuntimeException("submit"), AssertionError("submit"), LinkageError("submit"))
            .forEachIndexed { index, delivered ->
                val harness = submitHarness(RuntimeIdentity(70L + index)) { throw delivered }
                val execution = requireNotNull(RuntimeOwnerBoundary.submit(
                    harness.machine,
                    harness.authorization,
                    harness.opened,
                ))
                assertEquals(
                    SubmissionFailure.DEPENDENCY_THROW,
                    (execution.result as RuntimeSubmitResult.Failed).reason,
                )
            }
    }

    @Test
    fun stateIssuedSubmitAuthorityRejectsReplayCopiedPayloadAndArbitraryHighFirst() {
        var calls = 0
        val harness = submitHarness(RuntimeIdentity(80L)) {
            calls += 1
            PoseRuntimeSubmitEvidence.RETURNED
        }
        val genuineCommand = harness.authorization.command
        val swapped = genuineCommand.copy(
            submission = genuineCommand.submission.copy(
                frameId = genuineCommand.submission.frameId + 99L,
                packetTimestampUs = genuineCommand.submission.packetTimestampUs + 1L,
            ),
        )
        val copiedAuthority = hostileSubmitAuthorization(swapped)

        assertNull(RuntimeOwnerBoundary.submit(harness.machine, copiedAuthority, harness.opened))
        assertEquals(0, calls)
        val genuine = requireNotNull(RuntimeOwnerBoundary.submit(
            harness.machine,
            harness.authorization,
            harness.opened,
        ))
        assertTrue(genuine.result is RuntimeSubmitResult.Returned)
        assertEquals(1, calls)
        assertNull(RuntimeOwnerBoundary.submit(
            harness.machine,
            harness.authorization,
            harness.opened,
        ))
        assertEquals(1, calls)

        var highFirstCalls = 0
        val highFirstHarness = submitHarness(RuntimeIdentity(81L)) {
            highFirstCalls += 1
            PoseRuntimeSubmitEvidence.RETURNED
        }
        val highFirstCommand = highFirstHarness.authorization.command.copy(
            key = highFirstHarness.authorization.command.key.copy(
                reservationToken = Long.MAX_VALUE,
            ),
        )
        assertNull(RuntimeOwnerBoundary.submit(
            highFirstHarness.machine,
            hostileSubmitAuthorization(highFirstCommand),
            highFirstHarness.opened,
        ))
        assertEquals(0, highFirstCalls)
        assertTrue(requireNotNull(RuntimeOwnerBoundary.submit(
            highFirstHarness.machine,
            highFirstHarness.authorization,
            highFirstHarness.opened,
        )).result is RuntimeSubmitResult.Returned)
        assertEquals(1, highFirstCalls)
    }

    @Test
    fun submitAuthorityIsBoundToTheExactMachineAndOpenExecution() {
        var firstCalls = 0
        var secondCalls = 0
        val first = submitHarness(RuntimeIdentity(82L)) {
            firstCalls += 1
            PoseRuntimeSubmitEvidence.RETURNED
        }
        val second = submitHarness(RuntimeIdentity(83L)) {
            secondCalls += 1
            PoseRuntimeSubmitEvidence.RETURNED
        }

        assertNull(RuntimeOwnerBoundary.submit(
            second.machine,
            first.authorization,
            second.opened,
        ))
        assertEquals(0, firstCalls)
        assertEquals(0, secondCalls)
        assertTrue(requireNotNull(RuntimeOwnerBoundary.submit(
            first.machine,
            first.authorization,
            first.opened,
        )).result is RuntimeSubmitResult.Returned)
        assertTrue(requireNotNull(RuntimeOwnerBoundary.submit(
            second.machine,
            second.authorization,
            second.opened,
        )).result is RuntimeSubmitResult.Returned)
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun blockedSubmitKeepsStateCloseClaimUnavailableAndExternalMonitorFree() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val harness = submitHarness(RuntimeIdentity(90L)) {
            assertFalse(Thread.holdsLock(harnessRef.get().machine))
            entered.countDown()
            release.await()
            PoseRuntimeSubmitEvidence.RETURNED
        }
        harnessRef.set(harness)
        val execution = AtomicReference<RuntimeSubmitExecution>()
        val thread = Thread {
            execution.set(RuntimeOwnerBoundary.submit(
                harness.machine,
                harness.authorization,
                harness.opened,
            ))
        }
        thread.start()
        assertTrue(entered.await(1L, TimeUnit.SECONDS))
        harness.machine.abortIncomplete()
        assertNull(harness.machine.beginClose())
        release.countDown()
        thread.join()
        assertTrue(requireNotNull(execution.get()).result is RuntimeSubmitResult.Returned)
    }

    @Test
    fun successfulOpenPublishesExactlyOnePreboundMachine() {
        val opened = RuntimeTestDeepFixture.open(
            command(),
            RecordingRuntime(RuntimeIdentity(99L)),
            proofSeed = 99,
        )
        val first = RuntimeOwnerBoundary.stateMachine(opened)
        val second = RuntimeOwnerBoundary.stateMachine(opened)
        assertTrue(first === second)
        assertTrue(RuntimeOwnerBoundary::class.java.declaredMethods.none {
            it.name == "createProbeStateMachine"
        })
    }

    private val harnessRef = AtomicReference<SubmitHarness>()

    private fun submitHarness(
        identity: RuntimeIdentity,
        operation: () -> PoseRuntimeSubmitEvidence,
    ): SubmitHarness {
        val opened = RuntimeTestDeepFixture.open(
            command(),
            RecordingRuntime(identity),
            PoseRuntimeSubmissionPort { operation() },
            proofSeed = identity.value.toInt(),
        )
        val machine = RuntimeTestDeepFixture.stateMachine(opened)
        assertTrue(machine.startWarmup())
        val reservation = machine.reserveFrame(0L, source(1L)) as FrameAdmission.Reserved
        val authorization = requireNotNull(machine.startSubmission(reservation.reservation.token))
        return SubmitHarness(opened, machine, authorization)
    }

    private fun hostileSubmitAuthorization(command: SubmitRuntimeCommand): RuntimeSubmitAuthorization {
        val type = Class.forName(
            "com.motionarcade.vision.capability.runtime.IssuedRuntimeSubmitAuthorization",
        )
        val constructor = type.declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance(command) as RuntimeSubmitAuthorization
    }

    private fun assertIdentityFailureCleanup(
        seed: Int,
        identityThrowable: Throwable,
        expectedCleanup: RuntimeCreateCleanupOutcome,
        closeOperation: () -> RuntimeCloseEvidence,
    ) {
        val command = command()
        val runtime = IdentityThrowingRuntime(identityThrowable, closeOperation)
        val grant = RuntimeTestDeepFixture.nativeGrant(command, seed + 100)
        val result = RuntimeOwnerBoundary.open(
            command,
            grant.request,
            NativeCreateAuthorizer { grant.authorization },
            RuntimeTestDeepFixture.noOpCallbacks(),
            FreshPoseRuntimeFactory { _, _, owner ->
                check(owner.bind(runtime, PoseRuntimeSubmissionPort {
                    PoseRuntimeSubmitEvidence.RETURNED
                }))
                runtime
            },
            ProbeClock { 0L },
        )
        assertEquals(
            RuntimeOpenFailure.IdentityReadThrew(expectedCleanup),
            (result.result as RuntimeOpenResult.Failed).reason,
        )
        assertFalse(result.ownsRuntime)
        assertEquals(1, runtime.closeCalls)
    }

    private fun command(): OpenRuntimeCommand {
        val attemptEpoch = ProbeAttemptEpoch(nextAttemptEpoch.getAndIncrement().toULong())
        val attempt = ProbeAttemptContext(
            GameMode.DUAL,
            attemptEpoch,
            RuntimeArtifactId(digest(1)),
            ProbeBaseScopeId(digest(2)),
        )
        return OpenRuntimeCommand(RuntimeCreateRequest(
            attempt,
            RuntimeRoute(
                RuntimeRouteKey(attempt.attemptEpoch, 1, RuntimeGeneration(1L)),
                RuntimeRouteKind.CPU_CANDIDATE,
            ),
        ))
    }

    private fun source(value: Long): SourceTimestampNs =
        (SourceTimestampNs.from(value) as CapabilityDomainResult.Valid).value

    private fun digest(seed: Int): Sha256Digest =
        (Sha256Digest.fromBytes(ByteArray(32) { index -> (seed + index).toByte() }) as
            CapabilityDomainResult.Valid).value

    private data class SubmitHarness(
        val opened: RuntimeOpenExecution,
        val machine: ProbeStateMachine,
        val authorization: RuntimeSubmitAuthorization,
    )

    private class RecordingRuntime(
        override val identity: RuntimeIdentity,
        private val closeOperation: () -> RuntimeCloseEvidence = { RuntimeCloseEvidence.CLEAN },
    ) : PoseRuntime() {
        var closeCalls = 0
        override fun close(): RuntimeCloseEvidence {
            closeCalls += 1
            return closeOperation()
        }
    }

    private class IdentityThrowingRuntime(
        private val identityThrowable: Throwable,
        private val closeOperation: () -> RuntimeCloseEvidence,
    ) : PoseRuntime() {
        var closeCalls = 0
        override val identity: RuntimeIdentity
            get() = throw identityThrowable
        override fun close(): RuntimeCloseEvidence {
            closeCalls += 1
            return closeOperation()
        }
    }

    private companion object {
        val nextAttemptEpoch = AtomicLong(3_000L)
    }
}
