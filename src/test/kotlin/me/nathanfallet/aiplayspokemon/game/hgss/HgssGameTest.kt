package me.nathanfallet.aiplayspokemon.game.hgss

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.nathanfallet.aiplayspokemon.game.GameMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks what the agent gets out of the HGSS reader on real snapshots. */
class HgssGameTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)

    @Test
    fun `bedroom is an overworld observation with a location and a map for Jev`() {
        val observation = game.observe(HgssFixtures.load("d9"))
        assertEquals(GameMode.OVERWORLD, observation.mode)
        val location = assertNotNull(observation.location)
        assertEquals(64, location.mapId)
        assertEquals(6 to 6, location.x to location.y)

        val map = observation.state["surroundings"]!!.jsonObject["map_around_player"]!!.jsonObject
        assertTrue(map["rows"]!!.jsonArray.any { '@' in it.toString() })
        assertTrue("frame" !in observation.state)
    }

    @Test
    fun `intro screens map to the intro mode`() {
        assertEquals(GameMode.INTRO, game.observe(HgssFixtures.load("d1")).mode)
        assertEquals(GameMode.INTRO, game.observe(HgssFixtures.load("d5")).mode)
    }
}
