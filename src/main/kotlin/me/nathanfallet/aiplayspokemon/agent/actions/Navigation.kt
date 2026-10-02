package me.nathanfallet.aiplayspokemon.agent.actions

import me.nathanfallet.aiplayspokemon.agent.GameController
import me.nathanfallet.aiplayspokemon.agent.actions.Pathfinder.Point
import me.nathanfallet.aiplayspokemon.emulator.Button
import me.nathanfallet.aiplayspokemon.game.Direction
import me.nathanfallet.aiplayspokemon.game.GameMode
import me.nathanfallet.aiplayspokemon.game.MenuState
import me.nathanfallet.aiplayspokemon.game.PointOfInterest

/** Movement mechanics shared by the assisted actions. */
internal object Navigation {

    private const val MAX_WALK_PRESSES = 80

    /**
     * Follows the shortest path to a target, replanning after every step (people move, doors open).
     * Stops when the map changes, when something interrupts (dialogue, battle), or when blocked.
     */
    suspend fun walk(controller: GameController, isTarget: (Point) -> Boolean): ActionOutcome {
        var observation = controller.observe()
        val startMap = observation.location?.mapId
        var blockedPresses = 0
        repeat(MAX_WALK_PRESSES) {
            val location = observation.location ?: return ActionOutcome(observation, "position unknown")
            if (location.mapId != startMap) return ActionOutcome(observation)
            if (observation.mode != GameMode.OVERWORLD) return ActionOutcome(observation, "interrupted (${observation.mode.name.lowercase()})")
            val map = observation.map ?: return ActionOutcome(observation, "map unknown")
            val path = Pathfinder.path(map, Point(location.x, location.y), isTarget)
                ?: return ActionOutcome(observation, "no path any more")
            if (path.isEmpty()) return ActionOutcome(observation)
            observation = controller.press(button(path.first()))
            val after = observation.location
            if (after != null && after.x == location.x && after.y == location.y && after.facing == location.facing) {
                if (++blockedPresses >= 3) return ActionOutcome(observation, "blocked")
            } else {
                blockedPresses = 0
            }
        }
        return ActionOutcome(observation, "too far")
    }

    /** The four tiles next to a point of interest. */
    fun neighbours(poi: PointOfInterest) = Direction.entries.map { Point(poi.x - it.dx, poi.y - it.dy) }

    fun button(direction: Direction): Button = when (direction) {
        Direction.NORTH -> Button.UP
        Direction.SOUTH -> Button.DOWN
        Direction.WEST -> Button.LEFT
        Direction.EAST -> Button.RIGHT
    }

    /** The D-pad button moving a menu cursor from option [from] towards option [to] (rows first, then columns). */
    fun cursorButton(menu: MenuState, from: Int, to: Int): Button {
        val current = menu.position(from)
        val target = menu.position(to)
        return when {
            target.row > current.row -> Button.DOWN
            target.row < current.row -> Button.UP
            target.column > current.column -> Button.RIGHT
            else -> Button.LEFT
        }
    }
}
