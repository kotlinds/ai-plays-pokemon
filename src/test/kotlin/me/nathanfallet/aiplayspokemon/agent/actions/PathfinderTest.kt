package me.nathanfallet.aiplayspokemon.agent.actions

import me.nathanfallet.aiplayspokemon.agent.actions.Pathfinder.Point
import me.nathanfallet.aiplayspokemon.game.Direction
import me.nathanfallet.aiplayspokemon.game.LocalMap
import me.nathanfallet.aiplayspokemon.game.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PathfinderTest {

    /** `#` blocked, `.` walkable, `W` warp, `N` person. */
    private fun map(vararg rows: String) = LocalMap(0, 0, rows.map { row ->
        row.map {
            when (it) {
                '#' -> Tile.BLOCKED
                'W' -> Tile.WARP
                'N' -> Tile.OCCUPIED
                else -> Tile.WALKABLE
            }
        }
    })

    @Test
    fun `finds the shortest path around walls`() {
        val room = map(
            "#####",
            "#.#W#",
            "#.#.#",
            "#...#",
            "#####",
        )
        assertEquals(
            listOf(Direction.SOUTH, Direction.SOUTH, Direction.EAST, Direction.EAST, Direction.NORTH, Direction.NORTH),
            Pathfinder.path(room, Point(1, 1)) { it == Point(3, 1) },
        )
    }

    @Test
    fun `never walks over warps or people on the way`() {
        val corridor = map("..W..", ".#N#.", ".....")
        val path = Pathfinder.path(corridor, Point(0, 0)) { it == Point(4, 0) }!!
        assertEquals(8, path.size) // around through the bottom row, not over the warp
        assertNull(Pathfinder.path(map(".N."), Point(0, 0)) { it == Point(2, 0) })
    }

    @Test
    fun `lists reachable tiles with distances`() {
        val reachable = Pathfinder.reachable(map("..#."), Point(0, 0))
        assertEquals(mapOf(Point(0, 0) to 0, Point(1, 0) to 1), reachable)
    }
}
