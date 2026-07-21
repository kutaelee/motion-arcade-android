package com.motionarcade.app.checkpoint.typed

import com.motionarcade.wire.snapshot.v1.BoxingDualStateProto
import com.motionarcade.wire.snapshot.v1.BoxingPlayerProto
import com.motionarcade.wire.snapshot.v1.BoxingSoloStateProto
import com.motionarcade.wire.snapshot.v1.DeterministicPrngProto
import com.motionarcade.wire.snapshot.v1.FishingDualPlayerProto
import com.motionarcade.wire.snapshot.v1.FishingDualStateProto
import com.motionarcade.wire.snapshot.v1.FishingSoloPlayerProto
import com.motionarcade.wire.snapshot.v1.FishingSoloStateProto
import com.motionarcade.wire.snapshot.v1.FishingTimelineEpochProto
import com.motionarcade.wire.snapshot.v1.GameSessionSnapshotProto
import com.motionarcade.wire.snapshot.v1.GameStateProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidPlayerProto
import com.motionarcade.wire.snapshot.v1.MonsterRaidStateProto
import com.motionarcade.wire.snapshot.v1.PlayerStateProto
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TypedSessionSnapshotSchemaParityTest {
    @Test
    fun everyJsonPayloadPropertyHasExactlyOneProtoFieldNumber() {
        val schema = loadSchema()
        val definitions = schema.getJSONObject("$" + "defs")
        val mappings = mapOf(
            GameSessionSnapshotProto::class.java to schema.getJSONObject("properties"),
            DeterministicPrngProto::class.java to definitions.properties("prng"),
            FishingTimelineEpochProto::class.java to definitions.properties("timelineEpoch"),
            FishingSoloStateProto::class.java to definitions.properties("fishingSoloState"),
            FishingSoloPlayerProto::class.java to definitions.properties("fishingSoloPlayer"),
            FishingDualStateProto::class.java to definitions.properties("fishingDualState"),
            FishingDualPlayerProto::class.java to definitions.properties("fishingDualPlayer"),
            BoxingSoloStateProto::class.java to definitions.properties("boxingSoloState"),
            BoxingDualStateProto::class.java to definitions.properties("boxingDualState"),
            BoxingPlayerProto::class.java to definitions.properties("boxingPlayer"),
            MonsterRaidStateProto::class.java to definitions.properties("monsterState"),
            MonsterRaidPlayerProto::class.java to definitions.properties("monsterPlayer"),
        )

        mappings.forEach { (protoClass, jsonProperties) ->
            assertEquals(protoClass.simpleName, protoFieldNames(protoClass), jsonProperties.keysSet())
        }
    }

    @Test
    fun stateAndPlayerUnionsContainOnlyTheSixExecutableVariants() {
        val expected = setOf(
            "fishingSolo",
            "fishingDual",
            "boxingSolo",
            "boxingDual",
            "monsterSolo",
            "monsterDual",
        )
        val schema = loadSchema()
        val definitions = schema.getJSONObject("$" + "defs")
        val stateVariants = variantProperties(definitions, definitions.getJSONObject("gameState"))
        val playerVariants = variantProperties(definitions, definitions.getJSONObject("playerState"))

        assertEquals(expected, stateVariants)
        assertEquals(expected, playerVariants)
        assertEquals(expected, protoFieldNames(GameStateProto::class.java))
        assertEquals(expected, protoFieldNames(PlayerStateProto::class.java))
        assertFalse(schema.toString().contains("additionalProperties\":true"))
        assertFalse(schema.toString().contains("google.protobuf.Struct"))
    }

    @Test
    fun allNestedObjectDefinitionsAreClosed() {
        val definitions = loadSchema().getJSONObject("$" + "defs")
        definitions.keysSet().forEach { name ->
            val definition = definitions.getJSONObject(name)
            if (definition.optString("type") == "object") {
                assertTrue("$name must reject unknown properties", definition.has("additionalProperties"))
                assertFalse("$name must reject unknown properties", definition.getBoolean("additionalProperties"))
            }
        }
    }

    @Test
    fun rootBranchesFreezeExecutablePlayerOrderAndRewardPolicy() {
        val schema = loadSchema()
        val statusValues = schema.getJSONObject("properties")
            .getJSONObject("status")
            .getJSONArray("enum")
            .stringSet()
        assertEquals(setOf("RUNNING", "PAUSED", "COMPLETED"), statusValues)

        val expectedPlayerDefinitions = mapOf(
            "FISHING/SOLO" to listOf("fishingSoloP1"),
            "FISHING/DUAL" to listOf("fishingDualP1", "fishingDualP2"),
            "BOXING/SOLO" to listOf("boxingSoloP1"),
            "BOXING/DUAL" to listOf("boxingDualP1", "boxingDualP2"),
            "MONSTER/SOLO" to listOf("monsterSoloP1", "monsterSoloAi"),
            "MONSTER/DUAL" to listOf("monsterDualP1", "monsterDualP2"),
        )
        val branches = schema.getJSONArray("oneOf")
        val actual = mutableMapOf<String, List<String>>()
        for (index in 0 until branches.length()) {
            val properties = branches.getJSONObject(index).getJSONObject("properties")
            val key = properties.getJSONObject("gameId").getString("const") + "/" +
                properties.getJSONObject("mode").getString("const")
            val players = properties.getJSONObject("players")
            val prefixItems = players.getJSONArray("prefixItems")
            assertEquals(prefixItems.length(), players.getInt("minItems"))
            assertEquals(prefixItems.length(), players.getInt("maxItems"))
            assertFalse(players.getBoolean("items"))
            actual[key] = (0 until prefixItems.length()).map { itemIndex ->
                prefixItems.getJSONObject(itemIndex).getString("$" + "ref").substringAfterLast('/')
            }
            if (key != "FISHING/SOLO") {
                assertEquals(0, properties.getJSONObject("committedRewardIds").getInt("maxItems"))
            }
        }
        assertEquals(expectedPlayerDefinitions, actual)
    }

    @Test
    fun pauseAndMonsterPendingUltimateNullabilityAreClosed() {
        val schema = loadSchema()
        val pauseRule = schema.getJSONArray("allOf").getJSONObject(0)
        assertEquals(
            "PAUSED",
            pauseRule.getJSONObject("if").getJSONObject("properties")
                .getJSONObject("status").getString("const"),
        )
        assertEquals("pauseReason", pauseRule.getJSONObject("then").getJSONArray("required").getString(0))
        assertEquals(
            "null",
            pauseRule.getJSONObject("else").getJSONObject("properties")
                .getJSONObject("pauseReason").getString("type"),
        )

        val monsterSolo = schema.getJSONObject("$" + "defs")
            .getJSONObject("monsterSoloStateVariant")
            .getJSONObject("properties")
            .getJSONObject("monsterSolo")
            .getJSONArray("allOf")
            .getJSONObject(1)
            .getJSONObject("properties")
        assertTrue(monsterSolo.getJSONObject("pendingUltimatePlayer").isNull("const"))
        assertTrue(monsterSolo.getJSONObject("pendingUltimateTimestampNs").isNull("const"))

        val monsterPendingRule = schema.getJSONObject("$" + "defs")
            .getJSONObject("monsterState")
            .getJSONArray("allOf")
            .getJSONObject(0)
        val pendingProperties = monsterPendingRule.getJSONObject("then").getJSONObject("properties")
        assertEquals(
            "pendingUltimateTimestampNs",
            monsterPendingRule.getJSONObject("then").getJSONArray("required").getString(0),
        )
        assertEquals("BOSS", pendingProperties.getJSONObject("stage").getString("const"))
        assertEquals("PHASE_3", pendingProperties.getJSONObject("bossPhase").getString("const"))
        assertEquals(100, pendingProperties.getJSONObject("teamCharge").getInt("const"))
        assertEquals(
            "null",
            monsterPendingRule.getJSONObject("else").getJSONObject("properties")
                .getJSONObject("pendingUltimateTimestampNs").getString("type"),
        )
    }

    private fun loadSchema(): JSONObject {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("contracts/session-snapshot-g1.schema.json"),
        )
        return stream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }

    private fun JSONObject.properties(name: String): JSONObject =
        getJSONObject(name).getJSONObject("properties")

    private fun JSONObject.keysSet(): Set<String> =
        keys().asSequence().toSet()

    private fun org.json.JSONArray.stringSet(): Set<String> =
        (0 until length()).mapTo(linkedSetOf()) { index -> getString(index) }

    private fun protoFieldNames(protoClass: Class<*>): Set<String> =
        protoClass.fields
            .asSequence()
            .map { it.name }
            .filter { it.endsWith(FIELD_NUMBER_SUFFIX) }
            .map { it.removeSuffix(FIELD_NUMBER_SUFFIX).lowercase(Locale.ROOT).snakeToLowerCamel() }
            .toSet()

    private fun variantProperties(definitions: JSONObject, union: JSONObject): Set<String> =
        (0 until union.getJSONArray("oneOf").length()).mapTo(linkedSetOf()) { index ->
            val reference = union.getJSONArray("oneOf").getJSONObject(index).getString("$" + "ref")
            val definitionName = reference.substringAfterLast('/')
            val properties = definitions.getJSONObject(definitionName).getJSONObject("properties")
            require(properties.length() == 1)
            properties.keys().next()
        }

    private fun String.snakeToLowerCamel(): String {
        val parts = split('_')
        return buildString {
            append(parts.first())
            parts.drop(1).forEach { part ->
                append(part.replaceFirstChar(Char::uppercaseChar))
            }
        }
    }

    private companion object {
        const val FIELD_NUMBER_SUFFIX = "_FIELD_NUMBER"
    }
}
