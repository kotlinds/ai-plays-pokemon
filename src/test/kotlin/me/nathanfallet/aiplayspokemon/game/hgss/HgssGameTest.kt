package me.nathanfallet.aiplayspokemon.game.hgss

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.game.GameMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks what the agent gets out of the HGSS reader on real snapshots. */
class HgssGameTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)

    @Test
    fun `bedroom is an overworld observation with a location and a map for the AI`() {
        val observation = game.observe(HgssFixtures.load("d9"))
        assertEquals(GameMode.OVERWORLD, observation.mode)
        val location = assertNotNull(observation.location)
        assertEquals(64, location.mapId)
        assertEquals(6 to 6, location.x to location.y)

        val state = observation.state
        assertEquals("You are free to walk around. No message box is open.", state["situation"]!!.jsonPrimitive.content)
        assertTrue(state["map"]!!.jsonObject["rows"]!!.jsonArray.any { '@' in it.toString() })
        val exit = state["exits"]!!.jsonArray.single().jsonObject
        assertEquals("New Bark Player House 1F (New Bark Town)", exit["leads_to"]!!.jsonPrimitive.content)
        assertEquals("3 tiles west and 2 tiles north", exit["where"]!!.jsonPrimitive.content)
        assertEquals("walkable", state["you"]!!.jsonObject["tile_in_front"]!!.jsonPrimitive.content)
    }

    @Test
    fun `intro screens map to the intro mode`() {
        assertEquals(GameMode.INTRO, game.observe(HgssFixtures.load("d1")).mode)
        assertEquals(GameMode.INTRO, game.observe(HgssFixtures.load("d5")).mode)
    }
}
