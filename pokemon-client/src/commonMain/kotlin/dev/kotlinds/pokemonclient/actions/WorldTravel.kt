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
        return finish(context, trip, goal).withNotes(trip.notes).withMovement(context, field, trip.taken)
    }

    /**
     * A failure after the player already moved (walked towards a link, took a warp, then found no way on) says so:
     * where they started, where they are now and the warps taken, so the answer never looks like nothing happened.
     * Interruptions already tell their steps.
     */
    private fun ActionOutcome.withMovement(context: PlanContext, start: FieldState, taken: List<ZoneLink>): ActionOutcome {
        if (this !is ActionOutcome.Failed || error is ActionError.Interrupted) return this
        val now = context.state().field ?: return this
        if (now.mapId == start.mapId && now.x == start.x && now.y == start.y) return this
        val where = if (now.mapId == start.mapId) "" else " on ${now.mapName}"
        val moved = "you moved before this was found: from ${start.x},${start.y}" + (if (now.mapId == start.mapId) "" else " on ${start.mapName}") +
            " to ${now.x},${now.y}$where" + (if (taken.isEmpty()) "" else " (${describe(taken)})")
        return when (val e = error) {
            is ActionError.Unavailable -> ActionOutcome.Failed(e.copy(detail = "${e.detail} ($moved)"))
            is ActionError.Timeout -> ActionOutcome.Failed(e.copy(detail = "${e.detail} ($moved)"))
            else -> this
        }
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

    /**
     * How a [travel] ended: the last walk, the links taken before it, the active triggers of its map, and what the
     * agent should know about the way (the bicycle...).
     */
    data class Trip(val walk: MovePlans.Walk, val taken: List<ZoneLink>, val triggers: Set<Pair<Int, Int>>, val notes: List<String> = emptyList())

    // region Targets

    private sealed interface Resolved {
        data class Found(val goal: Goal) : Resolved
        data class Failed(val outcome: ActionOutcome) : Resolved
    }

    private fun resolve(context: PlanContext, field: FieldState, world: WorldSource, action: GameAction.GoTo): Resolved {
        val area = world.areaOf(field.mapId) ?: return Resolved.Failed(noMap(field))
        val target = action.target
        val near = nearZones(world, area, field)
        if (action.map != null) {
            val zones = zonesNamed(context, world, action.map, near)
            if (zones.isEmpty()) return Resolved.Failed(ActionOutcome.Failed(ActionError.InvalidParameter("map", action.map, knownMaps(context, world, area, field))))
            ambiguous(context, world, area, "map", action.map, zones)?.let { return it }
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
        val zones = zonesNamed(context, world, target, near)
        if (zones.isNotEmpty()) {
            ambiguous(context, world, area, "target", target, zones)?.let { return it }
            val zone = zones.firstOrNull { world.areaOf(it) === area } ?: zones.first()
            if (zone == field.mapId) return Resolved.Failed(ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "You are already on ${field.mapName}")))
            return Resolved.Found(Goal(zone, zoneTarget(target, world, zone)))
        }
        val exits = WorldLinks.connections(area, field.mapId).map { it.id }.distinct() + FRONTIER
        // A name that matched no map: the maps around here are what it most likely meant.
        val maps = if (':' in target) emptyList() else knownMaps(context, world, area, field)
        return Resolved.Failed(MovePlans.unknownTarget(context, target, exits + maps))
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

    /**
     * Zones named [name] or `map:<id>`. Our map names, case and punctuation ignored (apostrophes too: "Elm's" =
     * "Elms"); when none has exactly that name, the looser variants an agent writes ([looseMatch]): without the town
     * ("Elm's Lab" for "New Bark Elms Lab 1F") or without the floor of a ground floor ("1F"). Among several loose
     * matches, those around the player (this area, the maps its exits lead to) win; still several: all of them,
     * which [resolve] reports as ambiguous.
     */
    private fun zonesNamed(context: PlanContext, world: WorldSource, name: String, near: Set<Int> = emptySet()): List<Int> {
        name.removePrefix("map:").toIntOrNull()?.takeIf { name.startsWith("map:") }?.let { id -> return listOfNotNull(id.takeIf { world.areaOf(it) != null }) }
        if (normalize(name).isEmpty()) return emptyList()
        val names = (0 until world.zoneCount).mapNotNull { id -> context.game.zoneName(id)?.let { id to it } }
        // A possessive is written either way: "Elm's Lab" is "Elms Lab", "Diglett's Cave" is "Diglett Cave".
        val queries = listOf(name, POSSESSIVE.replace(name, "")).distinct()
        val exact = names.filter { (_, n) -> queries.any { sameMapName(n, it) } }.map { it.first }
        if (exact.isNotEmpty()) return exact
        val loose = names.filter { (_, n) -> queries.any { looseMatch(n, it) } }.map { it.first }
        val nearby = loose.filter { it in near }
        return nearby.ifEmpty { loose }
    }

    /**
     * True when [query] names map [name] loosely: [name] (or [name] without its ground-floor "1F") ends with [query],
     * a whole word or more of it ("Elms Lab" → "New Bark Elms Lab 1F", "Dept Store" → "Goldenrod Dept Store 1F").
     */
    internal fun looseMatch(name: String, query: String): Boolean {
        val q = mapKey(query)
        if (q.length < MIN_LOOSE_NAME) return false
        val words = name.split(' ', '-', '.').filter { it.isNotBlank() }
        val variants = listOfNotNull(words, words.takeIf { it.lastOrNull()?.equals(GROUND_FLOOR, ignoreCase = true) == true }?.dropLast(1))
        // A suffix made of whole words of the name: "Lab" matches "Elms Lab", "ab" doesn't.
        return variants.any { w -> w.indices.any { start -> mapKey(w.drop(start).joinToString(" ")) == q } }
    }

    /** "'s" / "’s" ending a word. */
    private val POSSESSIVE = Regex("['’]s\\b", RegexOption.IGNORE_CASE)

    /** Ground floors: "Elm's Lab" means its 1F. */
    private const val GROUND_FLOOR = "1F"

    /** Loose names shorter than this (normalized) are too vague to guess from. */
    private const val MIN_LOOSE_NAME = 3

    /**
     * True when [a] and [b] name the same map, ignoring case, spaces and punctuation, and a "Town" / "City" one of
     * them adds: the town is "New Bark Town" on screen, its map "New Bark".
     */
    internal fun sameMapName(a: String, b: String): Boolean = mapKey(a) == mapKey(b)

    private fun mapKey(value: String) = normalize(value).removeSuffix("town").removeSuffix("city")

    /**
     * The INVALID_PARAM for a name several maps of different names answer to loosely ("Pokecenter 1F" in every town),
     * listing them; null when they all share one name (the floors of a map spread over areas) or one is on this area.
     */
    private fun ambiguous(context: PlanContext, world: WorldSource, area: Area, parameter: String, value: String, zones: List<Int>): Resolved? {
        if (zones.any { world.areaOf(it) === area }) return null
        val names = zones.mapNotNull { context.game.zoneName(it) }.distinct()
        if (names.size <= 1) return null
        return Resolved.Failed(ActionOutcome.Failed(ActionError.InvalidParameter(parameter, value, names.take(MAX_SUGGESTED_NAMES))))
    }

    /** The zones of [area] and those this map's exits lead to: where a loose name most likely points. */
    private fun nearZones(world: WorldSource, area: Area, field: FieldState): Set<Int> =
        (area.zoneBounds.keys + field.mapId + WorldLinks.connections(area, field.mapId).map { it.toZone } +
            WorldLinks.links(world, area, field.mapId).map { it.targetZone }).toSet()

    /** Maps worth suggesting: the neighbours and the destinations of this map's warps and holes. */
    private fun knownMaps(context: PlanContext, world: WorldSource, area: Area, field: FieldState): List<String> =
        (WorldLinks.connections(area, field.mapId).map { it.toZone } + WorldLinks.links(world, area, field.mapId).map { it.targetZone })
            .distinct().mapNotNull { context.game.zoneName(it) }

    private fun normalize(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    // endregion

    // region Walking

    /** Walks to [goal]: on this area directly, or through the links the [WorldRouter] finds, one at a time. */
    private fun travel(context: PlanContext, world: WorldSource, goal: Goal, options: MoveOptions): Trip {
        val notes = mutableListOf<String>()
        val trip = travel(context, world, goal, options, notes)
        // Got off at a building that doesn't allow cycling after riding there: nothing worth saying.
        val said = if (RODE in notes) notes.filterNot { it.startsWith(NO_CYCLING) } else notes
        return trip.copy(notes = said + trip.notes)
    }

    private fun travel(context: PlanContext, world: WorldSource, goal: Goal, options: MoveOptions, notes: MutableList<String>): Trip {
        val taken = mutableListOf<ZoneLink>()
        var localFailure: MovePlans.Walk.NoRoute? = null
        repeat(MAX_HOPS) {
            // Back on the bicycle after each warp (a building gets the player off it).
            BikeRide.mount(context, options)?.let { if (it !in notes) notes += it }
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
                    localFailure?.takeIf { it.failure is RouteFailure.NeedsFieldMove || it.failure is RouteFailure.BlockedByBarrier || it.failure == RouteFailure.DifferentLevel || it.failure is dev.kotlinds.pokemonclient.world.NeedsMechanism }
                        ?: blocked(context, world, field, area, goalArea, goal, options) ?: localFailure
                        ?: MovePlans.Walk.NoRoute(RouteFailure.Unreachable, "no way to ${goal.target.id} from ${field.x},${field.y} (${field.mapName})"),
                    taken, triggers,
                )
            val walked = MovePlans.walkTo(context, MovePlans.Target(link.id, link.x, link.y, warp = true, exit = link.exitDirection), options)
            if (walked !is MovePlans.Walk.Arrived) return Trip(walked, taken, triggers)
            // A warp leads to another area (a building, a floor); a warp to this same map (a gym's pad) leaves the map
            // unchanged: it worked when the player was moved off it. Walking into the next zone of the overworld is no warp.
            if (world.areaOf(walked.field.mapId) === area && !teleported(link, walked.field)) {
                return Trip(MovePlans.Walk.Stuck("${link.id} at ${link.x},${link.y} didn't take the player anywhere"), taken, triggers)
            }
            taken += link
            // What the walk to the link did besides walking (a tree cut on the way): told with the final answer.
            walked.notes.forEach { if (it !in notes) notes += it }
            localFailure = null
        }
        return Trip(MovePlans.Walk.Stuck("still not there after ${taken.size} warps: ${describe(taken)}"), taken, emptySet())
    }

    /**
     * True when [link], a warp to the map it is on, moved the player: they stand on its arrival tile, or anywhere but
     * on the warp itself (the same-map jump is the only sign it fired).
     */
    internal fun teleported(link: ZoneLink, field: FieldState): Boolean =
        link.targetZone == field.mapId && ((link.toX == field.x && link.toY == field.y) || (field.x != link.x || field.y != link.y))

    /** The first link of the cheapest route from the player to [goal] across zones, or null when there is none. */
    private fun nextLink(context: PlanContext, world: WorldSource, field: FieldState, area: Area, goalArea: Area, goal: Goal, options: MoveOptions): ZoneLink? =
        (worldRoute(context, world, field, area, goalArea, goal, options)
            // The only way out crosses a scene trigger (Elm's aide by the lab's door): take it, the scene will start
            // on the way like for a target on this map (the walk to the link allows triggers the same way).
            ?: worldRoute(context, world, field, area, goalArea, goal, options, crossScenes = true))?.links?.firstOrNull()

    private fun worldRoute(
        context: PlanContext,
        world: WorldSource,
        field: FieldState,
        area: Area,
        goalArea: Area,
        goal: Goal,
        options: MoveOptions,
        relaxed: Boolean = false,
        crossScenes: Boolean = false,
    ): WorldRouter.WorldRoute? {
        val overlay = MovePlans.overlay(context, field, emptySet()).let { if (crossScenes) it.copy(activeTriggers = emptySet()) else it }
        val router = WorldRouter(world) { _, a -> if (a === area) overlay else WorldRouter.staticOverlay(a) }
        val start = Node(field.x, field.y, Pathfinder(area).levelAt(field.x, field.y, field.height * MovePlans.HEIGHT_UNITS))
        val goalTiles = MovePlans.goalTiles(goalArea, goal.target)
        val enterable = if (goal.target.adjacent) emptySet() else goalTiles
        return router.route(
            field.mapId, start, worldRouteOptions(context, field, options),
            goalTiles = { a -> if (a === goalArea) enterable else emptySet() }, relaxed = relaxed, ignorePeople = relaxed,
        ) { place -> place.area === goalArea && (goal.target.isGoal?.invoke(place.node) ?: ((place.node.x to place.node.y) in goalTiles)) }
    }

    /**
     * The route options across zones: the same field moves as a walk on one map ([FieldMoveWalk.usable]: Cut, Surf...
     * the party can use by itself), so a Cut tree in front of another map's door is cut like one on the way inside a
     * gym (NOTES: "needs Cut" in front of the Vermilion Gym while the walk out cut it).
     */
    internal fun worldRouteOptions(context: PlanContext, field: FieldState, options: MoveOptions) =
        MovePlans.routeOptions(field, options, FieldMoveWalk.usable(FieldMoveWalk.access(context, context.state())))

    /**
     * Why there is no route across zones either: the first obstacle (field move, person) of the route that crosses
     * obstacles, with the map it's on. Null when even that finds nothing.
     */
    private fun blocked(context: PlanContext, world: WorldSource, field: FieldState, area: Area, goalArea: Area, goal: Goal, options: MoveOptions): MovePlans.Walk.NoRoute? {
        if (!options.acceptOneWay && worldRoute(context, world, field, area, goalArea, goal, options.copy(acceptOneWay = true)) != null) {
            return MovePlans.Walk.NoRoute(RouteFailure.OnlyOneWay, "no way to ${goal.target.id} from ${field.x},${field.y} without jumping down ledges")
        }
        // Scene triggers don't block the way (a scene starts, then the walk goes on): crossing them keeps the search
        // from inventing a detour over water around a trigger (NOTES-run P6: "needs Surf" next to Violet's bridge).
        val route = worldRoute(context, world, field, area, goalArea, goal, options, relaxed = true, crossScenes = true) ?: return null
        val routeOptions = worldRouteOptions(context, field, options)
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
            is MovePlans.Walk.Arrived -> ActionOutcome.Done(
                "arrived at ${walked.field.x},${walked.field.y}" + (if (taken.isEmpty()) "" else " on ${walked.field.mapName}") + via +
                    walked.notes.joinToString("") { "; $it" },
            )
            is MovePlans.Walk.Interrupted -> {
                val at = walked.at
                if (at != null && at in triggers && walked.state.battle == null) {
                    val onGoal = goal.target.x == at.first && goal.target.y == at.second
                    if (onGoal) ActionOutcome.Done("arrived at ${at.first},${at.second}: this started a scene (now: ${walked.state.screen.kind})$via")
                    else ActionOutcome.Failed(ActionError.Interrupted(InterruptionCause.SCRIPT,
                        "${walked.steps} step(s)$via; ${at.first},${at.second} wasn't the destination but the only way to ${goal.target.id} crosses it: " +
                            (MovePlans.sceneNote(context, walked.state.field?.mapId ?: context.state().field?.mapId, at) ?: "a scene trigger there started a scene")))
                } else with(MovePlans) { walked.toOutcome(context) { "" } }.let { outcome ->
                    if (taken.isNotEmpty() && outcome is ActionOutcome.Failed && outcome.error is ActionError.Interrupted) {
                        ActionOutcome.Failed(outcome.error.copy(performed = outcome.error.performed + via))
                    } else outcome
                }
            }
            else -> with(MovePlans) { walked.toOutcome(context) { "" } }
        }
    }

    /** [notes] added to the detail of a success or of an interruption. */
    private fun ActionOutcome.withNotes(notes: List<String>): ActionOutcome {
        if (notes.isEmpty()) return this
        val text = notes.joinToString("") { "; $it" }
        return when (this) {
            is ActionOutcome.Done -> copy(detail = (detail ?: "done") + text)
            is ActionOutcome.Failed -> if (error is ActionError.Interrupted) ActionOutcome.Failed(error.copy(performed = error.performed + text)) else this
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

    private const val RODE = BikeRide.RODE
    private const val NO_CYCLING = BikeRide.NO_CYCLING

    /** An ambiguous name lists at most this many maps. */
    private const val MAX_SUGGESTED_NAMES = 12

    /** At most this many warps / falls per `go_to`. */
    private const val MAX_HOPS = 12
}
