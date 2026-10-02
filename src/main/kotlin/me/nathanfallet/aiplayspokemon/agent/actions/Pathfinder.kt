package me.nathanfallet.aiplayspokemon.agent.actions

import me.nathanfallet.aiplayspokemon.game.Direction
import me.nathanfallet.aiplayspokemon.game.LocalMap
import me.nathanfallet.aiplayspokemon.game.Tile

/**
 * Shortest paths on a [LocalMap] (breadth-first search, 4 directions), used by assisted actions.
 *
 * Warps are only allowed as the destination (walking over one would change the map); ledges and
 * water are not crossed.
 */
object Pathfinder {

    data class Point(val x: Int, val y: Int) {
        fun step(direction: Direction) = Point(x + direction.dx, y + direction.dy)
    }

    /** Directions to walk from [from] to the first point matching [isTarget], or null if unreachable. */
    fun path(map: LocalMap, from: Point, isTarget: (Point) -> Boolean): List<Direction>? {
        if (isTarget(from)) return emptyList()
        val previous = HashMap<Point, Pair<Point, Direction>>()
        val queue = ArrayDeque(listOf(from))
        val seen = hashSetOf(from)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (direction in Direction.entries) {
                val next = current.step(direction)
                if (next in seen) continue
                val tile = map.tileAt(next.x, next.y)
                val target = isTarget(next)
                if (!target && (!tile.walkable || tile == Tile.WARP)) continue
                if (target && !tile.walkable) continue
                seen += next
                previous[next] = current to direction
                if (target) return rebuild(previous, from, next)
                queue.addLast(next)
            }
        }
        return null
    }

    /** Every tile reachable from [from] with its distance in steps (warps excluded). */
    fun reachable(map: LocalMap, from: Point): Map<Point, Int> {
        val distances = hashMapOf(from to 0)
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (direction in Direction.entries) {
                val next = current.step(direction)
                if (next in distances) continue
                val tile = map.tileAt(next.x, next.y)
                if (!tile.walkable || tile == Tile.WARP) continue
                distances[next] = distances.getValue(current) + 1
                queue.addLast(next)
            }
        }
        return distances
    }

    private fun rebuild(previous: Map<Point, Pair<Point, Direction>>, from: Point, to: Point): List<Direction> {
        val directions = ArrayList<Direction>()
        var current = to
        while (current != from) {
            val (before, direction) = previous.getValue(current)
            directions += direction
            current = before
        }
        return directions.reversed()
    }
}
