package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** [PushPlanner.pushInto]: one chosen boulder into its own hole, the others left where they are (the `push` action). */
class PushIntoHoleTest {

    private fun area(vararg rows: String): Area {
        val width = rows.maxOf { it.length }
        return Area(1, "test", 0, 0, width, rows.size, Array(width * rows.size) { i ->
            if (rows[i / width].getOrElse(i % width) { '#' } == '.') TileInfo(false, TileKind.Floor, listOf(0)) else TileInfo(true, TileKind.Wall)
        })
    }

    private val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.STRENGTH))

    @Test
    fun theChosenBoulderIsPushedIntoItsHoleAndThePlayerStaysBehindEachPush() {
        // Boulder A (2,1) belongs to the hole at 5,1; boulder B (2,3) to the hole at 5,3.
        val map = area(
            "#######",
            ".......",
            ".......",
            ".......",
        )
        val overlay = Overlay(
            objects = listOf(
                LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH, fallsInto = 5 to 1),
                LiveObject(2, 3, null, clearedBy = FieldMoveKind.STRENGTH, fallsInto = 5 to 3),
            ),
            activeTriggers = setOf(5 to 1, 5 to 3),
        )
        val route = assertIs<Route>(PushPlanner(map, overlay).pushInto(Node(0, 1), options, 2 to 1))
        val pushes = route.edges.filterIsInstance<PushEdge>()
        assertEquals(listOf(2 to 1, 3 to 1, 4 to 1), pushes.map { it.objectFrom })
        assertEquals(5 to 1, pushes.last().objectTo)
        assertEquals(Direction.EAST, pushes.last().direction)
        // The player doesn't follow the boulder: each push ends where it started (then a step follows it).
        route.edges.zipWithNext().filter { (_, next) -> next is PushEdge }.forEach { (before, push) -> assertEquals(before.to, push.to) }
        // Not a boulder with a hole, or without Strength: no plan.
        assertNull(PushPlanner(map, overlay).pushInto(Node(0, 1), options, 6 to 2))
        assertNull(PushPlanner(map, overlay).pushInto(Node(0, 1), RouteOptions(), 2 to 1))
    }

    @Test
    fun theOtherBouldersAreNeverMoved() {
        // B (3,1) stands right in A's way to its hole at 5,1, in a one-tile corridor: moving B would be needed, so no plan.
        val map = area(
            "#######",
            "......#",
            "#######",
        )
        val overlay = Overlay(
            objects = listOf(
                LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH, fallsInto = 5 to 1),
                LiveObject(3, 1, null, clearedBy = FieldMoveKind.STRENGTH, fallsInto = 9 to 9),
            ),
            activeTriggers = setOf(5 to 1),
        )
        assertNull(PushPlanner(map, overlay).pushInto(Node(0, 1), options, 2 to 1))
    }
}
