package com.motionarcade.vision.capability.recovery

import com.motionarcade.vision.capability.domain.CapabilityDomainResult
import com.motionarcade.vision.capability.domain.ProbeDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class RecoveryJournalModelValidationTest {
    @Test
    fun uint64ZeroHighBitAndMaximumBoundariesAreExact() {
        val empty = RecoveryJournalPayloadV5.empty(RecoveryJournalTestFixtures.runtime(), RecoveryJournalMode.SOLO)
        assertValid(empty)

        val highBit = 0x8000_0000_0000_0000uL
        assertRoundTrip(payloadWith(listOf(RecoveryJournalTestFixtures.quarantineEntry(epoch = highBit)), highBit))
        assertRoundTrip(payloadWith(listOf(RecoveryJournalTestFixtures.quarantineEntry(epoch = ULong.MAX_VALUE)), ULong.MAX_VALUE))

        assertInvalid(payloadWith(listOf(RecoveryJournalTestFixtures.quarantineEntry(epoch = 0uL)), 0uL))
        assertInvalid(payloadWith(listOf(RecoveryJournalTestFixtures.quarantineEntry(epoch = 2uL)), 1uL))

        val overflowTuple =
            ManualRetryContextV1(
                probeBaseScopeId = RecoveryJournalTestFixtures.scope(),
                targetDelegate = ProbeDelegate.CPU,
                originQuarantineEpoch = ULong.MAX_VALUE,
                originQuarantineReason = JournalReason.RECOVERED_ACTIVE,
                originQuarantineEntrySha256 = RecoveryJournalTestFixtures.digest(64),
                attemptEpoch = 0uL,
            )
        assertTrue(RecoveryJournalV5Codec.encodeManualRetryContext(overflowTuple) is CapabilityDomainResult.Invalid)
    }

    @Test
    fun gpuFallbackRolesAreRejectedInActiveAndTerminalProof() {
        val active =
            ActiveV5(
                probeBaseScopeId = RecoveryJournalTestFixtures.scope(),
                attemptEpoch = 1uL,
                delegate = ProbeDelegate.GPU,
                role = JournalRouteRole.FALLBACK_CANDIDATE,
                state = JournalActiveState.ACTIVE,
                retryUsed = false,
                retryContextId = null,
            )
        assertInvalid(payloadWith(emptyList(), 1uL, active = active))

        val proof =
            RecoveryJournalTestFixtures.proof(
                delegate = ProbeDelegate.GPU,
                role = JournalRouteRole.FALLBACK_SELECTED,
            )
        assertTrue(RecoveryJournalV5Codec.encodeTerminalProof(proof) is CapabilityDomainResult.Invalid)
    }

    @Test
    fun terminalAndNonterminalProofRulesFailClosed() {
        val terminal = RecoveryJournalTestFixtures.terminalEntry()
        assertValid(payloadWith(listOf(terminal), 1uL))
        assertInvalid(payloadWith(listOf(terminal.copy(terminalEvidence = null)), 1uL))
        assertInvalid(
            payloadWith(
                listOf(
                    RecoveryJournalTestFixtures.quarantineEntry().copy(
                        terminalEvidence = RecoveryJournalTestFixtures.proof(),
                    ),
                ),
                1uL,
            ),
        )
        assertInvalid(
            payloadWith(
                listOf(
                    terminal.copy(
                        terminalEvidence =
                            RecoveryJournalTestFixtures.proof(
                                reason = TerminalProofReason.DETECT_EXCEPTION_RETURNED,
                            ),
                    ),
                ),
                1uL,
            ),
        )
        assertTrue(
            RecoveryJournalV5Codec.encodeTerminalProof(
                RecoveryJournalTestFixtures.proof().copy(
                    closure = TerminalClosure.POST_CREATE_ALL_OWNERS_RETURNED_CLEAN,
                ),
            ) is CapabilityDomainResult.Invalid,
        )
        assertTrue(
            RecoveryJournalV5Codec.encodeTerminalProof(
                RecoveryJournalTestFixtures.proof().copy(resolvedOwnerMask = 0xfe),
            ) is CapabilityDomainResult.Invalid,
        )
    }

    @Test
    fun entryStateReasonTableIsExhaustiveAndSealed() {
        val terminalTemplate = RecoveryJournalTestFixtures.terminalEntry()
        val quarantineTemplate = RecoveryJournalTestFixtures.quarantineEntry()
        val consumedTemplate = RecoveryJournalTestFixtures.consumedEntry()
        val manualTemplate = RecoveryJournalTestFixtures.manualRetryPayload().entries.single()

        JournalReason.entries.forEach { reason ->
            val terminalAllowed = reason.wireValue in 0..2
            val terminal =
                terminalTemplate.copy(
                    reason = reason,
                    terminalEvidence =
                        terminalTemplate.terminalEvidence?.copy(
                            reason = TerminalProofReason.entries.firstOrNull { it.wireValue == reason.wireValue }
                                ?: TerminalProofReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY,
                            closure =
                                if (reason == JournalReason.OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY) {
                                    TerminalClosure.PRE_CREATE_NO_NATIVE_ENTRY
                                } else {
                                    TerminalClosure.POST_CREATE_ALL_OWNERS_RETURNED_CLEAN
                                },
                        ),
                )
            assertEquals(terminalAllowed, RecoveryJournalV5Codec.encodeEntry(terminal) is CapabilityDomainResult.Valid)
            if (terminalAllowed) assertEntryRoundTrip(terminal)

            val quarantine = quarantineTemplate.copy(reason = reason)
            assertEquals(reason.wireValue in 3..20, RecoveryJournalV5Codec.encodeEntry(quarantine) is CapabilityDomainResult.Valid)
            if (reason.wireValue in 3..20) assertEntryRoundTrip(quarantine)

            val consumed = consumedTemplate.copy(reason = reason)
            assertEquals(reason.wireValue in 21..24, RecoveryJournalV5Codec.encodeEntry(consumed) is CapabilityDomainResult.Valid)
            if (reason.wireValue in 21..24) assertEntryRoundTrip(consumed)

            val manual = manualTemplate.copy(reason = reason)
            assertEquals(reason.wireValue == 25, RecoveryJournalV5Codec.encodeEntry(manual) is CapabilityDomainResult.Valid)
            if (reason.wireValue == 25) assertEntryRoundTrip(manual)
        }
        assertEntryRoundTrip(quarantineTemplate.copy(cacheInvalidated = false))
    }

    @Test
    fun activePendingAndControlLegalTuplesRoundTripExhaustively() {
        ProbeDelegate.entries.filter { it != ProbeDelegate.NONE }.forEach { delegate ->
            JournalRouteRole.entries.filterNot { role ->
                delegate == ProbeDelegate.GPU &&
                    role in setOf(JournalRouteRole.FALLBACK_CANDIDATE, JournalRouteRole.FALLBACK_SELECTED)
            }.forEach { role ->
                JournalActiveState.entries.forEach { state ->
                    val active =
                        ActiveV5(
                            probeBaseScopeId = RecoveryJournalTestFixtures.scope(),
                            attemptEpoch = 1uL,
                            delegate = delegate,
                            role = role,
                            state = state,
                            retryUsed = false,
                            retryContextId = null,
                        )
                    val prefix =
                        if (role in setOf(JournalRouteRole.FALLBACK_CANDIDATE, JournalRouteRole.FALLBACK_SELECTED)) {
                            listOf(
                                RecoveryJournalTestFixtures.terminalEntry(
                                    scope = active.probeBaseScopeId,
                                    delegate = ProbeDelegate.GPU,
                                    role = JournalRouteRole.SELECTED,
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    assertRoundTrip(payloadWith(prefix, 1uL, active = active))
                }
            }
        }

        ProbeDelegate.entries.forEach { selected ->
            ExpectedOldFileState.entries.forEach { oldState ->
                val pending =
                    PendingStoreCommitV5(
                        probeBaseScopeId = RecoveryJournalTestFixtures.scope(),
                        attemptEpoch = 1uL,
                        capabilityResultId = RecoveryJournalTestFixtures.result(),
                        selectedDelegate = selected,
                        retryUsed = false,
                        retryContextId = null,
                        expectedOldFileState = oldState,
                        expectedOldFileLength = if (oldState == ExpectedOldFileState.ABSENT) 0uL else 1uL,
                        expectedOldFileSha256 =
                            if (oldState == ExpectedOldFileState.ABSENT) null else RecoveryJournalTestFixtures.digest(64),
                        intendedNewFileLength = 1uL,
                        intendedNewFileSha256 = RecoveryJournalTestFixtures.digest(96),
                        intendedNewRecordSha256 = RecoveryJournalTestFixtures.digest(128),
                    )
                assertRoundTrip(payloadWith(emptyList(), 1uL).copy(pendingStoreCommit = pending))
            }
        }

        val controls =
            listOf(
                ModeControlV5.None,
                ModeControlV5.ResetRequested(1uL),
                ModeControlV5.ResetRunning(1uL),
                ModeControlV5.PostSealModePoison(
                    PostSealModePoisonReason.RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL,
                    false,
                ),
                ModeControlV5.PostSealModePoison(
                    PostSealModePoisonReason.RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL,
                    true,
                ),
            )
        controls.forEach { control -> assertRoundTrip(payloadWith(emptyList(), 1uL, modeControl = control)) }
        assertRoundTrip(RecoveryJournalTestFixtures.manualRetryPayload(activeDelegate = ProbeDelegate.CPU))
        assertRoundTrip(RecoveryJournalTestFixtures.manualRetryPayload(activeDelegate = ProbeDelegate.GPU))
        assertRoundTrip(RecoveryJournalTestFixtures.manualRetryPayload(pending = true))
    }

    @Test
    fun retryContextRequiresExactTargetEpochAndHashWhileConsumedIsAbsorbing() {
        val manual = RecoveryJournalTestFixtures.manualRetryPayload(activeDelegate = ProbeDelegate.GPU)
        assertValid(manual)

        val invalidOrigin = checkNotNull(manual.manualRetryContext).copy(
            originQuarantineEntrySha256 = RecoveryJournalTestFixtures.digest(7),
        )
        assertTrue(
            RecoveryJournalV5Codec.encodeManualRetryContext(invalidOrigin) is CapabilityDomainResult.Invalid,
        )

        assertInvalid(manual.copy(manualRetryContext = null))
        assertInvalid(
            manual.copy(
                entries =
                    listOf(
                        manual.entries.single().copy(
                            retryContextId = RecoveryJournalTestFixtures.digest(224),
                        ),
                    ),
            ),
        )
        val retryZeroActive = checkNotNull(manual.active).copy(retryUsed = false, retryContextId = null)
        assertInvalid(manual.copy(active = retryZeroActive))

        val context = checkNotNull(manual.manualRetryContext)
        val contextId = valid(RecoveryJournalV5Codec.manualRetryContextId(context))
        val extraManualMarker = manual.entries.single().copy(delegate = ProbeDelegate.GPU)
        assertInvalid(manual.copy(active = null, entries = listOf(manual.entries.single(), extraManualMarker)))
        val staleTerminal =
            RecoveryJournalTestFixtures.terminalEntry(
                scope = context.probeBaseScopeId,
                epoch = 1uL,
                delegate = ProbeDelegate.GPU,
                retryUsed = false,
                retryContextId = null,
            )
        assertInvalid(manual.copy(active = null, entries = listOf(manual.entries.single(), staleTerminal)))
        val targetTerminal =
            RecoveryJournalTestFixtures.terminalEntry(
                scope = context.probeBaseScopeId,
                epoch = context.attemptEpoch,
                delegate = context.targetDelegate,
                retryUsed = true,
                retryContextId = contextId,
            )
        assertValid(manual.copy(active = null, entries = listOf(targetTerminal)))

        val consumed = RecoveryJournalTestFixtures.consumedEntry()
        assertValid(payloadWith(listOf(consumed), 2uL))
    }

    @Test
    fun everyManualRetryOriginReasonAndTargetDelegateRoundTrips() {
        val base = RecoveryJournalTestFixtures.scope()
        val quarantineReasons = JournalReason.entries.filter { it.wireValue in 3..20 }
        listOf(ProbeDelegate.CPU, ProbeDelegate.GPU).forEach { target ->
            quarantineReasons.forEach { reason ->
                val origin =
                    RecoveryJournalTestFixtures.quarantineEntry(
                        scope = base,
                        epoch = 1uL,
                        delegate = target,
                        reason = reason,
                        cacheInvalidated = true,
                    )
                val context =
                    ManualRetryContextV1(
                        probeBaseScopeId = base,
                        targetDelegate = target,
                        originQuarantineEpoch = 1uL,
                        originQuarantineReason = reason,
                        originQuarantineEntrySha256 = valid(RecoveryJournalV5Codec.entrySha256(origin)),
                        attemptEpoch = 2uL,
                    )
                val contextId = valid(RecoveryJournalV5Codec.manualRetryContextId(context))
                val marker =
                    JournalEntryV5(
                        probeBaseScopeId = base,
                        attemptEpoch = 2uL,
                        delegate = target,
                        state = JournalEntryState.MANUAL_RETRY_ACTIVE,
                        reason = JournalReason.MANUAL_RETRY_AUTHORIZED,
                        retryUsed = true,
                        cacheInvalidated = true,
                        retryContextId = contextId,
                        terminalEvidence = null,
                    )
                val payload =
                    RecoveryJournalPayloadV5(
                        schemaRevision = RecoveryJournalPayloadV5.SCHEMA_REVISION,
                        recoveryBuildId = RecoveryJournalTestFixtures.runtime(),
                        mode = RecoveryJournalMode.SOLO,
                        lastEpoch = 2uL,
                        active = null,
                        manualRetryContext = context,
                        entries = listOf(marker),
                        pendingStoreCommit = null,
                        modeControl = ModeControlV5.None,
                    )
                assertRoundTrip(payload)
            }
        }
    }

    @Test
    fun terminalProofSetMustBeOneExactFiniteFallbackPrefix() {
        val base = RecoveryJournalTestFixtures.scope()
        val gpuCandidateTerminal =
            RecoveryJournalTestFixtures.terminalEntry(
                scope = base,
                delegate = ProbeDelegate.GPU,
                role = JournalRouteRole.CANDIDATE,
            )
        val cpuSelectedTerminal =
            RecoveryJournalTestFixtures.terminalEntry(
                scope = base,
                delegate = ProbeDelegate.CPU,
                role = JournalRouteRole.SELECTED,
            )
        assertValid(payloadWith(listOf(cpuSelectedTerminal, gpuCandidateTerminal), 1uL))
        assertInvalid(payloadWith(listOf(cpuSelectedTerminal), 1uL))

        val gpuSelectedTerminal =
            RecoveryJournalTestFixtures.terminalEntry(
                scope = base,
                delegate = ProbeDelegate.GPU,
                role = JournalRouteRole.SELECTED,
            )
        assertInvalid(payloadWith(listOf(cpuSelectedTerminal, gpuSelectedTerminal), 1uL))
        assertInvalid(
            payloadWith(
                listOf(cpuSelectedTerminal.copy(attemptEpoch = 2uL), gpuCandidateTerminal),
                2uL,
            ),
        )

        val cpuFallbackSelectedTerminal =
            RecoveryJournalTestFixtures.terminalEntry(
                scope = base,
                delegate = ProbeDelegate.CPU,
                role = JournalRouteRole.FALLBACK_SELECTED,
            )
        assertValid(payloadWith(listOf(cpuFallbackSelectedTerminal, gpuSelectedTerminal), 1uL))
    }

    @Test
    fun activeRouteMustContinueItsExactFiniteProofPrefix() {
        val base = RecoveryJournalTestFixtures.scope()
        val cpuCandidate =
            RecoveryJournalTestFixtures.terminalEntry(
                scope = base,
                delegate = ProbeDelegate.CPU,
                role = JournalRouteRole.CANDIDATE,
            )
        val gpuCandidate =
            RecoveryJournalTestFixtures.terminalEntry(
                scope = base,
                delegate = ProbeDelegate.GPU,
                role = JournalRouteRole.CANDIDATE,
            )
        val gpuSelected =
            RecoveryJournalTestFixtures.terminalEntry(
                scope = base,
                delegate = ProbeDelegate.GPU,
                role = JournalRouteRole.SELECTED,
            )

        assertRoundTrip(payloadWith(listOf(cpuCandidate), 1uL, active = active(base, ProbeDelegate.GPU, JournalRouteRole.CANDIDATE)))
        assertRoundTrip(payloadWith(listOf(cpuCandidate), 1uL, active = active(base, ProbeDelegate.GPU, JournalRouteRole.SELECTED)))
        assertRoundTrip(payloadWith(listOf(gpuCandidate), 1uL, active = active(base, ProbeDelegate.CPU, JournalRouteRole.CANDIDATE)))
        assertRoundTrip(payloadWith(listOf(gpuCandidate), 1uL, active = active(base, ProbeDelegate.CPU, JournalRouteRole.SELECTED)))
        assertRoundTrip(payloadWith(listOf(gpuSelected), 1uL, active = active(base, ProbeDelegate.CPU, JournalRouteRole.FALLBACK_CANDIDATE)))
        assertInvalid(payloadWith(listOf(cpuCandidate), 1uL, active = active(base, ProbeDelegate.CPU, JournalRouteRole.SELECTED)))
        assertInvalid(payloadWith(listOf(gpuCandidate), 1uL, active = active(base, ProbeDelegate.GPU, JournalRouteRole.SELECTED)))
        assertInvalid(payloadWith(listOf(cpuCandidate, gpuCandidate), 1uL, active = active(base, ProbeDelegate.CPU, JournalRouteRole.SELECTED)))

        val unreconciledCpuQuarantine =
            RecoveryJournalTestFixtures.quarantineEntry(
                scope = base,
                delegate = ProbeDelegate.CPU,
                cacheInvalidated = false,
            )
        val gpuActive = active(base, ProbeDelegate.GPU, JournalRouteRole.CANDIDATE)
        assertInvalid(payloadWith(listOf(unreconciledCpuQuarantine), 1uL, active = gpuActive))
        assertInvalid(
            payloadWith(
                listOf(
                    unreconciledCpuQuarantine.copy(
                        probeBaseScopeId = RecoveryJournalTestFixtures.scope(96),
                    ),
                ),
                1uL,
                active = gpuActive,
            ),
        )
        assertRoundTrip(
            payloadWith(
                listOf(unreconciledCpuQuarantine.copy(cacheInvalidated = true)),
                1uL,
                active = gpuActive,
            ),
        )

        assertValid(payloadWith(listOf(cpuCandidate), 1uL))
    }

    @Test
    fun everyFiniteFallbackProofSetCombinationHasAnExactVerdict() {
        val slots =
            listOf(
                ProofSlot("CPU_CAND", ProbeDelegate.CPU, JournalRouteRole.CANDIDATE),
                ProofSlot("GPU_CAND", ProbeDelegate.GPU, JournalRouteRole.CANDIDATE),
                ProofSlot("CPU_SELECTED", ProbeDelegate.CPU, JournalRouteRole.SELECTED),
                ProofSlot("GPU_SELECTED", ProbeDelegate.GPU, JournalRouteRole.SELECTED),
                ProofSlot("CPU_FALLBACK_CAND", ProbeDelegate.CPU, JournalRouteRole.FALLBACK_CANDIDATE),
                ProofSlot("CPU_FALLBACK_SELECTED", ProbeDelegate.CPU, JournalRouteRole.FALLBACK_SELECTED),
            )
        val validSets =
            setOf(
                setOf("CPU_CAND"),
                setOf("GPU_CAND"),
                setOf("CPU_CAND", "GPU_CAND"),
                setOf("CPU_CAND", "GPU_SELECTED"),
                setOf("GPU_CAND", "CPU_SELECTED"),
                setOf("GPU_SELECTED"),
                setOf("GPU_SELECTED", "CPU_FALLBACK_CAND"),
                setOf("GPU_SELECTED", "CPU_FALLBACK_SELECTED"),
            )
        val base = RecoveryJournalTestFixtures.scope()

        for (mask in 1 until (1 shl slots.size)) {
            val selected = slots.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val names = selected.map(ProofSlot::name).toSet()
            val entries =
                selected.map { slot ->
                    RecoveryJournalTestFixtures.terminalEntry(
                        scope = base,
                        delegate = slot.delegate,
                        role = slot.role,
                    )
                }.sortedWith(
                    compareBy<JournalEntryV5> { it.delegate.wireValue }
                        .thenBy { checkNotNull(it.terminalEvidence).traceOrdinal },
                )
            val payload = payloadWith(entries, 1uL)
            if (names in validSets) {
                assertRoundTrip(payload)
            } else {
                assertInvalid(payload)
            }
        }
    }

    @Test
    fun onlyExactManualMarkerMayShareAnActiveKeyAndCapacityIsReserved() {
        assertValid(RecoveryJournalTestFixtures.manualRetryPayload(activeDelegate = ProbeDelegate.CPU))

        val quarantine = RecoveryJournalTestFixtures.quarantineEntry()
        val sameKeyActive =
            ActiveV5(
                probeBaseScopeId = quarantine.probeBaseScopeId,
                attemptEpoch = quarantine.attemptEpoch,
                delegate = quarantine.delegate,
                role = JournalRouteRole.CANDIDATE,
                state = JournalActiveState.ACTIVE,
                retryUsed = false,
                retryContextId = null,
            )
        assertInvalid(payloadWith(listOf(quarantine), 1uL, active = sameKeyActive))

        val full = (0 until 8).map { index ->
            RecoveryJournalTestFixtures.quarantineEntry(
                scope = RecoveryJournalTestFixtures.scope(index * 32),
                delegate = ProbeDelegate.CPU,
            )
        }
        assertRoundTrip(payloadWith(full, 1uL))
        val ninthKey =
            ActiveV5(
                probeBaseScopeId = RecoveryJournalTestFixtures.scope(255),
                attemptEpoch = 1uL,
                delegate = ProbeDelegate.GPU,
                role = JournalRouteRole.CANDIDATE,
                state = JournalActiveState.ACTIVE,
                retryUsed = false,
                retryContextId = null,
            )
        assertInvalid(payloadWith(full, 1uL, active = ninthKey))
    }

    @Test
    fun entriesRequireStrictUnsignedScopeThenDelegateOrderingAndUniqueKeys() {
        val low = RecoveryJournalTestFixtures.quarantineEntry(scope = RecoveryJournalTestFixtures.scope(0x7f))
        val high = RecoveryJournalTestFixtures.quarantineEntry(scope = RecoveryJournalTestFixtures.scope(0x80))
        assertValid(payloadWith(listOf(low, high), 1uL))
        assertInvalid(payloadWith(listOf(high, low), 1uL))
        assertInvalid(payloadWith(listOf(low, low), 1uL))

        val cpu = RecoveryJournalTestFixtures.quarantineEntry(delegate = ProbeDelegate.CPU)
        val gpu = RecoveryJournalTestFixtures.quarantineEntry(delegate = ProbeDelegate.GPU)
        assertValid(payloadWith(listOf(cpu, gpu), 1uL))
        assertInvalid(payloadWith(listOf(gpu, cpu), 1uL))
    }

    @Test
    fun pendingWholeFileTuplesAndActiveExclusionAreExact() {
        val manualPending = RecoveryJournalTestFixtures.manualRetryPayload(pending = true)
        assertValid(manualPending)
        val pending = checkNotNull(manualPending.pendingStoreCommit)

        assertInvalid(
            manualPending.copy(
                pendingStoreCommit = pending.copy(expectedOldFileLength = 1uL),
            ),
        )
        assertInvalid(
            manualPending.copy(
                pendingStoreCommit =
                    pending.copy(
                        expectedOldFileState = ExpectedOldFileState.HASH_PRESENT,
                        expectedOldFileLength = 0uL,
                        expectedOldFileSha256 = RecoveryJournalTestFixtures.digest(64),
                    ),
            ),
        )
        assertInvalid(manualPending.copy(pendingStoreCommit = pending.copy(intendedNewFileLength = 0uL)))
        assertInvalid(
            manualPending.copy(
                pendingStoreCommit =
                    pending.copy(intendedNewFileLength = RecoveryJournalV5Codec.MAXIMUM_MODE_STORE_FILE_BYTES + 1uL),
            ),
        )
        val active =
            ActiveV5(
                probeBaseScopeId = pending.probeBaseScopeId,
                attemptEpoch = pending.attemptEpoch,
                delegate = ProbeDelegate.GPU,
                role = JournalRouteRole.CANDIDATE,
                state = JournalActiveState.ACTIVE,
                retryUsed = true,
                retryContextId = pending.retryContextId,
            )
        assertInvalid(manualPending.copy(active = active))

        val staleTerminal = RecoveryJournalTestFixtures.terminalEntry(epoch = 1uL)
        val retryZeroPending =
            pending.copy(
                probeBaseScopeId = staleTerminal.probeBaseScopeId,
                attemptEpoch = 2uL,
                retryUsed = false,
                retryContextId = null,
            )
        assertInvalid(
            payloadWith(listOf(staleTerminal), 2uL).copy(pendingStoreCommit = retryZeroPending),
        )
    }

    @Test
    fun resetControlEpochMustBeNonzeroCurrentAndQuiescent() {
        assertValid(
            payloadWith(
                entries = emptyList(),
                lastEpoch = 1uL,
                modeControl = ModeControlV5.ResetRequested(1uL),
            ),
        )
        assertInvalid(
            payloadWith(
                entries = emptyList(),
                lastEpoch = 1uL,
                modeControl = ModeControlV5.ResetRunning(0uL),
            ),
        )
        assertInvalid(
            payloadWith(
                entries = emptyList(),
                lastEpoch = 2uL,
                modeControl = ModeControlV5.ResetRequested(1uL),
            ),
        )
        val active =
            ActiveV5(
                probeBaseScopeId = RecoveryJournalTestFixtures.scope(),
                attemptEpoch = 1uL,
                delegate = ProbeDelegate.CPU,
                role = JournalRouteRole.CANDIDATE,
                state = JournalActiveState.ACTIVE,
                retryUsed = false,
                retryContextId = null,
            )
        assertInvalid(
            payloadWith(
                entries = emptyList(),
                lastEpoch = 1uL,
                active = active,
                modeControl = ModeControlV5.ResetRequested(1uL),
            ),
        )
        assertInvalid(
            payloadWith(
                entries = emptyList(),
                lastEpoch = 0uL,
                modeControl =
                    ModeControlV5.PostSealModePoison(
                        PostSealModePoisonReason.RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL,
                        cacheInvalidated = false,
                    ),
            ),
        )
        assertValid(
            payloadWith(
                entries = emptyList(),
                lastEpoch = 1uL,
                modeControl =
                    ModeControlV5.PostSealModePoison(
                        PostSealModePoisonReason.RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL,
                        cacheInvalidated = true,
                    ),
            ),
        )
    }

    @Test
    fun privacySurfaceHasNoRawSensorOrIdentifierContainers() {
        val modelClasses =
            listOf(
                RecoveryJournalPayloadV5::class.java,
                ActiveV5::class.java,
                ManualRetryContextV1::class.java,
                JournalEntryV5::class.java,
                TerminalProofV1::class.java,
                PendingStoreCommitV5::class.java,
                ModeControlV5.ResetRequested::class.java,
                ModeControlV5.ResetRunning::class.java,
                ModeControlV5.PostSealModePoison::class.java,
            )
        val forbidden =
            listOf(
                "pixel",
                "bitmap",
                "landmark",
                "rawpose",
                "frame",
                "timestamp",
                "camera",
                "device",
                "user",
                "path",
                "serial",
                "generation",
            )

        modelClasses.flatMap { type ->
            type.declaredFields.filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
        }.forEach { field ->
            val lowered = field.name.lowercase()
            assertTrue("Forbidden persisted field name: ${field.declaringClass.simpleName}.${field.name}", forbidden.none(lowered::contains))
            assertFalse("ByteArray persistence surface: ${field.name}", field.type == ByteArray::class.java)
            assertFalse("Generic map persistence surface: ${field.name}", Map::class.java.isAssignableFrom(field.type))
        }

        val manual = RecoveryJournalTestFixtures.manualRetryPayload(activeDelegate = ProbeDelegate.CPU)
        val printable =
            listOfNotNull(
                manual.toString(),
                manual.active?.toString(),
                manual.manualRetryContext?.toString(),
                manual.entries.single().toString(),
                ModeControlV5.ResetRequested(1uL).toString(),
                ModeControlV5.ResetRunning(1uL).toString(),
            ).joinToString("|")
        assertFalse(printable.contains("lastEpoch="))
        assertFalse(printable.contains("attemptEpoch="))
        assertFalse(printable.contains("controlEpoch=1"))
        assertFalse(printable.contains(manual.entries.single().probeBaseScopeId.digest.toLowerHex()))
        assertFalse(printable.contains(checkNotNull(manual.entries.single().retryContextId).toLowerHex()))
    }

    @Test
    fun schemaRevisionIsExactAndUnknownValueCannotEncode() {
        val empty = RecoveryJournalPayloadV5.empty(RecoveryJournalTestFixtures.runtime(), RecoveryJournalMode.SOLO)
        assertInvalid(empty.copy(schemaRevision = "recovery-journal-payload-v6"))
        assertEquals(RecoveryJournalPayloadV5.SCHEMA_REVISION, empty.schemaRevision)
    }

    private fun payloadWith(
        entries: List<JournalEntryV5>,
        lastEpoch: ULong,
        active: ActiveV5? = null,
        modeControl: ModeControlV5 = ModeControlV5.None,
    ): RecoveryJournalPayloadV5 =
        RecoveryJournalPayloadV5(
            schemaRevision = RecoveryJournalPayloadV5.SCHEMA_REVISION,
            recoveryBuildId = RecoveryJournalTestFixtures.runtime(),
            mode = RecoveryJournalMode.SOLO,
            lastEpoch = lastEpoch,
            active = active,
            manualRetryContext = null,
            entries = entries,
            pendingStoreCommit = null,
            modeControl = modeControl,
        )

    private fun active(
        base: com.motionarcade.vision.capability.domain.ProbeBaseScopeId,
        delegate: ProbeDelegate,
        role: JournalRouteRole,
    ): ActiveV5 =
        ActiveV5(
            probeBaseScopeId = base,
            attemptEpoch = 1uL,
            delegate = delegate,
            role = role,
            state = JournalActiveState.ACTIVE,
            retryUsed = false,
            retryContextId = null,
        )

    private fun assertValid(value: RecoveryJournalPayloadV5) {
        valid(RecoveryJournalV5Codec.encodePayload(value))
    }

    private fun assertInvalid(value: RecoveryJournalPayloadV5) {
        assertTrue(RecoveryJournalV5Codec.encodePayload(value) is CapabilityDomainResult.Invalid)
    }

    private fun assertRoundTrip(value: RecoveryJournalPayloadV5) {
        val file = valid(RecoveryJournalV5Codec.encodeFile(value))
        assertEquals(
            value,
            valid(
                RecoveryJournalV5Codec.decodeFile(
                    file,
                    value.recoveryBuildId,
                    value.mode,
                ),
            ),
        )
    }

    private fun assertEntryRoundTrip(value: JournalEntryV5) {
        val bytes = valid(RecoveryJournalV5Codec.encodeEntry(value))
        assertEquals(value, valid(RecoveryJournalV5Codec.decodeEntry(bytes)))
    }

    private fun <T> valid(result: CapabilityDomainResult<T>): T = RecoveryJournalTestFixtures.valid(result)

    private data class ProofSlot(
        val name: String,
        val delegate: ProbeDelegate,
        val role: JournalRouteRole,
    )
}
