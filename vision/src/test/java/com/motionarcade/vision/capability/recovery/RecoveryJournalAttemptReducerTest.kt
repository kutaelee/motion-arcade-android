package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.runtime.RouteAttemptOutcome
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryJournalAttemptReducerTest {
    @Test
    fun initialFreshAllocatesEpochOneWhileExplicitRecheckCanNeverForgeEntry() {
        val base = RecoveryJournalTestFixtures.scope()
        val empty = RecoveryJournalPayloadV5.empty(
            recoveryBuildId = RecoveryJournalTestFixtures.runtime(),
            mode = RecoveryJournalMode.SOLO,
        )

        val fresh = applied(RecoveryJournalAttemptReducer.startInitialFresh(empty, base))
        val active = requireNotNull(fresh.next.active)
        assertEquals(1uL, fresh.next.lastEpoch)
        assertEquals(base, active.probeBaseScopeId)
        assertEquals(1uL, active.attemptEpoch)
        assertEquals(ProbeDelegate.CPU, active.delegate)
        assertEquals(JournalRouteRole.CANDIDATE, active.role)
        assertFalse(active.retryUsed)
        assertNull(active.retryContextId)
        assertValid(fresh.next)
        assertTrue(
            fresh.effects.contains(RecoveryAttemptEffect.PersistJournalBeforeFurtherAction),
        )
        assertTrue(
            fresh.effects.contains(
                RecoveryAttemptEffect.NativeEntryRequiresCommittedJournal(
                    RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.CANDIDATE),
                ),
            ),
        )

        assertRejected(
            RecoveryJournalAttemptReducer.requestExplicitRecheck(empty, base),
            RecoveryAttemptRejection.RECHECK_MODE_STORE_DELETE_REQUIRED,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.startInitialFresh(fresh.next, base),
            RecoveryAttemptRejection.INITIAL_FRESH_REQUIRES_EMPTY_BASELINE,
        )

        val recheckMethod = RecoveryJournalAttemptReducer::class.java.methods.single {
            it.name.startsWith("requestExplicitRecheck")
        }
        assertFalse(recheckMethod.parameterTypes.contains(Boolean::class.javaPrimitiveType))
        assertFalse(recheckMethod.parameterTypes.contains(Boolean::class.javaObjectType))
        assertEquals(2, recheckMethod.parameterCount)
    }

    @Test
    fun invalidCallerPayloadIsFiniteRejectionAndDoesNotThrow() {
        val empty = RecoveryJournalPayloadV5.empty(
            recoveryBuildId = RecoveryJournalTestFixtures.runtime(),
            mode = RecoveryJournalMode.SOLO,
        )
        val invalid = empty.copy(lastEpoch = 0uL, entries = listOf(terminalEntry()))

        assertRejected(
            RecoveryJournalAttemptReducer.startInitialFresh(
                invalid,
                RecoveryJournalTestFixtures.scope(),
            ),
            RecoveryAttemptRejection.INVALID_CURRENT_PAYLOAD,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.recoverInterrupted(invalid),
            RecoveryAttemptRejection.INVALID_CURRENT_PAYLOAD,
        )
    }

    @Test
    fun everyPublicOperationRejectsInvalidCurrentBeforeCallerDataCanThrow() {
        val base = RecoveryJournalTestFixtures.scope()
        val validActive = initialFresh()
        val key = requireNotNull(validActive.active).key()
        val validCleared = cleanIntermediate(validActive)
        val continuation = continuation(validCleared)
        val invalid = emptyPayload().copy(lastEpoch = 0uL, entries = listOf(terminalEntry()))
        val operations =
            listOf(
                RecoveryJournalAttemptReducer.startInitialFresh(invalid, base),
                RecoveryJournalAttemptReducer.requestExplicitRecheck(invalid, base),
                RecoveryJournalAttemptReducer.resumeProofPrefix(invalid, base),
                RecoveryJournalAttemptReducer.authorizeManualRetry(
                    invalid,
                    base,
                    ProbeDelegate.CPU,
                ),
                RecoveryJournalAttemptReducer.markTeardownPending(invalid, key),
                RecoveryJournalAttemptReducer.completeCleanIntermediate(invalid, key),
                RecoveryJournalAttemptReducer.beginPermittedRoute(
                    invalid,
                    continuation,
                    continuation.allowedRoutes.single(),
                ),
                RecoveryJournalAttemptReducer.recordCleanTerminal(
                    invalid,
                    key,
                    proof(ProbeDelegate.CPU, JournalRouteRole.CANDIDATE),
                ),
                RecoveryJournalAttemptReducer.cancelAfterCleanup(invalid, key),
                RecoveryJournalAttemptReducer.abandonBetweenRuntimes(invalid, base, 1uL),
                RecoveryJournalAttemptReducer.recoverInterrupted(invalid),
            )
        assertTrue(operations.isNotEmpty())
        operations.forEach { result ->
            assertRejected(result, RecoveryAttemptRejection.INVALID_CURRENT_PAYLOAD)
        }
        applied(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                validCleared.next,
                continuation,
                continuation.allowedRoutes.single(),
            ),
        )
    }

    @Test
    fun manualRetryFixtureMovesEpochSevenToEightAndForcesFullRerun() {
        val base = RecoveryJournalTestFixtures.scope(32)
        val otherBase = RecoveryJournalTestFixtures.scope(96)
        val cpuProof = terminalEntry(
            scope = base,
            epoch = 7uL,
            delegate = ProbeDelegate.CPU,
        )
        val gpuQuarantine = quarantineEntry(
            scope = base,
            epoch = 7uL,
            delegate = ProbeDelegate.GPU,
        )
        val otherQuarantine = quarantineEntry(
            scope = otherBase,
            epoch = 4uL,
            delegate = ProbeDelegate.CPU,
        )
        val otherConsumed = consumedEntry(
            scope = otherBase,
            epoch = 5uL,
            delegate = ProbeDelegate.GPU,
        )
        val current = payload(
            lastEpoch = 7uL,
            entries = listOf(cpuProof, gpuQuarantine, otherQuarantine, otherConsumed),
        )

        val result = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                current = current,
                probeBaseScopeId = base,
                targetDelegate = ProbeDelegate.GPU,
            ),
        )

        assertEquals(8uL, result.next.lastEpoch)
        assertFalse(result.next.entries.contains(cpuProof))
        assertTrue(result.next.entries.contains(otherQuarantine))
        assertTrue(result.next.entries.contains(otherConsumed))
        val context = requireNotNull(result.next.manualRetryContext)
        assertEquals(base, context.probeBaseScopeId)
        assertEquals(ProbeDelegate.GPU, context.targetDelegate)
        assertEquals(7uL, context.originQuarantineEpoch)
        assertEquals(8uL, context.attemptEpoch)
        assertEquals(valid(RecoveryJournalV5Codec.entrySha256(gpuQuarantine)), context.originQuarantineEntrySha256)
        val contextId = valid(RecoveryJournalV5Codec.manualRetryContextId(context))
        val marker = result.next.entries.single { it.probeBaseScopeId == base }
        assertEquals(ProbeDelegate.GPU, marker.delegate)
        assertEquals(JournalEntryState.MANUAL_RETRY_ACTIVE, marker.state)
        assertEquals(8uL, marker.attemptEpoch)
        assertEquals(contextId, marker.retryContextId)
        val active = requireNotNull(result.next.active)
        assertEquals(ProbeDelegate.CPU, active.delegate)
        assertEquals(JournalRouteRole.CANDIDATE, active.role)
        assertEquals(8uL, active.attemptEpoch)
        assertTrue(active.retryUsed)
        assertEquals(contextId, active.retryContextId)
        assertTrue(result.effects.contains(RecoveryAttemptEffect.DiscardProcessLocalMetrics))
        assertTrue(result.effects.contains(RecoveryAttemptEffect.RerunProcessLocalCandidates))
        assertValid(result.next)
    }

    @Test
    fun manualTargetMarkerBecomesTerminalBeforeNextRetryOneRoute() {
        val base = RecoveryJournalTestFixtures.scope()
        val authorized = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                current =
                    payload(
                        lastEpoch = 1uL,
                        entries = listOf(quarantineEntry(scope = base, delegate = ProbeDelegate.CPU)),
                    ),
                probeBaseScopeId = base,
                targetDelegate = ProbeDelegate.CPU,
            ),
        ).next
        val context = requireNotNull(authorized.manualRetryContext)
        val contextId = valid(RecoveryJournalV5Codec.manualRetryContextId(context))
        assertEquals(JournalEntryState.MANUAL_RETRY_ACTIVE, authorized.entries.single().state)

        val cpuTerminal = cleanTerminal(authorized)
        val terminalEntry = cpuTerminal.next.entries.single()
        assertEquals(JournalEntryState.TERMINAL_THIS_ATTEMPT, terminalEntry.state)
        assertEquals(ProbeDelegate.CPU, terminalEntry.delegate)
        assertEquals(contextId, terminalEntry.retryContextId)
        assertEquals(context, cpuTerminal.next.manualRetryContext)

        val nextRoute = continuation(cpuTerminal).allowedRoutes.single()
        assertEquals(
            RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.CANDIDATE),
            nextRoute,
        )
        val gpuActive = applied(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                cpuTerminal.next,
                continuation(cpuTerminal),
                nextRoute,
            ),
        ).next
        val active = requireNotNull(gpuActive.active)
        assertEquals(ProbeDelegate.GPU, active.delegate)
        assertTrue(active.retryUsed)
        assertEquals(contextId, active.retryContextId)
        assertEquals(context, gpuActive.manualRetryContext)
        assertValid(gpuActive)
    }

    @Test
    fun manualRetryUsesCheckedUnsignedEpochAndRejectsMaximumWithoutActive() {
        val base = RecoveryJournalTestFixtures.scope()
        val quarantine = quarantineEntry(scope = base, epoch = 1uL)
        val high = payload(
            lastEpoch = ULong.MAX_VALUE - 1uL,
            entries = listOf(quarantine),
        )

        val highApplied = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                high,
                base,
                ProbeDelegate.CPU,
            ),
        )
        assertEquals(ULong.MAX_VALUE, highApplied.next.lastEpoch)
        assertEquals(ULong.MAX_VALUE, requireNotNull(highApplied.next.active).attemptEpoch)
        assertValid(highApplied.next)

        val maximum = payload(lastEpoch = ULong.MAX_VALUE, entries = listOf(quarantine))
        assertRejected(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                maximum,
                base,
                ProbeDelegate.CPU,
            ),
            RecoveryAttemptRejection.EPOCH_OVERFLOW,
        )
        assertNull(maximum.active)
    }

    @Test
    fun manualRetryRequiresExactEligibleBitOneQuarantineAndOriginKey() {
        val base = RecoveryJournalTestFixtures.scope()
        val other = RecoveryJournalTestFixtures.scope(96)
        val target = quarantineEntry(scope = base, cacheInvalidated = true)
        val bitZeroOther = quarantineEntry(scope = other, cacheInvalidated = false)
        val bitZeroPayload = payload(lastEpoch = 1uL, entries = listOf(target, bitZeroOther))
        assertRejected(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                bitZeroPayload,
                base,
                ProbeDelegate.CPU,
            ),
            RecoveryAttemptRejection.BIT_ZERO_QUARANTINE_BLOCKS,
        )

        assertRejected(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                payload(lastEpoch = 1uL, entries = listOf(target)),
                other,
                ProbeDelegate.CPU,
            ),
            RecoveryAttemptRejection.MANUAL_RETRY_TARGET_NOT_ELIGIBLE,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                payload(lastEpoch = 1uL, entries = listOf(target)),
                base,
                ProbeDelegate.NONE,
            ),
            RecoveryAttemptRejection.MANUAL_RETRY_TARGET_NOT_ELIGIBLE,
        )
    }

    @Test
    fun resumeMapsEverySingletonProofToItsUniqueSameEpochRoute() {
        val base = RecoveryJournalTestFixtures.scope()
        val cases =
            listOf(
                terminalEntry(
                    scope = base,
                    delegate = ProbeDelegate.CPU,
                    role = JournalRouteRole.CANDIDATE,
                ) to RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.CANDIDATE),
                terminalEntry(
                    scope = base,
                    delegate = ProbeDelegate.GPU,
                    role = JournalRouteRole.CANDIDATE,
                ) to RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.CANDIDATE),
                terminalEntry(
                    scope = base,
                    delegate = ProbeDelegate.GPU,
                    role = JournalRouteRole.SELECTED,
                ) to RecoveryAttemptRoute(
                    ProbeDelegate.CPU,
                    JournalRouteRole.FALLBACK_CANDIDATE,
                ),
            )

        cases.forEach { (proofEntry, expectedRoute) ->
            val current = payload(lastEpoch = 4uL, entries = listOf(proofEntry.copy(attemptEpoch = 4uL)))
            val result = applied(RecoveryJournalAttemptReducer.resumeProofPrefix(current, base))
            val active = requireNotNull(result.next.active)
            assertEquals(4uL, active.attemptEpoch)
            assertEquals(expectedRoute.delegate, active.delegate)
            assertEquals(expectedRoute.role, active.role)
            assertFalse(active.retryUsed)
            assertEquals(current.entries, result.next.entries)
            assertTrue(result.effects.contains(RecoveryAttemptEffect.DiscardProcessLocalMetrics))
            assertTrue(result.effects.contains(RecoveryAttemptEffect.RerunProcessLocalCandidates))
            assertValid(result.next)
        }
    }

    @Test
    fun resumeRejectsCompletedBlockedMissingAndCapacityExhaustedPrefixes() {
        val base = RecoveryJournalTestFixtures.scope(32)
        val cpu = terminalEntry(scope = base, delegate = ProbeDelegate.CPU)
        val gpu = terminalEntry(scope = base, delegate = ProbeDelegate.GPU)
        assertRejected(
            RecoveryJournalAttemptReducer.resumeProofPrefix(
                payload(lastEpoch = 1uL, entries = listOf(cpu, gpu)),
                base,
            ),
            RecoveryAttemptRejection.PROOF_PREFIX_COMPLETE,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.resumeProofPrefix(
                payload(lastEpoch = 1uL, entries = emptyList()),
                base,
            ),
            RecoveryAttemptRejection.NO_PROOF_PREFIX,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.resumeProofPrefix(
                payload(
                    lastEpoch = 1uL,
                    entries =
                        listOf(
                            cpu,
                            quarantineEntry(
                                scope = base,
                                delegate = ProbeDelegate.GPU,
                            ),
                        ),
                ),
                base,
            ),
            RecoveryAttemptRejection.ROUTE_BLOCKED,
        )

        val full = mutableListOf<JournalEntryV5>(cpu)
        repeat(7) { index ->
            full += quarantineEntry(
                scope = RecoveryJournalTestFixtures.scope(64 + index * 32),
                delegate = if (index % 2 == 0) ProbeDelegate.CPU else ProbeDelegate.GPU,
            )
        }
        assertEquals(8, full.size)
        assertRejected(
            RecoveryJournalAttemptReducer.resumeProofPrefix(
                payload(lastEpoch = 1uL, entries = full),
                base,
            ),
            RecoveryAttemptRejection.CAPACITY_EXCEEDED,
        )
    }

    @Test
    fun exactActiveKeyMovesActiveToTeardownAndCleanIntermediateOnlyClearsActive() {
        val start = initialFresh()
        val active = requireNotNull(start.active)
        val key = active.key()
        val wrong = key.copy(role = JournalRouteRole.SELECTED)
        val wrongEpoch = key.copy(attemptEpoch = 2uL)

        assertRejected(
            RecoveryJournalAttemptReducer.markTeardownPending(start, wrong),
            RecoveryAttemptRejection.ACTIVE_KEY_MISMATCH,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.markTeardownPending(start, wrongEpoch),
            RecoveryAttemptRejection.ACTIVE_KEY_MISMATCH,
        )
        val teardown = applied(
            RecoveryJournalAttemptReducer.markTeardownPending(start, key),
        ).next
        assertEquals(JournalActiveState.TEARDOWN_PENDING, requireNotNull(teardown.active).state)
        assertEquals(start.entries, teardown.entries)
        assertRejected(
            RecoveryJournalAttemptReducer.markTeardownPending(teardown, key),
            RecoveryAttemptRejection.ACTIVE_STATE_MISMATCH,
        )

        val cleared = applied(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                teardown,
                key,
                RecoveryClosureTestFixture.cleanAuthority(
                    teardown,
                    key,
                    RouteAttemptOutcome.MEASURED,
                ),
            ),
        )
        assertNull(cleared.next.active)
        assertEquals(teardown.entries, cleared.next.entries)
        val continuation = continuation(cleared)
        assertEquals(
            listOf(RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.CANDIDATE)),
            continuation.allowedRoutes,
        )
        assertValid(cleared.next)
    }

    @Test
    fun continuationIsPayloadBoundRouteBoundAndStillRequiresJournalCommit() {
        val start = initialFresh()
        val cleared = cleanIntermediate(start)
        val independentlyDerived = cleanIntermediate(start)
        assertEquals(cleared.next, independentlyDerived.next)
        val independentlyDerivedContinuation = continuation(independentlyDerived)
        val continuation = continuation(cleared)
        assertFalse(continuation === independentlyDerivedContinuation)
        assertFalse(continuation == independentlyDerivedContinuation)
        val wrongRoute = RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.SELECTED)
        assertRejected(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                cleared.next,
                continuation,
                wrongRoute,
            ),
            RecoveryAttemptRejection.ROUTE_PERMIT_ROUTE_NOT_ALLOWED,
        )

        val stale = cleared.next.copy(lastEpoch = 2uL)
        assertValid(stale)
        assertRejected(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                stale,
                continuation,
                continuation.allowedRoutes.single(),
            ),
            RecoveryAttemptRejection.ROUTE_PERMIT_PAYLOAD_MISMATCH,
        )

        val begun = applied(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                cleared.next,
                continuation,
                continuation.allowedRoutes.single(),
            ),
        )
        val active = requireNotNull(begun.next.active)
        assertEquals(ProbeDelegate.GPU, active.delegate)
        assertEquals(JournalRouteRole.CANDIDATE, active.role)
        assertTrue(
            begun.effects.contains(
                RecoveryAttemptEffect.NativeEntryRequiresCommittedJournal(
                    continuation.allowedRoutes.single(),
                ),
            ),
        )
        assertValid(begun.next)
        assertRejected(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                cleared.next,
                continuation,
                continuation.allowedRoutes.single(),
            ),
            RecoveryAttemptRejection.ROUTE_PERMIT_ALREADY_CONSUMED,
        )
    }

    @Test
    fun publicJvmFactoryForgedGpuSelectedContinuationIsNeverIssued() {
        val cleared = cleanIntermediate(initialFresh())
        val genuine = continuation(cleared)
        val companion =
            RecoveryRouteContinuation::class.java.getField("Companion").get(null)
        val factory =
            companion.javaClass.methods.single {
                it.name.startsWith("create-") && it.parameterCount == 4
            }
        assertTrue(Modifier.isPublic(factory.modifiers))
        val forgedRoute =
            RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.SELECTED)
        val forged =
            factory.invoke(
                companion,
                genuine.expectedPayloadSha256,
                genuine.probeBaseScopeId.digest,
                genuine.attemptEpoch.toLong(),
                listOf(forgedRoute),
            ) as RecoveryRouteContinuation

        assertRejected(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                cleared.next,
                forged,
                forgedRoute,
            ),
            RecoveryAttemptRejection.ROUTE_PERMIT_NOT_ISSUED,
        )
    }

    @Test
    fun cleanAuthorityBindsExactManualRetryProvenance() {
        val base = RecoveryJournalTestFixtures.scope(240)
        fun authorizedFrom(reason: JournalReason): RecoveryJournalPayloadV5 =
            applied(
                RecoveryJournalAttemptReducer.authorizeManualRetry(
                    current =
                        payload(
                            lastEpoch = 1uL,
                            entries =
                                listOf(
                                    RecoveryJournalTestFixtures.quarantineEntry(
                                        scope = base,
                                        epoch = 1uL,
                                        delegate = ProbeDelegate.CPU,
                                        reason = reason,
                                    ),
                                ),
                        ),
                    probeBaseScopeId = base,
                    targetDelegate = ProbeDelegate.CPU,
                ),
            ).next

        val first = teardown(authorizedFrom(JournalReason.RECOVERED_ACTIVE))
        val alternate =
            teardown(authorizedFrom(JournalReason.RECOVERED_TEARDOWN_PENDING))
        val key = requireNotNull(first.active).key()
        assertEquals(key, requireNotNull(alternate.active).key())
        assertFalse(
            requireNotNull(first.active).retryContextId ==
                requireNotNull(alternate.active).retryContextId,
        )
        val authority =
            RecoveryClosureTestFixture.cleanAuthority(
                first,
                key,
                RouteAttemptOutcome.MEASURED,
            )
        assertRejected(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                alternate,
                key,
                authority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH,
        )
        applied(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(first, key, authority),
        )
    }

    @Test
    fun consumedContinuationCannotReplayAfterCleanIntermediateOrRetryZeroCancel() {
        val beforeClean = cleanIntermediate(initialFresh())
        val cleanContinuation = continuation(beforeClean)
        val cleanRoute = cleanContinuation.allowedRoutes.single()
        val cleanActive = applied(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                beforeClean.next,
                cleanContinuation,
                cleanRoute,
            ),
        ).next
        val afterClean = cleanIntermediate(cleanActive)
        assertEquals(beforeClean.next, afterClean.next)
        assertRejected(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                afterClean.next,
                cleanContinuation,
                cleanRoute,
            ),
            RecoveryAttemptRejection.ROUTE_PERMIT_ALREADY_CONSUMED,
        )

        val beforeCancel = cleanIntermediate(initialFresh())
        val cancelContinuation = continuation(beforeCancel)
        val cancelRoute = cancelContinuation.allowedRoutes.single()
        val cancelActive = applied(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                beforeCancel.next,
                cancelContinuation,
                cancelRoute,
            ),
        ).next
        val cancelTeardown = teardown(cancelActive)
        val cancelKey = requireNotNull(cancelTeardown.active).key()
        val cancelled = applied(
            RecoveryJournalAttemptReducer.cancelAfterCleanup(
                cancelTeardown,
                cancelKey,
                RecoveryClosureTestFixture.cleanAuthority(
                    cancelTeardown,
                    cancelKey,
                    RouteAttemptOutcome.INCOMPLETE,
                ),
            ),
        ).next
        assertEquals(beforeCancel.next, cancelled)
        assertRejected(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                cancelled,
                cancelContinuation,
                cancelRoute,
            ),
            RecoveryAttemptRejection.ROUTE_PERMIT_ALREADY_CONSUMED,
        )
    }

    @Test
    fun selectedCompletionCannotReplayItsConsumedContinuation() {
        val cpuMeasured = cleanIntermediate(initialFresh())
        val gpuCandidate = beginOnly(cpuMeasured)
        val gpuMeasured = cleanIntermediate(gpuCandidate)
        val selectedContinuation = continuation(gpuMeasured)
        val selectedRoute = RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.SELECTED)
        val selectedActive = applied(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                gpuMeasured.next,
                selectedContinuation,
                selectedRoute,
            ),
        ).next
        val completed = cleanIntermediate(selectedActive)
        assertWindowComplete(completed)
        assertEquals(gpuMeasured.next, completed.next)
        assertRejected(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                completed.next,
                selectedContinuation,
                selectedRoute,
            ),
            RecoveryAttemptRejection.ROUTE_PERMIT_ALREADY_CONSUMED,
        )
    }

    @Test
    fun concurrentDoubleBeginConsumesContinuationExactlyOnce() {
        val cleared = cleanIntermediate(initialFresh())
        val continuation = continuation(cleared)
        val route = continuation.allowedRoutes.single()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit<RecoveryAttemptReductionResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    RecoveryJournalAttemptReducer.beginPermittedRoute(
                        cleared.next,
                        continuation,
                        route,
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is RecoveryAttemptReductionResult.Applied })
            assertEquals(
                1,
                results.count {
                    it == RecoveryAttemptReductionResult.Rejected(
                        RecoveryAttemptRejection.ROUTE_PERMIT_ALREADY_CONSUMED,
                    )
                },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun terminalRequiresTeardownAndExactCanonicalMatchingProof() {
        val start = initialFresh()
        val active = requireNotNull(start.active)
        val key = active.key()
        val proof = proof(
            delegate = ProbeDelegate.CPU,
            role = JournalRouteRole.CANDIDATE,
            reason = TerminalProofReason.RESULT_CALLBACK_DEADLINE_CLEAN,
        )
        val authority =
            RecoveryClosureTestFixture.cleanAuthority(
                start,
                key,
                RouteAttemptOutcome.CLEAN_TERMINAL,
            )
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(start, key, proof, authority),
            RecoveryAttemptRejection.ACTIVE_STATE_MISMATCH,
        )
        val teardown = teardown(start)
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(
                teardown,
                key,
                proof(
                    delegate = ProbeDelegate.GPU,
                    role = JournalRouteRole.CANDIDATE,
                    reason = TerminalProofReason.RESULT_CALLBACK_DEADLINE_CLEAN,
                ),
                authority,
            ),
            RecoveryAttemptRejection.TERMINAL_PROOF_MISMATCH,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(
                teardown,
                key,
                proof.copy(resolvedOwnerMask = 0xfe),
                authority,
            ),
            RecoveryAttemptRejection.TERMINAL_PROOF_INVALID,
        )

        val swappedReason =
            proof(
                delegate = ProbeDelegate.CPU,
                role = JournalRouteRole.CANDIDATE,
                reason = TerminalProofReason.DETECT_EXCEPTION_RETURNED,
            )
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(
                teardown,
                key,
                swappedReason,
                authority,
            ),
            RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH,
        )
        applied(
            RecoveryJournalAttemptReducer.recordCleanTerminal(
                teardown,
                key,
                proof,
                authority,
            ),
        )

        TerminalProofReason.entries.forEach { reason ->
            val independent = teardown(initialFresh())
            val independentActive = requireNotNull(independent.active)
            val independentKey = independentActive.key()
            val terminalAuthority: Any =
                if (reason == TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY) {
                    RecoveryClosureTestFixture.syntheticPreNativeAuthorityForReducerOnly(
                        independent,
                        independentKey,
                    )
                } else {
                    RecoveryClosureTestFixture.cleanAuthority(
                        independent,
                        independentKey,
                        RouteAttemptOutcome.CLEAN_TERMINAL,
                    )
                }
            val reduction =
                when (terminalAuthority) {
                    is RecoveryCleanClosureAuthority ->
                        RecoveryJournalAttemptReducer.recordCleanTerminal(
                            independent,
                            independentKey,
                            proof(
                                delegate = ProbeDelegate.CPU,
                                role = JournalRouteRole.CANDIDATE,
                                reason = reason,
                            ),
                            terminalAuthority,
                        )
                    is RecoveryPreNativeCreateDenialAuthority ->
                        RecoveryJournalAttemptReducer.recordCleanTerminal(
                            independent,
                            independentKey,
                            proof(
                                delegate = ProbeDelegate.CPU,
                                role = JournalRouteRole.CANDIDATE,
                                reason = reason,
                            ),
                            terminalAuthority,
                        )
                    else -> error("unexpected authority")
                }
            if (reason != TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY &&
                reason != TerminalProofReason.RESULT_CALLBACK_DEADLINE_CLEAN
            ) {
                assertRejected(
                    reduction,
                    RecoveryAttemptRejection.CLEAN_CLOSURE_AUTHORITY_MISMATCH,
                )
                return@forEach
            }
            val result = applied(reduction)
            val entry = result.next.entries.single()
            assertEquals(reason.wireValue, entry.reason.wireValue)
            assertNull(result.next.active)
            assertValid(result.next)
        }
    }

    @Test
    fun reducerProducesAllFiveFiniteCompletedTerminalProofSets() {
        val completedPairs = mutableListOf<Set<Pair<ProbeDelegate, JournalRouteRole>>>()

        // CPU candidate terminal + GPU candidate terminal.
        run {
            val cpuTerminal = cleanTerminal(initialFresh())
            val gpu = beginOnly(cpuTerminal)
            val completed = cleanTerminal(gpu)
            assertWindowComplete(completed)
            completedPairs += proofRoles(completed.next)
        }

        // CPU candidate terminal + GPU selected terminal.
        run {
            val cpuTerminal = cleanTerminal(initialFresh())
            val gpuCandidate = beginOnly(cpuTerminal)
            val gpuMeasured = cleanIntermediate(gpuCandidate)
            val gpuSelected = beginOnly(gpuMeasured)
            val completed = cleanTerminal(gpuSelected)
            assertWindowComplete(completed)
            completedPairs += proofRoles(completed.next)
        }

        // GPU candidate terminal + CPU selected terminal.
        run {
            val cpuMeasured = cleanIntermediate(initialFresh())
            val gpuCandidate = beginOnly(cpuMeasured)
            val gpuTerminal = cleanTerminal(gpuCandidate)
            val cpuSelected = beginOnly(gpuTerminal)
            val completed = cleanTerminal(cpuSelected)
            assertWindowComplete(completed)
            completedPairs += proofRoles(completed.next)
        }

        // GPU selected terminal + CPU fallback-candidate terminal.
        run {
            val gpuSelected = driveToGpuSelected()
            val gpuTerminal = cleanTerminal(gpuSelected)
            val fallbackCandidate = beginOnly(gpuTerminal)
            val completed = cleanTerminal(fallbackCandidate)
            assertWindowComplete(completed)
            completedPairs += proofRoles(completed.next)
        }

        // GPU selected terminal + CPU fallback-selected terminal.
        run {
            val gpuSelected = driveToGpuSelected()
            val gpuTerminal = cleanTerminal(gpuSelected)
            val fallbackCandidate = beginOnly(gpuTerminal)
            val fallbackMeasured = cleanIntermediate(fallbackCandidate)
            val fallbackSelected = beginOnly(fallbackMeasured)
            val completed = cleanTerminal(fallbackSelected)
            assertWindowComplete(completed)
            completedPairs += proofRoles(completed.next)
        }

        assertEquals(
            setOf(
                setOf(
                    ProbeDelegate.CPU to JournalRouteRole.CANDIDATE,
                    ProbeDelegate.GPU to JournalRouteRole.CANDIDATE,
                ),
                setOf(
                    ProbeDelegate.CPU to JournalRouteRole.CANDIDATE,
                    ProbeDelegate.GPU to JournalRouteRole.SELECTED,
                ),
                setOf(
                    ProbeDelegate.GPU to JournalRouteRole.CANDIDATE,
                    ProbeDelegate.CPU to JournalRouteRole.SELECTED,
                ),
                setOf(
                    ProbeDelegate.GPU to JournalRouteRole.SELECTED,
                    ProbeDelegate.CPU to JournalRouteRole.FALLBACK_CANDIDATE,
                ),
                setOf(
                    ProbeDelegate.GPU to JournalRouteRole.SELECTED,
                    ProbeDelegate.CPU to JournalRouteRole.FALLBACK_SELECTED,
                ),
            ),
            completedPairs.toSet(),
        )
    }

    @Test
    fun candidateIntermediateRoutesCoverBothMetricChoicesAndBothSkipForms() {
        val cpuMeasured = cleanIntermediate(initialFresh())
        val gpuCandidate = beginOnly(cpuMeasured)
        val gpuMeasured = cleanIntermediate(gpuCandidate)
        assertEquals(
            setOf(
                RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.SELECTED),
                RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.SELECTED),
            ),
            continuation(gpuMeasured).allowedRoutes.toSet(),
        )

        val base = RecoveryJournalTestFixtures.scope()
        val skipCpu = payload(
            lastEpoch = 1uL,
            entries =
                listOf(
                    quarantineEntry(scope = base, delegate = ProbeDelegate.CPU),
                    quarantineEntry(scope = base, delegate = ProbeDelegate.GPU),
                ),
        )
        val gpuManual = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                skipCpu,
                base,
                ProbeDelegate.GPU,
            ),
        ).next
        assertEquals(ProbeDelegate.GPU, requireNotNull(gpuManual.active).delegate)
        val gpuMeasuredWithCpuSkip = cleanIntermediate(gpuManual)
        assertEquals(
            listOf(RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.SELECTED)),
            continuation(gpuMeasuredWithCpuSkip).allowedRoutes,
        )

        val skipGpu = payload(
            lastEpoch = 1uL,
            entries =
                listOf(
                    quarantineEntry(scope = base, delegate = ProbeDelegate.CPU),
                    quarantineEntry(scope = base, delegate = ProbeDelegate.GPU),
                ),
        )
        val cpuManual = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                skipGpu,
                base,
                ProbeDelegate.CPU,
            ),
        ).next
        assertEquals(ProbeDelegate.CPU, requireNotNull(cpuManual.active).delegate)
        val cpuMeasuredWithGpuSkip = cleanIntermediate(cpuManual)
        assertEquals(
            listOf(RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.SELECTED)),
            continuation(cpuMeasuredWithGpuSkip).allowedRoutes,
        )

        val gpuSelectedWithCpuSkip = beginOnly(gpuMeasuredWithCpuSkip)
        val gpuTerminalWithCpuSkip = cleanTerminal(gpuSelectedWithCpuSkip)
        assertWindowComplete(gpuTerminalWithCpuSkip)
        assertEquals(
            setOf(ProbeDelegate.GPU to JournalRouteRole.SELECTED),
            proofRoles(gpuTerminalWithCpuSkip.next),
        )

        val cpuSelectedWithGpuSkip = beginOnly(cpuMeasuredWithGpuSkip)
        val cpuSelectedTeardown = teardown(cpuSelectedWithGpuSkip)
        val selectedActive = requireNotNull(cpuSelectedTeardown.active)
        val selectedKey = selectedActive.key()
        assertRejected(
            RecoveryJournalAttemptReducer.recordCleanTerminal(
                cpuSelectedTeardown,
                selectedKey,
                proof(selectedActive.delegate, selectedActive.role),
                RecoveryClosureTestFixture.syntheticPreNativeAuthorityForReducerOnly(
                    cpuSelectedTeardown,
                    selectedKey,
                ),
            ),
            RecoveryAttemptRejection.NEXT_STATE_INVALID,
        )
    }

    @Test
    fun everyMeasuredSelectedAndFallbackSelectedRouteCompletesWithoutProofReuse() {
        fun measuredCandidates(): RecoveryAttemptReductionResult.Applied {
            val cpuMeasured = cleanIntermediate(initialFresh())
            return cleanIntermediate(beginOnly(cpuMeasured))
        }

        val cpuSelected = begin(
            measuredCandidates(),
            RecoveryAttemptRoute(ProbeDelegate.CPU, JournalRouteRole.SELECTED),
        )
        assertWindowComplete(cleanIntermediate(cpuSelected))

        val gpuSelected = begin(
            measuredCandidates(),
            RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.SELECTED),
        )
        assertWindowComplete(cleanIntermediate(gpuSelected))

        val terminalGpuSelected = cleanTerminal(driveToGpuSelected())
        val fallbackCandidate = beginOnly(terminalGpuSelected)
        val fallbackMeasured = cleanIntermediate(fallbackCandidate)
        val fallbackSelected = beginOnly(fallbackMeasured)
        assertWindowComplete(cleanIntermediate(fallbackSelected))
    }

    @Test
    fun cancelRetryZeroDeletesOnlyExactAttemptTerminalsAndIsIdempotentlyRejected() {
        val otherBase = RecoveryJournalTestFixtures.scope(96)
        val cpuTerminal = cleanTerminal(initialFresh()).next
        val withOther = cpuTerminal.copy(
            entries =
                sortedEntries(
                    cpuTerminal.entries +
                        terminalEntry(
                            scope = otherBase,
                            epoch = 1uL,
                            delegate = ProbeDelegate.CPU,
                        ),
                ),
        )
        val resumed = applied(
            RecoveryJournalAttemptReducer.resumeProofPrefix(
                withOther,
                RecoveryJournalTestFixtures.scope(),
            ),
        ).next
        val teardown = teardown(resumed)
        val key = requireNotNull(teardown.active).key()
        val authority =
            RecoveryClosureTestFixture.cleanAuthority(
                teardown,
                key,
                RouteAttemptOutcome.INCOMPLETE,
            )
        val cancelled = applied(
            RecoveryJournalAttemptReducer.cancelAfterCleanup(teardown, key, authority),
        )
        assertNull(cancelled.next.active)
        assertFalse(cancelled.next.entries.any {
            it.probeBaseScopeId == RecoveryJournalTestFixtures.scope()
        })
        assertTrue(cancelled.next.entries.any { it.probeBaseScopeId == otherBase })
        assertValid(cancelled.next)
        assertRejected(
            RecoveryJournalAttemptReducer.cancelAfterCleanup(cancelled.next, key, authority),
            RecoveryAttemptRejection.NO_ACTIVE,
        )
    }

    @Test
    fun cancelManualRetryConsumesMarkerAndDistinctActiveWithSharedContext() {
        val base = RecoveryJournalTestFixtures.scope()
        val current = payload(
            lastEpoch = 7uL,
            entries =
                listOf(
                    terminalEntry(scope = base, epoch = 7uL, delegate = ProbeDelegate.CPU),
                    quarantineEntry(scope = base, epoch = 7uL, delegate = ProbeDelegate.GPU),
                ),
        )
        val manual = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                current,
                base,
                ProbeDelegate.GPU,
            ),
        ).next
        val contextId = requireNotNull(requireNotNull(manual.active).retryContextId)
        val teardown = teardown(manual)
        val key = requireNotNull(teardown.active).key()
        val cancelled = applied(
            RecoveryJournalAttemptReducer.cancelAfterCleanup(
                teardown,
                key,
                RecoveryClosureTestFixture.cleanAuthority(
                    teardown,
                    key,
                    RouteAttemptOutcome.INCOMPLETE,
                ),
            ),
        ).next

        assertNull(cancelled.active)
        assertNull(cancelled.manualRetryContext)
        assertEquals(2, cancelled.entries.size)
        assertEquals(setOf(ProbeDelegate.CPU, ProbeDelegate.GPU), cancelled.entries.map { it.delegate }.toSet())
        cancelled.entries.forEach { entry ->
            assertEquals(JournalEntryState.RETRY_CONSUMED, entry.state)
            assertEquals(JournalReason.MANUAL_RETRY_ABORTED, entry.reason)
            assertEquals(contextId, entry.retryContextId)
            assertTrue(entry.retryUsed)
            assertTrue(entry.cacheInvalidated)
            assertNull(entry.terminalEvidence)
        }
        assertValid(cancelled)
    }

    @Test
    fun abandonBetweenRuntimesIsTotalForRetryZeroAndRetryOne() {
        val base = RecoveryJournalTestFixtures.scope()
        val proofPrefix = cleanTerminal(initialFresh()).next
        val abandonedZero = applied(
            RecoveryJournalAttemptReducer.abandonBetweenRuntimes(proofPrefix, base, 1uL),
        ).next
        assertTrue(abandonedZero.entries.isEmpty())
        assertRejected(
            RecoveryJournalAttemptReducer.abandonBetweenRuntimes(abandonedZero, base, 1uL),
            RecoveryAttemptRejection.ATTEMPT_NOT_FOUND,
        )

        val manualActive = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                payload(
                    lastEpoch = 1uL,
                    entries = listOf(quarantineEntry(scope = base)),
                ),
                base,
                ProbeDelegate.CPU,
            ),
        ).next
        val between = cleanIntermediate(manualActive).next
        assertNull(between.active)
        val abandonedOne = applied(
            RecoveryJournalAttemptReducer.abandonBetweenRuntimes(between, base, 2uL),
        ).next
        assertNull(abandonedOne.manualRetryContext)
        assertEquals(JournalEntryState.RETRY_CONSUMED, abandonedOne.entries.single().state)
        assertEquals(JournalReason.MANUAL_RETRY_ABANDONED, abandonedOne.entries.single().reason)
        assertValid(abandonedOne)

        val otherBase = RecoveryJournalTestFixtures.scope(96)
        val withUnreconciledOtherScope = proofPrefix.copy(
            entries =
                sortedEntries(
                    proofPrefix.entries +
                        quarantineEntry(
                            scope = otherBase,
                            delegate = ProbeDelegate.CPU,
                            cacheInvalidated = false,
                        ),
                ),
        )
        assertValid(withUnreconciledOtherScope)
        val stillAbandoned = applied(
            RecoveryJournalAttemptReducer.abandonBetweenRuntimes(
                withUnreconciledOtherScope,
                base,
                1uL,
            ),
        ).next
        assertEquals(
            false,
            stillAbandoned.entries.single { it.probeBaseScopeId == otherBase }.cacheInvalidated,
        )
    }

    @Test
    fun interruptedRetryZeroQuarantinesExactActiveAndPreservesProofPrefix() {
        val cpuTerminal = cleanTerminal(initialFresh())
        val gpuActive = beginOnly(cpuTerminal)
        val active = requireNotNull(gpuActive.active)

        val recovered = applied(RecoveryJournalAttemptReducer.recoverInterrupted(gpuActive))
        assertNull(recovered.next.active)
        assertEquals(2, recovered.next.entries.size)
        assertTrue(recovered.next.entries.any { it.state == JournalEntryState.TERMINAL_THIS_ATTEMPT })
        val quarantine = recovered.next.entries.single { it.delegate == ProbeDelegate.GPU }
        assertEquals(active.attemptEpoch, quarantine.attemptEpoch)
        assertEquals(JournalReason.RECOVERED_ACTIVE, quarantine.reason)
        assertFalse(quarantine.cacheInvalidated)
        assertTrue(
            recovered.effects.contains(RecoveryAttemptEffect.QuarantineCacheDeletionRequired),
        )
        assertValid(recovered.next)

        val teardownRecovery = applied(
            RecoveryJournalAttemptReducer.recoverInterrupted(teardown(initialFresh())),
        ).next
        assertEquals(
            JournalReason.RECOVERED_TEARDOWN_PENDING,
            teardownRecovery.entries.single().reason,
        )
    }

    @Test
    fun interruptedManualAttemptConsumesEveryLiveRetryEntryEvenBetweenRoutes() {
        val base = RecoveryJournalTestFixtures.scope()
        val current = payload(
            lastEpoch = 1uL,
            entries = listOf(quarantineEntry(scope = base, delegate = ProbeDelegate.GPU)),
        )
        val manual = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                current,
                base,
                ProbeDelegate.GPU,
            ),
        ).next
        val activeRecovered = applied(RecoveryJournalAttemptReducer.recoverInterrupted(manual)).next
        assertNull(activeRecovered.active)
        assertNull(activeRecovered.manualRetryContext)
        val consumed = activeRecovered.entries.filter { it.state == JournalEntryState.RETRY_CONSUMED }
        assertEquals(2, consumed.size)
        assertTrue(consumed.all { it.reason == JournalReason.MANUAL_RETRY_INTERRUPTED })
        assertValid(activeRecovered)

        val between = cleanIntermediate(manual).next
        val betweenRecovered = applied(RecoveryJournalAttemptReducer.recoverInterrupted(between)).next
        assertNull(betweenRecovered.manualRetryContext)
        assertEquals(JournalEntryState.RETRY_CONSUMED, betweenRecovered.entries.single {
            it.delegate == ProbeDelegate.GPU
        }.state)
        assertValid(betweenRecovered)

        val terminalBetween = cleanTerminal(manual).next
        assertNull(terminalBetween.active)
        assertEquals(JournalEntryState.TERMINAL_THIS_ATTEMPT, terminalBetween.entries.single {
            it.delegate == ProbeDelegate.CPU
        }.state)
        val terminalRecovered = applied(
            RecoveryJournalAttemptReducer.recoverInterrupted(terminalBetween),
        ).next
        assertNull(terminalRecovered.manualRetryContext)
        assertEquals(2, terminalRecovered.entries.size)
        assertTrue(terminalRecovered.entries.all {
            it.state == JournalEntryState.RETRY_CONSUMED &&
                it.reason == JournalReason.MANUAL_RETRY_INTERRUPTED
        })
        assertValid(terminalRecovered)
    }

    @Test
    fun pendingAndModeControlBlockAttemptInterpretation() {
        val pending = RecoveryJournalTestFixtures.manualRetryPayload(pending = true)
        assertRejected(
            RecoveryJournalAttemptReducer.recoverInterrupted(pending),
            RecoveryAttemptRejection.PENDING_STORE_COMMIT_BLOCKS,
        )
        val controlled = payload(lastEpoch = 1uL, entries = emptyList()).copy(
            modeControl = ModeControlV5.ResetRequested(1uL),
        )
        assertValid(controlled)
        assertRejected(
            RecoveryJournalAttemptReducer.resumeProofPrefix(
                controlled,
                RecoveryJournalTestFixtures.scope(),
            ),
            RecoveryAttemptRejection.MODE_CONTROL_BLOCKS,
        )

        val poisonedActive = initialFresh().copy(
            modeControl =
                ModeControlV5.PostSealModePoison(
                    reason = PostSealModePoisonReason.RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL,
                    cacheInvalidated = true,
                ),
        )
        assertValid(poisonedActive)
        assertRejected(
            RecoveryJournalAttemptReducer.markTeardownPending(
                poisonedActive,
                requireNotNull(poisonedActive.active).key(),
            ),
            RecoveryAttemptRejection.MODE_CONTROL_BLOCKS,
        )
    }

    @Test
    fun wrongRetryContextIsRejectedBeforeAnyLifecycleMutation() {
        val manual = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                payload(
                    lastEpoch = 1uL,
                    entries = listOf(quarantineEntry()),
                ),
                RecoveryJournalTestFixtures.scope(),
                ProbeDelegate.CPU,
            ),
        ).next
        val active = requireNotNull(manual.active)
        val invalid = manual.copy(
            active = active.copy(retryContextId = RecoveryJournalTestFixtures.digest(224)),
        )
        assertRejected(
            RecoveryJournalAttemptReducer.markTeardownPending(invalid, active.key()),
            RecoveryAttemptRejection.INVALID_CURRENT_PAYLOAD,
        )
        assertRejected(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                manual,
                RecoveryJournalTestFixtures.scope(),
                ProbeDelegate.CPU,
            ),
            RecoveryAttemptRejection.RETRY_CONTEXT_BLOCKS,
        )
    }

    @Test
    fun capacityEightIsReservedBeforeManualDistinctRouteButSameMarkerKeyFits() {
        val base = RecoveryJournalTestFixtures.scope(32)
        val targetGpu = quarantineEntry(scope = base, delegate = ProbeDelegate.GPU)
        val entries = mutableListOf(targetGpu)
        repeat(7) { index ->
            entries += quarantineEntry(
                scope = RecoveryJournalTestFixtures.scope(64 + index * 32),
                delegate = if (index % 2 == 0) ProbeDelegate.CPU else ProbeDelegate.GPU,
            )
        }
        assertRejected(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                payload(lastEpoch = 1uL, entries = entries),
                base,
                ProbeDelegate.GPU,
            ),
            RecoveryAttemptRejection.CAPACITY_EXCEEDED,
        )

        val targetCpu = quarantineEntry(scope = base, delegate = ProbeDelegate.CPU)
        val sameKeyEntries = entries.toMutableList().also { it[0] = targetCpu }
        val fits = applied(
            RecoveryJournalAttemptReducer.authorizeManualRetry(
                payload(lastEpoch = 1uL, entries = sameKeyEntries),
                base,
                ProbeDelegate.CPU,
            ),
        )
        assertEquals(8, fits.next.entries.size)
        assertEquals(ProbeDelegate.CPU, requireNotNull(fits.next.active).delegate)
        assertValid(fits.next)
    }

    @Test
    fun effectsAndContinuationCollectionsAreImmutableAndSensitiveValuesAreRedacted() {
        val startResult = applied(
            RecoveryJournalAttemptReducer.startInitialFresh(
                emptyPayload(),
                RecoveryJournalTestFixtures.scope(),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val mutableEffects = startResult.effects as MutableList<RecoveryAttemptEffect>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableEffects.clear()
        }

        val cleared = cleanIntermediate(startResult.next)
        val continuation = continuation(cleared)
        @Suppress("UNCHECKED_CAST")
        val mutableRoutes = continuation.allowedRoutes as MutableList<RecoveryAttemptRoute>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableRoutes.clear()
        }
        val printable =
            listOf(
                requireNotNull(startResult.next.active).key().toString(),
                continuation.toString(),
                startResult.toString(),
            ).joinToString()
        assertFalse(printable.contains(RecoveryJournalTestFixtures.scope().digest.toLowerHex()))
        assertFalse(printable.contains("attemptEpoch=1"))
    }

    @Test
    fun noPublicSameEpochQuarantineRetryOrDurableReceiptApiExists() {
        val publicMethods = RecoveryJournalAttemptReducer::class.java.methods.filter {
            Modifier.isPublic(it.modifiers) && it.declaringClass == RecoveryJournalAttemptReducer::class.java
        }
        assertFalse(publicMethods.any { it.name.contains("sameEpoch", ignoreCase = true) })
        assertFalse(publicMethods.any { method ->
            method.name.contains("retry", ignoreCase = true) &&
                method.parameterTypes.any { it == Boolean::class.javaPrimitiveType }
        })
        assertEquals(
            setOf("authorizeManualRetry"),
            publicMethods.filter { it.name.contains("retry", ignoreCase = true) }
                .map { it.name.substringBefore('-') }
                .toSet(),
        )
    }

    private fun driveToGpuSelected(): RecoveryJournalPayloadV5 {
        val cpuMeasured = cleanIntermediate(initialFresh())
        val gpuCandidate = beginOnly(cpuMeasured)
        val gpuMeasured = cleanIntermediate(gpuCandidate)
        return begin(
            gpuMeasured,
            RecoveryAttemptRoute(ProbeDelegate.GPU, JournalRouteRole.SELECTED),
        )
    }

    private fun initialFresh(): RecoveryJournalPayloadV5 =
        applied(
            RecoveryJournalAttemptReducer.startInitialFresh(
                emptyPayload(),
                RecoveryJournalTestFixtures.scope(),
            ),
        ).next

    private fun emptyPayload(): RecoveryJournalPayloadV5 =
        RecoveryJournalPayloadV5.empty(
            recoveryBuildId = RecoveryJournalTestFixtures.runtime(),
            mode = RecoveryJournalMode.SOLO,
        )

    private fun teardown(current: RecoveryJournalPayloadV5): RecoveryJournalPayloadV5 {
        val key = requireNotNull(current.active).key()
        return applied(RecoveryJournalAttemptReducer.markTeardownPending(current, key)).next
    }

    private fun cleanIntermediate(
        current: RecoveryJournalPayloadV5,
    ): RecoveryAttemptReductionResult.Applied {
        val teardown = teardown(current)
        val key = requireNotNull(teardown.active).key()
        return applied(
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                teardown,
                key,
                RecoveryClosureTestFixture.cleanAuthority(
                    teardown,
                    key,
                    RouteAttemptOutcome.MEASURED,
                ),
            ),
        )
    }

    private fun cleanTerminal(
        current: RecoveryJournalPayloadV5,
        reason: TerminalProofReason = TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY,
    ): RecoveryAttemptReductionResult.Applied {
        val teardown = teardown(current)
        val active = requireNotNull(teardown.active)
        val key = active.key()
        val terminalProof = proof(active.delegate, active.role, reason)
        return applied(
            if (reason == TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY) {
                RecoveryJournalAttemptReducer.recordCleanTerminal(
                    teardown,
                    key,
                    terminalProof,
                    RecoveryClosureTestFixture.syntheticPreNativeAuthorityForReducerOnly(
                        teardown,
                        key,
                    ),
                )
            } else {
                RecoveryJournalAttemptReducer.recordCleanTerminal(
                    teardown,
                    key,
                    terminalProof,
                    RecoveryClosureTestFixture.cleanAuthority(
                        teardown,
                        key,
                        RouteAttemptOutcome.CLEAN_TERMINAL,
                    ),
                )
            },
        )
    }

    private fun beginOnly(result: RecoveryAttemptReductionResult.Applied): RecoveryJournalPayloadV5 =
        begin(result, continuation(result).allowedRoutes.single())

    private fun begin(
        result: RecoveryAttemptReductionResult.Applied,
        route: RecoveryAttemptRoute,
    ): RecoveryJournalPayloadV5 =
        applied(
            RecoveryJournalAttemptReducer.beginPermittedRoute(
                result.next,
                continuation(result),
                route,
            ),
        ).next

    private fun continuation(
        result: RecoveryAttemptReductionResult.Applied,
    ): RecoveryRouteContinuation =
        result.effects.filterIsInstance<RecoveryAttemptEffect.ContinueWith>()
            .single()
            .continuation

    private fun ActiveV5.key(): RecoveryAttemptRouteKey =
        RecoveryAttemptRouteKey(
            probeBaseScopeId = probeBaseScopeId,
            attemptEpoch = attemptEpoch,
            delegate = delegate,
            role = role,
        )

    private fun proofRoles(
        payload: RecoveryJournalPayloadV5,
    ): Set<Pair<ProbeDelegate, JournalRouteRole>> =
        payload.entries.mapNotNull { entry ->
            entry.terminalEvidence?.let { it.delegate to it.role }
        }.toSet()

    private fun assertWindowComplete(result: RecoveryAttemptReductionResult.Applied) {
        assertTrue(result.effects.contains(RecoveryAttemptEffect.AttemptWindowComplete))
        assertFalse(result.effects.any { it is RecoveryAttemptEffect.ContinueWith })
        assertNull(result.next.active)
        assertValid(result.next)
    }

    private fun payload(
        lastEpoch: ULong,
        entries: List<JournalEntryV5>,
    ): RecoveryJournalPayloadV5 =
        RecoveryJournalPayloadV5(
            schemaRevision = RecoveryJournalPayloadV5.SCHEMA_REVISION,
            recoveryBuildId = RecoveryJournalTestFixtures.runtime(),
            mode = RecoveryJournalMode.SOLO,
            lastEpoch = lastEpoch,
            active = null,
            manualRetryContext = null,
            entries = sortedEntries(entries),
            pendingStoreCommit = null,
            modeControl = ModeControlV5.None,
        ).also(::assertValid)

    private fun sortedEntries(entries: List<JournalEntryV5>): List<JournalEntryV5> =
        entries.sortedWith(
            compareBy<JournalEntryV5> { it.probeBaseScopeId.digest.toLowerHex() }
                .thenBy { it.delegate.wireValue },
        )

    private fun terminalEntry(
        scope: ProbeBaseScopeId = RecoveryJournalTestFixtures.scope(),
        epoch: ULong = 1uL,
        delegate: ProbeDelegate = ProbeDelegate.CPU,
        role: JournalRouteRole = JournalRouteRole.CANDIDATE,
    ): JournalEntryV5 =
        RecoveryJournalTestFixtures.terminalEntry(
            scope = scope,
            epoch = epoch,
            delegate = delegate,
            role = role,
        )

    private fun quarantineEntry(
        scope: ProbeBaseScopeId = RecoveryJournalTestFixtures.scope(),
        epoch: ULong = 1uL,
        delegate: ProbeDelegate = ProbeDelegate.CPU,
        cacheInvalidated: Boolean = true,
    ): JournalEntryV5 =
        RecoveryJournalTestFixtures.quarantineEntry(
            scope = scope,
            epoch = epoch,
            delegate = delegate,
            cacheInvalidated = cacheInvalidated,
        )

    private fun consumedEntry(
        scope: ProbeBaseScopeId,
        epoch: ULong,
        delegate: ProbeDelegate,
    ): JournalEntryV5 =
        RecoveryJournalTestFixtures.consumedEntry(
            scope = scope,
            epoch = epoch,
            delegate = delegate,
        )

    private fun proof(
        delegate: ProbeDelegate,
        role: JournalRouteRole,
        reason: TerminalProofReason = TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY,
    ): TerminalProofV1 =
        RecoveryJournalTestFixtures.proof(
            delegate = delegate,
            role = role,
            reason = reason,
        )

    private fun applied(
        result: RecoveryAttemptReductionResult,
    ): RecoveryAttemptReductionResult.Applied {
        assertTrue("Expected Applied but was $result", result is RecoveryAttemptReductionResult.Applied)
        return result as RecoveryAttemptReductionResult.Applied
    }

    private fun assertRejected(
        result: RecoveryAttemptReductionResult,
        expected: RecoveryAttemptRejection,
    ) {
        assertEquals(RecoveryAttemptReductionResult.Rejected(expected), result)
    }

    private fun assertValid(payload: RecoveryJournalPayloadV5) {
        val result = RecoveryJournalV5Codec.encodePayload(payload)
        assertTrue("Expected valid payload but was $result", result is CapabilityDomainResult.Valid)
    }

    private fun <T> valid(result: CapabilityDomainResult<T>): T =
        when (result) {
            is CapabilityDomainResult.Valid -> result.value
            is CapabilityDomainResult.Invalid -> error("Expected valid result: ${result.violations}")
        }
}
