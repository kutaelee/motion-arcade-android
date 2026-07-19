package com.motionarcade.vision.capability.runtime

import com.motionarcade.vision.capability.domain.Sha256Digest
import java.util.concurrent.atomic.AtomicBoolean

enum class NativeCreateDenialReason {
    NATIVE_CLOSE_FENCE_PROOF_UNAVAILABLE,
    PROOF_SNAPSHOT_BINDING_MISMATCH,
    AUTHORIZATION_BINDING_MISMATCH,
    AUTHORIZATION_ALREADY_CONSUMED,
}

/**
 * Opaque proof identity. There is deliberately no production issuer until the registry verifier
 * exists. In particular, neither this contract nor its permitted implementation exposes a
 * factory, copy method, default argument, or JVM-visible construction path.
 */
sealed interface NativeCreateProofSnapshot

/** Opaque, request-bound, single-use permit contract. */
sealed interface OneShotNativeCreatePermit

data class NativeCreateAuthorizationRequest(
    val request: RuntimeCreateRequest,
    val proofSnapshot: NativeCreateProofSnapshot,
)

sealed interface NativeCreateConsumeResult {
    data object Consumed : NativeCreateConsumeResult

    data class Denied(val reason: NativeCreateDenialReason) : NativeCreateConsumeResult
}

sealed interface NativeCreateAuthorization {
    data class Denied(val reason: NativeCreateDenialReason) : NativeCreateAuthorization
}

/*
 * These three classes intentionally have no production construction path. They reserve the exact
 * shape that a future same-module registry verifier must populate. Tests may use hostile deep
 * reflection to exercise downstream semantics, but ordinary Java/Kotlin and ordinary reflection
 * cannot mint any of them.
 */
private class VerifiedNativeCreateProofSnapshot private constructor(
    val boundRequest: RuntimeCreateRequest,
    val snapshotIdentity: Sha256Digest,
    val issuerIdentity: Any,
    val issuerGeneration: Long,
) : NativeCreateProofSnapshot

private class IssuedOneShotNativeCreatePermit private constructor(
    val bound: NativeCreateAuthorizationRequest,
    val issuerIdentity: Any,
    val issuerGeneration: Long,
) : OneShotNativeCreatePermit {
    val consumed = AtomicBoolean(false)
}

private class GrantedNativeCreateAuthorization private constructor(
    val permit: IssuedOneShotNativeCreatePermit,
    val issuerIdentity: Any,
    val issuerGeneration: Long,
) : NativeCreateAuthorization

/**
 * Consumes only the exact private implementation with a coherent issuer, generation, request,
 * route, artifact and proof binding. This function cannot mint authority.
 */
internal fun consumeNativeCreateAuthorization(
    authorization: NativeCreateAuthorization,
    candidate: NativeCreateAuthorizationRequest,
): NativeCreateConsumeResult {
    if (authorization is NativeCreateAuthorization.Denied) {
        return NativeCreateConsumeResult.Denied(authorization.reason)
    }
    if (authorization !is GrantedNativeCreateAuthorization) {
        return NativeCreateConsumeResult.Denied(
            NativeCreateDenialReason.AUTHORIZATION_BINDING_MISMATCH,
        )
    }
    val permit = authorization.permit
    val snapshot = candidate.proofSnapshot as? VerifiedNativeCreateProofSnapshot
        ?: return NativeCreateConsumeResult.Denied(
            NativeCreateDenialReason.PROOF_SNAPSHOT_BINDING_MISMATCH,
        )
    val boundSnapshot = permit.bound.proofSnapshot as? VerifiedNativeCreateProofSnapshot
        ?: return NativeCreateConsumeResult.Denied(
            NativeCreateDenialReason.PROOF_SNAPSHOT_BINDING_MISMATCH,
        )
    if (authorization.issuerGeneration <= 0L ||
        authorization.issuerGeneration != permit.issuerGeneration ||
        authorization.issuerIdentity !== permit.issuerIdentity ||
        permit.issuerGeneration != snapshot.issuerGeneration ||
        permit.issuerGeneration != boundSnapshot.issuerGeneration ||
        permit.issuerIdentity !== snapshot.issuerIdentity ||
        permit.issuerIdentity !== boundSnapshot.issuerIdentity
    ) {
        return NativeCreateConsumeResult.Denied(
            NativeCreateDenialReason.AUTHORIZATION_BINDING_MISMATCH,
        )
    }
    if (candidate.request != permit.bound.request ||
        candidate.request != snapshot.boundRequest ||
        permit.bound.request != boundSnapshot.boundRequest ||
        snapshot.snapshotIdentity != boundSnapshot.snapshotIdentity
    ) {
        return NativeCreateConsumeResult.Denied(
            NativeCreateDenialReason.AUTHORIZATION_BINDING_MISMATCH,
        )
    }
    return if (permit.consumed.compareAndSet(false, true)) {
        NativeCreateConsumeResult.Consumed
    } else {
        NativeCreateConsumeResult.Denied(
            NativeCreateDenialReason.AUTHORIZATION_ALREADY_CONSUMED,
        )
    }
}

fun interface NativeCreateAuthorizer {
    fun authorize(request: NativeCreateAuthorizationRequest): NativeCreateAuthorization
}

/** Production default until the complete registry verifier supplies bound permits. */
object FailClosedNativeCreateAuthorizer : NativeCreateAuthorizer {
    override fun authorize(request: NativeCreateAuthorizationRequest): NativeCreateAuthorization =
        NativeCreateAuthorization.Denied(
            NativeCreateDenialReason.NATIVE_CLOSE_FENCE_PROOF_UNAVAILABLE,
        )
}
