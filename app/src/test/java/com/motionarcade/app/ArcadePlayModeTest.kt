package com.motionarcade.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ArcadePlayModeTest {
    @Test
    fun everyGameAndPlayerCountMapsToItsExecutableRoute() {
        val expected = mapOf(
            ArcadeGameChoice.FISHING to mapOf(
                ArcadePlayerCount.SOLO to ArcadePlayMode.FISHING,
                ArcadePlayerCount.DUAL to ArcadePlayMode.FISHING_DUAL,
            ),
            ArcadeGameChoice.BOXING to mapOf(
                ArcadePlayerCount.SOLO to ArcadePlayMode.BOXING_SOLO,
                ArcadePlayerCount.DUAL to ArcadePlayMode.BOXING_DUAL,
            ),
            ArcadeGameChoice.MONSTER to mapOf(
                ArcadePlayerCount.SOLO to ArcadePlayMode.MONSTER_SOLO,
                ArcadePlayerCount.DUAL to ArcadePlayMode.MONSTER_DUAL,
            ),
        )

        expected.forEach { (game, modes) ->
            modes.forEach { (players, route) ->
                assertEquals(route, arcadePlayMode(game, players))
            }
        }
    }
}
