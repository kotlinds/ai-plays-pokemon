package dev.kotlinds.pokemonclient.world

/** Where one moving platform is: its pivot tile and how many quarter turns (clockwise) it has made from its rest pose. */
data class PlatformPose(val x: Int, val y: Int, val rotation: Int)

/**
 * What stepping on a platform's trigger tile does: the platforms afterwards ([poses]) and where the player stands
 * then ([playerX], [playerY]: the platform carries the player). [moved] is false when the platform was blocked (by a
 * wall, the floor, another platform) and stayed where it was.
 */
data class PlatformRide(val poses: List<PlatformPose>, val playerX: Int, val playerY: Int, val moved: Boolean)

/**
 * Platforms that the player rides and moves by stepping on their trigger tiles (the Blackthorn Gym's platforms on the
 * lava). The rules are the game's ([dev.kotlinds.pokemonclient.games.hgss.HgssBlackthornGym] for HeartGold / SoulSilver);
 * [PlatformPlanner] searches routes with them.
 */
interface MovingPlatforms {
    /** The platforms now, read from the game. */
    val poses: List<PlatformPose>

    /** Tiles the player can stand on with the platforms at [poses] (on top of the lava). */
    fun walkTiles(poses: List<PlatformPose>): Set<Pair<Int, Int>>

    /** Stepping on ([x], [y]) with the platforms at [poses]: the ride, or null when that tile isn't a trigger. */
    fun ride(poses: List<PlatformPose>, x: Int, y: Int): PlatformRide?
}

/**
 * Plans routes over [MovingPlatforms]: a Dijkstra over (player tile, platform poses), each pose configuration getting
 * its own [Pathfinder] where the platforms' tiles are walkable ([Overlay.openTiles]). Stepping on a trigger tile is an
 * [Edge.Teleport] (step onto the trigger, the platform moves and carries the player to [Edge.Teleport.to]); the walker
 * re-reads the position after each one and plans again when the game disagrees.
 */
class PlatformPlanner(
    private val area: Area,
    private val overlay: Overlay,
    private val platforms: MovingPlatforms,
    private val maxStates: Int = DEFAULT_MAX_STATES,
) {

    private data class State(val node: Node, val poses: List<PlatformPose>)

    private val pathfinders = HashMap<List<PlatformPose>, Pathfinder>()

    private fun pathfinder(poses: List<PlatformPose>): Pathfinder = pathfinders.getOrPut(poses) {
        Pathfinder(area, overlay.copy(openTiles = overlay.openTiles + platforms.walkTiles(poses)))
    }

    /**
     * The cheapest route from [start] to a node satisfying [isGoal], riding platforms on the way; null when none. Like
     * [Pathfinder.route], active triggers are crossed only when there is no other way (the route then warns).
     */
    fun route(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>> = emptySet(), isGoal: (Node) -> Boolean): Route? {
        search(start, options, goalTiles, isGoal, allowTriggers = false)?.let { return it }
        val edges = search(start, options, goalTiles, isGoal, allowTriggers = true)?.edges ?: return null
        val scene = edges.flatMap { it.tiles }.firstOrNull { (it.x to it.y) in overlay.activeTriggers }
        return Route(edges, listOfNotNull(scene?.let { RouteWarning.StartsScene(it.x, it.y) }))
    }

    private fun search(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, isGoal: (Node) -> Boolean, allowTriggers: Boolean): Route? {
        val initial = State(start, platforms.poses)
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
            for ((edge, next) in moves(state, options, goalTiles, allowTriggers)) {
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

    /** The plain moves with the platforms where they are; a step onto a trigger tile becomes the ride it starts. */
    private fun moves(state: State, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, allowTriggers: Boolean): List<Pair<Edge, State>> =
        pathfinder(state.poses).neighbours(state.node, options, goalTiles, allowJumps = options.acceptOneWay, allowTriggers = allowTriggers).map { edge ->
            val ride = if (edge is Edge.Step) platforms.ride(state.poses, edge.to.x, edge.to.y) else null
            when {
                ride == null || !ride.moved -> edge to state.copy(node = edge.to)
                else -> {
                    val landing = Node(ride.playerX, ride.playerY, edge.to.level)
                    Edge.Teleport(landing, edge.direction, edge.to, RIDE_COST) to State(landing, ride.poses)
                }
            }
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

    companion object {
        /** States expanded at most (the Blackthorn Gym needs a few thousand). */
        const val DEFAULT_MAX_STATES = 200_000

        /** A ride (the platform turning or sliding with the player on it) takes a couple of seconds. */
        const val RIDE_COST = 8
    }
}
