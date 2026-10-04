package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.WorldRouter
import dev.kotlinds.pokemonclient.world.WorldSource
import dev.kotlinds.pokemonclient.world.ZoneLink

/**
 * `go_to` across the world: resolves where to go (a tile, an object, `exit:<direction>`, a map's name, a tile on
 * another map, `frontier`), then walks there with [MovePlans.walkTo], going through warps, holes and map edges when
 * the destination isn't reachable on the current map ([WorldRouter]). After each warp or fall the route is planned
 * again from where the player really arrived, with the live state of the new map.
 *
 * Targets:
 * - `x`/`y` (current map), or `x`/`y` with `map` (a map's name or `map:<id>`: another floor, a neighbouring map);
 * - `person:N`, `item:N` (item ball), `warp:N`, `hole:N`, `sign:N`, `hidden_item:N`: things of the current map;
 * - `exit:north|south|east|west`: the edge of the current map towards a neighbouring map (overworld), the walk ends
 *   on the first tile of the neighbour;
 * - a map's name ("Route 26", "Victory Road 2F") or `map:<id>`: the walk ends on entering that map;
 * - `frontier`: when the agent doesn't know where to go, the nearest reachable tile of this map next to a way out
 *   (an edge towards a neighbouring map, a warp, a hole) other than those within [FRONTIER_SKIP] tiles of the player
 *   (the way they came in). The walk stops before taking it, so the agent sees what is there.
 */
internal object WorldTravel {

    /** A destination: [target], in the coordinates of the area of [zone]. */
    data class Goal(val zone: Int, val target: MovePlans.Target)

    fun goTo(action: GameAction.GoTo, context: PlanContext): ActionOutcome {
        val field = context.state().field ?: return ActionOutcome.Failed(ActionError.UnexpectedScreen("the overworld", context.state().screen.kind))
        val world = context.game.world ?: return noMap(field)
        val goal = when (val resolved = resolve(context, field, world, action)) {
            is Resolved.Found -> resolved.goal
            is Resolved.Failed -> return resolved.outcome
        }
        val trip = travel(context, world, goal, action.options)
        return finish(context, trip, goal)
    }

    /**
     * Walks next to [target] of the current map (by its id, see [MovePlans.resolve]), through other floors when the
     * way goes there: the walk of `interact`.
     */
    fun walkNextTo(context: PlanContext, target: MovePlans.Target): Trip {
        val field = context.state().field ?: return Trip(MovePlans.Walk.Interrupted(context.state(), 0), emptyList(), emptySet())
        val world = context.game.world ?: return Trip(MovePlans.walkTo(context, target, MoveOptions()), emptyList(), emptySet())
        return travel(context, world, Goal(field.mapId, target), MoveOptions())
    }

    /** How a [travel] ended: the last walk, the links taken before it, the active triggers of its map. */
    data class Trip(val walk: MovePlans.Walk, val taken: List<ZoneLink>, val triggers: Set<Pair<Int, Int>>)

    // region Targets

    private sealed interface Resolved {
        data class Found(val goal: Goal) : Resolved
        data class Failed(val outcome: ActionOutcome) : Resolved
    }

    private fun resolve(context: PlanContext, field: FieldState, world: WorldSource, action: GameAction.GoTo): Resolved {
        val area = world.areaOf(field.mapId) ?: return Resolved.Failed(noMap(field))
        val target = action.target
        if (action.map != null) {
            val zones = zonesNamed(context, world, action.map)
            if (zones.isEmpty()) return Resolved.Failed(ActionOutcome.Failed(ActionError.InvalidParameter("map", action.map, knownMaps(context, world, area, field))))
            // Prefer the zone of that name closest to here: the current area, then a map linked to it.
            val zone = zones.firstOrNull { world.areaOf(it) === area } ?: zones.first()
            if (action.x == null || action.y == null) return Resolved.Found(Goal(zone, zoneTarget("map:$zone", world, zone)))
            val targetArea = world.areaOf(zone) ?: return Resolved.Failed(noMap(field))
            val warp = targetArea.warps.firstOrNull { it.zone == zone && it.x == action.x && it.y == action.y }
            return Resolved.Found(Goal(zone, MovePlans.Target("${action.x},${action.y} on ${context.game.zoneName(zone) ?: "map:$zone"}", action.x, action.y, warp = warp != null, exit = warp?.exitDirection)))
        }
        if (target == null) {
            return MovePlans.resolve(context, null, action.x, action.y)?.let { Resolved.Found(Goal(field.mapId, it)) }
                ?: Resolved.Failed(MovePlans.unknownTarget(context, null))
        }
        if (target == FRONTIER) return frontier(world, area, field)
        if (target.startsWith("exit:")) return exit(context, area, field, target)
        MovePlans.resolve(context, target, null, null)?.let { return Resolved.Found(Goal(field.mapId, it)) }
        val zones = zonesNamed(context, world, target)
        if (zones.isNotEmpty()) {
            val zone = zones.firstOrNull { world.areaOf(it) === area } ?: zones.first()
            if (zone == field.mapId) return Resolved.Failed(ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "You are already on ${field.mapName}")))
            return Resolved.Found(Goal(zone, zoneTarget(target, world, zone)))
        }
        val exits = WorldLinks.connections(area, field.mapId).map { it.id }.distinct() + FRONTIER
        return Resolved.Failed(MovePlans.unknownTarget(context, target, exits))
    }

    /** Entering zone [zone]: any tile of it (its area is shared with other zones on the overworld). */
    private fun zoneTarget(id: String, world: WorldSource, zone: Int): MovePlans.Target {
        val area = world.areaOf(zone)
        return MovePlans.Target(id, null, null, isGoal = { node -> area?.zoneAt(node.x, node.y) == zone })
    }

    /** `exit:<direction>`: the tiles of the neighbouring maps one step past this map's edge that way. */
    private fun exit(context: PlanContext, area: Area, field: FieldState, target: String): Resolved {
        val connections = WorldLinks.connections(area, field.mapId)
        val direction = Direction.parse(target.substringAfter(':'))
        val chosen = connections.filter { it.direction == direction }
        if (direction == null || chosen.isEmpty()) {
            val allowed = connections.map { "${it.id} (${context.game.zoneName(it.toZone) ?: "map:${it.toZone}"})" }.distinct()
            val detail = if (connections.isEmpty()) "${field.mapName} has no edge leading to another map: use its warps (see exits)" else "No exit that way"
            return Resolved.Failed(ActionOutcome.Failed(if (connections.isEmpty()) ActionError.Unavailable(UnavailableReason.NO_PATH, detail) else ActionError.InvalidParameter("target", target, allowed)))
        }
        val beyond = chosen.flatMap { c -> c.tiles.map { (x, y) -> x + c.direction.dx to y + c.direction.dy } }.toSet()
        return Resolved.Found(Goal(field.mapId, MovePlans.Target(target, null, null, isGoal = { node -> (node.x to node.y) in beyond })))
    }

    /** `frontier`: the nearest reachable tile next to a way out of this map, away from where the player stands. */
    private fun frontier(world: WorldSource, area: Area, field: FieldState): Resolved {
        val zone = field.mapId
        val edgeTiles = WorldLinks.connections(area, zone).flatMap { it.tiles }
        val nearLinks = WorldLinks.links(world, area, zone).flatMap { l -> Direction.entries.map { l.x + it.dx to l.y + it.dy } }
            .filter { (x, y) -> area.zoneAt(x, y) == zone && area.tile(x, y)?.blocked == false }
        val candidates = (edgeTiles + nearLinks).filter { (x, y) -> kotlin.math.abs(x - field.x) + kotlin.math.abs(y - field.y) > FRONTIER_SKIP }.toSet()
        if (candidates.isEmpty()) {
            return Resolved.Failed(ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "No other way out of ${field.mapName} than the one here")))
        }
        return Resolved.Found(Goal(zone, MovePlans.Target(FRONTIER, null, null, isGoal = { node -> (node.x to node.y) in candidates })))
    }

    /** Zones named [name] (our map names, case and punctuation ignored) or `map:<id>`. */
    private fun zonesNamed(context: PlanContext, world: WorldSource, name: String): List<Int> {
        name.removePrefix("map:").toIntOrNull()?.takeIf { name.startsWith("map:") }?.let { id -> return listOfNotNull(id.takeIf { world.areaOf(it) != null }) }
        val wanted = normalize(name)
        if (wanted.isEmpty()) return emptyList()
        return (0 until world.zoneCount).filter { id -> context.game.zoneName(id)?.let(::normalize) == wanted }
    }

    /** Maps worth suggesting: the neighbours and the destinations of this map's warps and holes. */
    private fun knownMaps(context: PlanContext, world: WorldSource, area: Area, field: FieldState): List<String> =
        (WorldLinks.connections(area, field.mapId).map { it.toZone } + WorldLinks.links(world, area, field.mapId).map { it.targetZone })
            .distinct().mapNotNull { context.game.zoneName(it) }

    private fun normalize(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    // endregion

    // region Walking

    /** Walks to [goal]: on this area directly, or through the links the [WorldRouter] finds, one at a time. */
    private fun travel(context: PlanContext, world: WorldSource, goal: Goal, options: MoveOptions): Trip {
        val taken = mutableListOf<ZoneLink>()
        var localFailure: MovePlans.Walk.NoRoute? = null
        repeat(MAX_HOPS) {
            val state = context.navigator.settle()
            val field = state.field
            if (field == null || state.screen !is Screen.Overworld) return Trip(MovePlans.Walk.Interrupted(state, 0), taken, emptySet())
            val area = world.areaOf(field.mapId)
            val goalArea = world.areaOf(goal.zone)
            if (area == null || goalArea == null) return Trip(MovePlans.Walk.NoRoute(RouteFailure.StartUnknown, "no map data for ${field.mapName}"), taken, emptySet())
            val triggers = MovePlans.activeTriggers(context, field)
            if (area === goalArea) {
                val walked = MovePlans.walkTo(context, goal.target, options)
                if (walked !is MovePlans.Walk.NoRoute || walked.failure == RouteFailure.StartUnknown) return Trip(walked, taken, triggers)
                // Not reachable on this map by walking: maybe through other floors / buildings.
                localFailure = walked
            }
            val link = nextLink(context, world, field, area, goalArea, goal, options)
                ?: return Trip(
                    // A field move needed on this very map: the local failure knows where to use it from and what the
                    // party lacks; keep it over the less precise cross-map one.
                    localFailure?.takeIf { it.failure is RouteFailure.NeedsFieldMove }
                        ?: blocked(context, world, field, area, goalArea, goal, options) ?: localFailure
                        ?: MovePlans.Walk.NoRoute(RouteFailure.Unreachable, "no way to ${goal.target.id} from ${field.x},${field.y} (${field.mapName})"),
                    taken, triggers,
                )
            val walked = MovePlans.walkTo(context, MovePlans.Target(link.id, link.x, link.y, warp = true, exit = link.exitDirection), options)
            if (walked !is MovePlans.Walk.Arrived) return Trip(walked, taken, triggers)
            if (walked.field.mapId == field.mapId) return Trip(MovePlans.Walk.Stuck("${link.id} at ${link.x},${link.y} didn't take the player anywhere"), taken, triggers)
            taken += link
            localFailure = null
        }
        return Trip(MovePlans.Walk.Stuck("still not there after ${taken.size} warps: ${describe(taken)}"), taken, emptySet())
    }

    /** The first link of the cheapest route from the player to [goal] across zones, or null when there is none. */
    private fun nextLink(context: PlanContext, world: WorldSource, field: FieldState, area: Area, goalArea: Area, goal: Goal, options: MoveOptions): ZoneLink? =
        worldRoute(context, world, field, area, goalArea, goal, options)?.links?.firstOrNull()

    private fun worldRoute(
        context: PlanContext,
        world: WorldSource,
        field: FieldState,
        area: Area,
        goalArea: Area,
        goal: Goal,
        options: MoveOptions,
        relaxed: Boolean = false,
    ): WorldRouter.WorldRoute? {
        val overlay = MovePlans.overlay(context, field, emptySet())
        val router = WorldRouter(world) { _, a -> if (a === area) overlay else WorldRouter.staticOverlay(a) }
        val start = Node(field.x, field.y, Pathfinder(area).levelAt(field.x, field.y, field.height * MovePlans.HEIGHT_UNITS))
        val goalTiles = MovePlans.goalTiles(goalArea, goal.target)
        val enterable = if (goal.target.adjacent) emptySet() else goalTiles
        return router.route(
            field.mapId, start, MovePlans.routeOptions(field, options),
            goalTiles = { a -> if (a === goalArea) enterable else emptySet() }, relaxed = relaxed, ignorePeople = relaxed,
        ) { place -> place.area === goalArea && (goal.target.isGoal?.invoke(place.node) ?: ((place.node.x to place.node.y) in goalTiles)) }
    }

    /**
     * Why there is no route across zones either: the first obstacle (field move, person) of the route that crosses
     * obstacles, with the map it's on. Null when even that finds nothing.
     */
    private fun blocked(context: PlanContext, world: WorldSource, field: FieldState, area: Area, goalArea: Area, goal: Goal, options: MoveOptions): MovePlans.Walk.NoRoute? {
        if (!options.acceptOneWay && worldRoute(context, world, field, area, goalArea, goal, options.copy(acceptOneWay = true)) != null) {
            return MovePlans.Walk.NoRoute(RouteFailure.OnlyOneWay, "no way to ${goal.target.id} from ${field.x},${field.y} without jumping down ledges")
        }
        val route = worldRoute(context, world, field, area, goalArea, goal, options, relaxed = true) ?: return null
        val routeOptions = MovePlans.routeOptions(field, options)
        val overlay = MovePlans.overlay(context, field, emptySet())
        for (place in route.places) {
            val pathfinder = if (place.area === area) Pathfinder(area, overlay) else Pathfinder(place.area, WorldRouter.staticOverlay(place.area))
            val failure = pathfinder.blockerAt(place.node.x, place.node.y, routeOptions) ?: continue
            val zone = place.zone
            val where = if (place.area === area) "" else " on ${zone?.let { context.game.zoneName(it) } ?: "another floor"}"
            val via = if (route.links.isEmpty()) "" else " (the way: " +
                route.links.joinToString(", ") { it.id + " → " + (context.game.zoneName(it.targetZone) ?: "map:${it.targetZone}") } + ")"
            return MovePlans.Walk.NoRoute(failure, "no way to ${goal.target.id} from ${field.x},${field.y}: blocked at ${place.node.x},${place.node.y}$where$via")
        }
        return null
    }

    /** The outcome of the last walk, with the warps taken and the scene a trigger started. */
    private fun finish(context: PlanContext, trip: Trip, goal: Goal): ActionOutcome {
        val walked = trip.walk
        val taken = trip.taken
        val triggers = trip.triggers
        val via = if (taken.isEmpty()) "" else " (${describe(taken)})"
        return when (walked) {
            is MovePlans.Walk.Arrived -> ActionOutcome.Done("arrived at ${walked.field.x},${walked.field.y}" + (if (taken.isEmpty()) "" else " on ${walked.field.mapName}") + via)
            is MovePlans.Walk.Interrupted -> {
                val at = walked.at
                if (at != null && at in triggers && walked.state.battle == null) {
                    val onGoal = goal.target.x == at.first && goal.target.y == at.second
                    if (onGoal) ActionOutcome.Done("arrived at ${at.first},${at.second}: this started a scene (now: ${walked.state.screen.kind})$via")
                    else ActionOutcome.Failed(ActionError.Interrupted(InterruptionCause.SCRIPT, "${walked.steps} step(s)$via; the only way crosses a scene trigger at ${at.first},${at.second}, which started"))
                } else with(MovePlans) { walked.toOutcome(context) { "" } }.let { outcome ->
                    if (taken.isNotEmpty() && outcome is ActionOutcome.Failed && outcome.error is ActionError.Interrupted) {
                        ActionOutcome.Failed(outcome.error.copy(performed = outcome.error.performed + via))
                    } else outcome
                }
            }
            else -> with(MovePlans) { walked.toOutcome(context) { "" } }
        }
    }

    private fun describe(taken: List<ZoneLink>): String =
        if (taken.isEmpty()) "no warp taken" else "via " + taken.joinToString(", ") { l -> l.id + if (l.oneWay) " (fell, one way)" else "" }

    private fun noMap(field: FieldState) =
        ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "no map data for ${field.mapName}"))

    // endregion

    /** The target id of [frontier]. */
    const val FRONTIER = "frontier"

    /** `frontier` skips the ways out this close to the player (the one they just came through). */
    private const val FRONTIER_SKIP = 2

    /** At most this many warps / falls per `go_to`. */
    private const val MAX_HOPS = 12
}
