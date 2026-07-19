package com.motionarcade.vision.capability.runtime

import com.motionarcade.core.contract.GameMode
import com.motionarcade.vision.capability.domain.ProbeBaseScopeId
import com.motionarcade.vision.capability.domain.RuntimeArtifactId
import com.motionarcade.vision.capability.domain.Sha256Digest
import java.util.concurrent.atomic.AtomicLong

/**
 * Hostile test-only construction. Production code intentionally has no equivalent mint path.
 * JVM surface tests prove these constructors are inaccessible without suppressing access checks.
 */
internal object RuntimeTestDeepFixture {
    private val nextIssuerGeneration = AtomicLong(1L)
    private val nextContextIdentity = AtomicLong(1L)

    class NativeGrant(
        val request: NativeCreateAuthorizationRequest,
        val authorization: NativeCreateAuthorization,
    )

    fun nativeGrant(
        command: OpenRuntimeCommand,
        proofSeed: Int = 1,
    ): NativeGrant {
        val issuer = Any()
        val generation = nextIssuerGeneration.getAndIncrement()
        val snapshot = construct(
            "com.motionarcade.vision.capability.runtime.VerifiedNativeCreateProofSnapshot",
            command.request,
            digest(proofSeed),
            issuer,
            generation,
        ) as NativeCreateProofSnapshot
        val request = NativeCreateAuthorizationRequest(command.request, snapshot)
        val permit = construct(
            "com.motionarcade.vision.capability.runtime.IssuedOneShotNativeCreatePermit",
            request,
            issuer,
            generation,
        )
        val authorization = construct(
            "com.motionarcade.vision.capability.runtime.GrantedNativeCreateAuthorization",
            permit,
            issuer,
            generation,
        ) as NativeCreateAuthorization
        return NativeGrant(request, authorization)
    }

    fun open(
        command: OpenRuntimeCommand,
        runtime: PoseRuntime,
        submissionPort: PoseRuntimeSubmissionPort = PoseRuntimeSubmissionPort {
            PoseRuntimeSubmitEvidence.RETURNED
        },
        proofSeed: Int = 1,
        clock: ProbeClock = ProbeClock { 0L },
    ): RuntimeOpenExecution {
        val grant = nativeGrant(command, proofSeed)
        return RuntimeOwnerBoundary.open(
            command,
            grant.request,
            NativeCreateAuthorizer { grant.authorization },
            noOpCallbacks(),
            FreshPoseRuntimeFactory { _, _, owner ->
                check(owner.bind(runtime, submissionPort))
                runtime
            },
            clock,
        )
    }

    fun openForRoute(
        routeKey: RuntimeRouteKey,
        runtime: PoseRuntime,
        submissionPort: PoseRuntimeSubmissionPort = PoseRuntimeSubmissionPort {
            PoseRuntimeSubmitEvidence.RETURNED
        },
        kind: RuntimeRouteKind = RuntimeRouteKind.CPU_CANDIDATE,
        clock: ProbeClock = ProbeClock { 0L },
    ): RuntimeOpenExecution {
        val contextIdentity = nextContextIdentity.getAndIncrement().toInt()
        val artifact = digest(contextIdentity)
        val scope = digest(contextIdentity xor 0x5a)
        val attempt = ProbeAttemptContext(
            GameMode.SOLO,
            routeKey.attemptEpoch,
            RuntimeArtifactId(artifact),
            ProbeBaseScopeId(scope),
        )
        return open(
            OpenRuntimeCommand(RuntimeCreateRequest(attempt, RuntimeRoute(routeKey, kind))),
            runtime,
            submissionPort,
            proofSeed = routeKey.routeOrdinal,
            clock = clock,
        )
    }

    fun failedOpen(
        routeKey: RuntimeRouteKey,
        reason: RuntimeOpenFailure,
    ): RuntimeOpenExecution = construct(
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$FailedRuntimeOpenExecution",
        RuntimeOpenResult.Failed(routeKey, reason),
    ) as RuntimeOpenExecution

    fun recordedSubmit(
        machine: ProbeStateMachine,
        command: SubmitRuntimeCommand,
        result: RuntimeSubmitResult,
    ): RuntimeSubmitExecution = construct(
        "com.motionarcade.vision.capability.runtime.RuntimeOwnerBoundary\$RecordedRuntimeSubmitExecution",
        command,
        result,
        ownerResource(machine),
        machine,
        ownerIssuerIdentity(),
        ownerGeneration(machine),
    ) as RuntimeSubmitExecution

    fun stateMachine(execution: RuntimeOpenExecution): ProbeStateMachine =
        requireNotNull(RuntimeOwnerBoundary.stateMachine(execution))

    fun openExecution(machine: ProbeStateMachine): RuntimeOpenExecution {
        val resource = ownerResource(machine)
        val field = resource.javaClass.getDeclaredField("openedExecution")
        field.isAccessible = true
        return requireNotNull(field.get(resource)) as RuntimeOpenExecution
    }

    fun noOpCallbacks(): RuntimeCallbackPort = object : RuntimeCallbackPort {
        override fun onResult(callback: RuntimeResultCallback) = Unit

        override fun onError(callback: RuntimeErrorCallback) = Unit
    }

    private fun construct(className: String, vararg arguments: Any): Any {
        val type = Class.forName(className)
        val constructor = type.declaredConstructors.single { it.parameterCount == arguments.size }
        constructor.isAccessible = true
        return constructor.newInstance(*arguments)
    }

    private fun ownerIssuerIdentity(): Any {
        val field = RuntimeOwnerBoundary::class.java.getDeclaredField("ISSUER_IDENTITY")
        field.isAccessible = true
        return requireNotNull(field.get(null))
    }

    private fun ownerResource(machine: ProbeStateMachine): Any {
        val bindingField = ProbeStateMachine::class.java.getDeclaredField("machineBinding")
        bindingField.isAccessible = true
        val binding = requireNotNull(bindingField.get(machine))
        val resourceField = binding.javaClass.getDeclaredField("resource")
        resourceField.isAccessible = true
        return requireNotNull(resourceField.get(binding))
    }

    private fun ownerGeneration(machine: ProbeStateMachine): Long {
        val resource = ownerResource(machine)
        val generationField = resource.javaClass.getDeclaredField("generation")
        generationField.isAccessible = true
        return generationField.getLong(resource)
    }

    private fun digest(seed: Int): Sha256Digest =
        (Sha256Digest.fromBytes(ByteArray(Sha256Digest.BYTE_COUNT) { seed.toByte() }) as
            com.motionarcade.vision.capability.domain.CapabilityDomainResult.Valid).value
}
