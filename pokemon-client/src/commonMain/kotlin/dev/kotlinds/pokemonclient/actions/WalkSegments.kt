package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.Node

/**
 * Smooth straight-line walking: consecutive steps in the same direction are walked as one segment, the direction
 * held all along like a player does, instead of stopping on every tile.
 *
 * The position is read on every frame: each new tile must be the next one of the segment (anything else stops the
 * segment at once and the walk re-plans), a screen other than the overworld (a battle, a trainer, a message) stops
 * it, and no progress for a while means the game refused the step. The direction is let go one tile before the end
 * (when the player starts the last tile, or the one before on a bike, whose input the game reads ahead), then the
 * walker waits for the player to stand still and checks the final tile.
 */
internal object WalkSegments {

    /** A straight run of plain steps in [direction], [tiles] in order (the last one is the segment's end). */
    data class Segment(val direction: Direction, val tiles: List<Node>)

    /** How a segment ended. */
    sealed interface Result {
        /** On the segment's last tile. */
        data class Reached(val field: FieldState) : Result

        /** Somewhere else than planned (pushed, slid, another map): the walk re-plans from [field]. */
        data class Elsewhere(val field: FieldState) : Result

        /** The game refused the step leaving [from] (an invisible wall, a person who moved in the way). */
        data class Refused(val from: Node) : Result

        /** Another screen took over (a battle, a trainer spotting the player, a phone call, a script), after [walked] tiles. */
        /** Stopped by the game; [at] is the tile the player was stepping onto (what started a scene, a battle...). */
        data class Stopped(val state: GameState, val walked: Int = 0, val at: Node? = null) : Result
    }

    /**
     * The segment starting at [edges]`[start]`: the following [Edge.Step]s in the same direction that don't enter a
     * warp tile of [area] (a door is a single, longer step). Null when that edge isn't a plain step.
     */
    fun segmentAt(edges: List<Edge>, start: Int, area: Area): Segment? {
        val first = edges[start] as? Edge.Step ?: return null
        if (enters(area, first)) return null
        val tiles = mutableListOf(first.to)
        var i = start + 1
        while (i < edges.size) {
            val next = edges[i] as? Edge.Step ?: break
            if (next.direction != first.direction || enters(area, next)) break
            tiles += next.to
            i++
        }
        return Segment(first.direction, tiles)
    }

    private fun enters(area: Area, edge: Edge) = area.warps.any { it.x == edge.to.x && it.y == edge.to.y }

    /** Walks [segment] holding its direction (with B when running), as described on [WalkSegments]. */
    fun walk(context: PlanContext, segment: Segment, options: MoveOptions): Result {
        var remaining = segment.tiles
        var round = 0
        while (remaining.isNotEmpty() && round++ < MAX_ROUNDS) {
            val start = context.state().field ?: return Result.Stopped(context.state())
            val done = segment.tiles.size - remaining.size
            when (val held = hold(context, segment.direction, remaining, start, options)) {
                is Held.Stopped -> return Result.Stopped(held.state, done + held.walked, held.at)
                is Held.Refused -> return Result.Refused(held.from)
                is Held.Released -> Unit
            }
            // Let go: wait for the player to stand still (a bike may still take one more tile), then check.
            val end = settleStill(context) ?: return Result.Stopped(context.state())
            val at = remaining.indexOfFirst { it.x == end.x && it.y == end.y }
            when {
                at == remaining.lastIndex -> return Result.Reached(end)
                // Stopped short (released early on a bike): hold again for the rest.
                at >= 0 -> remaining = remaining.drop(at + 1)
                end.x == start.x && end.y == start.y -> continue
                else -> return Result.Elsewhere(end)
            }
        }
        val field = context.state().field ?: return Result.Stopped(context.state())
        return Result.Elsewhere(field)
    }

    private sealed interface Held {
        data object Released : Held
        data class Refused(val from: Node) : Held
        data class Stopped(val state: GameState, val walked: Int, val at: Node?) : Held
    }

    /** Holds the direction until the release point of [tiles], checking each new position. */
    private fun hold(context: PlanContext, direction: Direction, tiles: List<Node>, start: FieldState, options: MoveOptions): Held {
        val input = InputFrame(buildSet {
            add(direction.button)
            if (options.run) add(Button.B)
        })
        val releaseAt = if (start.movement == MovementMode.BIKE && tiles.size >= 2) tiles.size - 2 else tiles.size - 1
        var next = 0
        var lastX = start.x
        var lastY = start.y
        var idle = 0
        while (true) {
            context.scope.step(1, input)
            val state = context.state()
            val field = state.field
            if (field == null || (state.screen !is Screen.Overworld && state.screen.awaiting != Awaiting.ANIMATION)) {
                val walked = next + if (tiles.getOrNull(next)?.let { it.x == field?.x && it.y == field.y } == true) 1 else 0
                return Held.Stopped(state, walked, tiles.getOrNull(next))
            }
            if (field.x != lastX || field.y != lastY) {
                lastX = field.x
                lastY = field.y
                idle = 0
                val expected = tiles.getOrNull(next)
                // Anything but the next tile: let go, the caller sees where the player ends.
                if (expected == null || expected.x != field.x || expected.y != field.y) return Held.Released
                if (next >= releaseAt) return Held.Released
                next++
                continue
            }
            idle = if (field.moving) 0 else idle + 1
            if (idle >= REFUSED_FRAMES) {
                context.scope.step(1)
                val from = if (next == 0) Node(start.x, start.y) else tiles[next - 1]
                return Held.Refused(from)
            }
        }
    }

    /** Steps without input until the player has stood still for a few frames in a row; null when it never does. */
    private fun settleStill(context: PlanContext): FieldState? {
        var still = 0
        var waited = 0
        while (waited < SETTLE_FRAMES) {
            context.scope.step(1)
            waited++
            val state = context.state()
            val field = state.field ?: return null
            if (state.screen !is Screen.Overworld && state.screen.awaiting != Awaiting.ANIMATION) return null
            still = if (field.moving) 0 else still + 1
            if (still >= STILL_FRAMES) return field
        }
        return context.state().field
    }

    private val Direction.button
        get() = when (this) {
            Direction.NORTH -> Button.UP
            Direction.SOUTH -> Button.DOWN
            Direction.WEST -> Button.LEFT
            Direction.EAST -> Button.RIGHT
        }

    /** No new tile for this long while holding (and not moving): the game refuses the step (turning included). */
    private const val REFUSED_FRAMES = 24
    private const val SETTLE_FRAMES = 64
    private const val STILL_FRAMES = 6
    private const val MAX_ROUNDS = 8
}
