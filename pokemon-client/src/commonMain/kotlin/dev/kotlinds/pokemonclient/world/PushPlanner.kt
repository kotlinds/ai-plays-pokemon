package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/**
 * Plans routes that move objects out of the way, a small puzzle search over (player position, object positions):
 * - Strength boulders ([LiveObject.clearedBy] = [FieldMoveKind.STRENGTH], when [RouteOptions.fieldMoves] has
 *   Strength): walking into one pushes it one tile, when the tile behind it is free floor;
 * - ice blocks ([LiveObject.iceBlock], the Mahogany Gym): sliding on the ice into one stops the player and slides the
 *   block on (src/unk_0206D494.c): it stops before a wall, a tile that isn't ice or another object; meeting another
 *   ice block, both freeze together and can't be pushed any more.
 *
 * Used when the plain [Pathfinder] finds no route. The search is a Dijkstra over states, each object configuration
 * getting its own [Pathfinder]; pushes are expensive so routes push as little as possible, and the search gives up
 * after [maxStates] states (null: no plan within the bound).
 */
class PushPlanner(private val area: Area, private val overlay: Overlay, private val maxStates: Int = DEFAULT_MAX_STATES) {

    /**
     * One movable object in a configuration: where it is, and whether it can still move. [fallsInto]: the hole a
     * boulder drops through; [gone] once it did.
     */
    private data class Movable(
        val x: Int,
        val y: Int,
        val boulder: Boolean,
        val frozen: Boolean = false,
        val fallsInto: Pair<Int, Int>? = null,
        val gone: Boolean = false,
    )

    private data class State(val node: Node, val movables: List<Movable>)

    private val movableTemplates = overlay.objects.filter { it.clearedBy == FieldMoveKind.STRENGTH || it.iceBlock }
    private val others = overlay.objects.filter { it.clearedBy != FieldMoveKind.STRENGTH && !it.iceBlock }
    private val warpTiles = area.warps.map { it.x to it.y }.toSet()
    private val pathfinders = HashMap<List<Movable>, Pathfinder>()

    /** True when the overlay has objects this planner can move with [options]. */
    fun hasMovables(options: RouteOptions): Boolean =
        movableTemplates.any { it.iceBlock || FieldMoveKind.STRENGTH in options.fieldMoves }

    /** The cheapest route from [start] to a node satisfying [isGoal], pushing objects on the way; null when none. */
    fun route(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>> = emptySet(), isGoal: (Node) -> Boolean): Route? {
        val initial = State(start, movableTemplates.map { Movable(it.x, it.y, boulder = it.clearedBy == FieldMoveKind.STRENGTH, fallsInto = it.fallsInto) })
        val dist = HashMap<State, Int>()
        val previous = HashMap<State, Pair<State, Edge>>()
        val queue = PriorityQueue<Pair<State, Int>> { a, b -> a.second - b.second }
        dist[initial] = 0
        queue.add(initial to 0)
        var expanded = 0
        while (queue.isNotEmpty()) {
            val (state, d) = queue.poll()
            if (d > (dist[state] ?: Int.MAX_VALUE)) continue
            if (state != initial && isGoal(state.node)) return Route(path(previous, initial, state), emptyList())
            if (++expanded > maxStates) return null
            for ((edge, next) in moves(state, options, goalTiles)) {
                val cost = d + edge.cost
                if (cost < (dist[next] ?: Int.MAX_VALUE)) {
                    dist[next] = cost
                    previous[next] = state to edge
                    queue.add(next to cost)
                }
            }
        }
        return null
    }

    private fun path(previous: Map<State, Pair<State, Edge>>, start: State, end: State): List<Edge> {
        val edges = ArrayDeque<Edge>()
        var at = end
        while (at != start) {
            val (from, edge) = previous.getValue(at)
            edges.addFirst(edge)
            at = from
        }
        return edges.toList()
    }

    private fun pathfinder(movables: List<Movable>): Pathfinder = pathfinders.getOrPut(movables) {
        val objects = others + movables.filterNot { it.gone }.map { m ->
            LiveObject(m.x, m.y, null, clearedBy = if (m.boulder) FieldMoveKind.STRENGTH else null, iceBlock = !m.boulder && !m.frozen)
        }
        Pathfinder(area, overlay.copy(objects = objects))
    }

    /** The moves from [state]: the plain ones with the objects where they are, and the pushes. */
    private fun moves(state: State, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>): List<Pair<Edge, State>> {
        val node = state.node
        val result = mutableListOf<Pair<Edge, State>>()
        for (edge in pathfinder(state.movables).neighbours(node, options, goalTiles, allowJumps = options.acceptOneWay)) {
            result += (icePush(edge, state) ?: (edge to state.copy(node = edge.to)))
        }
        if (FieldMoveKind.STRENGTH in options.fieldMoves) {
            for (dir in Direction.entries) strengthPush(state, dir)?.let { result += it }
        }
        return result
    }

    /**
     * When [edge] ends sliding on ice against a movable ice block, the block slides on: the same edge as a
     * [PushEdge], with the block's new place.
     */
    private fun icePush(edge: Edge, state: State): Pair<Edge, State>? {
        val landing = edge.to
        if (area.tile(landing.x, landing.y)?.kind != TileKind.Ice) return null
        val dir = lastDirection(edge)
        val bx = landing.x + dir.dx
        val by = landing.y + dir.dy
        val index = state.movables.indexOfFirst { it.x == bx && it.y == by && !it.boulder && !it.frozen }
        if (index < 0) return null
        var x = bx
        var y = by
        var joined: Int? = null
        while (true) {
            val nx = x + dir.dx
            val ny = y + dir.dy
            val other = state.movables.indexOfFirst { it.x == nx && it.y == ny && !it.gone }
            if (other >= 0) {
                if (!state.movables[other].boulder) joined = other
                break
            }
            val tile = area.tile(nx, ny) ?: break
            if (tile.blocked || tile.kind != TileKind.Ice || others.any { !it.isFollower && it.x == nx && it.y == ny }) break
            x = nx
            y = ny
        }
        if (x == bx && y == by && joined == null) return null
        val moved = state.movables.mapIndexed { i, m ->
            when (i) {
                index -> m.copy(x = x, y = y, frozen = joined != null)
                joined -> m.copy(frozen = true)
                else -> m
            }
        }
        val push = PushEdge(landing, edge.direction, bx to by, x to y, needsStrength = false, tiles = edge.tiles, cost = edge.cost + PUSH_EXTRA_COST)
        return push to State(landing, moved)
    }

    /** The direction of the last tile of [edge] (a slide on spinners may turn; ice slides go straight). */
    private fun lastDirection(edge: Edge): Direction {
        val tiles = edge.tiles
        if (tiles.size < 2) return edge.direction
        val a = tiles[tiles.size - 2]
        val b = tiles.last()
        return Direction.entries.firstOrNull { a.x + it.dx == b.x && a.y + it.dy == b.y } ?: edge.direction
    }

    /** Walking [dir] into a boulder next to the player, when the tile behind it is free: a Strength push. */
    private fun strengthPush(state: State, dir: Direction): Pair<Edge, State>? {
        val node = state.node
        val bx = node.x + dir.dx
        val by = node.y + dir.dy
        val index = state.movables.indexOfFirst { it.x == bx && it.y == by && it.boulder && !it.gone }
        if (index < 0) return null
        val boulderTile = area.tile(bx, by) ?: return null
        if (boulderTile.blocked) return null
        val tx = bx + dir.dx
        val ty = by + dir.dy
        val target = area.tile(tx, ty) ?: return null
        val falls = state.movables[index].fallsInto == (tx to ty)
        if (!falls && !boulderCanEnter(target, tx, ty, state)) return null
        val here = area.tile(node.x, node.y) ?: return null
        val level = levelOf(boulderTile, here.heights.getOrNull(node.level))
        val moved = state.movables.mapIndexed { i, m -> if (i == index) m.copy(x = tx, y = ty, gone = falls) else m }
        val to = Node(bx, by, level)
        return PushEdge(to, dir, bx to by, tx to ty, needsStrength = true) to State(to, moved)
    }

    /**
     * A boulder moves onto plain free floor only (no wall, water, ledge, warp, active trigger such as another
     * boulder's hole, person or other object).
     */
    private fun boulderCanEnter(tile: TileInfo, x: Int, y: Int, state: State): Boolean {
        if (tile.blocked || (x to y) in warpTiles || (x to y) in overlay.blockedTiles || (x to y) in overlay.activeTriggers) return false
        when (tile.kind) {
            TileKind.Floor, TileKind.Cave, TileKind.Sand, TileKind.TallGrass -> Unit
            else -> return false
        }
        if (state.movables.any { it.x == x && it.y == y && !it.gone }) return false
        return others.none { !it.isFollower && it.x == x && it.y == y }
    }

    private fun levelOf(tile: TileInfo, fromHeight: Int?): Int {
        if (tile.heights.size <= 1 || fromHeight == null) return 0
        return tile.heights.indices.minBy { kotlin.math.abs(tile.heights[it] - fromHeight) }
    }

    companion object {
        /** States expanded at most: beyond, the puzzle is left to the agent (with the obstacle named). */
        const val DEFAULT_MAX_STATES = 60_000

        /** Pushing an ice block costs a little more than the slide itself. */
        private const val PUSH_EXTRA_COST = 2
    }
}
