package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.game.GameMode
import me.nathanfallet.aiplayspokemon.game.Location
import me.nathanfallet.aiplayspokemon.game.Observation
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentMemoryTest {

    private fun at(x: Int, y: Int, mode: GameMode = GameMode.OVERWORLD, map: Int = 1, dialogue: String? = null) =
        Observation(mode, Location(map, "Town $map", x, y), "", JsonObject(emptyMap()), dialogue)

    private fun AgentMemory.list(key: String) = describe()[key]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `records the facts that changed after each press`() {
        val memory = AgentMemory()
        memory.record(ButtonPress.UP, at(5, 5), at(5, 4))
        memory.record(ButtonPress.UP, at(5, 4), at(3, 9, map = 2))
        memory.record(ButtonPress.A, at(3, 9, map = 2), at(3, 9, mode = GameMode.DIALOGUE, map = 2))

        assertEquals(
            listOf(
                "position (5, 5) -> (5, 4)",
                "map changed to Town 2, position (3, 9)",
                "position unchanged (3, 9); mode overworld -> dialogue",
            ),
            memory.list("recent_presses").map { it["what_changed"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `groups repeated presses with the same outcome`() {
        val memory = AgentMemory()
        repeat(8) { memory.record(ButtonPress.A, at(5, 5), at(5, 5)) }
        val press = memory.list("recent_presses").single()
        assertEquals("a", press["button"]!!.jsonPrimitive.content)
        assertEquals(8, press["times"]!!.jsonPrimitive.int)
    }

    @Test
    fun `remembers places, dialogues and thoughts`() {
        val memory = AgentMemory()
        memory.record(ButtonPress.DOWN, at(5, 5), at(5, 6), thought = "Let's leave the room")
        memory.record(ButtonPress.DOWN, at(5, 6), at(1, 1, map = 2))
        memory.record(ButtonPress.A, at(1, 1, map = 2), at(1, 1, map = 2, dialogue = "Hi! Go see the professor."))

        assertEquals(
            listOf("Town 1" to 1, "Town 2" to 2),
            memory.list("places_visited").map { it["place"]!!.jsonPrimitive.content to it["presses_spent"]!!.jsonPrimitive.int },
        )
        assertEquals("Hi! Go see the professor.", memory.describe()["dialogues_read"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("Let's leave the room", memory.describe()["your_recent_thoughts"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `keeps only the most recent presses`() {
        val memory = AgentMemory(maxPresses = 2)
        memory.record(ButtonPress.A, at(0, 0), at(0, 0))
        memory.record(ButtonPress.B, at(0, 0), at(0, 0))
        memory.record(ButtonPress.X, at(0, 0), at(0, 0))
        assertEquals(listOf("b", "x"), memory.list("recent_presses").map { it["button"]!!.jsonPrimitive.content })
    }
}
