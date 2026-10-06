package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.MapName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NOTES.md: "`version` stays 2444 over several calls that moved the player". */
class StateVersionTest {

    private fun field(x: Int, y: Int, map: Int = 1, facing: Direction = Direction.NORTH) =
        FieldState(map, MapName(map, map = "Route"), x, y, 0, facing, MovementMode.WALK, false)

    @Test
    fun theVersionChangesWhenThePlayerMovesOnTheSameScreen() {
        val version = StateVersion()
        version.observe(field(10, 10))
        val before = version.of(2444)
        version.observe(field(10, 9))
        assertTrue(version.of(2444) > before, "a move on the same screen gives a newer version")
    }

    @Test
    fun turningOrStandingStillKeepsTheVersion() {
        val version = StateVersion()
        version.observe(field(10, 10))
        val before = version.of(7)
        version.observe(field(10, 10, facing = Direction.EAST))
        version.observe(null)
        assertEquals(before, version.of(7))
    }

    @Test
    fun theVersionOnlyGrows() {
        val version = StateVersion()
        var last = version.of(0)
        listOf(field(1, 1), field(1, 2), field(1, 1, map = 2), field(1, 1, map = 2)).forEachIndexed { i, f ->
            version.observe(f)
            val now = version.of(i.toLong())
            assertTrue(now >= last)
            last = now
        }
        assertEquals(3L + 2L, last, "screen seq 3 plus 2 moves")
    }
}
