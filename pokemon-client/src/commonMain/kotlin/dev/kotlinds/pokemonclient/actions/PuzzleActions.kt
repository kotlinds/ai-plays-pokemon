package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.PushEdge
import dev.kotlinds.pokemonclient.world.PushPlanner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The movement-puzzle actions the agent names explicitly: `push` (a Strength boulder into its own hole, Ice Path B1F).
 *
 * They are the agent's own act (it chose which boulder and that it goes into its hole), so they run whatever
 * [ActionSettings.solvePuzzles] says: that setting only stops the walks from operating mechanisms by themselves.
 * Listed with the common actions ([CommonActions.definitions]).
 */
object PuzzleActions {

    private val assisted = setOf(ActionMode.ASSISTED)

    val push = ActionDefinition(GameAction.Push::class, object : ActionSpec<GameAction.Push> {
        override val name = "push"
        override val description = "Push a Strength boulder into its own hole (puzzle.boulder_holes, Ice Path B1F: it drops to the " +
            "floor below, where it stops slides on the ice). Plans the pushes (only this boulder moves), walks to each push, uses " +
            "Strength (a Pokémon must know it, with the badge) and pushes until it falls. Works even when go_to leaves puzzles to you."
        override val parameters = listOf(Parameter("boulder", ParameterType.STRING, "The boulder: person:N (see puzzle.boulder_holes)."))
        override val modes = assisted
        override fun availability(state: GameState): Availability {
            if (!MovePlans.canWalk(state, hasWorld = true)) return Availability.Hidden
            val holes = state.field?.puzzle?.boulderHoles.orEmpty().filter { !it.fallen }
            if (holes.isEmpty()) return Availability.Hidden
            return Availability.Available(mapOf("boulder" to holes.map { Choice(it.boulder, "boulder for the hole at ${it.hole.x},${it.hole.y}") }))
        }
        override fun parse(json: JsonObject) = GameAction.Push(
            json["boulder"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw ActionException(ActionError.InvalidParameter("boulder", "missing")),
        )
    }, ActionPlan { action, context -> PushPlans.push(action, context) })

    val definitions: List<ActionDefinition<*>> get() = listOf(push)
}

/** The recipe of `push`: plan with [PushPlanner.pushInto], then walk to each push and push, re-planning after each one. */
internal object PushPlans {

    fun push(action: GameAction.Push, context: PlanContext): ActionOutcome {
        val done = mutableListOf<String>()
        var strengthUsed = false
        repeat(MAX_PUSHES) {
            val state = context.navigator.settle()
            val field = state.field
            if (field == null || state.screen !is Screen.Overworld) return interrupted(state, done)
            val hole = field.puzzle?.boulderHoles?.firstOrNull { it.boulder == action.boulder }
                ?: return ActionOutcome.Failed(ActionError.InvalidParameter("boulder", action.boulder, field.puzzle?.boulderHoles.orEmpty().filter { !it.fallen }.map { it.boulder }))
            if (hole.fallen) {
                return ActionOutcome.Done(
                    (if (done.isEmpty()) "${action.boulder} has already fallen through its hole" else "${action.boulder} fell through its hole at ${hole.hole.x},${hole.hole.y} to the floor below") +
                        done.joinToString("") { "; $it" },
                )
            }
            val boulder = field.objects.firstOrNull { it.id == action.boulder }
                ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "${action.boulder} isn't on this floor"))
            val area = context.game.world?.areaOf(field.mapId)
                ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "no map data for ${field.mapName}"))
            val access = FieldMoveWalk.access(context, state)
            strengthMissing(access[FieldMoveKind.STRENGTH])?.let { return ActionOutcome.Failed(it) }
            val options = MovePlans.routeOptions(field, MoveOptions(), FieldMoveWalk.usable(access))
            // The puzzle's live state (shutters, people) as walks see it, every mechanism allowed: this is the agent's act.
            val overlay = MovePlans.overlay(context, field, emptySet(), solve = true)
            val start = Node(field.x, field.y, Pathfinder(area, overlay).levelAt(field.x, field.y, field.height * MovePlans.HEIGHT_UNITS))
            val route = PushPlanner(area, overlay).pushInto(start, options, boulder.x to boulder.y)
                ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH,
                    "no way found to push ${action.boulder} (at ${boulder.x},${boulder.y}) into its hole at ${hole.hole.x},${hole.hole.y} from ${field.x},${field.y}" +
                        done.joinToString("") { "; $it" },
                    "something may block it (another boulder, a wall): leave the floor and come back to reset the boulders, or push by hand (step)"))
            val index = route.edges.indexOfFirst { it is PushEdge }
            val edge = route.edges[index] as PushEdge
            val standAt = if (index == 0) start else route.edges[index - 1].to
            if (field.x != standAt.x || field.y != standAt.y) {
                // Walk only: no other boulder moved on the way (the plan keeps them where they are).
                val walker = PlanContext(context.scope, context.game, context.navigator, context.settings.copy(solvePuzzles = false))
                val walked = MovePlans.walkTo(walker, MovePlans.Target("${standAt.x},${standAt.y}", standAt.x, standAt.y), MoveOptions())
                if (walked !is MovePlans.Walk.Arrived) return with(MovePlans) { walked.toOutcome(context) { "" } }.withDone(done)
                if (walked.field.x != standAt.x || walked.field.y != standAt.y || walked.field.mapId != field.mapId) return@repeat
            }
            if (!strengthUsed) {
                when (val used = FieldMoveWalk.activateStrength(context, edge.direction)) {
                    is FieldMoveWalk.Use.Done -> strengthUsed = true
                    is FieldMoveWalk.Use.Stopped -> return interrupted(used.state, done)
                    is FieldMoveWalk.Use.Failed -> return ActionOutcome.Failed(used.error).withDone(done)
                }
            }
            when (val pushed = FieldMoveWalk.push(context, edge, MoveOptions())) {
                is MovePlans.StepResult.Moved -> done += "pushed it ${edge.direction.name.lowercase()} from ${edge.objectFrom.first},${edge.objectFrom.second}"
                is MovePlans.StepResult.Stopped -> {
                    // The last push drops the boulder: the game says so ("The boulder fell down!"), read to its end.
                    if (pushed.state.battle != null || edge.objectTo.first != hole.hole.x || edge.objectTo.second != hole.hole.y) return interrupted(pushed.state, done)
                    done += "pushed it ${edge.direction.name.lowercase()} from ${edge.objectFrom.first},${edge.objectFrom.second}"
                    val read = context.navigator.advanceUntil(FALL_MESSAGE_PRESSES) { it.battle != null || it.screen is Screen.Overworld }
                    if (read !is Step.Done || read.value.screen !is Screen.Overworld) return interrupted(context.state(), done)
                }
                MovePlans.StepResult.Refused -> return ActionOutcome.Failed(ActionError.Timeout(
                    "the boulder at ${edge.objectFrom.first},${edge.objectFrom.second} didn't move when pushed ${edge.direction.name.lowercase()} from ${standAt.x},${standAt.y}",
                )).withDone(done)
            }
        }
        return ActionOutcome.Failed(ActionError.Timeout("${action.boulder} still hasn't fallen after $MAX_PUSHES pushes" + done.joinToString("") { "; $it" }))
    }

    /** The typed reason Strength can't be used, or null when it can. */
    private fun strengthMissing(access: FieldMoveAccess?): ActionError? = when (access) {
        is FieldMoveAccess.Usable -> null
        is FieldMoveAccess.NoBadge -> ActionError.Unavailable(UnavailableReason.NEEDS_BADGE, "Strength needs the ${access.badge} Badge")
        FieldMoveAccess.NoPokemon -> ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "No Pokémon of the party knows Strength")
        FieldMoveAccess.Unknown, null -> ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "Strength can't be used in this game")
    }

    private fun interrupted(state: GameState, done: List<String>) = ActionOutcome.Failed(ActionError.Interrupted(
        if (state.battle != null) InterruptionCause.WILD_BATTLE else InterruptionCause.SCRIPT,
        (if (done.isEmpty()) "no push" else done.joinToString("; ")) + " (now: ${state.screen.kind})",
    ))

    private fun ActionOutcome.withDone(done: List<String>): ActionOutcome =
        if (done.isEmpty() || this !is ActionOutcome.Failed || error !is ActionError.Interrupted) this
        else ActionOutcome.Failed(error.copy(performed = error.performed + done.joinToString("") { "; $it" }))

    /** Presses to read the message of the boulder's fall. */
    private const val FALL_MESSAGE_PRESSES = 4

    /** Pushes at most per `push` (the Ice Path boulders need a handful). */
    private const val MAX_PUSHES = 30
}
