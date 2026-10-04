package dev.kotlinds.pokemonclient.world

/**
 * Routes across zones: floors of a dungeon, buildings and the outdoor area, linked by warps and holes
 * ([WorldLinks]). One Dijkstra over (area, [Node]) pairs: inside an area the moves are the [Pathfinder]'s; stepping
 * onto a warp (or pressing its direction on an exit mat) or onto a hole jumps to the arrival tile of the other zone.
 *
 * The live state is only known for the player's zone ([overlayFor] gives the people, refused steps and active
 * triggers there); other zones use the static maps with their obstacle objects where the map places them
 * ([staticOverlay]: boulders and rocks are back in place whenever the player enters a map). The walker follows the first link of the route, then plans again
 * from where it really arrived, so the static guess for the other zones is corrected on the way.
 */
class WorldRouter(
    private val world: WorldSource,
    /** The live overlay of a zone (people, triggers...), [Overlay] with nothing for the zones not shown. */
    private val overlayFor: (zone: Int, area: Area) -> Overlay = { _, area -> staticOverlay(area) },
) {
    /** A position in the world: an area (identity) and a node on it. */
    data class Place(val area: Area, val node: Node) {
        val zone: Int? get() = area.zoneAt(node.x, node.y)
    }

    /** A route found: the links taken in order (empty when the goal is on the start area), and its cost. */
    data class WorldRoute(val links: List<ZoneLink>, val end: Place, val cost: Int, val places: List<Place> = emptyList()) {
        /** True when it takes a hole: no way back the same way. */
        val oneWay: Boolean get() = links.any { it.oneWay }
    }

    private class AreaInfo(val pathfinder: Pathfinder, val links: Map<Pair<Int, Int>, ZoneLink>, val goalTiles: Set<Pair<Int, Int>>)

    /**
     * The cheapest route from [start] (in zone [startZone]) to a place where [isGoal] holds. [goalTiles] lists the
     * tiles of an area that may be entered as destinations (warps, triggers, blocked targets): they are entered only
     * as goals. Ledges are jumped only with [RouteOptions.acceptOneWay]; holes always (they are one way: see
     * [WorldRoute.oneWay]). Null when no route exists within [maxNodes] explored nodes. With [relaxed] and
     * [ignorePeople], field-move obstacles and people are crossed (at a high cost), to tell what blocks a route.
     */
    fun route(
        startZone: Int,
        start: Node,
        options: RouteOptions,
        goalTiles: (Area) -> Set<Pair<Int, Int>> = { emptySet() },
        maxNodes: Int = MAX_NODES,
        relaxed: Boolean = false,
        ignorePeople: Boolean = false,
        isGoal: (Place) -> Boolean,
    ): WorldRoute? {
        val startArea = world.areaOf(startZone) ?: return null
        val infos = HashMap<Area, AreaInfo>()
        fun info(area: Area, zone: Int): AreaInfo = infos.getOrPut(area) {
            val zones = if (area.zoneBounds.isEmpty()) setOf(zone) else area.zoneBounds.keys
            val links = zones.flatMap { WorldLinks.links(world, area, it) }.associateBy { it.x to it.y }
            AreaInfo(Pathfinder(area, overlayFor(zone, area)), links, goalTiles(area))
        }
        val startPlace = Place(startArea, start)
        val dist = HashMap<Place, Int>()
        val previous = HashMap<Place, Pair<Place, ZoneLink?>>()
        val queue = PriorityQueue<Pair<Place, Int>> { a, b -> a.second - b.second }
        dist[startPlace] = 0
        queue.add(startPlace to 0)
        var explored = 0
        while (queue.isNotEmpty()) {
            val (place, d) = queue.poll()
            if (d > (dist[place] ?: Int.MAX_VALUE)) continue
            if (place != startPlace && isGoal(place)) return WorldRoute(links(previous, startPlace, place), place, d, places(previous, startPlace, place))
            if (++explored > maxNodes) return null
            val zone = place.zone ?: startZone
            val here = info(place.area, zone)
            fun relax(next: Place, cost: Int, via: ZoneLink?) {
                val nd = d + cost
                if (nd < (dist[next] ?: Int.MAX_VALUE)) {
                    dist[next] = nd
                    previous[next] = place to via
                    queue.add(next to nd)
                }
            }
            fun take(link: ZoneLink, cost: Int) {
                val toX = link.toX ?: return
                val toY = link.toY ?: return
                val area = world.areaOf(link.targetZone) ?: return
                if (area.tile(toX, toY) == null) return
                relax(Place(area, Node(toX, toY)), cost + LINK_COST, link)
            }
            // Pressing the direction of the exit mat the player stands on.
            here.links[place.node.x to place.node.y]?.takeIf { it.exitDirection != null }?.let { take(it, 0) }
            val enterable = here.goalTiles + here.links.keys
            for (edge in here.pathfinder.neighbours(place.node, options, enterable, allowJumps = options.acceptOneWay, relaxed = relaxed, ignorePeople = ignorePeople)) {
                val to = edge.to
                val next = Place(place.area, to)
                val link = here.links[to.x to to.y]
                when {
                    link == null -> if ((to.x to to.y) !in here.goalTiles || isGoal(next)) relax(next, edge.cost, null)
                    // Stepping on a door, a ladder down or a hole takes it at once (unless it's the destination).
                    link.exitDirection == null -> if (isGoal(next)) relax(next, edge.cost, null) else take(link, edge.cost)
                    else -> relax(next, edge.cost, null)
                }
            }
        }
        return null
    }

    private fun places(previous: Map<Place, Pair<Place, ZoneLink?>>, start: Place, end: Place): List<Place> {
        val places = ArrayDeque<Place>()
        var at = end
        while (at != start) {
            places.addFirst(at)
            at = previous.getValue(at).first
        }
        return places.toList()
    }

    private fun links(previous: Map<Place, Pair<Place, ZoneLink?>>, start: Place, end: Place): List<ZoneLink> {
        val links = ArrayDeque<ZoneLink>()
        var at = end
        while (at != start) {
            val (from, link) = previous.getValue(at)
            if (link != null) links.addFirst(link)
            at = from
        }
        return links.toList()
    }

    companion object {
        /**
         * What is known of [area] without seeing it: its obstacles (boulders, rocks, trees) where its events place
         * them, and its warp pads ([Area.scriptWarps]: stepping on the trigger takes the player to the pad's
         * destination), assumed active.
         */
        fun staticOverlay(area: Area): Overlay = Overlay(
            objects = area.people.filter { it.obstacle != null }.map { LiveObject(it.x, it.y, it.facing, clearedBy = it.obstacle) },
            teleports = area.scriptWarps.flatMap { pad ->
                val trigger = area.triggers.firstOrNull { it.zone == pad.zone && it.id == pad.trigger } ?: return@flatMap emptyList()
                (trigger.x until trigger.x + maxOf(1, trigger.width)).flatMap { x ->
                    (trigger.y until trigger.y + maxOf(1, trigger.height)).map { y -> TeleportLink(x, y, pad.x, pad.y) }
                }
            },
        )

        /** A warp or a fall takes a few seconds (fade): worth about this many steps. */
        const val LINK_COST = 10

        /** Bound of the search (the outdoor area is large: about a million tiles). */
        const val MAX_NODES = 400_000
    }
}
