package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PuzzleBarrier
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleTeleport
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text map draws the live puzzle: lifts (`L`), other teleport tiles (`W`) and closed gates (`G`). */
class PuzzleMapViewTest {

    @Test
    fun liftsTeleportsAndClosedGatesAreDrawn() {
        val area = Area(7, "gym", 0, 0, 5, 1, Array(5) { TileInfo(false, TileKind.Floor) })
        val puzzle = PuzzleState(
            PuzzleKind.LIFT, "rule",
            barriers = listOf(PuzzleBarrier("gate:0", false, listOf(PuzzleTile(3, 0))), PuzzleBarrier("gate:1", true, listOf(PuzzleTile(4, 0)))),
            teleports = listOf(
                PuzzleTeleport("lift:up", TeleportKind.LIFT, listOf(PuzzleTile(0, 0)), PuzzleTile(0, 0), 4, 62),
                PuzzleTeleport("teleport:0", TeleportKind.PAD, listOf(PuzzleTile(1, 0)), PuzzleTile(4, 0)),
            ),
        )
        val field = FieldState(7, "gym", 2, 0, 0, Direction.EAST, MovementMode.WALK, moving = false, puzzle = puzzle)
        val view = MapView.render(area, field, width = 5, height = 1)
        assertEquals("   0 L W } G .", view.getValue("map").jsonArray[1].jsonPrimitive.content)
        val legend = view.getValue("legend").jsonPrimitive.content
        assertTrue("L lift" in legend && "W teleport" in legend && "G closed gate" in legend, legend)
    }
}
