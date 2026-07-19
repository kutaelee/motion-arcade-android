package com.motionarcade.vision.tracking

import com.motionarcade.core.contract.PlayerId
import com.motionarcade.core.contract.TrackState
import java.util.Collections
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic two-role tracker over normalized, kinematic-only observations.
 *
 * The tracker never infers a role from appearance. Initial role selection and every role re-arm
 * are explicit caller decisions, while subsequent frame-to-frame continuity is selected only when
 * both the absolute cost gate and the best-vs-second-best margin pass.
 */
class PlayerTracker(
    private val loadedConfig: LoadedPlayerTrackerConfig,
) {
    private val config = loadedConfig.config
    private val runtimes = linkedMapOf<PlayerId, RoleRuntime>()
    private val roleSequences = linkedMapOf(PlayerId.P1 to 0L, PlayerId.P2 to 0L)
    private val transitionSequences = linkedMapOf(PlayerId.P1 to 0L, PlayerId.P2 to 0L)

    private var lastTimestampNanos: Long? = null
    private var calibrationRevision: Long? = null
    private var latestFrame: PlayerObservationFrame? = null
    private var lastOutput: PlayerTrackerOutput? = null
    private var nextTrackId = 1L
    private var lastCommittedSpatialOrder = 0
    private var singleObservationStickyRole: PlayerId? = null
    private var crossingCandidate: CrossingCandidate? = null
    private var rearmCandidate: RearmCandidate? = null
    private var frameSessionNonce: String? = null
    private var lastAcceptedFrameSequence: Long? = null
    private var latestFrameBindingConsumed = false

    @Synchronized
    fun initialize(
        frame: PlayerObservationFrame,
        binding: RoleObservationBinding,
    ): PlayerTrackerResult {
        if (runtimes.isNotEmpty()) return rejected(PlayerTrackerViolation.ALREADY_INITIALIZED)
        val snapshot = validateAndSnapshot(frame) ?: return rejected(PlayerTrackerViolation.INVALID_FRAME)
        val bound = resolveBinding(snapshot, binding, config.minimumObservationConfidence)
            ?: return rejected(PlayerTrackerViolation.INVALID_ROLE_BINDING)
        if (isUnsafeOverlap(bound.values.toList())) {
            return rejected(PlayerTrackerViolation.INVALID_ROLE_BINDING)
        }

        frameSessionNonce = snapshot.frameToken.sessionNonce
        lastAcceptedFrameSequence = snapshot.frameToken.frameSequence
        latestFrameBindingConsumed = true
        lastTimestampNanos = snapshot.timestampNanos
        calibrationRevision = snapshot.calibrationRevision
        latestFrame = snapshot

        val transitions = ArrayList<RoleTrackTransition>(ROLE_ORDER.size)
        ROLE_ORDER.forEach { role ->
            val observation = bound.getValue(role)
            runtimes[role] = RoleRuntime(
                roleId = role,
                trackId = allocateTrackId(),
                features = TrackFeatures.initial(snapshot.timestampNanos, observation),
                homeLaneX = observation.pelvis.x,
                state = TrackState.TENTATIVE,
                lastObservedAtNanos = snapshot.timestampNanos,
                stableSinceNanos = snapshot.timestampNanos,
                hasActivated = false,
            )
            transitions += transition(role, previous = null, current = TrackState.TENTATIVE, snapshot.timestampNanos)
        }
        lastCommittedSpatialOrder = spatialOrder(bound)

        return accepted(
            buildOutput(
                timestampNanos = snapshot.timestampNanos,
                calibrationRevision = snapshot.calibrationRevision,
                evidence = bound.mapValues { AssignmentEvidence(it.value.observationId, 0.0) },
                transitions = transitions,
                pauseReason = IdentityPauseReason.TENTATIVE_TRACKS,
            ),
        )
    }

    @Synchronized
    fun processFrame(frame: PlayerObservationFrame): PlayerTrackerResult {
        if (runtimes.isEmpty()) return rejected(PlayerTrackerViolation.NOT_INITIALIZED)
        val snapshot = validateAndSnapshot(frame) ?: return rejected(PlayerTrackerViolation.INVALID_FRAME)
        if (
            snapshot.frameToken.sessionNonce != frameSessionNonce ||
            snapshot.frameToken.frameSequence <= requireNotNull(lastAcceptedFrameSequence)
        ) {
            return rejected(PlayerTrackerViolation.FRAME_TOKEN_REPLAY)
        }
        val previousTimestamp = requireNotNull(lastTimestampNanos)
        if (snapshot.timestampNanos <= previousTimestamp) {
            return rejected(PlayerTrackerViolation.TIMESTAMP_NOT_STRICTLY_INCREASING)
        }
        val previousCalibration = requireNotNull(calibrationRevision)
        if (snapshot.calibrationRevision < previousCalibration) {
            return rejected(PlayerTrackerViolation.CALIBRATION_REVISION_ROLLBACK)
        }

        lastAcceptedFrameSequence = snapshot.frameToken.frameSequence
        latestFrameBindingConsumed = false
        lastTimestampNanos = snapshot.timestampNanos
        latestFrame = snapshot
        val frameGapNanos = snapshot.timestampNanos - previousTimestamp

        if (snapshot.calibrationRevision > previousCalibration) {
            calibrationRevision = snapshot.calibrationRevision
            crossingCandidate = null
            singleObservationStickyRole = null
            rearmCandidate = null
            val transitions = ArrayList<RoleTrackTransition>(ROLE_ORDER.size)
            ROLE_ORDER.forEach { role ->
                val runtime = runtimes.getValue(role)
                setState(runtime, TrackState.REARM, snapshot.timestampNanos, transitions)
                runtime.trackId = null
            }
            return accepted(
                buildOutput(
                    timestampNanos = snapshot.timestampNanos,
                    calibrationRevision = snapshot.calibrationRevision,
                    evidence = emptyMap(),
                    transitions = transitions,
                    pauseReason = IdentityPauseReason.CALIBRATION_CHANGED,
                ),
            )
        }

        calibrationRevision = snapshot.calibrationRevision
        val candidate = rearmCandidate
        if (candidate != null) return accepted(processRearm(snapshot, candidate))
        if (runtimes.values.any { it.state == TrackState.REARM }) {
            return accepted(
                buildOutput(
                    snapshot.timestampNanos,
                    snapshot.calibrationRevision,
                    emptyMap(),
                    emptyList(),
                    IdentityPauseReason.REARM_REQUIRED,
                ),
            )
        }
        if (frameGapNanos > config.occlusionGraceNanos) {
            crossingCandidate = null
            singleObservationStickyRole = null
            val transitions = ArrayList<RoleTrackTransition>(ROLE_ORDER.size)
            ROLE_ORDER.forEach { role ->
                val runtime = runtimes.getValue(role)
                val state = if (
                    snapshot.timestampNanos - runtime.lastObservedAtNanos >= config.lostAfterNanos
                ) {
                    TrackState.LOST
                } else {
                    TrackState.AMBIGUOUS
                }
                setState(runtime, state, snapshot.timestampNanos, transitions)
            }
            return accepted(
                buildOutput(
                    snapshot.timestampNanos,
                    snapshot.calibrationRevision,
                    emptyMap(),
                    transitions,
                    IdentityPauseReason.REARM_REQUIRED,
                ),
            )
        }

        val usable = snapshot.observations.filter { it.confidence >= config.minimumObservationConfidence }
        return accepted(
            when (usable.size) {
                2 -> processTwoObservations(snapshot, usable)
                1 -> processOneObservation(snapshot, usable.single())
                else -> processNoObservations(snapshot)
            },
        )
    }

    /**
     * Starts explicit role re-association from the latest accepted frame.
     *
     * A request does not activate either role. Both bound candidates must remain neutral and pass
     * kinematic assignment gates for [PlayerTrackerConfig.rearmNeutralDurationNanos].
     */
    @Synchronized
    fun requestRearm(binding: RoleObservationBinding): RearmRequestResult {
        if (runtimes.isEmpty()) return rearmRejected(PlayerTrackerViolation.NOT_INITIALIZED)
        if (
            crossingCandidate == null &&
            runtimes.values.none {
                it.state == TrackState.AMBIGUOUS ||
                    it.state == TrackState.LOST ||
                    it.state == TrackState.REARM
            }
        ) {
            return rearmRejected(PlayerTrackerViolation.REARM_NOT_REQUIRED)
        }
        val frame = requireNotNull(latestFrame)
        val bound = resolveBinding(frame, binding, config.rearmMinimumConfidence)
            ?: return rearmRejected(PlayerTrackerViolation.INVALID_ROLE_BINDING)
        if (latestFrameBindingConsumed) {
            return rearmRejected(PlayerTrackerViolation.INVALID_ROLE_BINDING)
        }
        if (isUnsafeOverlap(bound.values.toList())) {
            return rearmRejected(PlayerTrackerViolation.INVALID_ROLE_BINDING)
        }

        latestFrameBindingConsumed = true
        crossingCandidate = null
        singleObservationStickyRole = null
        val transitions = ArrayList<RoleTrackTransition>(ROLE_ORDER.size)
        ROLE_ORDER.forEach { role ->
            val runtime = runtimes.getValue(role)
            setState(runtime, TrackState.REARM, frame.timestampNanos, transitions)
            runtime.trackId = null
        }
        val initialNeutral = bound.values.all { it.neutral && it.confidence >= config.rearmMinimumConfidence }
        rearmCandidate = RearmCandidate(
            features = bound.mapValues { TrackFeatures.initial(frame.timestampNanos, it.value) },
            homeLaneX = bound.mapValues { it.value.pelvis.x },
            neutralSinceNanos = if (initialNeutral) frame.timestampNanos else null,
        )
        return RearmRequestResult.Accepted(
            buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                bound.mapValues { AssignmentEvidence(it.value.observationId, 0.0) },
                transitions,
                IdentityPauseReason.REARM_STABILITY,
            ),
        )
    }

    @Synchronized
    fun currentOutput(): PlayerTrackerOutput? = lastOutput

    private fun processTwoObservations(
        frame: PlayerObservationFrame,
        observations: List<PlayerTrackObservation>,
    ): PlayerTrackerOutput {
        val transitions = ArrayList<RoleTrackTransition>(ROLE_ORDER.size)
        singleObservationStickyRole = null
        if (isUnsafeOverlap(observations)) {
            crossingCandidate = null
            markBothAmbiguous(frame.timestampNanos, transitions)
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                emptyMap(),
                transitions,
                IdentityPauseReason.PLAYER_OVERLAP,
            )
        }

        val crossing = crossingCandidate
        if (crossing != null) {
            val previousCandidateTimestamp = crossing.features.values.first().timestampNanos
            if (frame.timestampNanos - previousCandidateTimestamp > config.maximumStableObservationGapNanos) {
                crossingCandidate = null
                markBothAmbiguous(frame.timestampNanos, transitions)
                return buildOutput(
                    frame.timestampNanos,
                    frame.calibrationRevision,
                    emptyMap(),
                    transitions,
                    IdentityPauseReason.ASSIGNMENT_AMBIGUOUS,
                )
            }
            val match = chooseTwoAssignment(
                frame.timestampNanos,
                observations,
                crossing.features,
                crossing.homeLaneX,
            )
            if (match == null) {
                crossingCandidate = null
                markBothAmbiguous(frame.timestampNanos, transitions)
                return buildOutput(
                    frame.timestampNanos,
                    frame.calibrationRevision,
                    emptyMap(),
                    transitions,
                    IdentityPauseReason.ASSIGNMENT_AMBIGUOUS,
                )
            }
            val exitOutput = processDefinitiveExits(frame, match, transitions)
            if (exitOutput != null) return exitOutput
            if (spatialOrder(match.observations) != crossing.targetOrder) {
                crossingCandidate = null
                markBothAmbiguous(frame.timestampNanos, transitions)
                return buildOutput(
                    frame.timestampNanos,
                    frame.calibrationRevision,
                    emptyMap(),
                    transitions,
                    IdentityPauseReason.ASSIGNMENT_AMBIGUOUS,
                )
            }
            val updated = crossing.copy(
                features = updatedFeatures(frame.timestampNanos, match.observations, crossing.features),
                frameCount = crossing.frameCount + 1,
            )
            crossingCandidate = updated
            if (!crossingReady(updated, frame.timestampNanos)) {
                return buildOutput(
                    frame.timestampNanos,
                    frame.calibrationRevision,
                    emptyMap(),
                    emptyList(),
                    IdentityPauseReason.CROSSING_HYSTERESIS,
                )
            }
            commitObserved(frame.timestampNanos, match.observations, transitions)
            crossingCandidate = null
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                match.evidence,
                transitions,
                pauseForRuntimeStates(),
            )
        }

        val references = runtimes.mapValues { it.value.features }
        val homes = runtimes.mapValues { it.value.homeLaneX }
        val match = chooseTwoAssignment(frame.timestampNanos, observations, references, homes)
        if (match == null) {
            markBothAmbiguous(frame.timestampNanos, transitions)
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                emptyMap(),
                transitions,
                IdentityPauseReason.ASSIGNMENT_AMBIGUOUS,
            )
        }
        val proposedOrder = spatialOrder(match.observations)
        val recoveringFromPartialOcclusion = runtimes.values.any { it.state == TrackState.OCCLUDED }
        if (
            recoveringFromPartialOcclusion &&
            lastCommittedSpatialOrder != 0 &&
            proposedOrder != 0 &&
            proposedOrder != lastCommittedSpatialOrder
        ) {
            crossingCandidate = null
            markBothAmbiguous(frame.timestampNanos, transitions)
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                emptyMap(),
                transitions,
                IdentityPauseReason.REARM_REQUIRED,
            )
        }

        val exitOutput = processDefinitiveExits(frame, match, transitions)
        if (exitOutput != null) return exitOutput

        if (runtimes.values.any { it.state == TrackState.AMBIGUOUS || it.state == TrackState.LOST }) {
            updateLostDeadlines(frame.timestampNanos, transitions)
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                emptyMap(),
                transitions,
                IdentityPauseReason.REARM_REQUIRED,
            )
        }

        val committedOrder = lastCommittedSpatialOrder
        if (committedOrder != 0 && proposedOrder != 0 && committedOrder != proposedOrder) {
            val candidate = CrossingCandidate(
                features = updatedFeatures(frame.timestampNanos, match.observations, references),
                homeLaneX = homes,
                targetOrder = proposedOrder,
                startedAtNanos = frame.timestampNanos,
                frameCount = 1,
            )
            if (crossingReady(candidate, frame.timestampNanos)) {
                commitObserved(frame.timestampNanos, match.observations, transitions)
                return buildOutput(
                    frame.timestampNanos,
                    frame.calibrationRevision,
                    match.evidence,
                    transitions,
                    pauseForRuntimeStates(),
                )
            }
            crossingCandidate = candidate
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                emptyMap(),
                emptyList(),
                IdentityPauseReason.CROSSING_HYSTERESIS,
            )
        }

        commitObserved(frame.timestampNanos, match.observations, transitions)
        return buildOutput(
            frame.timestampNanos,
            frame.calibrationRevision,
            match.evidence,
            transitions,
            pauseForRuntimeStates(),
        )
    }

    private fun processOneObservation(
        frame: PlayerObservationFrame,
        observation: PlayerTrackObservation,
    ): PlayerTrackerOutput {
        val transitions = ArrayList<RoleTrackTransition>(ROLE_ORDER.size)
        if (crossingCandidate != null) {
            crossingCandidate = null
            markBothAmbiguous(frame.timestampNanos, transitions)
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                emptyMap(),
                transitions,
                IdentityPauseReason.ASSIGNMENT_AMBIGUOUS,
            )
        }

        val stickyRole = singleObservationStickyRole
        val bestRole: PlayerId
        val bestCost: Double
        if (stickyRole != null) {
            val oppositeRole = if (stickyRole == PlayerId.P1) PlayerId.P2 else PlayerId.P1
            val runtime = runtimes.getValue(stickyRole)
            val stickyCost = assignmentCost(
                timestampNanos = frame.timestampNanos,
                reference = runtime.features,
                homeLaneX = runtime.homeLaneX,
                observation = observation,
            )
            val oppositeRuntime = runtimes.getValue(oppositeRole)
            val oppositeCost = assignmentCost(
                timestampNanos = frame.timestampNanos,
                reference = oppositeRuntime.features,
                homeLaneX = oppositeRuntime.homeLaneX,
                observation = observation,
            )
            if (!assignmentPasses(stickyCost, oppositeCost)) {
                singleObservationStickyRole = null
                markBothAmbiguous(frame.timestampNanos, transitions)
                return buildOutput(
                    frame.timestampNanos,
                    frame.calibrationRevision,
                    emptyMap(),
                    transitions,
                    IdentityPauseReason.ASSIGNMENT_AMBIGUOUS,
                )
            }
            bestRole = stickyRole
            bestCost = stickyCost
        } else {
            val costs = ROLE_ORDER.associateWith { role ->
                val runtime = runtimes.getValue(role)
                assignmentCost(
                    timestampNanos = frame.timestampNanos,
                    reference = runtime.features,
                    homeLaneX = runtime.homeLaneX,
                    observation = observation,
                )
            }
            val ordered = costs.entries.sortedWith(
                compareBy<Map.Entry<PlayerId, Double>> { it.value }.thenBy { it.key.name },
            )
            val best = ordered[0]
            val second = ordered[1]
            if (!assignmentPasses(best.value, second.value)) {
                markBothAmbiguous(frame.timestampNanos, transitions)
                return buildOutput(
                    frame.timestampNanos,
                    frame.calibrationRevision,
                    emptyMap(),
                    transitions,
                    IdentityPauseReason.ASSIGNMENT_AMBIGUOUS,
                )
            }
            bestRole = best.key
            bestCost = best.value
            singleObservationStickyRole = bestRole
        }

        val matchedRuntime = runtimes.getValue(bestRole)
        if (observation.definitiveFrameExit) {
            singleObservationStickyRole = null
            setState(matchedRuntime, TrackState.LOST, frame.timestampNanos, transitions)
            val missingRole = if (bestRole == PlayerId.P1) PlayerId.P2 else PlayerId.P1
            markMissing(runtimes.getValue(missingRole), frame.timestampNanos, transitions)
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                emptyMap(),
                transitions,
                IdentityPauseReason.REARM_REQUIRED,
            )
        }
        if (matchedRuntime.state != TrackState.AMBIGUOUS && matchedRuntime.state != TrackState.LOST) {
            commitObserved(frame.timestampNanos, bestRole, observation, transitions)
        }
        val missingRole = if (bestRole == PlayerId.P1) PlayerId.P2 else PlayerId.P1
        markMissing(runtimes.getValue(missingRole), frame.timestampNanos, transitions)
        updateLostDeadlines(frame.timestampNanos, transitions)

        val evidence = if (matchedRuntime.state == TrackState.ACTIVE || matchedRuntime.state == TrackState.TENTATIVE) {
            mapOf(bestRole to AssignmentEvidence(observation.observationId, bestCost))
        } else {
            emptyMap()
        }
        return buildOutput(
            frame.timestampNanos,
            frame.calibrationRevision,
            evidence,
            transitions,
            pauseForRuntimeStates(IdentityPauseReason.INSUFFICIENT_OBSERVATIONS),
        )
    }

    private fun processNoObservations(frame: PlayerObservationFrame): PlayerTrackerOutput {
        val transitions = ArrayList<RoleTrackTransition>(ROLE_ORDER.size)
        crossingCandidate = null
        ROLE_ORDER.forEach { markMissing(runtimes.getValue(it), frame.timestampNanos, transitions) }
        updateLostDeadlines(frame.timestampNanos, transitions)
        return buildOutput(
            frame.timestampNanos,
            frame.calibrationRevision,
            emptyMap(),
            transitions,
            pauseForRuntimeStates(IdentityPauseReason.INSUFFICIENT_OBSERVATIONS),
        )
    }

    private fun processDefinitiveExits(
        frame: PlayerObservationFrame,
        match: TwoRoleMatch,
        transitions: MutableList<RoleTrackTransition>,
    ): PlayerTrackerOutput? {
        val exitingRoles = ROLE_ORDER.filter { role ->
            match.observations.getValue(role).definitiveFrameExit
        }
        if (exitingRoles.isEmpty()) return null
        crossingCandidate = null
        val evidence = linkedMapOf<PlayerId, AssignmentEvidence>()
        ROLE_ORDER.forEach { role ->
            val runtime = runtimes.getValue(role)
            if (role in exitingRoles) {
                setState(runtime, TrackState.LOST, frame.timestampNanos, transitions)
            } else if (runtime.state != TrackState.AMBIGUOUS && runtime.state != TrackState.LOST) {
                commitObserved(frame.timestampNanos, role, match.observations.getValue(role), transitions)
                evidence[role] = match.evidence.getValue(role)
            }
        }
        return buildOutput(
            frame.timestampNanos,
            frame.calibrationRevision,
            evidence,
            transitions,
            IdentityPauseReason.REARM_REQUIRED,
        )
    }

    private fun processRearm(
        frame: PlayerObservationFrame,
        candidate: RearmCandidate,
    ): PlayerTrackerOutput {
        val transitions = ArrayList<RoleTrackTransition>(ROLE_ORDER.size)
        val observations = frame.observations.filter { it.confidence >= config.rearmMinimumConfidence }
        if (
            observations.size != 2 ||
            observations.any(PlayerTrackObservation::definitiveFrameExit) ||
            isUnsafeOverlap(observations)
        ) {
            rearmCandidate = candidate.copy(neutralSinceNanos = null)
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                emptyMap(),
                emptyList(),
                if (
                    observations.size == 2 &&
                    observations.none(PlayerTrackObservation::definitiveFrameExit)
                ) {
                    IdentityPauseReason.PLAYER_OVERLAP
                } else {
                    IdentityPauseReason.REARM_STABILITY
                },
            )
        }
        val match = chooseTwoAssignment(
            frame.timestampNanos,
            observations,
            candidate.features,
            candidate.homeLaneX,
        )
        if (match == null) {
            rearmCandidate = candidate.copy(neutralSinceNanos = null)
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                emptyMap(),
                emptyList(),
                IdentityPauseReason.REARM_STABILITY,
            )
        }

        val allNeutral = match.observations.values.all {
            it.neutral && it.confidence >= config.rearmMinimumConfidence
        }
        val continuouslyObserved = candidate.features.values.all {
            frame.timestampNanos - it.timestampNanos <= config.maximumStableObservationGapNanos
        }
        val neutralSince = when {
            !allNeutral -> null
            continuouslyObserved -> candidate.neutralSinceNanos ?: frame.timestampNanos
            else -> frame.timestampNanos
        }
        val updated = candidate.copy(
            features = updatedFeatures(frame.timestampNanos, match.observations, candidate.features),
            neutralSinceNanos = neutralSince,
        )
        rearmCandidate = updated
        if (neutralSince == null || frame.timestampNanos - neutralSince < config.rearmNeutralDurationNanos) {
            return buildOutput(
                frame.timestampNanos,
                frame.calibrationRevision,
                match.evidence,
                emptyList(),
                IdentityPauseReason.REARM_STABILITY,
            )
        }

        ROLE_ORDER.forEach { role ->
            val observation = match.observations.getValue(role)
            val runtime = runtimes.getValue(role)
            runtime.trackId = allocateTrackId()
            runtime.features = updated.features.getValue(role)
            runtime.homeLaneX = observation.pelvis.x
            runtime.lastObservedAtNanos = frame.timestampNanos
            runtime.stableSinceNanos = frame.timestampNanos
            runtime.hasActivated = true
            setState(runtime, TrackState.ACTIVE, frame.timestampNanos, transitions)
        }
        lastCommittedSpatialOrder = spatialOrder(match.observations)
        rearmCandidate = null
        return buildOutput(
            frame.timestampNanos,
            frame.calibrationRevision,
            match.evidence,
            transitions,
            IdentityPauseReason.NONE,
        )
    }

    private fun chooseTwoAssignment(
        timestampNanos: Long,
        observations: List<PlayerTrackObservation>,
        references: Map<PlayerId, TrackFeatures>,
        homeLaneX: Map<PlayerId, Double>,
    ): TwoRoleMatch? {
        check(observations.size == 2)
        val first = observations[0]
        val second = observations[1]
        val straight = alternative(
            timestampNanos,
            mapOf(PlayerId.P1 to first, PlayerId.P2 to second),
            references,
            homeLaneX,
        )
        val swapped = alternative(
            timestampNanos,
            mapOf(PlayerId.P1 to second, PlayerId.P2 to first),
            references,
            homeLaneX,
        )
        val ordered = listOf(straight, swapped).sortedWith(
            compareBy<AssignmentAlternative> { it.totalCost }
                .thenBy { it.observations.getValue(PlayerId.P1).observationId }
                .thenBy { it.observations.getValue(PlayerId.P2).observationId },
        )
        val best = ordered[0]
        val secondBest = ordered[1]
        if (!assignmentPasses(best.totalCost, secondBest.totalCost)) return null
        return TwoRoleMatch(best.observations, best.evidence)
    }

    private fun alternative(
        timestampNanos: Long,
        observations: Map<PlayerId, PlayerTrackObservation>,
        references: Map<PlayerId, TrackFeatures>,
        homeLaneX: Map<PlayerId, Double>,
    ): AssignmentAlternative {
        val evidence = ROLE_ORDER.associateWith { role ->
            val observation = observations.getValue(role)
            AssignmentEvidence(
                observationId = observation.observationId,
                cost = assignmentCost(timestampNanos, references.getValue(role), homeLaneX.getValue(role), observation),
            )
        }
        val total = evidence.values.fold(0.0) { sum, item -> sum + item.cost }
        return AssignmentAlternative(observations, evidence, total)
    }

    private fun updatedFeatures(
        timestampNanos: Long,
        observations: Map<PlayerId, PlayerTrackObservation>,
        references: Map<PlayerId, TrackFeatures>,
    ): Map<PlayerId, TrackFeatures> = ROLE_ORDER.associateWith { role ->
        TrackFeatures.next(
            timestampNanos,
            observations.getValue(role),
            references.getValue(role),
        )
    }

    private fun assignmentCost(
        timestampNanos: Long,
        reference: TrackFeatures,
        homeLaneX: Double,
        observation: PlayerTrackObservation,
    ): Double {
        val elapsedNanos = min(timestampNanos - reference.timestampNanos, config.maximumPredictionHorizonNanos)
        val elapsedSeconds = elapsedNanos.toDouble() / NANOS_PER_SECOND
        val predictedPelvis = NormalizedPoint(
            x = reference.observation.pelvis.x + reference.velocity.xPerSecond * elapsedSeconds,
            y = reference.observation.pelvis.y + reference.velocity.yPerSecond * elapsedSeconds,
        )
        val predictedShoulder = NormalizedPoint(
            x = reference.observation.shoulderCenter.x + reference.velocity.xPerSecond * elapsedSeconds,
            y = reference.observation.shoulderCenter.y + reference.velocity.yPerSecond * elapsedSeconds,
        )
        val scale = max(
            config.minimumBodyScale,
            (reference.observation.bodyScale + observation.bodyScale) / 2.0,
        )
        val pelvisDistance = distance(predictedPelvis, observation.pelvis) / scale
        val shoulderDistance = distance(predictedShoulder, observation.shoulderCenter) / scale
        val candidateVelocity = impliedVelocity(timestampNanos, reference, observation)
        val velocityMismatch = hypot(
            reference.velocity.xPerSecond - candidateVelocity.xPerSecond,
            reference.velocity.yPerSecond - candidateVelocity.yPerSecond,
        ) / config.maximumNormalizedSpeedPerSecond
        val scaleMismatch = abs(ln(observation.bodyScale / reference.observation.bodyScale))
        val directionDiscontinuity = abs(
            observation.facingDirection - reference.observation.facingDirection,
        ) / 2.0
        val lanePenalty = max(0.0, abs(observation.pelvis.x - homeLaneX) - config.laneToleranceNormalized)
        val cost =
            pelvisDistance * config.pelvisDistanceWeight +
                shoulderDistance * config.shoulderDistanceWeight +
                velocityMismatch * config.velocityMismatchWeight +
                scaleMismatch * config.scaleMismatchWeight +
                directionDiscontinuity * config.directionDiscontinuityWeight +
                lanePenalty * config.lanePenaltyWeight
        return if (cost.isFinite()) cost else Double.POSITIVE_INFINITY
    }

    private fun impliedVelocity(
        timestampNanos: Long,
        reference: TrackFeatures,
        observation: PlayerTrackObservation,
    ): TrackVelocity {
        val elapsedSeconds = (timestampNanos - reference.timestampNanos).toDouble() / NANOS_PER_SECOND
        check(elapsedSeconds > 0.0)
        return TrackVelocity(
            xPerSecond = (observation.pelvis.x - reference.observation.pelvis.x) / elapsedSeconds,
            yPerSecond = (observation.pelvis.y - reference.observation.pelvis.y) / elapsedSeconds,
        )
    }

    private fun assignmentPasses(bestCost: Double, secondCost: Double): Boolean =
        bestCost.isFinite() &&
            secondCost.isFinite() &&
            bestCost <= config.absoluteAssignmentGate &&
            secondCost - bestCost > config.assignmentMargin

    private fun commitObserved(
        timestampNanos: Long,
        observations: Map<PlayerId, PlayerTrackObservation>,
        transitions: MutableList<RoleTrackTransition>,
    ) {
        ROLE_ORDER.forEach { role ->
            commitObserved(timestampNanos, role, observations.getValue(role), transitions)
        }
        val order = spatialOrder(observations)
        if (order != 0) lastCommittedSpatialOrder = order
    }

    private fun commitObserved(
        timestampNanos: Long,
        role: PlayerId,
        observation: PlayerTrackObservation,
        transitions: MutableList<RoleTrackTransition>,
    ) {
        val runtime = runtimes.getValue(role)
        val observationGap = timestampNanos - runtime.lastObservedAtNanos
        runtime.features = TrackFeatures.next(timestampNanos, observation, runtime.features)
        runtime.lastObservedAtNanos = timestampNanos
        if (runtime.hasActivated) {
            setState(runtime, TrackState.ACTIVE, timestampNanos, transitions)
            return
        }
        if (
            runtime.state == TrackState.OCCLUDED ||
            observationGap > config.maximumStableObservationGapNanos
        ) {
            runtime.stableSinceNanos = timestampNanos
        }
        if (timestampNanos - runtime.stableSinceNanos >= config.tentativeDurationNanos) {
            runtime.hasActivated = true
            setState(runtime, TrackState.ACTIVE, timestampNanos, transitions)
        } else {
            setState(runtime, TrackState.TENTATIVE, timestampNanos, transitions)
        }
    }

    private fun markMissing(
        runtime: RoleRuntime,
        timestampNanos: Long,
        transitions: MutableList<RoleTrackTransition>,
    ) {
        if (runtime.state == TrackState.LOST || runtime.state == TrackState.REARM) return
        val elapsed = timestampNanos - runtime.lastObservedAtNanos
        val state = when {
            elapsed >= config.lostAfterNanos -> TrackState.LOST
            elapsed > config.occlusionGraceNanos -> TrackState.AMBIGUOUS
            else -> TrackState.OCCLUDED
        }
        setState(runtime, state, timestampNanos, transitions)
    }

    private fun updateLostDeadlines(
        timestampNanos: Long,
        transitions: MutableList<RoleTrackTransition>,
    ) {
        ROLE_ORDER.forEach { role ->
            val runtime = runtimes.getValue(role)
            if (
                runtime.state == TrackState.AMBIGUOUS &&
                timestampNanos - runtime.lastObservedAtNanos >= config.lostAfterNanos
            ) {
                setState(runtime, TrackState.LOST, timestampNanos, transitions)
            }
        }
    }

    private fun markBothAmbiguous(
        timestampNanos: Long,
        transitions: MutableList<RoleTrackTransition>,
    ) {
        singleObservationStickyRole = null
        ROLE_ORDER.forEach { role ->
            val runtime = runtimes.getValue(role)
            if (runtime.state != TrackState.LOST && runtime.state != TrackState.REARM) {
                setState(runtime, TrackState.AMBIGUOUS, timestampNanos, transitions)
            }
        }
    }

    private fun pauseForRuntimeStates(
        fallback: IdentityPauseReason = IdentityPauseReason.TENTATIVE_TRACKS,
    ): IdentityPauseReason {
        val states = runtimes.values.map(RoleRuntime::state)
        return when {
            states.any { it == TrackState.AMBIGUOUS || it == TrackState.LOST || it == TrackState.REARM } ->
                IdentityPauseReason.REARM_REQUIRED
            states.any { it == TrackState.OCCLUDED } -> IdentityPauseReason.INSUFFICIENT_OBSERVATIONS
            states.any { it == TrackState.TENTATIVE } -> fallback
            else -> IdentityPauseReason.NONE
        }
    }

    private fun crossingReady(candidate: CrossingCandidate, timestampNanos: Long): Boolean {
        val frameGate = config.crossingHysteresisFrames > 0 &&
            candidate.frameCount >= config.crossingHysteresisFrames
        val timeGate = config.crossingHysteresisNanos > 0L &&
            timestampNanos - candidate.startedAtNanos >= config.crossingHysteresisNanos
        return frameGate || timeGate
    }

    private fun isUnsafeOverlap(observations: List<PlayerTrackObservation>): Boolean {
        if (observations.size != 2) return false
        val first = observations[0]
        val second = observations[1]
        val iouUnsafe = config.overlapIouPauseThreshold > 0.0 &&
            intersectionOverUnion(first.bounds, second.bounds) >= config.overlapIouPauseThreshold
        val bodyScale = max(config.minimumBodyScale, (first.bodyScale + second.bodyScale) / 2.0)
        val proximity = distance(first.pelvis, second.pelvis) / bodyScale
        val proximityUnsafe = config.proximityBodyScalePauseThreshold > 0.0 &&
            proximity <= config.proximityBodyScalePauseThreshold
        return iouUnsafe || proximityUnsafe
    }

    private fun resolveBinding(
        frame: PlayerObservationFrame,
        binding: RoleObservationBinding,
        minimumConfidence: Double,
    ): Map<PlayerId, PlayerTrackObservation>? {
        if (
            binding.sourceFrameToken != frame.frameToken ||
            binding.sourceTimestampNanos != frame.timestampNanos ||
            binding.calibrationRevision != frame.calibrationRevision
        ) {
            return null
        }
        if (binding.p1ObservationId == binding.p2ObservationId) return null
        val byId = frame.observations.associateBy(PlayerTrackObservation::observationId)
        val p1 = byId[binding.p1ObservationId] ?: return null
        val p2 = byId[binding.p2ObservationId] ?: return null
        if (p1.confidence < minimumConfidence || p2.confidence < minimumConfidence) return null
        if (p1.definitiveFrameExit || p2.definitiveFrameExit) return null
        return linkedMapOf(PlayerId.P1 to p1, PlayerId.P2 to p2)
    }

    private fun validateAndSnapshot(frame: PlayerObservationFrame): PlayerObservationFrame? {
        val observationSnapshot = boundedObservationSnapshot(frame.observations) ?: return null
        val snapshot = frame.copy(observations = observationSnapshot)
        if (snapshot.timestampNanos < 0L || snapshot.calibrationRevision < 0L) return null
        if (
            snapshot.observations.map(PlayerTrackObservation::observationId).distinct().size !=
            snapshot.observations.size
        ) {
            return null
        }
        if (snapshot.observations.any { !validObservation(it) }) return null
        return snapshot
    }

    /** Reads an untrusted caller list once and invokes next() at most three times. */
    private fun boundedObservationSnapshot(
        observations: List<PlayerTrackObservation?>,
    ): List<PlayerTrackObservation>? = try {
        val bounded = ArrayList<PlayerTrackObservation>(MAX_OBSERVATIONS + 1)
        val iterator = observations.iterator()
        while (bounded.size <= MAX_OBSERVATIONS && iterator.hasNext()) {
            bounded += iterator.next() ?: return null
        }
        if (bounded.size > MAX_OBSERVATIONS) {
            null
        } else {
            Collections.unmodifiableList(bounded)
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun validObservation(observation: PlayerTrackObservation): Boolean {
        if (observation.observationId < 0) return false
        if (!validPoint(observation.pelvis) || !validPoint(observation.shoulderCenter)) return false
        if (!observation.bodyScale.isFinite() || observation.bodyScale !in config.minimumBodyScale..1.0) {
            return false
        }
        if (!observation.facingDirection.isFinite() || observation.facingDirection !in -1.0..1.0) {
            return false
        }
        if (!observation.confidence.isFinite() || observation.confidence !in 0.0..1.0) return false
        val bounds = observation.bounds
        if (
            !listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all(Double::isFinite) ||
            bounds.left !in 0.0..1.0 ||
            bounds.top !in 0.0..1.0 ||
            bounds.right !in 0.0..1.0 ||
            bounds.bottom !in 0.0..1.0 ||
            bounds.left >= bounds.right ||
            bounds.top >= bounds.bottom
        ) {
            return false
        }
        if (
            observation.pelvis.x !in bounds.left..bounds.right ||
            observation.pelvis.y !in bounds.top..bounds.bottom ||
            observation.shoulderCenter.x !in bounds.left..bounds.right ||
            observation.shoulderCenter.y !in bounds.top..bounds.bottom
        ) {
            return false
        }
        return true
    }

    private fun validPoint(point: NormalizedPoint): Boolean =
        point.x.isFinite() && point.y.isFinite() && point.x in 0.0..1.0 && point.y in 0.0..1.0

    private fun setState(
        runtime: RoleRuntime,
        state: TrackState,
        timestampNanos: Long,
        transitions: MutableList<RoleTrackTransition>,
    ) {
        if (runtime.state == state) return
        val previous = runtime.state
        runtime.state = state
        transitions += transition(runtime.roleId, previous, state, timestampNanos)
    }

    private fun transition(
        role: PlayerId,
        previous: TrackState?,
        current: TrackState,
        timestampNanos: Long,
    ): RoleTrackTransition {
        val sequence = Math.incrementExact(transitionSequences.getValue(role))
        transitionSequences[role] = sequence
        return RoleTrackTransition(role, sequence, previous, current, timestampNanos)
    }

    private fun buildOutput(
        timestampNanos: Long,
        calibrationRevision: Long,
        evidence: Map<PlayerId, AssignmentEvidence>,
        transitions: List<RoleTrackTransition>,
        pauseReason: IdentityPauseReason,
    ): PlayerTrackerOutput {
        val assignments = ROLE_ORDER.map { role ->
            val sequence = Math.incrementExact(roleSequences.getValue(role))
            roleSequences[role] = sequence
            val runtime = runtimes.getValue(role)
            val item = evidence[role]
            RoleTrackAssignment(
                roleId = role,
                trackId = runtime.trackId,
                observationId = item?.observationId,
                state = runtime.state,
                assignmentCost = item?.cost,
                roleSequenceNumber = sequence,
            )
        }
        val output = PlayerTrackerOutput(
            configId = config.configId,
            configSchemaVersion = config.schemaVersion,
            configSchemaId = loadedConfig.schemaId,
            configSourceSha256Hex = loadedConfig.sourceSha256Hex,
            configReviewStatus = loadedConfig.reviewStatus,
            timestampNanos = timestampNanos,
            calibrationRevision = calibrationRevision,
            assignments = Collections.unmodifiableList(ArrayList(assignments)),
            transitions = Collections.unmodifiableList(ArrayList(transitions)),
            pauseRequired = pauseReason != IdentityPauseReason.NONE,
            pauseReason = pauseReason,
        )
        lastOutput = output
        return output
    }

    private fun allocateTrackId(): TrackId {
        val value = nextTrackId
        nextTrackId = Math.incrementExact(nextTrackId)
        return TrackId(value)
    }

    private fun accepted(output: PlayerTrackerOutput): PlayerTrackerResult = PlayerTrackerResult.Accepted(output)

    private fun rejected(violation: PlayerTrackerViolation): PlayerTrackerResult =
        PlayerTrackerResult.Rejected(violation, lastOutput)

    private fun rearmRejected(violation: PlayerTrackerViolation): RearmRequestResult =
        RearmRequestResult.Rejected(violation, lastOutput)

    private data class RoleRuntime(
        val roleId: PlayerId,
        var trackId: TrackId?,
        var features: TrackFeatures,
        var homeLaneX: Double,
        var state: TrackState,
        var lastObservedAtNanos: Long,
        var stableSinceNanos: Long,
        var hasActivated: Boolean,
    )

    private data class TrackFeatures(
        val timestampNanos: Long,
        val observation: PlayerTrackObservation,
        val velocity: TrackVelocity,
    ) {
        companion object {
            fun initial(timestampNanos: Long, observation: PlayerTrackObservation): TrackFeatures =
                TrackFeatures(timestampNanos, observation, TrackVelocity(0.0, 0.0))

            fun next(
                timestampNanos: Long,
                observation: PlayerTrackObservation,
                previous: TrackFeatures,
            ): TrackFeatures {
                val elapsedSeconds = (timestampNanos - previous.timestampNanos).toDouble() / NANOS_PER_SECOND
                check(elapsedSeconds > 0.0)
                return TrackFeatures(
                    timestampNanos = timestampNanos,
                    observation = observation,
                    velocity = TrackVelocity(
                        xPerSecond = (observation.pelvis.x - previous.observation.pelvis.x) / elapsedSeconds,
                        yPerSecond = (observation.pelvis.y - previous.observation.pelvis.y) / elapsedSeconds,
                    ),
                )
            }
        }
    }

    private data class TrackVelocity(
        val xPerSecond: Double,
        val yPerSecond: Double,
    )

    private data class AssignmentEvidence(
        val observationId: Int,
        val cost: Double,
    )

    private data class AssignmentAlternative(
        val observations: Map<PlayerId, PlayerTrackObservation>,
        val evidence: Map<PlayerId, AssignmentEvidence>,
        val totalCost: Double,
    )

    private data class TwoRoleMatch(
        val observations: Map<PlayerId, PlayerTrackObservation>,
        val evidence: Map<PlayerId, AssignmentEvidence>,
    )

    private data class CrossingCandidate(
        val features: Map<PlayerId, TrackFeatures>,
        val homeLaneX: Map<PlayerId, Double>,
        val targetOrder: Int,
        val startedAtNanos: Long,
        val frameCount: Int,
    )

    private data class RearmCandidate(
        val features: Map<PlayerId, TrackFeatures>,
        val homeLaneX: Map<PlayerId, Double>,
        val neutralSinceNanos: Long?,
    )

    private companion object {
        val ROLE_ORDER = listOf(PlayerId.P1, PlayerId.P2)
        const val MAX_OBSERVATIONS = 2
        const val NANOS_PER_SECOND = 1_000_000_000.0

        fun distance(first: NormalizedPoint, second: NormalizedPoint): Double =
            hypot(first.x - second.x, first.y - second.y)

        fun spatialOrder(observations: Map<PlayerId, PlayerTrackObservation>): Int =
            observations.getValue(PlayerId.P1).pelvis.x.compareTo(
                observations.getValue(PlayerId.P2).pelvis.x,
            )

        fun intersectionOverUnion(first: NormalizedBounds, second: NormalizedBounds): Double {
            val intersectionWidth = max(0.0, min(first.right, second.right) - max(first.left, second.left))
            val intersectionHeight = max(0.0, min(first.bottom, second.bottom) - max(first.top, second.top))
            val intersection = intersectionWidth * intersectionHeight
            if (intersection == 0.0) return 0.0
            val firstArea = (first.right - first.left) * (first.bottom - first.top)
            val secondArea = (second.right - second.left) * (second.bottom - second.top)
            return intersection / (firstArea + secondArea - intersection)
        }
    }
}
