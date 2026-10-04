package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleTeleport
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Sign
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Knowledge levels: what the player hasn't seen is shown only with a walkthrough (`showHidden`). Hidden items are never
 * seen; a teleport is seen once one of its tiles has been on screen ([Sightings]).
 */
class HiddenKnowledgeViewTest {

    private val area = Area(1, "cave", 0, 0, 40, 5, Array(200) { TileInfo(false, TileKind.Floor) }, signs = listOf(Sign(1, 2, 3, 2, 8001, SignKind.HIDDEN_ITEM, 801)))

    private fun field(x: Int, puzzle: PuzzleState? = null) = FieldState(1, "cave", x, 2, 0, Direction.EAST, MovementMode.WALK, moving = false, puzzle = puzzle)

    @Test
    fun hiddenItemsOnlyWithAWalkthrough() {
        val shown = MapView.render(area, field(1)).toString()
        assertTrue("hidden_item:2" in shown && "\$ hidden item" in shown, shown)
        val hidden = MapView.render(area, field(1), showHidden = false).toString()
        assertFalse("hidden_item" in hidden || "\$ hidden item" in hidden, hidden)
    }

    @Test
    fun teleportsOnlyOnceSeenOnScreen() {
        val puzzle = PuzzleState(
            PuzzleKind.TELEPORT_PADS, "rule",
            teleports = listOf(
                PuzzleTeleport("teleport:0", TeleportKind.PAD, listOf(PuzzleTile(4, 2)), PuzzleTile(0, 0)),
                PuzzleTeleport("teleport:1", TeleportKind.PAD, listOf(PuzzleTile(30, 2)), PuzzleTile(0, 0)),
            ),
        )
        fun state(x: Int) = GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field(x, puzzle))
        // With a walkthrough: both. Without: only the one on screen.
        val all = StateView.state(state(1), showHidden = true).toString()
        assertTrue("teleport:0" in all && "teleport:1" in all)
        val sightings = Sightings()
        sightings.observe(state(1).field)
        val near = StateView.state(state(1), showHidden = false, sightings = sightings).toString()
        assertTrue("teleport:0" in near && "teleport:1" !in near, near)
        // Walked near the far one: now both are known, even back at the start.
        sightings.observe(state(26).field)
        val later = StateView.state(state(1), showHidden = false, sightings = sightings).toString()
        assertTrue("teleport:0" in later && "teleport:1" in later, later)
    }
}
