package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.world.RouteFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A route failing on a map with a mechanism the planner doesn't model says so, instead of blaming walls. */
class UnmodeledMechanismHintTest {

    private fun field(puzzle: PuzzleState?) = FieldState(141, "gym", 13, 87, 0, Direction.NORTH, MovementMode.WALK, moving = false, puzzle = puzzle)

    @Test
    fun anUnreachableTargetOnAMapWithAnUnmodeledMechanismNamesIt() {
        val platforms = PuzzleState(PuzzleKind.UNMODELED, "rule", unmodeled = "three moving and rotating platforms")
        val hint = with(MovePlans) { RouteFailure.Unreachable.hint(field(platforms)) }!!
        assertTrue("doesn't model" in hint && "rotating platforms" in hint, hint)
        assertTrue("walls" !in hint, hint)
        // Other failures keep their own hint; maps without such a mechanism keep the plain one.
        assertEquals(with(MovePlans) { RouteFailure.OnlyOneWay.hint(null) }, with(MovePlans) { RouteFailure.OnlyOneWay.hint(field(platforms)) })
        assertTrue("walls" in with(MovePlans) { RouteFailure.Unreachable.hint(field(null)) }!!)
    }
}
