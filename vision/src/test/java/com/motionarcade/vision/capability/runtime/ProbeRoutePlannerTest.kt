package com.motionarcade.vision.capability.runtime

import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.domain.ProbeTimeContract
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import com.motionarcade.vision.capability.domain.SourceTimestampNs
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRoutePlannerTest {
    private val runtimes = mutableMapOf<Long, RecordingRuntime>()
    private val clocks = mutableMapOf<RuntimeOpenExecution, MutableProbeClock>()

    @Test
    fun unsignedHighBitAttemptEpochPropagatesWithoutNarrowing() {
        listOf(0x8000000000000000uL, ULong.MAX_VALUE).forEach { value ->
            val epoch = ProbeAttemptEpoch(value)
            val request = command(planner(epoch).start()).request

            assertEquals(value, request.attempt.attemptEpoch.value)
            assertEquals(epoch, request.attempt.attemptEpoch)
            assertEquals(epoch, request.route.key.attemptEpoch)
        }
    }

    @Test
    fun normalRouteIsCpuThenGpuThenFreshSelectedSteady() {
        val planner = planner()

        val cpu = open(planner, planner.start(), identity = 1L)
        val gpuRequest = close(planner, cpu, RouteAttemptOutcome.MEASURED)
        assertKind(gpuRequest, RuntimeRouteKind.GPU_CANDIDATE)
        val gpu = open(planner, gpuRequest, identity = 2L)
        val awaiting = close(planner, gpu, RouteAttemptOutcome.MEASURED)
        assertTrue(awaiting is RouteTransition.AwaitingSelectedDelegate)

        val selectedRequest = planner.select(ProbeDelegate.GPU)
        assertKind(selectedRequest, RuntimeRouteKind.SELECTED_GPU)
        val selected = open(planner, selectedRequest, identity = 3L)
        val completed = close(planner, selected, RouteAttemptOutcome.MEASURED)

        assertEquals(
            RouteTransition.Completed(RouteAttemptOutcome.MEASURED),
            completed,
        )
        assertEquals(listOf(1, 2, 3), listOf(
            cpu.route.key.routeOrdinal,
            gpu.route.key.routeOrdinal,
            selected.route.key.routeOrdinal,
        ))
        assertEquals(listOf(1L, 2L, 3L), listOf(
            cpu.route.key.runtimeGeneration.value,
            gpu.route.key.runtimeGeneration.value,
            selected.route.key.runtimeGeneration.value,
        ))
    }

    @Test
    fun stalePreviousRuntimeCompletionCannotCloseOrAdvanceCurrentRuntime() {
        val planner = planner()
        val cpu = open(planner, planner.start(), 1L)
        val cpuClose = requestClose(planner, cpu, RouteAttemptOutcome.MEASURED)
        val staleClaim = cpuClose.claim
        val gpuRequest = executeClose(planner, cpuClose)
        val gpu = open(planner, gpuRequest, 2L)

        val stale = planner.completeActive(staleClaim)

        assertEquals(
            RouteTransition.Ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE),
            stale,
        )
        prepareOutcome(gpu, RouteAttemptOutcome.MEASURED)
        val actual = planner.completeActive(
            requireNotNull(RuntimeOwnerBoundary.stateMachine(gpu.execution)).beginClose()
                ?: error("expected exact GPU close claim"),
        )
        assertTrue(actual is RouteTransition.CloseRequested)
        val awaiting = executeClose(planner, actual as RouteTransition.CloseRequested)
        assertTrue(awaiting is RouteTransition.AwaitingSelectedDelegate)
    }

    @Test
    fun rawForeignAndReplayedCloseEvidenceCannotAdvancePendingExactClose() {
        val planner = planner()
        val cpu = open(planner, planner.start(), 1L)
        val requested = requestClose(planner, cpu, RouteAttemptOutcome.MEASURED)
        val forgedRaw = RuntimeCloseResult.Clean(
            requested.command.routeKey,
            requested.command.runtimeIdentity,
        )
        val untrustedRaw: Any = forgedRaw

        assertTrue(untrustedRaw !is RuntimeCloseExecution)
        assertTrue(untrustedRaw !is ProbeCloseCompletion)
        assertEquals(0, requireNotNull(runtimes[1L]).closeCalls)
        assertEquals(
            RouteTransition.Ignored(IgnoredRouteEventReason.INVALID_TRANSITION),
            planner.start(),
        )

        val foreignPlanner = planner()
        val foreign = open(foreignPlanner, foreignPlanner.start(), 9L)
        val foreignCompletion = completeOwnerClose(
            requestClose(foreignPlanner, foreign, RouteAttemptOutcome.MEASURED),
        )
        assertEquals(
            RouteTransition.Ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE),
            planner.acceptCloseCompletion(foreignCompletion),
        )
        assertEquals(0, requireNotNull(runtimes[1L]).closeCalls)

        val completion = completeOwnerClose(requested)
        val next = planner.acceptCloseCompletion(completion)
        assertKind(next, RuntimeRouteKind.GPU_CANDIDATE)
        assertEquals(
            RouteTransition.Ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE),
            planner.acceptCloseCompletion(completion),
        )
        assertEquals(2, command(next).request.route.key.routeOrdinal)
    }

    @Test
    fun closeInFlightCompletionRetriesSameExactClaimThenAdvancesOnce() {
        val planner = planner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cpu = open(
            planner,
            planner.start(),
            identity = 1L,
            closeEntered = entered,
            closeRelease = release,
        )
        val requested = requestClose(planner, cpu, RouteAttemptOutcome.MEASURED)
        val machine = requireNotNull(RuntimeOwnerBoundary.stateMachine(requested.execution))
        val firstExecution = AtomicReference<RuntimeCloseExecution>()
        val firstClose = Thread {
            firstExecution.set(
                requireNotNull(RuntimeOwnerBoundary.close(requested.claim, requested.execution)),
            )
        }

        firstClose.start()
        assertTrue(entered.await(1L, TimeUnit.SECONDS))
        val deferred = requireNotNull(
            RuntimeOwnerBoundary.close(requested.claim, requested.execution),
        )
        assertEquals(
            RuntimeCloseDeferredReason.CLOSE_IN_FLIGHT,
            (deferred.result as RuntimeCloseResult.Deferred).reason,
        )
        val retryCompletion = requireNotNull(machine.completeClose(requested.claim, deferred))
        val retry = planner.acceptCloseCompletion(retryCompletion)

        assertTrue(retry is RouteTransition.CloseRequested)
        retry as RouteTransition.CloseRequested
        assertTrue(retry.claim === requested.claim)
        assertTrue(retry.execution === requested.execution)
        assertEquals(requested.command, retry.command)

        release.countDown()
        firstClose.join(1_000L)
        assertTrue(!firstClose.isAlive)
        val cleanCompletion = requireNotNull(
            machine.completeClose(requested.claim, requireNotNull(firstExecution.get())),
        )
        val next = planner.acceptCloseCompletion(cleanCompletion)

        assertKind(next, RuntimeRouteKind.GPU_CANDIDATE)
        assertEquals(1, requireNotNull(runtimes[1L]).closeCalls)
        assertEquals(
            RouteTransition.Ignored(IgnoredRouteEventReason.STALE_ATTEMPT_OR_ROUTE),
            planner.acceptCloseCompletion(cleanCompletion),
        )
    }

    @Test
    fun concurrentStartAndCompletionProduceExactlyOneCommandEach() {
        val planner = planner()
        val startResults = concurrent(2) { planner.start() }

        assertEquals(1, startResults.count { it is RouteTransition.OpenRequested })
        assertEquals(1, startResults.count { it is RouteTransition.Ignored })
        val cpu = open(
            planner,
            startResults.single { it is RouteTransition.OpenRequested },
            1L,
        )

        prepareOutcome(cpu, RouteAttemptOutcome.MEASURED)
        val claim = requireNotNull(RuntimeOwnerBoundary.stateMachine(cpu.execution)).beginClose()
            ?: error("expected exact CPU close claim")
        val completionResults = concurrent(2) { planner.completeActive(claim) }

        assertEquals(1, completionResults.count { it is RouteTransition.CloseRequested })
        assertEquals(1, completionResults.count { it is RouteTransition.Ignored })
        val next = executeClose(
            planner,
            completionResults.single { it is RouteTransition.CloseRequested } as
                RouteTransition.CloseRequested,
        )
        assertKind(next, RuntimeRouteKind.GPU_CANDIDATE)
    }

    @Test
    fun selectedGpuCleanTerminalGetsExactlyOneFreshCpuCandidateAndSteady() {
        val planner = planner()
        val kinds = mutableListOf<RuntimeRouteKind>()

        val cpu = open(planner, planner.start(), 1L).also { kinds += it.route.kind }
        val gpu = open(
            planner,
            close(planner, cpu, RouteAttemptOutcome.MEASURED),
            2L,
        ).also { kinds += it.route.kind }
        close(planner, gpu, RouteAttemptOutcome.MEASURED)
        val selectedGpu = open(planner, planner.select(ProbeDelegate.GPU), 3L)
            .also { kinds += it.route.kind }
        val fallbackCandidate = open(
            planner,
            close(planner, selectedGpu, RouteAttemptOutcome.CLEAN_TERMINAL),
            4L,
        ).also { kinds += it.route.kind }
        val fallbackSelected = open(
            planner,
            close(planner, fallbackCandidate, RouteAttemptOutcome.MEASURED),
            5L,
        ).also { kinds += it.route.kind }
        val completed = close(planner, fallbackSelected, RouteAttemptOutcome.MEASURED)

        assertEquals(
            listOf(
                RuntimeRouteKind.CPU_CANDIDATE,
                RuntimeRouteKind.GPU_CANDIDATE,
                RuntimeRouteKind.SELECTED_GPU,
                RuntimeRouteKind.FALLBACK_CPU_CANDIDATE,
                RuntimeRouteKind.FALLBACK_CPU_SELECTED,
            ),
            kinds,
        )
        assertEquals(RouteTransition.Completed(RouteAttemptOutcome.MEASURED), completed)
    }

    @Test
    fun selectedCpuCleanTerminalWithMeasuredGpuIsIncomplete() {
        val planner = planner()
        val cpu = open(planner, planner.start(), 1L)
        val gpu = open(
            planner,
            close(planner, cpu, RouteAttemptOutcome.MEASURED),
            2L,
        )
        close(planner, gpu, RouteAttemptOutcome.MEASURED)
        val selectedCpu = open(planner, planner.select(ProbeDelegate.CPU), 3L)

        val outcome = close(planner, selectedCpu, RouteAttemptOutcome.CLEAN_TERMINAL)

        assertEquals(RouteTransition.Incomplete(), outcome)
    }

    @Test
    fun createAndCloseFailureResultsQuarantineExplicitly() {
        val createPlanner = planner()
        val createCommand = command(createPlanner.start())
        val createFailure = createPlanner.acceptOpenExecution(
            RuntimeTestDeepFixture.failedOpen(
                createCommand.request.route.key,
                RuntimeOpenFailure.CREATE_THREW,
            ),
        )
        assertEquals(
            RouteTransition.Quarantined(RoutePlannerQuarantineReason.NATIVE_CREATE_UNCERTAIN),
            createFailure,
        )

        val closePlanner = planner()
        val opened = open(
            closePlanner,
            closePlanner.start(),
            1L,
            closeEvidence = RuntimeCloseEvidence.THREW,
        )
        val closeFailure = executeClose(
            closePlanner,
            requestClose(closePlanner, opened, RouteAttemptOutcome.MEASURED),
        )
        assertEquals(
            RouteTransition.Quarantined(RoutePlannerQuarantineReason.NATIVE_CLOSE_UNCERTAIN),
            closeFailure,
        )
    }

    @Test
    fun everyIdentityReadCleanupOutcomeQuarantinesCreateRouteAfterOneCloseAttempt() {
        val cases = listOf(
            RuntimeCreateCleanupOutcome.CLEAN to RuntimeCloseEvidence.CLEAN,
            RuntimeCreateCleanupOutcome.REPORTED_FAILURE to RuntimeCloseEvidence.THREW,
            RuntimeCreateCleanupOutcome.TIMED_OUT to RuntimeCloseEvidence.TIMED_OUT,
            RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN to RuntimeCloseEvidence.RESOURCE_UNCERTAIN,
        )
        cases.forEachIndexed { index, (expectedCleanup, evidence) ->
            assertIdentityCleanupQuarantine(index, expectedCleanup) { evidence }
        }
        assertIdentityCleanupQuarantine(
            99,
            RuntimeCreateCleanupOutcome.CALL_THREW,
        ) { throw AssertionError("native close") }
    }

    @Test
    fun allCleanTerminalCandidatesCanFinishUnsupported() {
        val planner = planner()
        val cpu = open(planner, planner.start(), 1L)
        val gpu = open(
            planner,
            close(planner, cpu, RouteAttemptOutcome.CLEAN_TERMINAL),
            2L,
        )

        val completed = close(planner, gpu, RouteAttemptOutcome.CLEAN_TERMINAL)

        assertEquals(
            RouteTransition.Completed(RouteAttemptOutcome.CLEAN_TERMINAL),
            completed,
        )
    }

    @Test
    fun staleOpenedBeforeStartTransfersExactlyOneOrphanCleanupOwnership() {
        val planner = planner()
        val source = planner()
        val request = command(source.start()).request
        val runtime = RecordingRuntime(RuntimeIdentity(41L))
        val execution = openedExecution(request.route.key, runtime)

        val transition = planner.acceptOpenExecution(execution)

        assertTrue(transition is RouteTransition.OrphanRuntimeCloseRequested)
        assertTrue(execution.ownsRuntime)
        assertEquals(1, planner.pendingOrphanCleanupCount())
        assertEquals(
            RouteTransition.Ignored(IgnoredRouteEventReason.OPEN_EXECUTION_ALREADY_CONSUMED),
            planner.acceptOpenExecution(execution),
        )
        val cleanup = (transition as RouteTransition.OrphanRuntimeCloseRequested).cleanup
        val closed = requireNotNull(RuntimeOwnerBoundary.closeOrphan(planner, cleanup))
        assertEquals(
            RouteTransition.OrphanRuntimeCleaned(OrphanOpenedRuntimeReason.NO_PENDING_OPEN),
            planner.acceptOrphanCloseExecution(cleanup.token, closed),
        )
        assertEquals(1, runtime.closeCalls)
        assertEquals(0, planner.pendingOrphanCleanupCount())
        assertTrue(planner.start() is RouteTransition.OpenRequested)
    }

    @Test
    fun wrongKeyOpenedAndStaleFailureDoNotConsumeExpectedPendingOpen() {
        val planner = planner()
        val expectedTransition = planner.start()
        val expected = command(expectedTransition).request
        val wrongKey = expected.route.key.copy(
            routeOrdinal = expected.route.key.routeOrdinal + 10,
            runtimeGeneration = RuntimeGeneration(expected.route.key.runtimeGeneration.value + 10L),
        )
        val orphanRuntime = RecordingRuntime(RuntimeIdentity(42L))
        val orphan = planner.acceptOpenExecution(
            openedExecution(wrongKey, orphanRuntime),
        ) as RouteTransition.OrphanRuntimeCloseRequested

        assertEquals(
            OrphanOpenedRuntimeReason.ROUTE_KEY_MISMATCH,
            orphan.cleanup.reason,
        )
        assertTrue(
            planner.acceptOpenExecution(
                RuntimeTestDeepFixture.failedOpen(wrongKey, RuntimeOpenFailure.CREATE_THREW),
            ) is RouteTransition.Ignored,
        )
        val orphanClose = requireNotNull(RuntimeOwnerBoundary.closeOrphan(planner, orphan.cleanup))
        assertTrue(
            planner.acceptOrphanCloseExecution(orphan.cleanup.token, orphanClose) is
                RouteTransition.OrphanRuntimeCleaned,
        )

        val expectedRuntime = RecordingRuntime(RuntimeIdentity(43L))
        val accepted = planner.acceptOpenExecution(
            openedExecution(expected.route.key, expectedRuntime),
        )
        assertTrue(accepted is RouteTransition.RuntimeOpened)
        assertEquals(1, orphanRuntime.closeCalls)
        assertEquals(0, expectedRuntime.closeCalls)
    }

    @Test
    fun independentlyOwnedDuplicateAfterAcceptedIsCleanedWithoutConsumingActiveRuntime() {
        val planner = planner()
        val active = open(planner, planner.start(), 51L)
        val duplicateRuntime = RecordingRuntime(RuntimeIdentity(52L))
        val duplicate = planner.acceptOpenExecution(
            openedExecution(active.route.key, duplicateRuntime),
        ) as RouteTransition.OrphanRuntimeCloseRequested

        val duplicateClose = requireNotNull(
            RuntimeOwnerBoundary.closeOrphan(planner, duplicate.cleanup),
        )
        assertTrue(
            planner.acceptOrphanCloseExecution(duplicate.cleanup.token, duplicateClose) is
                RouteTransition.OrphanRuntimeCleaned,
        )
        assertEquals(1, duplicateRuntime.closeCalls)

        val activeClose = requestClose(planner, active, RouteAttemptOutcome.MEASURED)
        assertTrue(executeClose(planner, activeClose) is RouteTransition.OpenRequested)
    }

    @Test
    fun reusedIdentityIsOrphanClosedThenQuarantined() {
        val planner = planner()
        val cpu = open(planner, planner.start(), 61L)
        val gpuRequest = close(planner, cpu, RouteAttemptOutcome.MEASURED)
        val gpuCommand = command(gpuRequest)
        val reusedRuntime = RecordingRuntime(RuntimeIdentity(61L))

        val cleanupTransition = planner.acceptOpenExecution(
            openedExecution(gpuCommand.request.route.key, reusedRuntime),
        ) as RouteTransition.OrphanRuntimeCloseRequested
        assertEquals(
            OrphanOpenedRuntimeReason.RUNTIME_IDENTITY_REUSED,
            cleanupTransition.cleanup.reason,
        )

        val closeResult = requireNotNull(
            RuntimeOwnerBoundary.closeOrphan(planner, cleanupTransition.cleanup),
        )
        assertEquals(
            RouteTransition.Quarantined(RoutePlannerQuarantineReason.RUNTIME_IDENTITY_REUSED),
            planner.acceptOrphanCloseExecution(cleanupTransition.cleanup.token, closeResult),
        )
        assertEquals(1, reusedRuntime.closeCalls)
    }

    @Test
    fun duplicateOwnedHandleReusingActiveIdentityClosesBothBeforeQuarantine() {
        val planner = planner()
        val active = open(planner, planner.start(), 66L)
        val duplicateRuntime = RecordingRuntime(RuntimeIdentity(66L))
        val orphan = planner.acceptOpenExecution(
            openedExecution(active.route.key, duplicateRuntime),
        ) as RouteTransition.OrphanRuntimeCloseRequested

        val orphanClosed = requireNotNull(RuntimeOwnerBoundary.closeOrphan(planner, orphan.cleanup))
        val activeCleanup = planner.acceptOrphanCloseExecution(orphan.cleanup.token, orphanClosed)
            as RouteTransition.CloseRequested

        assertEquals(1, duplicateRuntime.closeCalls)
        assertEquals(
            RouteTransition.Quarantined(RoutePlannerQuarantineReason.RUNTIME_IDENTITY_REUSED),
            executeClose(planner, activeCleanup),
        )
        assertEquals(1, requireNotNull(runtimes[66L]).closeCalls)
    }

    @Test
    fun failedDuplicateOrphanCloseStillClosesActiveBeforeQuarantine() {
        val planner = planner()
        val active = open(planner, planner.start(), 67L)
        val duplicateRuntime = RecordingRuntime(
            RuntimeIdentity(68L),
            closeThrowable = LinkageError("orphan close"),
        )
        val orphan = planner.acceptOpenExecution(
            openedExecution(active.route.key, duplicateRuntime),
        ) as RouteTransition.OrphanRuntimeCloseRequested

        val orphanFailed = requireNotNull(RuntimeOwnerBoundary.closeOrphan(planner, orphan.cleanup))
        val activeCleanup = planner.acceptOrphanCloseExecution(orphan.cleanup.token, orphanFailed)
            as RouteTransition.CloseRequested

        assertEquals(
            RouteTransition.Quarantined(RoutePlannerQuarantineReason.NATIVE_CLOSE_UNCERTAIN),
            executeClose(planner, activeCleanup),
        )
        assertEquals(1, duplicateRuntime.closeCalls)
        assertEquals(1, requireNotNull(runtimes[67L]).closeCalls)
    }

    @Test
    fun orphanCleanupCleanFailedAndThrowAdmissionsAreIdempotentUnderRace() {
        val cases = listOf(
            RecordingRuntime(RuntimeIdentity(71L)),
            RecordingRuntime(
                RuntimeIdentity(72L),
                closeEvidence = RuntimeCloseEvidence.RESOURCE_UNCERTAIN,
            ),
            RecordingRuntime(
                RuntimeIdentity(73L),
                closeThrowable = AssertionError("close"),
            ),
        )

        cases.forEach { runtime ->
            val planner = planner()
            val request = command(planner().start()).request
            val orphan = planner.acceptOpenExecution(
                openedExecution(request.route.key, runtime),
            ) as RouteTransition.OrphanRuntimeCloseRequested
            val closeResult = requireNotNull(
                RuntimeOwnerBoundary.closeOrphan(planner, orphan.cleanup),
            )

            val admissions = concurrent(2) {
                planner.acceptOrphanCloseExecution(orphan.cleanup.token, closeResult)
            }

            assertEquals(1, admissions.count { it is RouteTransition.Ignored })
            assertEquals(
                1,
                admissions.count {
                    it is RouteTransition.OrphanRuntimeCleaned || it is RouteTransition.Quarantined
                },
            )
            assertEquals(1, runtime.closeCalls)
            assertEquals(0, planner.pendingOrphanCleanupCount())
        }
    }

    private fun planner(
        attemptEpoch: ProbeAttemptEpoch = ProbeAttemptEpoch(11uL),
    ): ProbeRoutePlanner {
        val contextSeed = nextPlannerContextSeed.getAndIncrement()
        return ProbeRoutePlanner(
            ProbeAttemptContext(
                mode = GameMode.SOLO,
                attemptEpoch = attemptEpoch,
                runtimeArtifactId = RuntimeArtifactId(digest(contextSeed)),
                probeBaseScopeId = ProbeBaseScopeId(digest(contextSeed xor 0x5a)),
            ),
        )
    }

    private fun assertIdentityCleanupQuarantine(
        seed: Int,
        expectedCleanup: RuntimeCreateCleanupOutcome,
        closeOperation: () -> RuntimeCloseEvidence,
    ) {
        val planner = planner()
        val command = command(planner.start())
        var closeCalls = 0
        val runtime = object : PoseRuntime() {
            override val identity: RuntimeIdentity
                get() = throw LinkageError("identity")

            override fun close(): RuntimeCloseEvidence {
                closeCalls += 1
                return closeOperation()
            }
        }
        val grant = RuntimeTestDeepFixture.nativeGrant(command, 120 + seed)
        val execution = RuntimeOwnerBoundary.open(
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
            (execution.result as RuntimeOpenResult.Failed).reason,
        )
        assertEquals(
            RouteTransition.Quarantined(RoutePlannerQuarantineReason.NATIVE_CREATE_UNCERTAIN),
            planner.acceptOpenExecution(execution),
        )
        assertEquals(1, closeCalls)
    }

    private fun open(
        planner: ProbeRoutePlanner,
        transition: RouteTransition,
        identity: Long,
        closeEvidence: RuntimeCloseEvidence = RuntimeCloseEvidence.CLEAN,
        closeEntered: CountDownLatch? = null,
        closeRelease: CountDownLatch? = null,
    ): RouteTransition.RuntimeOpened {
        val request = command(transition).request
        val runtime = RecordingRuntime(
            RuntimeIdentity(identity),
            closeEvidence,
            closeEntered = closeEntered,
            closeRelease = closeRelease,
        )
        runtimes[identity] = runtime
        val execution = openedExecution(request.route, runtime)
        val opened = planner.acceptOpenExecution(execution)
        assertTrue(opened is RouteTransition.RuntimeOpened)
        assertTrue(execution.ownsRuntime)
        return opened as RouteTransition.RuntimeOpened
    }

    private fun close(
        planner: ProbeRoutePlanner,
        opened: RouteTransition.RuntimeOpened,
        outcome: RouteAttemptOutcome,
    ): RouteTransition = executeClose(planner, requestClose(planner, opened, outcome))

    private fun requestClose(
        planner: ProbeRoutePlanner,
        opened: RouteTransition.RuntimeOpened,
        outcome: RouteAttemptOutcome,
    ): RouteTransition.CloseRequested {
        prepareOutcome(opened, outcome)
        val machine = requireNotNull(RuntimeOwnerBoundary.stateMachine(opened.execution))
        val claim = requireNotNull(machine.beginClose())
        val requested = planner.completeActive(claim)
        assertTrue(requested is RouteTransition.CloseRequested)
        return requested as RouteTransition.CloseRequested
    }

    private fun executeClose(
        planner: ProbeRoutePlanner,
        requested: RouteTransition.CloseRequested,
    ): RouteTransition = planner.acceptCloseCompletion(completeOwnerClose(requested))

    private fun completeOwnerClose(
        requested: RouteTransition.CloseRequested,
    ): ProbeCloseCompletion {
        val machine = requireNotNull(RuntimeOwnerBoundary.stateMachine(requested.execution))
        val execution = requireNotNull(
            RuntimeOwnerBoundary.close(requested.claim, requested.execution),
        )
        return requireNotNull(machine.completeClose(requested.claim, execution))
    }

    private fun prepareOutcome(
        opened: RouteTransition.RuntimeOpened,
        outcome: RouteAttemptOutcome,
    ) {
        val machine = requireNotNull(RuntimeOwnerBoundary.stateMachine(opened.execution))
        val clock = requireNotNull(clocks[opened.execution])
        when (outcome) {
            RouteAttemptOutcome.MEASURED -> {
                assertTrue(machine.startWarmup())
                repeat(ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS) { index ->
                    completeFrame(machine, opened.execution, index.toLong())
                }
                completeFrame(
                    machine,
                    opened.execution,
                    ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS.toLong(),
                )
                val end = requireNotNull(machine.measurementEndNs)
                clock.nowNs = end
                assertTrue(
                    machine.reserveFrame(
                        frameId = ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS + 1L,
                        sourceTimestampNs = source(
                            ProbeTimeContract.REQUIRED_SUCCESSFUL_WARMUP_CALLBACKS + 1L,
                        ),
                    ) is FrameAdmission.OutsideMeasurementWindow,
                )
                clock.nowNs = end + ProbeStateMachine.MEASUREMENT_DRAIN_NS + 1L
                machine.runWatchdog()
            }
            RouteAttemptOutcome.CLEAN_TERMINAL -> {
                assertTrue(machine.startWarmup())
                submitFrameWithoutResult(machine, opened.execution, frameId = 0L)
                clock.nowNs = ProbeStateMachine.RESULT_CALLBACK_DEADLINE_NS + 1L
                machine.runWatchdog()
            }
            RouteAttemptOutcome.INCOMPLETE -> machine.abortIncomplete()
            RouteAttemptOutcome.RESOURCE_UNCERTAIN -> error(
                "resource-uncertain route outcomes require an explicit failure path",
            )
        }
        assertEquals(outcome, machine.pendingCloseOutcome())
    }

    private fun completeFrame(
        machine: ProbeStateMachine,
        execution: RuntimeOpenExecution,
        frameId: Long,
    ) {
        val reservation = submitFrameWithoutResult(machine, execution, frameId)
        val result = machine.admitResult(
            RuntimeResultCallback(
                runtimeIdentity = reservation.submission.runtimeIdentity,
                taskTimestampMs = reservation.submission.taskTimestampMs.value,
                poseCount = 1,
            ),
        )
        assertTrue(result is ResultAdmission.Reserved)
        val cleanup = requireNotNull(result.cleanup)
        assertTrue(
            machine.completeCallbackOutput(
                cleanup.token,
                CallbackOutputCleanupEvidence.CLOSED,
            ) is CallbackResolution.Completed,
        )
    }

    private fun submitFrameWithoutResult(
        machine: ProbeStateMachine,
        execution: RuntimeOpenExecution,
        frameId: Long,
    ): FrameReservation {
        val admission = machine.reserveFrame(frameId, source(frameId))
        assertTrue(admission is FrameAdmission.Reserved)
        val reservation = (admission as FrameAdmission.Reserved).reservation
        val authorization = requireNotNull(machine.startSubmission(reservation.token))
        val submitExecution = requireNotNull(
            RuntimeOwnerBoundary.submit(machine, authorization, execution),
        )
        assertTrue(machine.admitSubmitExecution(submitExecution) is RuntimeSubmitAdmission.Returned)
        return reservation
    }

    private fun openedExecution(
        routeKey: RuntimeRouteKey,
        runtime: RecordingRuntime,
        kind: RuntimeRouteKind = RuntimeRouteKind.CPU_CANDIDATE,
    ): RuntimeOpenExecution = openedExecution(RuntimeRoute(routeKey, kind), runtime)

    private fun openedExecution(
        route: RuntimeRoute,
        runtime: RecordingRuntime,
    ): RuntimeOpenExecution {
        val contextSeed = nextPlannerContextSeed.getAndIncrement()
        val attempt = ProbeAttemptContext(
            mode = GameMode.SOLO,
            attemptEpoch = route.key.attemptEpoch,
            runtimeArtifactId = RuntimeArtifactId(digest(contextSeed)),
            probeBaseScopeId = ProbeBaseScopeId(digest(contextSeed xor 0x5a)),
        )
        val clock = MutableProbeClock()
        val execution = RuntimeTestDeepFixture.open(
            OpenRuntimeCommand(RuntimeCreateRequest(attempt, route)),
            runtime,
            proofSeed = route.key.routeOrdinal,
            clock = clock,
        )
        clocks[execution] = clock
        return execution
    }

    private fun command(transition: RouteTransition): OpenRuntimeCommand =
        (transition as RouteTransition.OpenRequested).command

    private fun assertKind(transition: RouteTransition, expected: RuntimeRouteKind) {
        assertEquals(expected, command(transition).request.route.kind)
    }

    private fun concurrent(
        count: Int,
        operation: () -> RouteTransition,
    ): List<RouteTransition> {
        val ready = CountDownLatch(count)
        val go = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<RouteTransition>()
        val threads = List(count) {
            Thread {
                ready.countDown()
                go.await()
                results += operation()
            }
        }
        threads.forEach(Thread::start)
        ready.await()
        go.countDown()
        threads.forEach(Thread::join)
        return results.toList()
    }

    private fun digest(seed: Int): Sha256Digest =
        valid(Sha256Digest.fromBytes(ByteArray(32) { index -> (seed + index).toByte() }))

    private fun source(value: Long): SourceTimestampNs =
        valid(SourceTimestampNs.from(value))

    private fun <T> valid(result: com.motionarcade.vision.capability.domain.CapabilityDomainResult<T>): T =
        (result as com.motionarcade.vision.capability.domain.CapabilityDomainResult.Valid).value

    private class RecordingRuntime(
        override val identity: RuntimeIdentity,
        private val closeEvidence: RuntimeCloseEvidence = RuntimeCloseEvidence.CLEAN,
        private val closeThrowable: Throwable? = null,
        private val closeEntered: CountDownLatch? = null,
        private val closeRelease: CountDownLatch? = null,
    ) : PoseRuntime() {
        var closeCalls = 0

        override fun close(): RuntimeCloseEvidence {
            closeCalls += 1
            closeEntered?.countDown()
            closeRelease?.await()
            closeThrowable?.let { throw it }
            return closeEvidence
        }
    }

    private class MutableProbeClock(
        var nowNs: Long = 0L,
    ) : ProbeClock {
        override fun nowNs(): Long = nowNs
    }

    private companion object {
        val nextPlannerContextSeed = AtomicInteger(1)
    }
}
