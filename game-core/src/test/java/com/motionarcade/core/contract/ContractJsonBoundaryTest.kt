package com.motionarcade.core.contract

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractJsonBoundaryTest {
    @Test
    fun motionEvent_acceptsSchemaBoundaryValues() {
        val value = validMotionEvent().toMutableMap().apply {
            this["sequenceNumber"] = Long.MAX_VALUE
            this["eventId"] = "session-1/P1/${Long.MAX_VALUE}"
            this["eventTimestampNs"] = Long.MAX_VALUE
            this["calibrationRevision"] = Int.MAX_VALUE
            this["quality"] = 0.0
            this["confidence"] = 1.0
        }

        val result = ContractJsonBoundary.motionEvent(value)

        assertTrue(result is ContractResult.Valid)
        val event = (result as ContractResult.Valid).value
        assertEquals(Long.MAX_VALUE, event.sequenceNumber)
        assertEquals(Int.MAX_VALUE, event.calibrationRevision)
    }

    @Test
    fun motionEvent_rejectsMissingAndUnknownProperties() {
        val missing = validMotionEvent().toMutableMap().apply { remove("metadata") }
        val unknown = validMotionEvent().toMutableMap().apply { this["frame"] = "forbidden" }

        assertViolation<ContractViolation.MissingProperty>(ContractJsonBoundary.motionEvent(missing))
        assertViolation<ContractViolation.UnknownProperty>(ContractJsonBoundary.motionEvent(unknown))
    }

    @Test
    fun motionEvent_rejectsUnknownExecutableTypeAndNonFiniteNumbers() {
        val unknownType = validMotionEvent().toMutableMap().apply { this["type"] = "FUTURE_ACTION" }
        val infinite = validMotionEvent().toMutableMap().apply { this["confidence"] = Double.POSITIVE_INFINITY }
        val metadataNan = validMotionEvent().toMutableMap().apply {
            this["metadata"] = mapOf("speed" to Double.NaN)
        }

        assertViolation<ContractViolation.UnknownEnumValue>(ContractJsonBoundary.motionEvent(unknownType))
        assertViolation<ContractViolation.OutOfRange>(ContractJsonBoundary.motionEvent(infinite))
        assertViolation<ContractViolation.OutOfRange>(ContractJsonBoundary.motionEvent(metadataNan))
    }

    @Test
    fun motionEvent_domainConversionRejectsSchemaValidButNonDeterministicId() {
        val mismatched = validMotionEvent().toMutableMap().apply { this["eventId"] = "arbitrary" }

        assertViolation<ContractViolation.InvalidFormat>(ContractJsonBoundary.motionEvent(mismatched))
        assertEquals(6, MotionTypeRegistry.VERSION)
    }

    @Test
    fun motionTypeRegistry_v6HasExactMembershipAndStrictWireConversion() {
        val expected = setOf(
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
            MotionType.BOXING_GUARD,
            MotionType.DODGE_LEFT,
            MotionType.DODGE_RIGHT,
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
            MotionType.MONSTER_BLOCK,
            MotionType.MONSTER_REVIVE,
            MotionType.MONSTER_SKILL_ONE,
            MotionType.MONSTER_SKILL_TWO,
            MotionType.MONSTER_MAGIC_CHARGE,
            MotionType.TEAM_ULTIMATE,
        )

        assertEquals(expected, MotionTypeRegistry.executableTypes)
        expected.forEach { type -> assertEquals(type, MotionTypeRegistry.fromWire(type.name)) }
        assertEquals(null, MotionTypeRegistry.fromWire("fish_cast"))
        assertEquals(null, MotionTypeRegistry.fromWire(" FISH_CAST"))
        assertEquals(null, MotionTypeRegistry.fromWire("FISH_CAST "))
        assertEquals(null, MotionTypeRegistry.fromWire("FISH_CAST_V3"))
    }

    @Test
    fun motionTypeRegistry_versionCompatibilityKeepsEarlierVersionsClosed() {
        val v1 = setOf(
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
            MotionType.DODGE_LEFT,
            MotionType.FISH_CAST,
            MotionType.FISH_REEL_CYCLE,
            MotionType.MONSTER_BLOCK,
            MotionType.TEAM_ULTIMATE,
        )
        val v2 = setOf(
            MotionType.PUNCH_JAB,
            MotionType.PUNCH_HOOK,
            MotionType.DODGE_LEFT,
            MotionType.FISH_READY,
            MotionType.FISH_CAST,
            MotionType.FISH_HOOK,
            MotionType.FISH_REEL_CYCLE,
            MotionType.FISH_TENSION_LEFT,
            MotionType.FISH_TENSION_RIGHT,
            MotionType.FISH_NET,
            MotionType.MONSTER_BLOCK,
            MotionType.TEAM_ULTIMATE,
        )
        val addedInV2 = v2 - v1
        val addedInV3 = requireNotNull(MotionTypeRegistry.executableTypesForVersion(3)) - v2

        assertEquals(v1, MotionTypeRegistry.executableTypesForVersion(1))
        assertEquals(v2, MotionTypeRegistry.executableTypesForVersion(2))
        assertEquals(
            MotionTypeRegistry.executableTypes -
                setOf(
                    MotionType.MONSTER_REVIVE,
                    MotionType.MONSTER_SKILL_ONE,
                    MotionType.MONSTER_SKILL_TWO,
                    MotionType.MONSTER_MAGIC_CHARGE,
                ),
            MotionTypeRegistry.executableTypesForVersion(3),
        )
        assertEquals(
            MotionTypeRegistry.executableTypes -
                setOf(
                    MotionType.MONSTER_SKILL_ONE,
                    MotionType.MONSTER_SKILL_TWO,
                    MotionType.MONSTER_MAGIC_CHARGE,
                ),
            MotionTypeRegistry.executableTypesForVersion(4),
        )
        assertEquals(
            MotionTypeRegistry.executableTypes - MotionType.MONSTER_MAGIC_CHARGE,
            MotionTypeRegistry.executableTypesForVersion(5),
        )
        assertEquals(MotionTypeRegistry.executableTypes, MotionTypeRegistry.executableTypesForVersion(6))
        assertEquals(
            setOf(
                MotionType.FISH_READY,
                MotionType.FISH_HOOK,
                MotionType.FISH_TENSION_LEFT,
                MotionType.FISH_TENSION_RIGHT,
                MotionType.FISH_NET,
            ),
            addedInV2,
        )
        v1.forEach { type -> assertTrue(MotionTypeRegistry.isExecutableInVersion(1, type)) }
        addedInV2.forEach { type -> assertFalse(MotionTypeRegistry.isExecutableInVersion(1, type)) }
        assertEquals(setOf(MotionType.BOXING_GUARD, MotionType.DODGE_RIGHT), addedInV3)
        addedInV3.forEach { type -> assertFalse(MotionTypeRegistry.isExecutableInVersion(2, type)) }
        assertFalse(MotionTypeRegistry.isExecutableInVersion(3, MotionType.MONSTER_REVIVE))
        assertTrue(MotionTypeRegistry.isExecutableInVersion(4, MotionType.MONSTER_REVIVE))
        assertFalse(MotionTypeRegistry.isExecutableInVersion(4, MotionType.MONSTER_SKILL_ONE))
        assertFalse(MotionTypeRegistry.isExecutableInVersion(4, MotionType.MONSTER_SKILL_TWO))
        assertTrue(MotionTypeRegistry.isExecutableInVersion(5, MotionType.MONSTER_SKILL_ONE))
        assertTrue(MotionTypeRegistry.isExecutableInVersion(5, MotionType.MONSTER_SKILL_TWO))
        assertFalse(MotionTypeRegistry.isExecutableInVersion(5, MotionType.MONSTER_MAGIC_CHARGE))
        assertTrue(MotionTypeRegistry.isExecutableInVersion(6, MotionType.MONSTER_MAGIC_CHARGE))
        assertEquals(null, MotionTypeRegistry.executableTypesForVersion(0))
        assertEquals(null, MotionTypeRegistry.executableTypesForVersion(7))
    }

    @Test
    fun motionEvent_rejectsIntegerOutsideKotlinIntersection() {
        val overflow = validMotionEvent().toMutableMap().apply {
            this["sequenceNumber"] = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)
        }

        val violation = assertViolation<ContractViolation.OutOfRange>(
            ContractJsonBoundary.motionEvent(overflow),
        )
        assertEquals("$/sequenceNumber", violation.path)
    }

    @Test
    fun calibration_enforcesClosedPlayerShapeAndFinitePositiveDimensions() {
        val valid = validCalibration()
        assertTrue(ContractJsonBoundary.calibrationProfile(valid) is ContractResult.Valid)

        val playerWithExtra = validPlayer().toMutableMap().apply { this["rawPose"] = true }
        val extra = valid.toMutableMap().apply { this["players"] = listOf(playerWithExtra) }
        val zeroShoulder = valid.toMutableMap().apply {
            this["players"] = listOf(validPlayer().toMutableMap().apply { this["shoulderWidth"] = 0.0 })
        }
        val ai = valid.toMutableMap().apply {
            this["players"] = listOf(validPlayer().toMutableMap().apply { this["playerId"] = "AI" })
        }

        assertViolation<ContractViolation.UnknownProperty>(ContractJsonBoundary.calibrationProfile(extra))
        assertViolation<ContractViolation.OutOfRange>(ContractJsonBoundary.calibrationProfile(zeroShoulder))
        assertViolation<ContractViolation.UnknownEnumValue>(ContractJsonBoundary.calibrationProfile(ai))
    }

    @Test
    fun poseFrame_preservesOpenLandmarkNamesButClosesLandmarkValues() {
        val valid = validPoseFrame()
        val result = ContractJsonBoundary.poseFrame(valid)
        assertTrue(result is ContractResult.Valid)
        val frame = (result as ContractResult.Valid).value
        assertTrue("custom_joint" in frame.poses.single().landmarks)

        val badLandmark = validLandmark().toMutableMap().apply { this["pixelX"] = 123 }
        val badPose = validPose().toMutableMap().apply {
            this["landmarks"] = mapOf("custom_joint" to badLandmark)
        }
        val invalid = valid.toMutableMap().apply { this["poses"] = listOf(badPose) }

        assertViolation<ContractViolation.UnknownProperty>(ContractJsonBoundary.poseFrame(invalid))
    }

    @Test
    fun poseFrame_rejectsNonFiniteCoordinateAndMoreThanTwoPoses() {
        val nanPose = validPose().toMutableMap().apply {
            this["landmarks"] = mapOf(
                "custom_joint" to validLandmark().toMutableMap().apply { this["x"] = Double.NaN },
            )
        }
        val nanFrame = validPoseFrame().toMutableMap().apply { this["poses"] = listOf(nanPose) }
        val threePoses = validPoseFrame().toMutableMap().apply {
            this["poses"] = listOf(validPose(), validPose(), validPose())
        }

        assertViolation<ContractViolation.OutOfRange>(ContractJsonBoundary.poseFrame(nanFrame))
        assertViolation<ContractViolation.OutOfRange>(ContractJsonBoundary.poseFrame(threePoses))
    }

    @Test
    fun snapshot_normalizesMissingAndNullPauseReasonToNone() {
        val missing = validSnapshot()
        val explicitNull = validSnapshot().toMutableMap().apply { this["pauseReason"] = null }

        val missingValue = (ContractJsonBoundary.sessionSnapshot(missing) as ContractResult.Valid).value
        val nullValue = (ContractJsonBoundary.sessionSnapshot(explicitNull) as ContractResult.Valid).value

        assertEquals(null, missingValue.pauseReason)
        assertEquals(null, nullValue.pauseReason)
    }

    @Test
    fun snapshot_rejectsFutureSchemaUnknownPauseAndDuplicateRewards() {
        val future = validSnapshot().toMutableMap().apply { this["schemaVersion"] = 2 }
        val pause = validSnapshot().toMutableMap().apply { this["pauseReason"] = "UNLISTED_REASON" }
        val duplicate = validSnapshot().toMutableMap().apply {
            this["committedRewardIds"] = listOf("reward-1", "reward-1")
        }

        assertViolation<ContractViolation.OutOfRange>(ContractJsonBoundary.sessionSnapshot(future))
        assertViolation<ContractViolation.UnknownEnumValue>(ContractJsonBoundary.sessionSnapshot(pause))
        assertViolation<ContractViolation.DuplicateValue>(ContractJsonBoundary.sessionSnapshot(duplicate))
    }

    @Test
    fun snapshot_temporaryBoundaryPreservesLargeStateIntegerWithoutDouble() {
        val large = BigInteger("9007199254740993")
        val value = validSnapshot().toMutableMap().apply {
            this["state"] = mapOf("large" to large, "nested" to mapOf("ok" to true))
        }

        val snapshot = (ContractJsonBoundary.sessionSnapshot(value) as ContractResult.Valid).value

        assertEquals(
            "{\"large\":9007199254740993,\"nested\":{\"ok\":true}}",
            snapshot.state.canonicalUtf8().toString(Charsets.UTF_8),
        )
    }

    @Test
    fun diagnostics_rejectsRawOrCoordinateClaimsEvenWithConsent() {
        val raw = validDiagnostic().toMutableMap().apply { this["containsRawImages"] = true }
        val coordinates = validDiagnostic().toMutableMap().apply {
            this["containsPoseCoordinates"] = true
            this["userConsentedToPoseExport"] = true
        }

        assertViolation<ContractViolation.SensitiveDiagnosticField>(
            ContractJsonBoundary.diagnosticBundle(raw),
        )
        assertViolation<ContractViolation.SensitiveDiagnosticField>(
            ContractJsonBoundary.diagnosticBundle(coordinates),
        )
    }

    @Test
    fun diagnostics_rejectsEveryMetricEventAndSessionKeyOutsideAllowlists() {
        val metric = validDiagnostic().toMutableMap().apply {
            this["metrics"] = mapOf("leftWristX" to 0.25)
        }
        val event = validDiagnostic().toMutableMap().apply {
            this["events"] = listOf(
                mapOf("type" to "CAMERA_BOUND", "timestampNs" to 1, "frameBytes" to "..."),
            )
        }
        val session = validDiagnostic().toMutableMap().apply {
            @Suppress("UNCHECKED_CAST")
            val original = this["session"] as Map<String, Any?>
            this["session"] = original + ("stableBodySignature" to "forbidden")
        }

        assertViolation<ContractViolation.SensitiveDiagnosticField>(
            ContractJsonBoundary.diagnosticBundle(metric),
        )
        assertViolation<ContractViolation.SensitiveDiagnosticField>(
            ContractJsonBoundary.diagnosticBundle(event),
        )
        assertViolation<ContractViolation.SensitiveDiagnosticField>(
            ContractJsonBoundary.diagnosticBundle(session),
        )
    }

    @Test
    fun diagnostics_acceptsTypedAggregateAllowlist() {
        val result = ContractJsonBoundary.diagnosticBundle(validDiagnostic())

        assertTrue(result is ContractResult.Valid)
        val bundle = (result as ContractResult.Valid).value
        assertFalse(bundle.containsRawImages)
        assertEquals(60.0, bundle.metrics[DiagnosticMetric.SOURCE_FPS] ?: -1.0, 0.0)
    }

    private fun validMotionEvent(): Map<String, Any?> = mapOf(
        "eventId" to "session-1/P1/0",
        "sessionId" to "session-1",
        "playerId" to "P1",
        "sequenceNumber" to 0,
        "type" to "FISH_CAST",
        "quality" to 0.75,
        "confidence" to 0.9,
        "eventTimestampNs" to 100,
        "calibrationRevision" to 0,
        "source" to "MOTION",
        "metadata" to mapOf("speed" to 1.25),
    )

    private fun validPlayer(): Map<String, Any?> = mapOf(
        "playerId" to "P1",
        "shoulderWidth" to 0.4,
        "torsoLength" to 0.5,
        "neutralVariance" to 0.01,
        "dominantSide" to "RIGHT",
        "stance" to "ORTHODOX",
        "oneArmMode" to "OFF",
    )

    private fun validCalibration(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1,
        "revision" to 0,
        "mode" to "SOLO",
        "lensFacing" to "FRONT",
        "orientation" to "PORTRAIT",
        "players" to listOf(validPlayer()),
        "valid" to true,
    )

    private fun validLandmark(): Map<String, Any?> = mapOf(
        "x" to 0.1,
        "y" to 0.2,
        "z" to -0.3,
        "visibility" to 0.95,
    )

    private fun validPose(): Map<String, Any?> = mapOf(
        "trackId" to "track-1",
        "trackState" to "ACTIVE",
        "landmarks" to mapOf("custom_joint" to validLandmark()),
        "scaleConfidence" to 0.8,
    )

    private fun validPoseFrame(): Map<String, Any?> = mapOf(
        "frameId" to 0,
        "sourceTimestampNs" to 100,
        "calibrationRevision" to 0,
        "poses" to listOf(validPose()),
    )

    private fun validSnapshot(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1,
        "sessionId" to "session-1",
        "gameId" to "FISHING",
        "mode" to "SOLO",
        "simulationTick" to 0,
        "status" to "PAUSED",
        "seed" to -1,
        "prng" to mapOf(
            "algorithmId" to "xoroshiro-v1",
            "algorithmVersion" to 1,
            "state" to listOf(-1, 2),
        ),
        "contentRevision" to "content-v1",
        "state" to mapOf("phase" to "READY"),
        "players" to listOf(mapOf("score" to 0)),
        "committedRewardIds" to emptyList<String>(),
    )

    private fun validDiagnostic(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1,
        "appVersion" to "1.0",
        "device" to mapOf("model" to "test", "osVersion" to "1", "memoryClassMb" to 256),
        "session" to mapOf(
            "gameId" to "FISHING",
            "mode" to "SOLO",
            "lensFacing" to "FRONT",
            "orientation" to "PORTRAIT",
            "qualityTier" to "TEST",
        ),
        "metrics" to mapOf("SOURCE_FPS" to 60.0, "RENDER_P90_MS" to 16.0),
        "events" to listOf(mapOf("type" to "CAMERA_BOUND", "timestampNs" to 1)),
        "containsRawImages" to false,
        "containsPoseCoordinates" to false,
    )

    private inline fun <reified T : ContractViolation> assertViolation(
        result: ContractResult<*>,
    ): T {
        assertTrue("Expected Invalid, got $result", result is ContractResult.Invalid)
        val violation = (result as ContractResult.Invalid).violations.first()
        assertTrue("Expected ${T::class.java.simpleName}, got $violation", violation is T)
        return violation as T
    }
}
