package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.MovementMode

/** A position in an [Area]: tile coordinates plus the height level (index into [TileInfo.heights], 0 when flat). */
data class Node(val x: Int, val y: Int, val level: Int = 0)

/** One move of a route. */
sealed interface Edge {
    val to: Node
    val direction: Direction
    val cost: Int

    /** Every tile the player enters during this move, [to] last. */
    val tiles: List<Node>

    /** Walk one tile. */
    data class Step(override val to: Node, override val direction: Direction, override val cost: Int) : Edge {
        override val tiles get() = listOf(to)
    }

    /** Jump down a ledge: two tiles in [direction], one way only. */
    data class Jump(override val to: Node, override val direction: Direction) : Edge {
        override val cost = 1
        override val tiles get() = listOf(to)
    }

    /**
     * A forced move started by one press of [direction]: sliding on ice, or being pushed by spinner arrows. The
     * player crosses [tiles] without control and stops on [to] (computed by simulating the game's rule); the walker
     * only starts it, then waits for the player to stand still.
     */
    data class Slide(
        override val to: Node,
        override val direction: Direction,
        override val tiles: List<Node>,
        override val cost: Int = tiles.size,
    ) : Edge

    /**
     * Step onto [via] (one tile in [direction]), which takes the player to [to] on the same map: a warp pad, a cart
     * ride ([TeleportLink]). The position is read again once it is over.
     */
    data class Teleport(override val to: Node, override val direction: Direction, val via: Node, override val cost: Int) : Edge {
        override val tiles get() = listOf(via, to)
    }
}

/** Movement options of a route request. */
data class RouteOptions(
    /** How the player moves right now. */
    val mode: MovementMode = MovementMode.WALK,
    /** The party can use Surf (a Pokémon knows it and the badge allows it): routes may cross surfable water. */
    val canSurf: Boolean = false,
    /** Make tall grass expensive (avoids wild battles when another way exists). */
    val avoidTallGrass: Boolean = false,
    /** Make tiles in the line of sight of undefeated trainers expensive. */
    val avoidTrainers: Boolean = false,
    /** Allow routes that can't be walked back (ledges): refused by default, with a [RouteWarning]. */
    val acceptOneWay: Boolean = false,
    /** Maximum height difference (game units) between two adjacent surfaces to walk between them. */
    val maxClimb: Int = DEFAULT_MAX_CLIMB,
    /**
     * Field moves the party can use now (a Pokémon knows it and the badge allows it): routes use them by themselves
     * ([FieldMoveEdge]: Surf from the shore, Waterfall, Whirlpool, Cut, Rock Smash; Strength pushes are planned by
     * [PushPlanner]). [FieldMoveKind.SURF] here implies [canSurf].
     */
    val fieldMoves: Set<FieldMoveKind> = emptySet(),
) {
    companion object {
        /** Gen 4 walks across height differences of about one stair step (BDHC heights, NOTES 17s §4). */
        const val DEFAULT_MAX_CLIMB = 20
    }
}

/** Something moving or dynamic on the map, from the RAM (people, the follower...). */
data class LiveObject(
    val x: Int,
    val y: Int,
    val facing: Direction?,
    /** The Pokémon following the player: never an obstacle. */
    val isFollower: Boolean = false,
    /** Sight range of an undefeated trainer (0 when not a trainer or already beaten). */
    val sightRange: Int = 0,
    /**
     * For an obstacle a field move removes or pushes (a Cut tree, a Rock Smash rock, a Strength boulder): that move.
     * It still blocks the route, but a route failing because of it says so ([RouteFailure.NeedsFieldMove]).
     */
    val clearedBy: FieldMoveKind? = null,
    /**
     * An ice block that can still be pushed (the Mahogany Gym): it blocks like a person, but sliding into it on the
     * ice pushes it ([PushPlanner]).
     */
    val iceBlock: Boolean = false,
)

/** What changes on the map, read live: people, tiles refused by the game, active triggers. */
data class Overlay(
    val objects: List<LiveObject> = emptyList(),
    /** Moves the game refused although the map allowed them (learned while walking). */
    val refused: Set<Pair<Node, Direction>> = emptySet(),
    /** Tiles that run a script when stepped on right now (active coordinate triggers): avoided unless targeted. */
    val activeTriggers: Set<Pair<Int, Int>> = emptySet(),
    /** Tiles blocked right now by the map's live state (closed shutters...), on top of the ROM collision. */
    val blockedTiles: Set<Pair<Int, Int>> = emptySet(),
    /** Teleports usable right now: stepping on a source tile takes the player elsewhere on the map. */
    val teleports: List<TeleportLink> = emptyList(),
)

/** Stepping on ([fromX], [fromY]) takes the player to ([toX], [toY]) on the same map (warp pad, cart ride). */
data class TeleportLink(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val cost: Int = DEFAULT_COST) {
    companion object {
        /** A teleport takes a few seconds (fade, ride): worth about this many steps. */
        const val DEFAULT_COST = 8
    }
}

/** A route found: the edges in order, and what the agent should know about it. */
data class Route(val edges: List<Edge>, val warnings: List<RouteWarning>) {
    val end: Node? get() = edges.lastOrNull()?.to
}

/** Things worth knowing about a route. */
sealed interface RouteWarning {
    data class CrossesTallGrass(val tiles: Int) : RouteWarning
    data object PassesTrainerSight : RouteWarning
    /** The route jumps down ledges: there is no way back the same way. */
    data object OneWay : RouteWarning

    /**
     * The route steps on an active trigger at ([x], [y]) (it's the destination, or the only way): a scene starts
     * there and stops the walk.
     */
    data class StartsScene(val x: Int, val y: Int) : RouteWarning
}

/** Why no route was found. */
sealed interface RouteFailure {
    data object StartUnknown : RouteFailure
    data object TargetUnknown : RouteFailure
    /** The target is on another height level reachable only elsewhere (stairs...). */
    data object DifferentLevel : RouteFailure
    /** A route exists only by jumping ledges (no way back), and [RouteOptions.acceptOneWay] is false. */
    data object OnlyOneWay : RouteFailure

    /**
     * No route by walking, but there is one using [move] first at ([x], [y]): water to surf, a whirlpool, a
     * waterfall, a Rock Climb wall, or an obstacle object (a boulder to push with Strength, a tree to cut...).
     */
    data class NeedsFieldMove(
        val move: FieldMoveKind,
        val x: Int,
        val y: Int,
        /** The tile to use it from (the shore for Surf, the tile in front of the obstacle), when known. */
        val from: Node? = null,
        /** The direction to face on [from] to use it. */
        val facing: Direction? = null,
    ) : RouteFailure
    data object Unreachable : RouteFailure

    /** The only way is through ([x], [y]), where a person stands (they may move, or step aside once talked to). */
    data class BlockedByPerson(val x: Int, val y: Int) : RouteFailure
}

/**
 * Finds routes on an [Area] (Dijkstra over [Node]s), using the static tiles from the ROM and the live [Overlay].
 *
 * Rules:
 * - collision and walls block; water only when surfing (and land, when surfing, only to step out of the water);
 * - ledges are crossed only in their direction, by a 2-tile jump;
 * - ice slides the player on until a tile that isn't ice or an obstacle; spinner arrows push the player (turning
 *   on each arrow) until a stop tile or an obstacle: both are one [Edge.Slide] landing where the game stops;
 * - field moves (Surf when not allowed, whirlpools, waterfalls, Rock Climb, obstacle objects) block, and a failed
 *   route tells which one would open the way ([RouteFailure.NeedsFieldMove]);
 * - warps and ladders are never crossed unless they are the destination; active triggers neither, unless they are the
 *   destination or the only way (the route then warns: [RouteWarning.StartsScene]);
 * - bridges: a bridge over water is floor for a player coming from the bridge, water otherwise; railings block the
 *   sides of their tile;
 * - height: between two tiles, a surface is reachable when the height difference is at most [RouteOptions.maxClimb];
 * - people block their tile, except the Pokémon following the player;
 * - live state ([Overlay]): closed shutters block their tiles; stepping on a teleport source is an [Edge.Teleport] to
 *   its destination (it is never walked through as a plain tile).
 */
class Pathfinder(private val area: Area, private val overlay: Overlay = Overlay()) {

    private val occupied = overlay.objects.filter { !it.isFollower }.map { it.x to it.y }.toSet()
    private val warpTiles = area.warps.map { it.x to it.y }.toSet()
    private val obstacles = overlay.objects.mapNotNull { o -> o.clearedBy?.let { (o.x to o.y) to it } }.toMap()
    private val teleportsFrom: Map<Pair<Int, Int>, List<TeleportLink>> = overlay.teleports.groupBy { it.fromX to it.fromY }
    private val inSight: Set<Pair<Int, Int>> by lazy {
        overlay.objects.filter { it.sightRange > 0 && it.facing != null }.flatMap { trainer ->
            (1..trainer.sightRange).map { trainer.x + trainer.facing!!.dx * it to trainer.y + trainer.facing.dy * it }
        }.toSet()
    }

    /** The level (height index) at (x, y) closest to [height], or 0 on flat tiles. */
    fun levelAt(x: Int, y: Int, height: Int): Int {
        val heights = area.tile(x, y)?.heights.orEmpty()
        if (heights.size <= 1) return 0
        return heights.indices.minBy { kotlin.math.abs(heights[it] - height) }
    }

    /** Cheapest route from [start] to any node satisfying [isGoal] (goal tiles may be warps or triggers). */
    fun route(start: Node, options: RouteOptions = RouteOptions(), goalTiles: Set<Pair<Int, Int>> = emptySet(), isGoal: (Node) -> Boolean): Result {
        if (area.tile(start.x, start.y) == null) return Result.Failed(RouteFailure.StartUnknown)
        // Active triggers (scenes) only when there is no other way.
        var triggers = false
        val found = search(start, options, goalTiles, isGoal, allowJumps = true)
            ?: search(start, options, goalTiles, isGoal, allowJumps = true, allowTriggers = true).also { triggers = true }
            ?: return Result.Failed(blockedBy(start, options, goalTiles, isGoal))
        val jumps = found.any { it is Edge.Jump }
        // One way = no way back, not "jumps a ledge": a ledge is just a shortcut while the start can be reached again
        // from the end (by another way: Route 29's ledges towards New Bark). Only a route with no way back needs
        // [RouteOptions.acceptOneWay] (the way from Blackthorn down to Route 45).
        val oneWay = jumps && !hasWayBack(found.last().to, start, options, triggers)
        if (oneWay && !options.acceptOneWay) {
            // Prefer a route without ledges when one exists; otherwise refuse with the reason.
            val flat = search(start, options, goalTiles, isGoal, allowJumps = false, allowTriggers = triggers)
            return if (flat != null) Result.Found(route(flat, options)) else Result.Failed(RouteFailure.OnlyOneWay)
        }
        return Result.Found(route(found, options, oneWay))
    }

    /** True when [start] can be walked back to from [end] (ledges allowed: any way back will do). */
    private fun hasWayBack(end: Node, start: Node, options: RouteOptions, triggers: Boolean): Boolean =
        search(end, options, setOf(start.x to start.y), { it.x == start.x && it.y == start.y }, allowJumps = true, allowTriggers = triggers) != null

    /** The outcome of [route]. */
    sealed interface Result {
        data class Found(val route: Route) : Result
        data class Failed(val failure: RouteFailure) : Result
    }

    /**
     * Why there is no walking route: searches again letting field moves through (each one very expensive, so the
     * route needs as few as possible) and names the first one on the way, or [RouteFailure.Unreachable].
     */
    private fun blockedBy(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, isGoal: (Node) -> Boolean): RouteFailure {
        val relaxed = search(start, options, goalTiles, isGoal, allowJumps = true, relaxed = true, allowTriggers = true)
        var from = start
        for (edge in relaxed.orEmpty()) {
            var before = from
            for (node in edge.tiles) {
                val tile = area.tile(node.x, node.y)
                val move = tile?.let { gate(it, node.x, node.y, options) }
                if (move != null) return RouteFailure.NeedsFieldMove(move, node.x, node.y, before, directionBetween(before, node) ?: edge.direction)
                before = node
            }
            from = edge.to
        }
        // A person standing in the only way (a guard in front of a door...).
        val throughPeople = search(start, options, goalTiles, isGoal, allowJumps = true, allowTriggers = true, ignorePeople = true)
        throughPeople?.flatMap { it.tiles }?.firstOrNull { (it.x to it.y) in occupied }?.let { return RouteFailure.BlockedByPerson(it.x, it.y) }
        return RouteFailure.Unreachable
    }

    /**
     * What makes (x, y) impassable for a plain walk, when it's something the agent can act on: a field move
     * ([RouteFailure.NeedsFieldMove]) or a person standing there ([RouteFailure.BlockedByPerson]). Null otherwise.
     */
    fun blockerAt(x: Int, y: Int, options: RouteOptions): RouteFailure? {
        val tile = area.tile(x, y) ?: return null
        gate(tile, x, y, options)?.let { return RouteFailure.NeedsFieldMove(it, x, y) }
        if ((x to y) in occupied) return RouteFailure.BlockedByPerson(x, y)
        return null
    }

    private fun route(edges: List<Edge>, options: RouteOptions, oneWay: Boolean = false): Route {
        val crossed = edges.flatMap { it.tiles }
        val warnings = buildList {
            val grass = crossed.count { area.tile(it.x, it.y)?.kind == TileKind.TallGrass }
            if (grass > 0) add(RouteWarning.CrossesTallGrass(grass))
            if (crossed.any { (it.x to it.y) in inSight }) add(RouteWarning.PassesTrainerSight)
            if (oneWay) add(RouteWarning.OneWay)
            crossed.firstOrNull { (it.x to it.y) in overlay.activeTriggers }?.let { add(RouteWarning.StartsScene(it.x, it.y)) }
        }
        return Route(edges, warnings)
    }

    private fun search(
        start: Node,
        options: RouteOptions,
        goalTiles: Set<Pair<Int, Int>>,
        isGoal: (Node) -> Boolean,
        allowJumps: Boolean,
        relaxed: Boolean = false,
        allowTriggers: Boolean = false,
        ignorePeople: Boolean = false,
    ): List<Edge>? {
        val dist = HashMap<Node, Int>()
        val previous = HashMap<Node, Pair<Node, Edge>>()
        val queue = PriorityQueue<Pair<Node, Int>> { a, b -> a.second - b.second }
        dist[start] = 0
        queue.add(start to 0)
        while (queue.isNotEmpty()) {
            val (node, d) = queue.poll()
            if (d > (dist[node] ?: Int.MAX_VALUE)) continue
            if (node != start && isGoal(node)) return path(previous, start, node)
            val heading = previous[node]?.second?.direction
            for (edge in neighbours(node, options, goalTiles, allowJumps, relaxed, allowTriggers, ignorePeople)) {
                val next = d + edge.cost
                val known = dist[edge.to] ?: Int.MAX_VALUE
                // Between routes of the same cost, prefer going on straight: fewer turns walk faster (each turn ends
                // a held segment, see the walker).
                val replaced = previous[edge.to]?.second
                val straighter = next == known && edge is Edge.Step && replaced is Edge.Step && edge.direction == heading && replaced.direction != heading
                if (next < known || straighter) {
                    dist[edge.to] = next
                    previous[edge.to] = node to edge
                    if (next < known) queue.add(edge.to to next)
                }
            }
        }
        return null
    }

    /**
     * Every node reachable from [start] within [maxCost] (Dijkstra without a goal), with its cost. Ledges are only
     * jumped when [RouteOptions.acceptOneWay] is set.
     */
    fun reachable(start: Node, options: RouteOptions = RouteOptions(), maxCost: Int = DEFAULT_REACH): Map<Node, Int> {
        val dist = HashMap<Node, Int>()
        val queue = PriorityQueue<Pair<Node, Int>> { a, b -> a.second - b.second }
        dist[start] = 0
        queue.add(start to 0)
        while (queue.isNotEmpty()) {
            val (node, d) = queue.poll()
            if (d > (dist[node] ?: Int.MAX_VALUE)) continue
            for (edge in neighbours(node, options, allowJumps = options.acceptOneWay)) {
                val next = d + edge.cost
                if (next <= maxCost && next < (dist[edge.to] ?: Int.MAX_VALUE)) {
                    dist[edge.to] = next
                    queue.add(edge.to to next)
                }
            }
        }
        return dist
    }

    private fun path(previous: Map<Node, Pair<Node, Edge>>, start: Node, end: Node): List<Edge> {
        val edges = ArrayDeque<Edge>()
        var at = end
        while (at != start) {
            val (from, edge) = previous.getValue(at)
            edges.addFirst(edge)
            at = from
        }
        return edges.toList()
    }

    /**
     * Moves possible from [node]. With [relaxed], tiles needing a field move ([gate]) are let through at a high
     * cost (to find which field move a failed route needs); with [allowTriggers], active triggers are walked on (at a
     * cost); with [ignorePeople], people don't block (to find who stands in the way).
     */
    fun neighbours(
        node: Node,
        options: RouteOptions,
        goalTiles: Set<Pair<Int, Int>> = emptySet(),
        allowJumps: Boolean = true,
        relaxed: Boolean = false,
        allowTriggers: Boolean = false,
        ignorePeople: Boolean = false,
    ): List<Edge> {
        val here = area.tile(node.x, node.y) ?: return emptyList()
        val hereHeight = here.heights.getOrNull(node.level)
        return Direction.entries.mapNotNull { dir ->
            if ((node to dir) in overlay.refused) return@mapNotNull null
            val x = node.x + dir.dx
            val y = node.y + dir.dy
            val tile = area.tile(x, y) ?: return@mapNotNull null
            if (railingBlocks(here, tile, dir)) return@mapNotNull null
            // A bridge over water is floor only for a player already on the bridge (water to surf otherwise).
            if ((tile.kind as? TileKind.Bridge)?.overWater == true && options.mode != MovementMode.SURF && here.kind !is TileKind.Bridge) {
                return@mapNotNull null
            }
            val isGoalTile = (x to y) in goalTiles
            // Field moves the party can use: Surf from the shore, Waterfall, Whirlpool, Cut, Rock Smash.
            fieldMoveEdge(node, here, tile, x, y, dir, options)?.let { return@mapNotNull it }
            // Ledges: jump over in their direction only (land 2 tiles away).
            val kind = tile.kind
            if (kind is TileKind.Ledge) {
                if (!allowJumps || kind.jump != dir) return@mapNotNull null
                val lx = x + dir.dx
                val ly = y + dir.dy
                val landing = area.tile(lx, ly) ?: return@mapNotNull null
                if (!walkable(landing, lx, ly, options, goalTiles, relaxed)) return@mapNotNull null
                return@mapNotNull Edge.Jump(Node(lx, ly, 0), dir)
            }
            if (!isGoalTile) teleportsFrom[x to y]?.let { return@mapNotNull teleport(it.first(), tile, x, y, dir, hereHeight, options) }
            if (!isGoalTile && !walkable(tile, x, y, options, goalTiles, relaxed, allowTriggers, ignorePeople)) return@mapNotNull null
            if (isGoalTile && tile.blocked && kind !is TileKind.Door) {
                // A blocked goal (a person, a sign, a counter) can't be entered: the plan stops next to it.
                return@mapNotNull null
            }
            val level = levelFrom(tile, hereHeight, options) ?: return@mapNotNull null
            val entered = Node(x, y, level)
            // Ice and spinners take the player further than the tile entered (a warp entered is taken at once).
            if ((kind == TileKind.Ice || kind is TileKind.Spinner) && (x to y) !in warpTiles) {
                return@mapNotNull slide(entered, dir, options, goalTiles, relaxed)
            }
            val sceneCost = if (allowTriggers && !isGoalTile && (x to y) in overlay.activeTriggers) TRIGGER_COST else 0
            val personCost = if (ignorePeople && !isGoalTile && (x to y) in occupied) FIELD_MOVE_COST else 0
            Edge.Step(entered, dir, cost(tile, x, y, options, relaxed) + sceneCost + personCost)
        }
    }

    private fun reverse(dir: Direction) = when (dir) {
        Direction.NORTH -> Direction.SOUTH
        Direction.SOUTH -> Direction.NORTH
        Direction.WEST -> Direction.EAST
        Direction.EAST -> Direction.WEST
    }

    /** True when a railing on [from] or [to] stops a move going [dir] between them (sub_02060DEC). */
    private fun railingBlocks(from: TileInfo, to: TileInfo, dir: Direction): Boolean =
        (from.kind as? TileKind.Railing)?.blockedSides?.contains(dir) == true ||
            (to.kind as? TileKind.Railing)?.blockedSides?.contains(reverse(dir)) == true

    /**
     * Simulates the forced move after entering [entry] (an ice or spinner tile) going [direction], with the game's
     * rules (HGSS overlay 1 for spinners, the ice forced-movement table of field_player_avatar):
     * - on ice, the player keeps going in [direction] and stops on the first tile that isn't ice, or before an
     *   obstacle;
     * - a spinner turns the push to its arrow; the push goes on over any floor, turning on each arrow, and stops on
     *   a [TileKind.SpinnerStop] tile or before an obstacle.
     *
     * Null when the move can't be planned: it would loop forever, or run into a warp or an active trigger that
     * isn't the destination (the game would take it). A slide of a single tile is a plain [Edge.Step].
     */
    private fun slide(entry: Node, direction: Direction, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, relaxed: Boolean): Edge? {
        val tiles = mutableListOf(entry)
        val seen = HashSet<Pair<Node, Direction>>()
        var at = entry
        var dir = direction
        var pushed = false
        var cost = 0
        while (true) {
            val tile = area.tile(at.x, at.y) ?: break
            cost += cost(tile, at.x, at.y, options, relaxed)
            when (val kind = tile.kind) {
                is TileKind.Spinner -> {
                    dir = kind.push
                    pushed = true
                }
                TileKind.SpinnerStop -> break
                TileKind.Ice -> Unit
                else -> if (!pushed) break
            }
            // A warp reached (only when it's the destination, see below): the game takes it.
            if ((at.x to at.y) in warpTiles && at != entry) break
            if (!seen.add(at to dir)) return null
            val nx = at.x + dir.dx
            val ny = at.y + dir.dy
            val next = area.tile(nx, ny) ?: break
            val takenAway = (nx to ny) in warpTiles || (nx to ny) in overlay.activeTriggers
            if (takenAway && (nx to ny) !in goalTiles) return null
            if (next.kind is TileKind.Ledge || !walkable(next, nx, ny, options, goalTiles, relaxed)) break
            if ((nx to ny) in goalTiles && next.blocked && next.kind !is TileKind.Door) break
            val level = levelFrom(next, tile.heights.getOrNull(at.level), options) ?: break
            at = Node(nx, ny, level)
            tiles += at
        }
        if (tiles.size == 1 && !pushed) return Edge.Step(entry, direction, cost)
        return Edge.Slide(at, direction, tiles, cost)
    }

    /**
     * The field move needed to enter (x, y), or null when the current movement is enough: an obstacle object
     * ([LiveObject.clearedBy]), Surf for water when it can't be used, whirlpools, waterfalls, Rock Climb walls.
     */
    private fun gate(tile: TileInfo, x: Int, y: Int, options: RouteOptions): FieldMoveKind? {
        obstacles[x to y]?.let { return it }
        return when (val kind = tile.kind) {
            is TileKind.Water -> FieldMoveKind.SURF.takeIf { kind.surfable && !canUse(FieldMoveKind.SURF, options) && options.mode != MovementMode.SURF }
            TileKind.Whirlpool -> FieldMoveKind.WHIRLPOOL
            TileKind.Waterfall -> FieldMoveKind.WATERFALL
            TileKind.RockClimb -> FieldMoveKind.ROCK_CLIMB
            else -> null
        }
    }

    /** True when [kind] is water the player surfs on (whirlpools and waterfalls are water too). */
    private fun isWater(kind: TileKind) = (kind is TileKind.Water && kind.surfable) || kind == TileKind.Whirlpool || kind == TileKind.Waterfall

    private fun canUse(move: FieldMoveKind, options: RouteOptions) =
        move in options.fieldMoves || (move == FieldMoveKind.SURF && options.canSurf)

    /**
     * The [FieldMoveEdge] entering (x, y) from [node] going [dir], when a field move the party can use is needed
     * there ([RouteOptions.fieldMoves]); null otherwise (the ordinary rules apply):
     * - Surf: from a tile that isn't water onto surfable water;
     * - Waterfall (while surfing, north or south): through the waterfall tiles to the water at the other end;
     * - Whirlpool (while surfing): across the whirlpool tiles to the water behind;
     * - Cut, Rock Smash: an obstacle object on a tile that is free once it's gone.
     */
    private fun fieldMoveEdge(node: Node, here: TileInfo, tile: TileInfo, x: Int, y: Int, dir: Direction, options: RouteOptions): Edge? {
        val kind = tile.kind
        val onWater = onWater(node, here)
        obstacles[x to y]?.let { move ->
            if (move == FieldMoveKind.STRENGTH || !canUse(move, options)) return null
            if (tile.blocked || isWater(kind) || (x to y) in overlay.blockedTiles) return null
            if (overlay.objects.any { !it.isFollower && it.clearedBy == null && it.x == x && it.y == y }) return null
            val to = Node(x, y, levelFrom(tile, here.heights.getOrNull(node.level), options) ?: 0)
            return FieldMoveEdge(to, dir, move, listOf(to), 1 + FIELD_MOVE_USE_COST)
        }
        return when {
            kind is TileKind.Water && kind.surfable && !onWater && canUse(FieldMoveKind.SURF, options) -> {
                if (tile.blocked || (x to y) in occupied) return null
                val to = Node(x, y, 0)
                FieldMoveEdge(to, dir, FieldMoveKind.SURF, listOf(to), 1 + SURF_START_COST + FIELD_MOVE_USE_COST)
            }
            // Going down a waterfall needs no move: surfing into it from above slides the player down
            // (ov01_021F24F4: surfing, facing south, a waterfall ahead → the waterfall task, no question).
            kind == TileKind.Waterfall && onWater && dir == Direction.SOUTH ->
                (crossing(x, y, dir, FieldMoveKind.WATERFALL, TileKind.Waterfall) as? FieldMoveEdge)?.let { Edge.Slide(it.to, dir, it.tiles, it.cost) }
            kind == TileKind.Waterfall && onWater && dir == Direction.NORTH && canUse(FieldMoveKind.WATERFALL, options) ->
                crossing(x, y, dir, FieldMoveKind.WATERFALL, TileKind.Waterfall)
            kind == TileKind.Whirlpool && onWater && canUse(FieldMoveKind.WHIRLPOOL, options) ->
                crossing(x, y, dir, FieldMoveKind.WHIRLPOOL, TileKind.Whirlpool)
            else -> null
        }
    }

    /**
     * True when the player on [node] is on the water: a water tile, or the upper surface of a tile with several
     * (the top of Tohjo Falls' waterfalls: a cave walkway below, the water above) at the height of water next to it.
     */
    private fun onWater(node: Node, tile: TileInfo): Boolean {
        if (isWater(tile.kind)) return true
        if (tile.heights.size <= 1) return false
        when (tile.kind) {
            TileKind.Floor, TileKind.Cave, TileKind.Sand, TileKind.TallGrass -> return false
            else -> Unit
        }
        val height = tile.heights.getOrNull(node.level) ?: return false
        return Direction.entries.any { d ->
            val next = area.tile(node.x + d.dx, node.y + d.dy) ?: return@any false
            next.kind is TileKind.Water && next.heights.any { kotlin.math.abs(it - height) <= DEFAULT_WATER_CLIMB }
        }
    }

    /** The level of [tile] at ([x], [y]) where the player is on the water ([onWater]), or 0. */
    private fun waterLevel(tile: TileInfo, x: Int, y: Int): Int =
        tile.heights.indices.firstOrNull { onWater(Node(x, y, it), tile) } ?: 0

    /** Through the [over] tiles from (x, y) going [dir], to the first water tile after them (surfed on). */
    private fun crossing(x: Int, y: Int, dir: Direction, move: FieldMoveKind, over: TileKind): Edge? {
        val tiles = mutableListOf<Node>()
        var cx = x
        var cy = y
        while (area.tile(cx, cy)?.kind == over) {
            tiles += Node(cx, cy, 0)
            cx += dir.dx
            cy += dir.dy
            if (tiles.size > MAX_CROSSING) return null
        }
        val landing = area.tile(cx, cy) ?: return null
        if (landing.blocked || (cx to cy) in occupied || tiles.isEmpty()) return null
        val to = Node(cx, cy, waterLevel(landing, cx, cy))
        if (!onWater(to, landing)) return null
        return FieldMoveEdge(to, dir, move, tiles + to, tiles.size + 1 + FIELD_MOVE_USE_COST)
    }

    /** Direction of one step from [a] to the adjacent [b], or null when they aren't adjacent. */
    private fun directionBetween(a: Node, b: Node): Direction? =
        Direction.entries.firstOrNull { a.x + it.dx == b.x && a.y + it.dy == b.y }

    /** Stepping onto the source of [teleport] at ([x], [y]): an [Edge.Teleport], when the tile can be entered. */
    private fun teleport(teleport: TeleportLink, tile: TileInfo, x: Int, y: Int, dir: Direction, hereHeight: Int?, options: RouteOptions): Edge? {
        if (tile.blocked || (x to y) in occupied || (x to y) in overlay.blockedTiles) return null
        // The trigger fires on entering the tile whatever its surface (a pit is lower than the floor around it).
        val level = levelFrom(tile, hereHeight, options) ?: 0
        val landing = area.tile(teleport.toX, teleport.toY) ?: return null
        val landingLevel = if (landing.heights.size <= 1) 0 else levelFrom(landing, tile.heights.getOrNull(level), options) ?: 0
        return Edge.Teleport(Node(teleport.toX, teleport.toY, landingLevel), dir, Node(x, y, level), teleport.cost)
    }

    private fun walkable(
        tile: TileInfo,
        x: Int,
        y: Int,
        options: RouteOptions,
        goalTiles: Set<Pair<Int, Int>>,
        relaxed: Boolean = false,
        allowTriggers: Boolean = false,
        ignorePeople: Boolean = false,
    ): Boolean {
        if ((x to y) in goalTiles) return true
        if (gate(tile, x, y, options) != null) return relaxed
        if (tile.blocked || (!ignorePeople && (x to y) in occupied) || (x to y) in overlay.blockedTiles) return false
        if ((x to y) in warpTiles || (!allowTriggers && (x to y) in overlay.activeTriggers) || tile.kind == TileKind.Ladder) return false
        val kind = tile.kind
        if (kind is TileKind.Water) return kind.surfable
        return kind != TileKind.Wall && kind != TileKind.Lava && kind != TileKind.Pc
    }

    /** The level reached on [tile] from a surface at [fromHeight], or null when too high or too low. */
    private fun levelFrom(tile: TileInfo, fromHeight: Int?, options: RouteOptions): Int? {
        if (tile.heights.isEmpty() || fromHeight == null) return 0
        val best = tile.heights.indices.minBy { kotlin.math.abs(tile.heights[it] - fromHeight) }
        return best.takeIf { kotlin.math.abs(tile.heights[it] - fromHeight) <= options.maxClimb }
    }

    private fun cost(tile: TileInfo, x: Int, y: Int, options: RouteOptions, relaxed: Boolean = false): Int {
        var cost = 1
        if (relaxed && gate(tile, x, y, options) != null) cost += FIELD_MOVE_COST
        // Starting to surf takes a prompt: prefer land when it's not much longer (without auto-Surf, every water
        // tile counts it; with it, only the edge entering the water does, see fieldMoveEdge).
        if (tile.kind is TileKind.Water && options.mode != MovementMode.SURF && !canUse(FieldMoveKind.SURF, options)) cost += SURF_START_COST
        if (options.avoidTallGrass && tile.kind == TileKind.TallGrass) cost += GRASS_COST
        if (options.avoidTrainers && (x to y) in inSight) cost += SIGHT_COST
        return cost
    }

    private companion object {
        const val GRASS_COST = 20
        const val SURF_START_COST = 5

        /** Using a field move (A, the question, the animation) takes a few seconds: worth about this many steps. */
        const val FIELD_MOVE_USE_COST = 8

        /** Height difference (game units) between the upper surface of a tile and the water next to it. */
        const val DEFAULT_WATER_CLIMB = RouteOptions.DEFAULT_MAX_CLIMB

        /** Longest waterfall or row of whirlpools crossed by one field move. */
        const val MAX_CROSSING = 16
        const val SIGHT_COST = 50

        /** Walking onto an active trigger starts a scene: only when nothing else works. */
        const val TRIGGER_COST = 10_000

        /** In the search for the field move a failed route needs: one field move outweighs any walk. */
        const val FIELD_MOVE_COST = 100_000
    }
}

/** A minimal binary-heap priority queue (commonMain has no java.util.PriorityQueue). */
internal class PriorityQueue<T>(private val comparator: Comparator<T>) {
    private val heap = ArrayList<T>()
    fun isNotEmpty() = heap.isNotEmpty()

    fun add(value: T) {
        heap.add(value)
        var i = heap.size - 1
        while (i > 0) {
            val parent = (i - 1) / 2
            if (comparator.compare(heap[i], heap[parent]) >= 0) break
            heap[i] = heap[parent].also { heap[parent] = heap[i] }
            i = parent
        }
    }

    fun poll(): T {
        val top = heap[0]
        val last = heap.removeAt(heap.size - 1)
        if (heap.isNotEmpty()) {
            heap[0] = last
            var i = 0
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var smallest = i
                if (l < heap.size && comparator.compare(heap[l], heap[smallest]) < 0) smallest = l
                if (r < heap.size && comparator.compare(heap[r], heap[smallest]) < 0) smallest = r
                if (smallest == i) break
                heap[i] = heap[smallest].also { heap[smallest] = heap[i] }
                i = smallest
            }
        }
        return top
    }
}

/** How far [Pathfinder.reachable] looks by default (cost units: a plain step costs about 1). */
const val DEFAULT_REACH = 400
