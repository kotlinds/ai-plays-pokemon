package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveEdge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.PushEdge
import dev.kotlinds.pokemonclient.world.PushPlanner
import dev.kotlinds.pokemonclient.world.Route
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.PlatformPlanner
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.TeleportLink
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileKind

/**
 * Recipes of the movement actions: routes computed on the ROM's maps ([dev.kotlinds.pokemonclient.world]) with the
 * live people on top, then walked one tile at a time, checking the position after each step.
 *
 * Walking never trusts the map blindly: a step the game refuses (an invisible wall, a person who moved in the way)
 * is remembered and the route is computed again; anything that takes the screen away from the overworld (a battle,
 * a trainer spotting the player, a phone call, a script) stops the walk with [ActionError.Interrupted].
 */
internal object MovePlans {

    /** Walks to a tile or a target, on this map or another one ([WorldTravel]: through warps, holes and map edges). */
    val goTo = ActionPlan<GameAction.GoTo> { action, context -> WorldTravel.goTo(action, context) }

    /**
     * Walks next to the target, faces it and presses A: the conversation / sign / item that follows is the result.
     *
     * People move: the target is looked up again (live position) before facing it and after a silent A. When the
     * player didn't end orthogonally next to it (or two tiles away across a counter), facing it, the walk starts
     * again (at most [INTERACT_TRIES] times). A press of A facing it that shows nothing is reported as such ("nothing
     * to say"), not as a failure to get there.
     */
    val interact = ActionPlan<GameAction.Interact> { action, context ->
        repeat(INTERACT_TRIES) {
            val target = resolve(context, action.target, null, null) ?: return@ActionPlan unknownTarget(context, action.target)
            val walked = WorldTravel.walkNextTo(context, target.copy(adjacent = true)).walk
            if (walked !is Walk.Arrived) return@ActionPlan walked.toOutcome(context) { "" }
            val field = walked.field
            val live = resolve(context, action.target, null, null) ?: target
            val facing = facingTowards(field, live) ?: return@repeat
            if (field.facing != facing) {
                context.scope.tap(facing.button)
                context.scope.step(TURN_FRAMES)
            }
            val beforeState = context.navigator.settle()
            val before = beforeState.screen
            context.scope.tap(Button.A)
            context.navigator.awaitChange(before)
            var after = context.navigator.settle()
            // Switches and levers are silent: their effect is the puzzle state changing (once the script has run).
            val puzzleBefore = beforeState.field?.puzzle
            if (puzzleBefore != null && after.screen is Screen.Overworld && after.field?.puzzle == puzzleBefore) {
                context.scope.step(SWITCH_FRAMES)
                after = context.navigator.settle()
            }
            val puzzleAfter = after.field?.puzzle
            if (puzzleBefore != null && puzzleAfter != null && puzzleAfter != puzzleBefore) {
                return@ActionPlan ActionOutcome.Done("the puzzle changed: ${puzzleChange(puzzleBefore, puzzleAfter)}")
            }
            if (!(after.screen is Screen.Overworld && after.screen.sameAsOverworld(before))) return@ActionPlan ActionOutcome.Done("now: ${after.screen.kind}")
            // Nothing on screen: still facing it (it has nothing to say), or it walked away (try again).
            val now = after.field ?: return@ActionPlan ActionOutcome.Done("now: ${after.screen.kind}")
            val stillThere = resolve(context, action.target, null, null)?.let { facingTowards(now, it) == now.facing } == true
            if (stillThere) return@ActionPlan ActionOutcome.Done("faced ${action.target} and pressed A: no message (nothing to say, or nothing to do there)")
        }
        ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "Couldn't get right in front of ${action.target} (it keeps moving, or only a diagonal tile is reachable)"))
    }

    /** The direction to face [target] from [field]: orthogonally next to it, or two tiles away across a counter. */
    private fun facingTowards(field: FieldState, target: Target): Direction? {
        val tx = target.x ?: return null
        val ty = target.y ?: return null
        val distance = kotlin.math.abs(tx - field.x) + kotlin.math.abs(ty - field.y)
        if (distance !in 1..2) return null
        return directionTo(field.x, field.y, tx, ty)
    }

    /**
     * Walks a straight line of [GameAction.Step.tiles] tiles, holding the direction (the turn, when the player faces
     * elsewhere, is part of the hold: no press is lost). Checked tile by tile; a refused step says where it stopped.
     */
    val step = ActionPlan<GameAction.Step> { action, context ->
        BikeRide.mount(context, action.options)
        val start = context.state().field ?: return@ActionPlan notInField(context)
        val d = action.direction
        val tiles = (1..action.tiles).map { Node(start.x + d.dx * it, start.y + d.dy * it) }
        when (val walked = WalkSegments.walk(context, WalkSegments.Segment(d, tiles), action.options)) {
            is WalkSegments.Result.Reached -> ActionOutcome.Done("walked ${action.tiles} tile(s) ${d.name.lowercase()}, now at ${walked.field.x},${walked.field.y}")
            is WalkSegments.Result.Elsewhere -> ActionOutcome.Done("moved to ${walked.field.x},${walked.field.y} (not a straight walk: slid, pushed, or another map)")
            is WalkSegments.Result.Refused -> {
                val done = kotlin.math.abs(walked.from.x - start.x) + kotlin.math.abs(walked.from.y - start.y)
                ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH,
                    "blocked after $done tile(s) at ${walked.from.x},${walked.from.y}: can't go ${d.name.lowercase()} from there"))
            }
            is WalkSegments.Result.Stopped -> ActionOutcome.Failed(ActionError.Interrupted(cause(walked.state), "${walked.walked} tile(s)"))
        }
    }

    /** Most tiles one [GameAction.Step] walks. */
    const val MAX_STEP_TILES = 20

    /** Walks to the nearest tall grass, then back and forth in it until a wild Pokémon appears. */
    val findEncounter = ActionPlan<GameAction.FindEncounter> { _, context ->
        val start = context.state().field ?: return@ActionPlan notInField(context)
        val area = context.game.world?.areaOf(start.mapId) ?: return@ActionPlan noMap(start)
        val grass = Target(GRASS, null, null, null, isGoal = { node -> area.tile(node.x, node.y)?.kind == TileKind.TallGrass })
        when (val walked = walkTo(context, grass, MoveOptions())) {
            is Walk.Arrived -> Unit
            is Walk.Interrupted -> return@ActionPlan if (walked.state.battle != null) ActionOutcome.Done("wild battle") else walked.toOutcome(context) { "" }
            else -> return@ActionPlan walked.toOutcome(context) { "" }
        }
        // Pace between two grass tiles: each step is a chance of an encounter.
        repeat(MAX_PACING_STEPS) {
            val field = context.state().field ?: return@ActionPlan battleOrStop(context)
            val back = Direction.entries.firstOrNull { d ->
                area.tile(field.x + d.dx, field.y + d.dy)?.let { it.kind == TileKind.TallGrass && !it.blocked } == true
            } ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "This patch of tall grass is a single tile"))
            when (val step = stepOnce(context, back, Node(field.x + back.dx, field.y + back.dy), MoveOptions())) {
                is StepResult.Moved -> Unit
                is StepResult.Stopped -> return@ActionPlan battleOrStop(context)
                is StepResult.Refused -> Unit
            }
            if (context.state().battle != null || context.state().screen !is Screen.Overworld) return@ActionPlan battleOrStop(context)
        }
        ActionOutcome.Failed(ActionError.Timeout("no wild Pokémon after $MAX_PACING_STEPS steps in the grass"))
    }

    /** What changed between two readings of a map puzzle, by id (shutters opened / closed, levers, routes). */
    private fun puzzleChange(before: PuzzleState, after: PuzzleState): String = buildList {
        val was = before.barriers.associate { it.id to it.open }
        after.barriers.filter { was[it.id] != null && was[it.id] != it.open }.forEach { add("${it.id} ${if (it.open) "opened" else "closed"}") }
        val flipped = before.switches.associate { it.id to it.flipped }
        after.switches.filter { it.flipped != null && flipped[it.id] != it.flipped }.forEach { add("${it.id} ${if (it.flipped == true) "flipped" else "back"}") }
        val routes = before.teleports.associate { it.id to it.to }
        after.teleports.filter { routes[it.id] != it.to }.forEach { add("${it.id} now leads to ${it.to.x},${it.to.y}") }
    }.joinToString().ifEmpty { "state updated" }

    // region Targets

    /**
     * Where to go: a tile, or a person / item / warp / hole / sign / hidden item of the current map by id. With
     * [adjacent] the walk stops next to it (or across a counter), never on it.
     */
    data class Target(
        val id: String,
        val x: Int?,
        val y: Int?,
        val warp: Boolean? = null,
        val adjacent: Boolean = false,
        val isGoal: ((Node) -> Boolean)? = null,
        /** For warps taken by pressing a direction once on them (exit mats, stairs): that direction. */
        val exit: Direction? = null,
    )

    internal fun resolve(context: PlanContext, target: String?, x: Int?, y: Int?): Target? {
        val state = context.state()
        val field = state.field
        val area = field?.let { context.game.world?.areaOf(it.mapId) }
        if (target == null) {
            if (x == null || y == null) return null
            // A door / stairs / exit tile: going there means going through.
            val warp = area?.warps?.firstOrNull { it.x == x && it.y == y }
            val hole = area?.triggerWarps?.any { it.x == x && it.y == y } == true
            return Target("$x,$y", x, y, warp = warp != null || hole, exit = warp?.exitDirection)
        }
        if (field == null || area == null) return null
        if (target == PC) {
            // Any PC tile will do: go to the closest reachable one (the nearest may be walled off from here). A PC only
            // answers when faced from the south (src/field/field_control.c: `IsPC && facingDirection == DIR_NORTH`).
            val (px, py) = nearestPc(area, field) ?: return null
            val goals = pcTiles(area, field).map { (x, y) -> x to y + 1 }.filter { (x, y) -> area.tile(x, y)?.kind != TileKind.Pc }.toSet()
            return Target(PC, px, py, adjacent = true, isGoal = { node -> (node.x to node.y) in goals })
        }
        val (kind, id) = target.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        val number = id.toIntOrNull()
        return when (kind) {
            "person" -> field.objects.firstOrNull { it.id == target }?.let { Target(target, it.x, it.y, adjacent = true) }
            // Item balls are objects of the map: `item:N` is the object `person:N` lying on the ground.
            "item" -> field.objects.firstOrNull { it.kind == FieldObjectKind.ITEM_BALL && it.id == "person:$id" }?.let { Target(target, it.x, it.y, adjacent = true) }
            "warp" -> area.warps.firstOrNull { it.zone == field.mapId && it.id == number }?.let { Target(target, it.x, it.y, warp = true, exit = it.exitDirection) }
            "hole" -> area.triggerWarps.firstOrNull { it.zone == field.mapId && it.trigger == number }?.let { Target(target, it.x, it.y, warp = true) }
            // `sign:N` also reaches a hidden item (older ids); `hidden_item:N` only items not picked up yet.
            "sign" -> area.signs.firstOrNull { it.zone == field.mapId && it.id == number }?.let { Target(target, it.x, it.y, adjacent = true) }
            "hidden_item" -> area.signs.firstOrNull { it.zone == field.mapId && it.id == number && it.kind == SignKind.HIDDEN_ITEM && target !in field.pickedUp }
                ?.let { Target(target, it.x, it.y, adjacent = true) }
            else -> null
        }
    }

    /** The closest PC tile around the player (Pokémon Centers have one; the player's room too). */
    private fun nearestPc(area: Area, field: FieldState): Pair<Int, Int>? =
        pcTiles(area, field).minByOrNull { (x, y) -> kotlin.math.abs(x - field.x) + kotlin.math.abs(y - field.y) }

    /** Every PC tile around the player. */
    private fun pcTiles(area: Area, field: FieldState): List<Pair<Int, Int>> =
        (-PC_SEARCH..PC_SEARCH).flatMap { dy -> (-PC_SEARCH..PC_SEARCH).map { dx -> field.x + dx to field.y + dy } }
            .filter { (x, y) -> area.tile(x, y)?.kind == TileKind.Pc }

    internal fun unknownTarget(context: PlanContext, target: String?, extra: List<String> = emptyList()): ActionOutcome {
        val field = context.state().field ?: return notInField(context)
        val known = buildList {
            field.objects.filter { it.kind != FieldObjectKind.FOLLOWER }.forEach { add(objectTargetId(it)) }
            context.game.world?.areaOf(field.mapId)?.let { area ->
                area.warps.filter { it.zone == field.mapId }.forEach { add("warp:${it.id}") }
                area.triggerWarps.filter { it.zone == field.mapId }.forEach { add("hole:${it.trigger}") }
                area.signs.filter { it.zone == field.mapId }.forEach { s ->
                    if (s.kind == SignKind.SIGN) add("sign:${s.id}") else if ("hidden_item:${s.id}" !in field.pickedUp) add("hidden_item:${s.id}")
                }
            }
            addAll(extra)
        }
        return ActionOutcome.Failed(ActionError.InvalidParameter("target", target ?: "none", known))
    }

    /** The target id of a map object: `item:N` for an item ball, its own id (`person:N`) otherwise. */
    fun objectTargetId(o: dev.kotlinds.pokemonclient.state.FieldObject): String =
        if (o.kind == FieldObjectKind.ITEM_BALL) "item:" + o.id.substringAfter(':') else o.id

    // endregion

    // region Walking

    /** How a walk ended. */
    sealed interface Walk {
        data class Arrived(
            val field: FieldState,
            /** What the walk did besides walking (field moves used, objects pushed), for the agent. */
            val notes: List<String> = emptyList(),
        ) : Walk
        /** Stopped by the game; [at] is the tile the player was stepping onto, when known. */
        data class Interrupted(val state: GameState, val steps: Int, val at: Pair<Int, Int>? = null, val notes: List<String> = emptyList()) : Walk
        data class NoRoute(
            val failure: RouteFailure,
            val detail: String,
            /** What the party can do with each field move (to say what's missing when one is needed). */
            val access: Map<FieldMoveKind, FieldMoveAccess> = emptyMap(),
        ) : Walk
        data class Stuck(val detail: String) : Walk

        /** A field move on the way didn't work (no question, another screen): [error] says what happened. */
        data class Failed(val error: ActionError) : Walk
    }

    /** Computes a route to [target] and walks it, computing it again after each refused step (at most a few times). */
    fun walkTo(context: PlanContext, target: Target, options: MoveOptions): Walk {
        val notes = mutableListOf<String>()
        val walk = walkTo(context, target, options, notes)
        if (notes.isEmpty()) return walk
        return when (walk) {
            is Walk.Arrived -> walk.copy(notes = notes)
            is Walk.Interrupted -> walk.copy(notes = notes)
            else -> walk
        }
    }

    private fun walkTo(context: PlanContext, target: Target, options: MoveOptions, notes: MutableList<String>): Walk {
        val refused = mutableSetOf<Pair<Node, Direction>>()
        var steps = 0
        val startMap = context.state().field?.mapId
        repeat(MAX_REPLANS) {
            val state = context.navigator.settle()
            val field = state.field ?: return Walk.Interrupted(state, steps)
            if (state.screen !is Screen.Overworld) return Walk.Interrupted(state, steps)
            // Through the targeted door already (its animation outlasted a step): arrived.
            if (target.warp == true && field.mapId != startMap) return Walk.Arrived(field)
            val area = context.game.world?.areaOf(field.mapId) ?: return Walk.NoRoute(RouteFailure.StartUnknown, "no map data for ${field.mapName}")
            val pathfinder = Pathfinder(area, overlay(context, field, refused))
            val start = Node(field.x, field.y, pathfinder.levelAt(field.x, field.y, field.height * HEIGHT_UNITS))
            val goalTiles = goalTiles(area, target)
            val isGoal: (Node) -> Boolean = target.isGoal ?: { node -> (node.x to node.y) in goalTiles }
            if (isGoal(start) && target.warp != true) return Walk.Arrived(field)
            // On the targeted exit mat already (just came in through it): only the press towards the exit is left.
            if (target.warp == true && target.exit != null && field.x == target.x && field.y == target.y) return takeExit(context, target.exit, field, options, steps)
            val access = FieldMoveWalk.access(context, state)
            val routeOptions = routeOptions(field, options, FieldMoveWalk.usable(access))
            // A tile target may be entered whatever it is (a warp, a scene trigger); targets reached with A never are.
            val enterable = if (target.adjacent) emptySet() else goalTiles
            // Moving platforms (Blackthorn Gym): plan the rides; a plain route would never step on a trigger knowingly.
            val ridden = field.puzzle?.mechanics?.let { PlatformPlanner(area, overlay(context, field, refused), it).route(start, routeOptions, enterable, isGoal) }
            val route = ridden ?: when (val result = pathfinder.route(start, routeOptions, enterable, isGoal)) {
                is Pathfinder.Result.Found -> result.route
                // No plain route: maybe one moving boulders / ice blocks out of the way.
                is Pathfinder.Result.Failed -> pushRoute(area, overlay(context, field, refused), start, routeOptions, enterable, isGoal)
                    ?: return Walk.NoRoute(result.failure, "no way to ${target.id} from ${field.x},${field.y}", access)
            }
            avoidanceNotes(route, options).forEach { if (it !in notes) notes += it }
            var from = start
            var index = -1
            var strengthActive = false
            while (++index < route.edges.size) {
                val edge = route.edges[index]
                // Straight runs of plain steps: walked holding the direction (smooth), checked on every tile.
                val segment = WalkSegments.segmentAt(route.edges, index, area)
                if (segment != null) {
                    when (val walked = WalkSegments.walk(context, segment, options)) {
                        is WalkSegments.Result.Reached -> {
                            steps += segment.tiles.size
                            if (changedArea(context, walked.field, field)) return Walk.Arrived(walked.field)
                            from = segment.tiles.last()
                            index += segment.tiles.size - 1
                            continue
                        }
                        is WalkSegments.Result.Elsewhere -> {
                            if (changedArea(context, walked.field, field)) return Walk.Arrived(walked.field)
                            return@repeat
                        }
                        is WalkSegments.Result.Refused -> {
                            val stuck = walked.from
                            refused += (route.edges.map { it.to }.firstOrNull { it.x == stuck.x && it.y == stuck.y } ?: start) to segment.direction
                            return@repeat
                        }
                        // [at]: the tile the player was stepping onto (a scene trigger there is what started).
                        is WalkSegments.Result.Stopped -> return Walk.Interrupted(
                            walked.state, steps + walked.walked, walked.at?.let { it.x to it.y },
                        )
                    }
                }
                if (edge is FieldMoveEdge) {
                    when (val used = FieldMoveWalk.use(context, edge, options)) {
                        is FieldMoveWalk.Use.Done -> {
                            steps++
                            with(FieldMoveWalk) { notes += "used ${edge.move.label()} at ${from.x},${from.y}" }
                            if (used.field.x != edge.to.x || used.field.y != edge.to.y) return@repeat
                            from = edge.to
                            continue
                        }
                        is FieldMoveWalk.Use.Stopped -> return Walk.Interrupted(used.state, steps)
                        is FieldMoveWalk.Use.Failed -> return Walk.Failed(used.error)
                    }
                }
                if (edge is PushEdge && edge.needsStrength && !strengthActive) {
                    when (val activated = FieldMoveWalk.activateStrength(context, edge.direction)) {
                        is FieldMoveWalk.Use.Done -> strengthActive = true
                        is FieldMoveWalk.Use.Stopped -> return Walk.Interrupted(activated.state, steps)
                        is FieldMoveWalk.Use.Failed -> return Walk.Failed(activated.error)
                    }
                }
                if (edge is Edge.Teleport) {
                    when (val ride = teleport(context, edge, options)) {
                        is StepResult.Moved -> {
                            steps++
                            if (ride.field.mapId != field.mapId) return Walk.Arrived(ride.field)
                            // Not where the teleport should have led (it didn't fire, or went elsewhere): compute again.
                            if (ride.field.x != edge.to.x || ride.field.y != edge.to.y) return@repeat
                            from = edge.to
                            continue
                        }
                        is StepResult.Refused -> {
                            refused += from to edge.direction
                            return@repeat
                        }
                        is StepResult.Stopped -> return Walk.Interrupted(ride.state, steps)
                    }
                }
                val intoWarp = area.warps.any { it.x == edge.to.x && it.y == edge.to.y }
                val slide = (edge as? Edge.Slide)?.tiles?.size ?: (edge as? PushEdge)?.takeIf { !it.needsStrength }?.tiles?.size ?: 0
                val intoGoalWarp = target.warp == true && (edge.to.x to edge.to.y) in goalTiles
                val long = edge is Edge.Jump || intoWarp || (edge as? PushEdge)?.needsStrength == true
                when (val step = stepOnce(context, edge.direction, edge.to, options, long = long, slide = slide)) {
                    is StepResult.Moved -> {
                        steps++
                        if (edge is PushEdge && step.field.x == edge.to.x && step.field.y == edge.to.y) {
                            notes += "pushed the ${if (edge.needsStrength) "boulder" else "ice block"} at ${edge.objectFrom.first},${edge.objectFrom.second} to ${edge.objectTo.first},${edge.objectTo.second}"
                        }
                        // Entering a door / warp changes the map: the walk is over.
                        if (step.field.mapId != field.mapId) return Walk.Arrived(step.field)
                        // Slid, pushed or overshot elsewhere than planned: compute the route again from where the
                        // player really is.
                        if (step.field.x != edge.to.x || step.field.y != edge.to.y) return@repeat
                        from = edge.to
                    }
                    is StepResult.Refused -> {
                        refused += from to edge.direction
                        return@repeat
                    }
                    is StepResult.Stopped -> {
                        // A hole (or a warp script) starts at once: wait for it to take the player to the other map.
                        if (intoGoalWarp) awaitMapChange(context, field.mapId)?.let { return Walk.Arrived(it) }
                        return Walk.Interrupted(step.state, steps, edge.to.x to edge.to.y)
                    }
                }
            }
            // On the targeted door / hole: its transition may still be running.
            if (target.warp == true && target.exit == null) awaitMapChange(context, field.mapId, WARP_START_FRAMES)?.let { return Walk.Arrived(it) }
            val end = context.navigator.settle()
            val endField = end.field ?: return Walk.Interrupted(end, steps)
            // Exit mats and stairs: standing on them isn't enough, the game waits for a press towards the exit.
            if (target.warp == true && endField.mapId == field.mapId && target.exit != null) return takeExit(context, target.exit, field, options, steps)
            val endNode = Node(endField.x, endField.y, pathfinder.levelAt(endField.x, endField.y, endField.height * HEIGHT_UNITS))
            if (!isGoal(endNode) && target.warp != true) return@repeat
            return Walk.Arrived(endField)
        }
        return Walk.Stuck("the game refused ${refused.size} steps on the way to ${target.id}")
    }

    /**
     * Standing on an exit mat or stairs, the game waits for a press towards the exit. Ladders and stairs: the press
     * starts a climb then a fade; the map changes well after the press, so wait for the change itself before
     * concluding (the step alone looks refused).
     */
    private fun takeExit(context: PlanContext, exit: Direction, field: FieldState, options: MoveOptions, steps: Int): Walk {
        val pushed = stepOnce(context, exit, Node(Int.MIN_VALUE, Int.MIN_VALUE), options)
        if (pushed is StepResult.Moved && pushed.field.mapId != field.mapId) return Walk.Arrived(pushed.field)
        awaitMapChange(context, field.mapId)?.let { return Walk.Arrived(it) }
        val now = context.navigator.settle()
        if (now.screen !is Screen.Overworld || now.field == null) return Walk.Interrupted(now, steps)
        return Walk.Stuck("the warp didn't take the player anywhere (still at ${now.field.x},${now.field.y})")
    }

    /**
     * Waits (up to [frames]) for the player to be on another map than [startMap] (a warp's fade, a fall), then for
     * the game to give the control back. The new map's field, or null when the map didn't change (or a battle or a
     * menu came first).
     */
    internal fun awaitMapChange(context: PlanContext, startMap: Int, frames: Int = WARP_CHANGE_FRAMES): FieldState? {
        var waited = 0
        while (waited < frames) {
            val state = context.state()
            if (state.battle != null || state.screen is Screen.Dialogue || state.screen is Screen.Selectable) return null
            val field = state.field
            if (field != null && field.mapId != startMap) return context.navigator.settle().field ?: field
            context.scope.step(2)
            waited += 2
        }
        return null
    }

    /** Tiles that end the walk: the target itself, or the tiles from which it can be reached with A. */
    internal fun goalTiles(area: Area, target: Target): Set<Pair<Int, Int>> {
        val x = target.x ?: return emptySet()
        val y = target.y ?: return emptySet()
        if (!target.adjacent) return setOf(x to y)
        return Direction.entries.flatMap { d ->
            val next = x + d.dx to y + d.dy
            // Across a counter (Pokémon Center nurse, shop clerk), A reaches two tiles away.
            val across = area.tile(next.first, next.second)?.kind == TileKind.Counter
            if (across) listOf(x + 2 * d.dx to y + 2 * d.dy) else listOf(next)
        }.toSet()
    }

    internal fun overlay(context: PlanContext, field: FieldState, refused: Set<Pair<Node, Direction>>): Overlay {
        val templates = context.game.world?.areaOf(field.mapId)?.people.orEmpty().filter { it.zone == field.mapId }
        return Overlay(
            objects = field.objects.map { o ->
                LiveObject(
                    o.x, o.y, o.facing,
                    isFollower = o.kind == FieldObjectKind.FOLLOWER,
                    sightRange = sightRange(o, templates),
                    clearedBy = when (o.obstacle) {
                        ObstacleKind.CUT_TREE -> FieldMoveKind.CUT
                        ObstacleKind.SMASH_ROCK -> FieldMoveKind.ROCK_SMASH
                        ObstacleKind.BOULDER -> FieldMoveKind.STRENGTH
                        ObstacleKind.ICE_BLOCK, null -> null
                    },
                    // An ice block facing north has frozen to another one: it no longer moves.
                    iceBlock = o.obstacle == ObstacleKind.ICE_BLOCK && o.facing == Direction.SOUTH,
                    fallsInto = field.puzzle?.boulderHoles?.firstOrNull { it.boulder == o.id && !it.fallen }?.let { it.hole.x to it.hole.y },
                )
            },
            refused = refused,
            activeTriggers = activeTriggers(context, field),
            blockedTiles = field.puzzle?.barriers.orEmpty().filterNot { it.open }.flatMap { b -> b.tiles.map { it.x to it.y } }.toSet(),
            teleports = puzzleTeleports(field.puzzle),
            surfaces = puzzleSurfaces(field.puzzle),
        )
    }

    /** The teleports of [puzzle] as route edges (heights converted to tile units). */
    internal fun puzzleTeleports(puzzle: PuzzleState?): List<TeleportLink> = puzzle?.teleports.orEmpty().flatMap { t ->
        t.from.map { TeleportLink(it.x, it.y, t.to.x, t.to.y, fromHeight = t.fromHeight?.times(HEIGHT_UNITS), toHeight = t.toHeight?.times(HEIGHT_UNITS)) }
    }

    /** The moving floors of [puzzle] (a lift platform) as live tile heights (tile units). */
    internal fun puzzleSurfaces(puzzle: PuzzleState?): Map<Pair<Int, Int>, List<Int>> =
        puzzle?.surfaces.orEmpty().flatMap { s -> s.tiles.map { (it.x to it.y) to s.heights.map { h -> h * HEIGHT_UNITS } } }.toMap()

    /**
     * How far [o] watches for the player: 0 once beaten (a beaten trainer never stops the player again), else its
     * live reading ([dev.kotlinds.pokemonclient.state.FieldTrainer.sightRange]) or the map's template.
     */
    internal fun sightRange(o: dev.kotlinds.pokemonclient.state.FieldObject, templates: List<dev.kotlinds.pokemonclient.world.PersonTemplate>): Int {
        val trainer = o.trainer
        if (trainer?.defeated == true) return 0
        return trainer?.sightRange ?: templates.firstOrNull { "person:${it.id}" == o.id }?.sightRange ?: 0
    }

    /**
     * Tiles that run a script when stepped on right now: map triggers whose variable has the value they wait for
     * (the Ecruteak Gym pits while the puzzle is unsolved, story events...). Avoided unless targeted.
     */
    internal fun activeTriggers(context: PlanContext, field: FieldState): Set<Pair<Int, Int>> {
        // Placeholder triggers (an empty script) start nothing: walked like floor.
        val triggers = context.game.world?.areaOf(field.mapId)?.triggers.orEmpty().filter { it.zone == field.mapId && !it.inert }
        if (triggers.isEmpty()) return emptySet()
        val memory = context.scope.memory()
        return triggers.filter { context.game.scriptVariable(memory, it.variable) == it.value }
            .flatMap { t -> (t.x until t.x + maxOf(1, t.width)).flatMap { x -> (t.y until t.y + maxOf(1, t.height)).map { y -> x to y } } }
            .toSet()
    }

    internal fun routeOptions(field: FieldState, options: MoveOptions, fieldMoves: Set<FieldMoveKind> = emptySet()) = RouteOptions(
        mode = field.movement,
        canSurf = FieldMoveKind.SURF in fieldMoves,
        avoidTallGrass = options.avoidTallGrass,
        avoidTrainers = options.avoidTrainers,
        acceptOneWay = options.acceptOneWay,
        fieldMoves = fieldMoves,
    )

    /**
     * A route moving objects out of the way ([PushPlanner]: Strength boulders, ice blocks), when the overlay has
     * some the party can move; null otherwise or when the puzzle search finds none within its bound.
     */
    private fun pushRoute(area: Area, overlay: Overlay, start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, isGoal: (Node) -> Boolean): Route? {
        val planner = PushPlanner(area, overlay)
        if (!planner.hasMovables(options)) return null
        return planner.route(start, options, goalTiles, isGoal)
    }

    /** True when [a] and [b] are on maps of different areas (a warp was taken), not zones of the same matrix. */
    private fun changedArea(context: PlanContext, a: FieldState, b: FieldState): Boolean {
        if (a.mapId == b.mapId) return false
        val world = context.game.world ?: return true
        return world.areaOf(a.mapId)?.id != world.areaOf(b.mapId)?.id
    }

    /** What the agent should know when the options asked to avoid something the only way crosses anyway. */
    private fun avoidanceNotes(route: Route, options: MoveOptions): List<String> = route.warnings.mapNotNull { w ->
        when {
            w is dev.kotlinds.pokemonclient.world.RouteWarning.CrossesTallGrass && options.avoidTallGrass ->
                "no way without tall grass here: the way crosses ${w.tiles} grass tile(s)"
            w is dev.kotlinds.pokemonclient.world.RouteWarning.PassesTrainerSight && options.avoidTrainers ->
                "no way around an unbeaten trainer's line of sight here"
            else -> null
        }
    }

    /** What one step did. */
    sealed interface StepResult {
        data class Moved(val field: FieldState) : StepResult
        data object Refused : StepResult
        data class Stopped(val state: GameState) : StepResult
    }

    /**
     * Holds [direction] until the player starts moving, then lets go and waits for the move to finish (the player
     * still for a few frames). No movement within a short delay ([long] for jumps and doors) means the game refused
     * the step.
     *
     * For a forced move ([slide] > 0: the number of tiles of an [Edge.Slide] on ice or spinners), the press only
     * starts it: the wait lasts as long as the slide, and "still" means longer than the spin a spinner does on each
     * arrow, so the player is on the landing tile [to] when it returns (or elsewhere, and the caller re-plans).
     */
    fun stepOnce(context: PlanContext, direction: Direction, to: Node, options: MoveOptions, long: Boolean = false, slide: Int = 0): StepResult {
        val buttons = buildSet {
            add(direction.button)
            if (options.run) add(Button.B)
        }
        val start = context.state().field ?: return StepResult.Stopped(context.state())
        var frames = 0
        val limit = if (long) LONG_STEP_FRAMES else STEP_FRAMES
        while (frames < limit) {
            context.scope.step(1, InputFrame(buttons))
            frames++
            val state = context.state()
            val field = state.field
            if (state.screen !is Screen.Overworld || field == null) return StepResult.Stopped(state)
            val started = field.moving || field.x != start.x || field.y != start.y || field.mapId != start.mapId
            if (!started) continue
            // The move has started: let go at once (on a bike, holding one frame too long commits to the next
            // tile), then wait for it to end and read where the player really is.
            // The game may still act on an input read a few frames ago (a bike starts the next tile by itself): the
            // step is over only once the player has stood still for a few frames in a row.
            var waited = 0
            var still = 0
            val settle = SETTLE_STEP_FRAMES + slide * SLIDE_TILE_FRAMES
            val stillFrames = if (slide > 0) SLIDE_STILL_FRAMES else STILL_FRAMES
            while (waited < settle && still < stillFrames) {
                context.scope.step(1)
                waited++
                still = if (context.state().field?.moving == true) 0 else still + 1
            }
            val after = context.state()
            val end = after.field
            if (after.screen !is Screen.Overworld && after.screen.awaiting != Awaiting.ANIMATION) return StepResult.Stopped(after)
            if (end == null) return StepResult.Stopped(after)
            if (end.mapId != start.mapId || (end.x == to.x && end.y == to.y)) return StepResult.Moved(end)
            // Only turned to face the direction (no tile change): keep holding.
            if (end.x == start.x && end.y == start.y) continue
            return StepResult.Moved(end)
        }
        context.scope.step(1)
        return StepResult.Refused
    }

    /**
     * Steps onto the source tile of a teleport (warp pad, cart ride), then waits for its script to run and for the
     * player to stand still again, wherever that is. A battle or a message on the way stops the walk.
     */
    private fun teleport(context: PlanContext, edge: Edge.Teleport, options: MoveOptions): StepResult {
        val start = context.state().field ?: return StepResult.Stopped(context.state())
        val step = stepOnce(context, edge.direction, edge.via, options, long = true)
        if (step is StepResult.Refused) return step
        // A lift keeps the player on its tile: its end is the height change.
        val viaHeight = (step as? StepResult.Moved)?.field?.height
        // The script starts at the end of the step: wait until it has moved the player (or for a while), then until
        // the player can walk again.
        var waited = 0
        var still = 0
        while (waited < TELEPORT_FRAMES) {
            context.scope.step(2)
            waited += 2
            val state = context.state()
            val field = state.field
            val awaiting = state.screen.awaiting
            if (state.battle != null || state.screen is Screen.Dialogue || state.screen is Screen.Selectable) return StepResult.Stopped(state)
            val arrived = field != null && (field.mapId != start.mapId || field.x != edge.via.x || field.y != edge.via.y ||
                (edge.to.x == edge.via.x && edge.to.y == edge.via.y && viaHeight != null && field.height != viaHeight))
            val idle = field != null && state.screen is Screen.Overworld && awaiting == Awaiting.INPUT && !field.moving
            still = if (idle) still + 1 else 0
            // Ready once idle for a while after moving away, or idle long enough on the source tile (didn't fire).
            if (idle && ((arrived && still >= TELEPORT_STILL_POLLS) || still >= TELEPORT_IDLE_POLLS)) return StepResult.Moved(field!!)
        }
        val end = context.navigator.settle()
        return end.field?.let { StepResult.Moved(it) } ?: StepResult.Stopped(end)
    }

    // endregion

    // region Outcomes

    internal fun Walk.toOutcome(context: PlanContext, done: (FieldState) -> String): ActionOutcome = when (this) {
        is Walk.Arrived -> ActionOutcome.Done(done(field))
        is Walk.NoRoute -> ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, detail,
            (failure as? RouteFailure.NeedsFieldMove)?.let { FieldMoveWalk.hint(it, access[it.move]) } ?: failure.hint(context.state().field)))
        is Walk.Stuck -> ActionOutcome.Failed(ActionError.Timeout(detail))
        is Walk.Failed -> ActionOutcome.Failed(error)
        is Walk.Interrupted -> ActionOutcome.Failed(ActionError.Interrupted(cause(state), "$steps step(s)" + notes.joinToString("") { "; $it" }))
    }

    /** Why there is no route, for the agent: what blocks and what to do about it. */
    internal fun RouteFailure.hint(field: FieldState?): String? {
        // A mechanism of the map the routes don't model may be what closes the way: say so rather than "walls".
        val unmodeled = field?.puzzle?.unmodeled
        if (unmodeled != null && (this == RouteFailure.Unreachable || this == RouteFailure.DifferentLevel)) {
            return "no route found, but this map has a mechanism the route planner doesn't model ($unmodeled): the way " +
                "probably goes through it. Work it out from field.puzzle and the map, and move step by step"
        }
        return plainHint(field)
    }

    private fun RouteFailure.plainHint(field: FieldState?): String? = when (this) {
        RouteFailure.OnlyOneWay -> "only by jumping down ledges (one way, no way back): retry with accept_one_way if that's fine"
        RouteFailure.DifferentLevel -> "it's on another height level (a walkway, a platform, a bridge above or below): " +
            "find the stairs, ladder or lift that leads there"
        RouteFailure.StartUnknown, RouteFailure.TargetUnknown -> null
        RouteFailure.Unreachable -> "not connected to where you stand by walking on this map (walls, heights), nor through its warps and holes"
        is RouteFailure.BlockedByPerson -> {
            val person = field?.objects?.firstOrNull { it.x == x && it.y == y }
            when {
                person == null -> "someone stands in the only way at $x,$y (on another floor or map): they may move, or step aside once spoken to"
                person.kind == FieldObjectKind.ITEM_BALL -> "an item ball lies in the only way at $x,$y: pick it up (interact ${objectTargetId(person)})"
                person.role == PersonRole.SHUTTER -> "a closed shutter (${person.id}) blocks the only way at $x,$y: flip the switches of this map's puzzle to open it"
                person.role == PersonRole.GATE -> "a closed door (${person.id}) blocks the only way at $x,$y: it opens after an event (a key, a battle...)"
                person.role == PersonRole.BLOCKER -> "${person.id} (${person.label}) blocks the only way at $x,$y until a story event moves them: " +
                    "follow the story (talk to them to learn what they wait for)"
                else -> "${person.id} (${person.label}) stands in the only way at $x,$y: talk to them (interact ${person.id}); " +
                    "some step aside once spoken to, others move by themselves; or leave the map and come back " +
                    "(people walking around go back to their place)"
            }
        }
        is RouteFailure.BlockedByBarrier -> {
            val barrier = field?.puzzle?.barriers?.firstOrNull { b -> b.tiles.any { it.x == x && it.y == y } }
            "a closed shutter${barrier?.let { " (${it.id})" } ?: ""} blocks the only way at $x,$y: flip the switches of this map's puzzle to open it"
        }
        is RouteFailure.NeedsFieldMove -> FieldMoveWalk.hint(this, null)
    }

    private fun cause(state: GameState): InterruptionCause = when {
        state.battle != null -> if (state.battle.trainers.isEmpty()) InterruptionCause.WILD_BATTLE else InterruptionCause.TRAINER_SIGHT
        state.field?.trainerEncounter == true -> InterruptionCause.TRAINER_SIGHT
        (state.screen as? Screen.Dialogue)?.source == TextSource.PHONE -> InterruptionCause.PHONE_CALL
        state.screen is Screen.Unknown -> InterruptionCause.UNKNOWN_SCREEN
        else -> InterruptionCause.SCRIPT
    }

    private fun battleOrStop(context: PlanContext): ActionOutcome {
        val state = context.navigator.settle()
        return if (state.battle != null) ActionOutcome.Done("wild battle")
        else ActionOutcome.Failed(ActionError.Interrupted(cause(state), "walking in the grass"))
    }

    private fun notInField(context: PlanContext) =
        ActionOutcome.Failed(ActionError.UnexpectedScreen("the overworld", context.state().screen.kind))

    private fun noMap(field: FieldState) =
        ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "no map data for ${field.mapName}"))

    // endregion

    /** Direction from (fx, fy) towards (tx, ty) when they are in line, else null. */
    private fun directionTo(fx: Int, fy: Int, tx: Int, ty: Int): Direction? = when {
        fx == tx && ty < fy -> Direction.NORTH
        fx == tx && ty > fy -> Direction.SOUTH
        fy == ty && tx < fx -> Direction.WEST
        fy == ty && tx > fx -> Direction.EAST
        else -> null
    }

    private fun Screen.Overworld.sameAsOverworld(other: Screen) = other is Screen.Overworld && other.banner == banner

    private val Direction.button
        get() = when (this) {
            Direction.NORTH -> Button.UP
            Direction.SOUTH -> Button.DOWN
            Direction.WEST -> Button.LEFT
            Direction.EAST -> Button.RIGHT
        }

    /** The player's height in RAM is the BDHC height of [dev.kotlinds.pokemonclient.world.TileInfo.heights] / 8. */
    internal const val HEIGHT_UNITS = 8
    private const val STEP_FRAMES = 24
    /** Jumps and doors (the door opens first) take longer than a step. */
    private const val LONG_STEP_FRAMES = 64
    private const val SETTLE_STEP_FRAMES = 64
    private const val STILL_FRAMES = 6

    /** After a slide, still for longer than a spinner's spin on each arrow (7 frames, overlay 1 ov01_021F31CC). */
    private const val SLIDE_STILL_FRAMES = 16

    /** Extra wait per tile of a slide (a tile takes about 8 frames on ice, 8 plus the 7-frame spin on arrows). */
    private const val SLIDE_TILE_FRAMES = 24
    private const val TURN_FRAMES = 4

    /** A silent switch's script (sound, shutters moving) runs a little while after A. */
    private const val SWITCH_FRAMES = 40
    private const val MAX_REPLANS = 6

    /** Longest wait for a teleport to end (a cart ride crosses the Azalea Gym in a few seconds). */
    private const val TELEPORT_FRAMES = 1800

    /** Polls (2 frames each) standing still after a teleport before walking on. */
    private const val TELEPORT_STILL_POLLS = 4

    /** Polls standing still on the source tile after which the teleport is considered not to have fired. */
    private const val TELEPORT_IDLE_POLLS = 45
    private const val MAX_PACING_STEPS = 200
    private const val GRASS = "tall grass"

    /** Longest wait for a warp's transition (climb, fade, fall) to change the map. */
    private const val WARP_CHANGE_FRAMES = 240

    /** After stepping on a door or a hole, how long its transition may take to start. */
    private const val WARP_START_FRAMES = 90

    /** The target id of the nearest PC (`interact(pc)`). */
    const val PC = "pc"
    private const val INTERACT_TRIES = 3
    private const val PC_SEARCH = 24

    /** True when moving actions can start: walking (or surfing) freely, with the maps known. */
    fun canWalk(state: GameState, hasWorld: Boolean): Boolean =
        hasWorld && state.battle == null && state.field != null && state.screen is Screen.Overworld && state.screen.awaiting == Awaiting.INPUT
}
