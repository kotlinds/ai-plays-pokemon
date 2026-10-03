package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.TeleportLink
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

    val goTo = ActionPlan<GameAction.GoTo> { action, context ->
        val target = resolve(context, action.target, action.x, action.y) ?: return@ActionPlan unknownTarget(context, action.target)
        walkTo(context, target, action.options).toOutcome(context) { "arrived at ${it.x},${it.y}" }
    }

    /** Walks next to the target, faces it and presses A: the conversation / sign / item that follows is the result. */
    val interact = ActionPlan<GameAction.Interact> { action, context ->
        val target = resolve(context, action.target, null, null) ?: return@ActionPlan unknownTarget(context, action.target)
        val walked = walkTo(context, target.copy(adjacent = true), MoveOptions())
        if (walked !is Walk.Arrived) return@ActionPlan walked.toOutcome(context) { "" }
        val field = walked.field
        val facing = directionTo(field.x, field.y, target.x ?: field.x, target.y ?: field.y)
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Timeout("ended next to ${action.target} but not in line with it"))
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
            ActionOutcome.Done("the puzzle changed: ${puzzleChange(puzzleBefore, puzzleAfter)}")
        } else if (after.screen is Screen.Overworld && after.screen.sameAsOverworld(before)) {
            ActionOutcome.Failed(ActionError.Timeout("nothing happened when pressing A on ${action.target}"))
        } else ActionOutcome.Done("now: ${after.screen.kind}")
    }

    /**
     * Walks as far as possible in a direction: to the reachable tile furthest that way (then the closest one among
     * those), which is often the map's edge, a connection to the next route or the foot of an obstacle.
     */
    val explore = ActionPlan<GameAction.Explore> { action, context ->
        val field = context.state().field ?: return@ActionPlan notInField(context)
        val area = context.game.world?.areaOf(field.mapId) ?: return@ActionPlan noMap(field)
        val pathfinder = Pathfinder(area, overlay(context, field, emptySet()))
        val start = Node(field.x, field.y, pathfinder.levelAt(field.x, field.y, field.height * HEIGHT_UNITS))
        val reach = pathfinder.reachable(start, routeOptions(field, action.options))
        val d = action.direction
        fun progress(node: Node) = node.x * d.dx + node.y * d.dy
        val best = reach.keys.maxByOrNull { progress(it) * PROGRESS_WEIGHT - reach.getValue(it) }
        if (best == null || progress(best) <= progress(start)) {
            return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "Can't go further ${d.name.lowercase()} from here"))
        }
        walkTo(context, Target("${d.name.lowercase()}", best.x, best.y), action.options).toOutcome(context) {
            "went ${progress(best) - progress(start)} tiles ${d.name.lowercase()}, now at ${it.x},${it.y}"
        }
    }

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
     * Where to go: a tile, or a person / warp / sign of the current map by id. With [adjacent] the walk stops next to
     * it (or across a counter), never on it.
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

    private fun resolve(context: PlanContext, target: String?, x: Int?, y: Int?): Target? {
        val state = context.state()
        val field = state.field
        val area = field?.let { context.game.world?.areaOf(it.mapId) }
        if (target == null) {
            if (x == null || y == null) return null
            // A door / stairs / exit tile: going there means going through.
            val warp = area?.warps?.firstOrNull { it.x == x && it.y == y }
            return Target("$x,$y", x, y, warp = warp != null, exit = warp?.exitDirection)
        }
        if (field == null || area == null) return null
        if (target == PC) return nearestPc(area, field)?.let { (px, py) -> Target(PC, px, py, adjacent = true) }
        val (kind, id) = target.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        val number = id.toIntOrNull()
        return when (kind) {
            "person" -> field.objects.firstOrNull { it.id == target }?.let { Target(target, it.x, it.y, adjacent = true) }
            "warp" -> area.warps.firstOrNull { it.zone == field.mapId && it.id == number }?.let { Target(target, it.x, it.y, warp = true, exit = it.exitDirection) }
            "sign" -> area.signs.firstOrNull { it.zone == field.mapId && it.id == number }?.let { Target(target, it.x, it.y, adjacent = true) }
            else -> null
        }
    }

    /** The closest PC tile around the player (Pokémon Centers have one; the player's room too). */
    private fun nearestPc(area: Area, field: FieldState): Pair<Int, Int>? =
        (-PC_SEARCH..PC_SEARCH).flatMap { dy -> (-PC_SEARCH..PC_SEARCH).map { dx -> field.x + dx to field.y + dy } }
            .filter { (x, y) -> area.tile(x, y)?.kind == TileKind.Pc }
            .minByOrNull { (x, y) -> kotlin.math.abs(x - field.x) + kotlin.math.abs(y - field.y) }

    private fun unknownTarget(context: PlanContext, target: String?): ActionOutcome {
        val field = context.state().field ?: return notInField(context)
        val known = buildList {
            field.objects.filter { it.kind != FieldObjectKind.FOLLOWER }.forEach { add(it.id) }
            context.game.world?.areaOf(field.mapId)?.let { area ->
                area.warps.filter { it.zone == field.mapId }.forEach { add("warp:${it.id}") }
                area.signs.filter { it.zone == field.mapId }.forEach { add("sign:${it.id}") }
            }
        }
        return ActionOutcome.Failed(ActionError.InvalidParameter("target", target ?: "none", known))
    }

    // endregion

    // region Walking

    /** How a walk ended. */
    sealed interface Walk {
        data class Arrived(val field: FieldState) : Walk
        data class Interrupted(val state: GameState, val steps: Int) : Walk
        data class NoRoute(val failure: RouteFailure, val detail: String) : Walk
        data class Stuck(val detail: String) : Walk
    }

    /** Computes a route to [target] and walks it, computing it again after each refused step (at most a few times). */
    fun walkTo(context: PlanContext, target: Target, options: MoveOptions): Walk {
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
            val route = when (val result = pathfinder.route(start, routeOptions(field, options), if (target.warp == true) goalTiles else emptySet(), isGoal)) {
                is Pathfinder.Result.Found -> result.route
                is Pathfinder.Result.Failed -> return Walk.NoRoute(result.failure, "no way to ${target.id} from ${field.x},${field.y}")
            }
            var from = start
            for (edge in route.edges) {
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
                val slide = (edge as? Edge.Slide)?.tiles?.size ?: 0
                when (val step = stepOnce(context, edge.direction, edge.to, options, long = edge is Edge.Jump || intoWarp, slide = slide)) {
                    is StepResult.Moved -> {
                        steps++
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
                    is StepResult.Stopped -> return Walk.Interrupted(step.state, steps)
                }
            }
            val end = context.navigator.settle()
            val endField = end.field ?: return Walk.Interrupted(end, steps)
            // Exit mats and stairs: standing on them isn't enough, the game waits for a press towards the exit.
            if (target.warp == true && endField.mapId == field.mapId && target.exit != null) {
                return when (val pushed = stepOnce(context, target.exit, Node(Int.MIN_VALUE, Int.MIN_VALUE), options)) {
                    is StepResult.Moved -> Walk.Arrived(pushed.field)
                    is StepResult.Stopped -> context.navigator.settle().field?.let { Walk.Arrived(it) } ?: Walk.Interrupted(pushed.state, steps)
                    StepResult.Refused -> Walk.Stuck("the warp didn't take the player anywhere")
                }
            }
            val endNode = Node(endField.x, endField.y, pathfinder.levelAt(endField.x, endField.y, endField.height * HEIGHT_UNITS))
            if (!isGoal(endNode) && target.warp != true) return@repeat
            return Walk.Arrived(endField)
        }
        return Walk.Stuck("the game refused ${refused.size} steps on the way to ${target.id}")
    }

    /** Tiles that end the walk: the target itself, or the tiles from which it can be reached with A. */
    private fun goalTiles(area: Area, target: Target): Set<Pair<Int, Int>> {
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

    private fun overlay(context: PlanContext, field: FieldState, refused: Set<Pair<Node, Direction>>): Overlay {
        val templates = context.game.world?.areaOf(field.mapId)?.people.orEmpty().filter { it.zone == field.mapId }
        return Overlay(
            objects = field.objects.map { o ->
                LiveObject(
                    o.x, o.y, o.facing,
                    isFollower = o.kind == FieldObjectKind.FOLLOWER,
                    sightRange = templates.firstOrNull { "person:${it.id}" == o.id }?.sightRange ?: 0,
                    clearedBy = when (o.obstacle) {
                        ObstacleKind.CUT_TREE -> FieldMoveKind.CUT
                        ObstacleKind.SMASH_ROCK -> FieldMoveKind.ROCK_SMASH
                        ObstacleKind.BOULDER -> FieldMoveKind.STRENGTH
                        null -> null
                    },
                )
            },
            refused = refused,
            activeTriggers = activeTriggers(context, field),
            blockedTiles = field.puzzle?.barriers.orEmpty().filterNot { it.open }.flatMap { b -> b.tiles.map { it.x to it.y } }.toSet(),
            teleports = field.puzzle?.teleports.orEmpty().flatMap { t -> t.from.map { TeleportLink(it.x, it.y, t.to.x, t.to.y) } },
        )
    }

    /**
     * Tiles that run a script when stepped on right now: map triggers whose variable has the value they wait for
     * (the Ecruteak Gym pits while the puzzle is unsolved, story events...). Avoided unless targeted.
     */
    private fun activeTriggers(context: PlanContext, field: FieldState): Set<Pair<Int, Int>> {
        val triggers = context.game.world?.areaOf(field.mapId)?.triggers.orEmpty().filter { it.zone == field.mapId }
        if (triggers.isEmpty()) return emptySet()
        val memory = context.scope.memory()
        return triggers.filter { context.game.scriptVariable(memory, it.variable) == it.value }
            .flatMap { t -> (t.x until t.x + maxOf(1, t.width)).flatMap { x -> (t.y until t.y + maxOf(1, t.height)).map { y -> x to y } } }
            .toSet()
    }

    private fun routeOptions(field: FieldState, options: MoveOptions) = RouteOptions(
        mode = field.movement,
        avoidTallGrass = options.avoidTallGrass,
        avoidTrainers = options.avoidTrainers,
        acceptOneWay = options.acceptOneWay,
    )

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
            val arrived = field != null && (field.mapId != start.mapId || field.x != edge.via.x || field.y != edge.via.y)
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

    private fun Walk.toOutcome(context: PlanContext, done: (FieldState) -> String): ActionOutcome = when (this) {
        is Walk.Arrived -> ActionOutcome.Done(done(field))
        is Walk.NoRoute -> ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, detail, failure.hint()))
        is Walk.Stuck -> ActionOutcome.Failed(ActionError.Timeout(detail))
        is Walk.Interrupted -> ActionOutcome.Failed(ActionError.Interrupted(cause(state), "$steps step(s)"))
    }

    private fun RouteFailure.hint(): String? = when (this) {
        RouteFailure.OnlyOneWay -> "only by jumping down ledges: retry with accept_one_way if that's fine"
        RouteFailure.DifferentLevel -> "it's on another floor or level: find the stairs"
        RouteFailure.StartUnknown, RouteFailure.TargetUnknown -> null
        RouteFailure.Unreachable -> "blocked from here (a person in the way, or a story event?)"
        is RouteFailure.NeedsFieldMove -> when (move) {
            FieldMoveKind.STRENGTH -> "a boulder blocks the way at $x,$y: push it with Strength (use the field move facing it)"
            FieldMoveKind.CUT -> "a small tree blocks the way at $x,$y: use Cut facing it"
            FieldMoveKind.ROCK_SMASH -> "a cracked rock blocks the way at $x,$y: use Rock Smash facing it"
            FieldMoveKind.SURF -> "the way goes over water at $x,$y: use Surf facing it"
            FieldMoveKind.WHIRLPOOL -> "a whirlpool blocks the way at $x,$y: use Whirlpool facing it"
            FieldMoveKind.WATERFALL -> "a waterfall is on the way at $x,$y: use Waterfall facing it"
            FieldMoveKind.ROCK_CLIMB -> "a rocky wall is on the way at $x,$y: use Rock Climb facing it"
        }
    }

    private fun cause(state: GameState): InterruptionCause = when {
        state.battle != null -> if (state.battle.trainers.isEmpty()) InterruptionCause.WILD_BATTLE else InterruptionCause.TRAINER_SIGHT
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
    private const val HEIGHT_UNITS = 8
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

    /** In [explore], one tile further counts more than any detour within the search radius. */
    private const val PROGRESS_WEIGHT = 1000

    /** The target id of the nearest PC (`interact(pc)`). */
    const val PC = "pc"
    private const val PC_SEARCH = 24

    /** True when moving actions can start: walking (or surfing) freely, with the maps known. */
    fun canWalk(state: GameState, hasWorld: Boolean): Boolean =
        hasWorld && state.battle == null && state.field != null && state.screen is Screen.Overworld && state.screen.awaiting == Awaiting.INPUT
}
