package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.agent.actions.PressButton
import me.nathanfallet.aiplayspokemon.emulator.Button
import me.nathanfallet.aiplayspokemon.game.Direction
import me.nathanfallet.aiplayspokemon.game.GameMode
import me.nathanfallet.aiplayspokemon.game.LocalMap
import me.nathanfallet.aiplayspokemon.game.Location
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentMemoryTest {

    private fun at(
        x: Int, y: Int, facing: Direction = Direction.SOUTH, mode: GameMode = GameMode.OVERWORLD, map: Int = 1,
        dialogue: String? = null, facts: Map<String, String> = emptyMap(), localMap: LocalMap? = null,
    ) = Observation(mode, Location(map, "Town $map", x, y, facing), "", JsonObject(emptyMap()), facts = facts, dialogue = dialogue, map = localMap)

    private fun AgentMemory.actions() = describe(false, null)["recent_actions"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `describes what each action changed`() {
        val memory = AgentMemory()
        memory.record(PressButton(Button.UP), at(5, 5), at(5, 4))
        memory.record(PressButton(Button.LEFT), at(5, 4), at(5, 4, facing = Direction.WEST))
        memory.record(PressButton(Button.LEFT), at(5, 4, facing = Direction.WEST), at(5, 4, facing = Direction.WEST))
        memory.record(PressButton(Button.UP), at(5, 4), at(3, 9, map = 2))
        memory.record(PressButton(Button.A), at(3, 9, map = 2), at(3, 9, mode = GameMode.DIALOGUE, map = 2, facts = mapOf("dialogue" to "Hello!")))

        assertEquals(
            listOf(
                "you moved from (5, 5) to (5, 4)",
                "you turned to face west without moving",
                "you did not move",
                "you arrived in Town 2 at (3, 9)",
                "you did not move; screen changed from overworld to dialogue; dialogue: none -> Hello!",
            ),
            memory.actions().map { it["what_changed"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `counts repeats in a row and in the same situation`() {
        val memory = AgentMemory()
        repeat(4) { memory.record(PressButton(Button.A), at(5, 5), at(5, 5)) }
        val action = memory.actions().single()
        assertEquals(4, action["times_in_a_row"]!!.jsonPrimitive.int)
        // The first press "discovers" the tile the player stands on; the next three bring nothing new.
        assertEquals(3, memory.decisionsWithoutProgress)
    }

    @Test
    fun `progress resets the stagnation counter`() {
        val memory = AgentMemory()
        memory.record(PressButton(Button.A), at(5, 5), at(5, 5))
        memory.record(PressButton(Button.UP), at(5, 5), at(5, 4))
        assertEquals(0, memory.decisionsWithoutProgress)
    }

    @Test
    fun `keeps the model note and renders the explored map`() {
        val memory = AgentMemory()
        val room = LocalMap(0, 0, listOf(listOf(Tile.BLOCKED, Tile.WARP), listOf(Tile.WALKABLE, Tile.WALKABLE)))
        memory.observeMap(at(0, 1, localMap = room))
        memory.updateNote("Take the stairs")
        val description = memory.describe(true, at(1, 1, localMap = room))
        assertEquals("Take the stairs", description["your_note"]!!.jsonPrimitive.content)
        val rows = description["explored_map"]!!.jsonObject["rows"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("y  0 #W", "y  1 o@"), rows)
        assertTrue(memory.describe(false, null)["explored_map"] == null)
    }
}
