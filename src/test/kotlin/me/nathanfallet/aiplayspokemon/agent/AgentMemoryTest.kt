package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.game.GameMode
import me.nathanfallet.aiplayspokemon.game.Location
import me.nathanfallet.aiplayspokemon.game.Observation
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentMemoryTest {

    private fun at(x: Int, y: Int, mode: GameMode = GameMode.OVERWORLD, map: Int = 1) =
        Observation(mode, Location(map, "Town $map", x, y), "", JsonObject(emptyMap()))

    private fun AgentMemory.changes() = describe().map { it.jsonObject["what_changed"]!!.jsonPrimitive.content }

    @Test
    fun `records the facts that changed after each press`() {
        val memory = AgentMemory()
        memory.record(ButtonPress.UP, at(5, 5), at(5, 4))
        memory.record(ButtonPress.LEFT, at(5, 4), at(5, 4))
        memory.record(ButtonPress.UP, at(5, 4), at(3, 9, map = 2))
        memory.record(ButtonPress.A, at(3, 9, map = 2), at(3, 9, mode = GameMode.DIALOGUE, map = 2))

        assertEquals(
            listOf(
                "position (5, 5) -> (5, 4)",
                "position unchanged (5, 4)",
                "map changed to Town 2, position (3, 9)",
                "position unchanged (3, 9); mode overworld -> dialogue",
            ),
            memory.changes(),
        )
    }

    @Test
    fun `keeps only the most recent presses`() {
        val memory = AgentMemory(size = 2)
        repeat(5) { memory.record(ButtonPress.B, at(0, 0), at(0, 0)) }
        assertEquals(2, memory.describe().size)
    }
}
