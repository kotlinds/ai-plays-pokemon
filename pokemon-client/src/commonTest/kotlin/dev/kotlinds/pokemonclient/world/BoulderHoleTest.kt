package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Boulders dropping through holes ([LiveObject.fallsInto], Ice Path B1F) in the [PushPlanner]. */
class BoulderHoleTest {

    private fun area(vararg rows: String): Area {
        val width = rows.maxOf { it.length }
        val tiles = Array<TileInfo?>(width * rows.size) { i ->
            when (rows[i / width].getOrElse(i % width) { ' ' }) {
                '.' -> TileInfo(false, TileKind.Floor, listOf(0))
                else -> TileInfo(true, TileKind.Wall)
            }
        }
        return Area(1, "test", 0, 0, width, rows.size, tiles)
    }

    // A corridor: the boulder can only go east, the hole (an active trigger) at its end, and the way on turns south
    // just before the hole: the boulder must drop for the player to get past.
    private val corridor = area(
        "#######",
        "......#",
        "####.##",
        "####.##",
    )
    private val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.STRENGTH))

    @Test
    fun aBoulderPushedIntoItsHoleIsGoneAndFreesTheWay() {
        val overlay = Overlay(
            objects = listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH, fallsInto = 5 to 1)),
            activeTriggers = setOf(5 to 1),
        )
        val route = assertIs<Route>(PushPlanner(corridor, overlay).route(Node(0, 1), options, setOf(4 to 3)) { it.x == 4 && it.y == 3 })
        val pushes = route.edges.filterIsInstance<PushEdge>()
        assertEquals(5 to 1, pushes.last().objectTo)
        assertEquals(Direction.EAST, pushes.last().direction)
        assertEquals(Node(4, 3), route.end)
    }

    @Test
    fun anotherBouldersHoleRefusesIt() {
        // Same corridor, but this boulder belongs to another hole: it can't be pushed onto 5,1, so it always blocks.
        val overlay = Overlay(
            objects = listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH, fallsInto = 9 to 9)),
            activeTriggers = setOf(5 to 1),
        )
        assertNull(PushPlanner(corridor, overlay).route(Node(0, 1), options, setOf(4 to 3)) { it.x == 4 && it.y == 3 })
    }
}
