package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.CanonicalManifestCodec
import com.motionarcade.vision.capability.domain.ProbeDelegate
import com.motionarcade.vision.capability.runtime.RouteAttemptOutcome
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPostSealPoisonPersistenceTest {
    @Test
    fun callbackBeforeDecisionCommitsTeardownPoisonAndDecisionMakesNoNewCommitClaim() {
        val scenario = scenario(9_101)

        assertTrue(closeLate(scenario.harness))

        val durable = requireNotNull(scenario.harness.poisonStorage.current())
        assertEquals(JournalActiveState.TEARDOWN_PENDING, durable.active?.state)
        assertTrue(durable.modeControl is ModeControlV5.PostSealModePoison)
        assertEquals(2, scenario.harness.poisonStorage.freshStrictReadCalls.get())
        assertEquals(1, scenario.harness.poisonStorage.checkedReplaceCalls.get())

        val decision =
            requireNotNull(
                RecoveryPostSealPoisonPersistenceBoundary.resolveForCheckedPersistence(
                    scenario.fence,
                ),
            )
        assertTrue(decision.alreadyDurablyCommitted)
        assertEquals(durable, decision.payload)
        assertEquals(payloadDigest(durable), decision.expectedOldPayloadSha256)
        assertNull(
            RecoveryPostSealPoisonPersistenceBoundary.resolveForCheckedPersistence(
                scenario.fence,
            ),
        )
    }

    @Test
    fun callbackAfterCleanDecisionPoisonsTeardownAndDoesNotRetroactivelyChangeDecision() {
        val scenario = scenario(9_102)
        val decision = resolveClean(scenario)

        assertTrue(closeLate(scenario.harness))

        val durable = requireNotNull(scenario.harness.poisonStorage.current())
        assertNull(decision.payload.active)
        assertFalse(decision.alreadyDurablyCommitted)
        assertEquals(JournalActiveState.TEARDOWN_PENDING, durable.active?.state)
        assertTrue(durable.modeControl is ModeControlV5.PostSealModePoison)
        assertEquals(ModeControlV5.None, decision.payload.modeControl)
    }

    @Test
    fun callbackAfterCleanWritePreservesEveryCleanFieldExceptModeControl() {
        val scenario = scenario(9_103)
        val decision = resolveClean(scenario)
        scenario.harness.poisonStorage.forceCurrent(decision.payload)

        assertTrue(closeLate(scenario.harness))

        val durable = requireNotNull(scenario.harness.poisonStorage.current())
        assertNull(durable.active)
        assertTrue(durable.modeControl is ModeControlV5.PostSealModePoison)
        assertOnlyModeControlChanged(decision.payload, durable)
    }

    @Test
    fun callbackAfterFirstServePoisonsCurrentCleanBytesWithoutRetroactiveServeClaim() {
        val scenario = scenario(9_104)
        val decision = resolveClean(scenario)
        scenario.harness.poisonStorage.forceCurrent(decision.payload)
        val firstServedSnapshot = requireNotNull(scenario.harness.poisonStorage.current())

        assertTrue(closeLate(scenario.harness))

        val durableAfterObservedCallback = requireNotNull(scenario.harness.poisonStorage.current())
        assertEquals(decision.payload, firstServedSnapshot)
        assertNotEquals(firstServedSnapshot, durableAfterObservedCallback)
        assertOnlyModeControlChanged(firstServedSnapshot, durableAfterObservedCallback)
        assertTrue(durableAfterObservedCallback.modeControl is ModeControlV5.PostSealModePoison)
    }

    @Test
    fun unrelatedFreshCurrentFailsWithoutCheckedReplace() {
        val scenario = scenario(9_105)
        resolveClean(scenario)
        val unrelated =
            scenario.harness.committedTeardown.copy(
                recoveryBuildId = RecoveryJournalTestFixtures.runtime(19_105),
            )
        scenario.harness.poisonStorage.forceCurrent(unrelated)

        assertFalse(closeLate(scenario.harness))

        assertEquals(unrelated, scenario.harness.poisonStorage.current())
        assertEquals(1, scenario.harness.poisonStorage.freshStrictReadCalls.get())
        assertEquals(0, scenario.harness.poisonStorage.checkedReplaceCalls.get())
    }

    @Test
    fun fsyncUncertaintyOrNonExactFreshRereadNeverClaimsCommit() {
        val fsyncUncertain = scenario(9_106)
        fsyncUncertain.harness.poisonStorage.nextWriteEvidence =
            RecoveryPostSealPoisonWriteEvidence.FAILED_OR_UNCERTAIN
        assertFalse(closeLate(fsyncUncertain.harness))
        assertEquals(
            fsyncUncertain.harness.committedTeardown,
            fsyncUncertain.harness.poisonStorage.current(),
        )
        assertNull(
            RecoveryPostSealPoisonPersistenceBoundary.resolveForCheckedPersistence(
                fsyncUncertain.fence,
            ),
        )

        val changedBeforeReread = scenario(9_107)
        val unrelated =
            changedBeforeReread.harness.committedTeardown.copy(
                recoveryBuildId = RecoveryJournalTestFixtures.runtime(19_107),
            )
        changedBeforeReread.harness.poisonStorage.mutateAfterSuccessfulReplace = { unrelated }
        assertFalse(closeLate(changedBeforeReread.harness))
        assertEquals(2, changedBeforeReread.harness.poisonStorage.freshStrictReadCalls.get())
        assertEquals(1, changedBeforeReread.harness.poisonStorage.checkedReplaceCalls.get())
        assertEquals(unrelated, changedBeforeReread.harness.poisonStorage.current())
        assertNull(
            RecoveryPostSealPoisonPersistenceBoundary.resolveForCheckedPersistence(
                changedBeforeReread.fence,
            ),
        )
    }

    @Test
    fun fenceIssuerIsPrivateNestedAndPostSealBoundaryHasNoMintBridge() {
        assertTrue(
            RecoveryPostSealPoisonPersistenceBoundary::class.java.declaredMethods.none {
                it.name.contains("issueCleanFence")
            },
        )
        val fenceImplementation =
            Class.forName(
                "com.motionarcade.vision.capability.recovery." +
                    "RecoveryCleanClosureBoundary\$IssuedRecoveryCleanJournalPersistenceFence",
            )
        assertTrue(Modifier.isPrivate(fenceImplementation.modifiers))
    }

    private fun scenario(seed: Int): Scenario {
        val harness =
            RecoveryClosureTestFixture.harness(
                routeKey(seed),
                RouteAttemptOutcome.MEASURED,
            )
        val authority = requireNotNull(harness.completeCleanClosure())
        val reduction =
            RecoveryJournalAttemptReducer.completeCleanIntermediate(
                harness.committedTeardown,
                harness.key,
                authority,
            ) as RecoveryAttemptReductionResult.Applied
        assertNull(reduction.next.active)
        return Scenario(
            harness,
            requireNotNull(reduction.cleanPersistenceFence),
        )
    }

    private fun resolveClean(scenario: Scenario): RecoveryJournalPersistenceDecision {
        val decision =
            requireNotNull(
                RecoveryPostSealPoisonPersistenceBoundary.resolveForCheckedPersistence(
                    scenario.fence,
                ),
            )
        assertFalse(decision.alreadyDurablyCommitted)
        assertEquals(ModeControlV5.None, decision.payload.modeControl)
        assertNull(decision.payload.active)
        return decision
    }

    private fun closeLate(harness: RecoveryClosureTestFixture.Harness): Boolean =
        RecoveryCleanClosureBoundary.closeLateCallbackOutput(
            harness.session,
            RecoveryClosureTestFixture.mpImage(),
        )

    private fun assertOnlyModeControlChanged(
        clean: RecoveryJournalPayloadV5,
        poisoned: RecoveryJournalPayloadV5,
    ) {
        assertEquals(clean, poisoned.copy(modeControl = clean.modeControl))
    }

    private fun payloadDigest(payload: RecoveryJournalPayloadV5) =
        when (val encoded = RecoveryJournalV5Codec.encodePayload(payload)) {
            is CapabilityDomainResult.Valid -> CanonicalManifestCodec.sha256(encoded.value)
            is CapabilityDomainResult.Invalid -> error(encoded.violations.toString())
        }

    private fun routeKey(seed: Int): RecoveryAttemptRouteKey =
        RecoveryAttemptRouteKey(
            probeBaseScopeId = RecoveryJournalTestFixtures.scope(seed),
            attemptEpoch = seed.toULong(),
            delegate = ProbeDelegate.CPU,
            role = JournalRouteRole.CANDIDATE,
        )

    private data class Scenario(
        val harness: RecoveryClosureTestFixture.Harness,
        val fence: RecoveryCleanJournalPersistenceFence,
    )
}
