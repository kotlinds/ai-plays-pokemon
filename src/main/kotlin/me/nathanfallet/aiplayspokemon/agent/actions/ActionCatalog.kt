package me.nathanfallet.aiplayspokemon.agent.actions

import me.nathanfallet.aiplayspokemon.agent.ControlMode
import me.nathanfallet.aiplayspokemon.agent.actions.Pathfinder.Point
import me.nathanfallet.aiplayspokemon.emulator.Button
import me.nathanfallet.aiplayspokemon.game.Direction
import me.nathanfallet.aiplayspokemon.game.GameMode
import me.nathanfallet.aiplayspokemon.game.MenuState
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.PointOfInterest

/**
 * Lists the actions available in a situation, depending on the control mode.
 *
 * Pure mode: the 12 buttons + wait. Assisted modes: the same, plus actions built from what's on
 * screen (reachable exits, people, objects; exploration directions; options of an open menu).
 */
object ActionCatalog {

    /** The 12 DS buttons + wait: the whole option set of pure mode. */
    val buttons: List<AgentAction> = Button.entries.map(::PressButton) + Wait

    fun actions(observation: Observation, mode: ControlMode): List<AgentAction> = when (mode) {
        ControlMode.PURE -> buttons
        ControlMode.ASSISTED, ControlMode.HYBRID -> assisted(observation) + buttons
    }

    private fun assisted(observation: Observation): List<AgentAction> = buildList {
        val location = observation.location
        val map = observation.map
        if (observation.mode == GameMode.OVERWORLD && location != null && map != null) {
            val here = Point(location.x, location.y)
            val reachable = Pathfinder.reachable(map, here)
            map.pointsOfInterest.forEachIndexed { index, poi ->
                when (poi.kind) {
                    PointOfInterest.Kind.EXIT -> Pathfinder.path(map, here) { it.x == poi.x && it.y == poi.y }
                        ?.let { add(TakeExit(index, poi, it.size)) }

                    else -> Navigation.neighbours(poi).mapNotNull { reachable[it] }.minOrNull()
                        ?.let { add(Interact(index, poi, it)) }
                }
            }
            Direction.entries.forEach { direction ->
                explorationTarget(reachable, here, direction)?.let { (target, steps) -> add(Explore(direction, target, steps)) }
            }
        }
        val menu = observation.menu
        if (menu?.cursor != null) menu.options.forEachIndexed { index, option ->
            if (option != MenuState.EMPTY_SLOT) add(ChooseOption(index, option, menu))
        }
        if (observation.dialogue != null && menu == null && observation.mode != GameMode.BATTLE) add(AdvanceDialogue)
    }

    /** The reachable tile farthest in [direction] (at least 2 steps away), closest first on ties. */
    private fun explorationTarget(reachable: Map<Point, Int>, here: Point, direction: Direction): Pair<Point, Int>? {
        fun progress(point: Point) = (point.x - here.x) * direction.dx + (point.y - here.y) * direction.dy
        val best = reachable.entries
            .filter { progress(it.key) >= 2 }
            .maxWithOrNull(compareBy<Map.Entry<Point, Int>> { progress(it.key) }.thenByDescending { it.value })
            ?: return null
        return best.key to best.value
    }
}
