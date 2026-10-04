package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveEdge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoves
import dev.kotlinds.pokemonclient.world.PushEdge
import dev.kotlinds.pokemonclient.world.RouteFailure

/**
 * The field moves used by walks ([MovePlans.walkTo]), the way a player does it: face the water / waterfall /
 * obstacle, press A, answer YES (by id) to the game's question, wait for the move's animation and messages to end,
 * then check where the player is. Also the Strength pushes (activated once, then each push walked into the boulder).
 */
internal object FieldMoveWalk {

    /** How using a field move ended. */
    sealed interface Use {
        /** Done: the player stands on [field] (the edge's end, or elsewhere and the walk re-plans). */
        data class Done(val field: FieldState) : Use

        /** Something else took the screen (a battle, a phone call...). */
        data class Stopped(val state: GameState) : Use

        /** The game didn't do it (no question, another screen): the typed reason. */
        data class Failed(val error: ActionError) : Use
    }

    /** The field moves the party can use now, by the game's rules ([dev.kotlinds.pokemonclient.PokemonGame.fieldMoveRule]). */
    fun access(context: PlanContext, state: GameState): Map<FieldMoveKind, FieldMoveAccess> =
        FieldMoves.access(state) { context.game.fieldMoveRule(it) }

    /** The field moves of [access] a route may use by itself. */
    fun usable(access: Map<FieldMoveKind, FieldMoveAccess>): Set<FieldMoveKind> = FieldMoves.usable(access)

    /**
     * Uses [edge]'s field move: faces its direction, A, YES, waits until the player can walk again; for Cut and Rock
     * Smash, then steps onto the cleared tile.
     */
    fun use(context: PlanContext, edge: FieldMoveEdge, options: MoveOptions): Use {
        when (val asked = ask(context, edge.direction, edge.move)) {
            is Use.Done -> Unit
            else -> return asked
        }
        val after = waitUntilFree(context) ?: return Use.Stopped(context.state())
        if (after.screen !is Screen.Overworld) return Use.Stopped(after)
        val field = after.field ?: return Use.Stopped(after)
        if (edge.move == FieldMoveKind.SURF && field.movement != MovementMode.SURF) {
            return Use.Failed(ActionError.Timeout("answered YES to Surf at ${field.x},${field.y}, but the player isn't surfing"))
        }
        if (!edge.clearsObstacle) return Use.Done(field)
        // The obstacle is gone (or not: the step is then refused and the walk re-plans).
        return when (val step = MovePlans.stepOnce(context, edge.direction, edge.to, options)) {
            is MovePlans.StepResult.Moved -> Use.Done(step.field)
            is MovePlans.StepResult.Stopped -> Use.Stopped(step.state)
            MovePlans.StepResult.Refused -> Use.Failed(ActionError.Timeout("used ${edge.move.label()} but ${edge.to.x},${edge.to.y} is still blocked"))
        }
    }

    /**
     * Makes boulders movable on this map (once): faces the boulder ahead, A, and YES to "use Strength?" (when it's
     * already active, the game only says so: that message is read too).
     */
    fun activateStrength(context: PlanContext, direction: Direction): Use {
        when (val asked = ask(context, direction, FieldMoveKind.STRENGTH, questionOptional = true)) {
            is Use.Done -> Unit
            else -> return asked
        }
        val after = waitUntilFree(context) ?: return Use.Stopped(context.state())
        val field = after.field ?: return Use.Stopped(after)
        return if (after.screen is Screen.Overworld) Use.Done(field) else Use.Stopped(after)
    }

    /**
     * Walks into the boulder of [edge] (Strength active): the boulder moves one tile and the player onto its tile.
     * Checked on the player's position (the walk re-plans with the boulders' live positions otherwise).
     */
    fun push(context: PlanContext, edge: PushEdge, options: MoveOptions): MovePlans.StepResult =
        MovePlans.stepOnce(context, edge.direction, edge.to, options, long = true)

    /**
     * Faces [direction] (turning without stepping: the tile ahead is water or an obstacle), presses A, and answers
     * YES to the game's question about [move]. With [questionOptional], a message without a question (Strength
     * already active) is read to its end and counts as done.
     */
    private fun ask(context: PlanContext, direction: Direction, move: FieldMoveKind, questionOptional: Boolean = false): Use {
        repeat(MAX_TURN_TRIES) {
            val field = context.state().field ?: return Use.Stopped(context.state())
            if (field.facing == direction) return@repeat
            context.scope.tap(direction.button)
            context.scope.step(TURN_FRAMES)
        }
        val ready = context.navigator.settle()
        val field = ready.field ?: return Use.Stopped(ready)
        if (ready.screen !is Screen.Overworld) return Use.Stopped(ready)
        if (field.facing != direction) {
            return Use.Failed(ActionError.VerificationFailed("face ${direction.name.lowercase()} to use ${move.label()}", direction.name.lowercase(), field.facing?.name?.lowercase() ?: "unknown", MAX_TURN_TRIES))
        }
        val before = ready.screen
        context.scope.tap(Button.A)
        context.navigator.awaitChange(before)
        val question = context.navigator.advanceUntil(QUESTION_PRESSES) { it.screen is Screen.YesNo || it.screen is Screen.Overworld || it.battle != null }
        val asked = when (question) {
            is Step.Failed -> return Use.Failed(question.error)
            is Step.Done -> question.value
        }
        if (asked.battle != null) return Use.Stopped(asked)
        if (asked.screen is Screen.Overworld) {
            return if (questionOptional) Use.Done(asked.field ?: return Use.Stopped(asked))
            else Use.Failed(ActionError.Timeout("pressed A facing ${direction.name.lowercase()} at ${field.x},${field.y}, but the game didn't offer ${move.label()}"))
        }
        return when (val yes = context.navigator.choose(Screen.YesNo::class, "YES (${move.label()})") { it.id == "option:yes" }) {
            is Step.Failed -> Use.Failed(yes.error)
            is Step.Done -> Use.Done(field)
        }
    }

    /**
     * After YES: reads the move's messages ("X used Surf!") and waits for its animation, until the player can walk
     * again. Null when it doesn't end in time.
     */
    private fun waitUntilFree(context: PlanContext): GameState? {
        val done = context.navigator.advanceUntil(ANIMATION_PRESSES) { state ->
            state.battle != null || (state.screen is Screen.Overworld && state.field?.moving == false)
        }
        return (done as? Step.Done)?.value
    }

    /**
     * What to tell the agent when a route needs a field move the party can't use: where to use it from (the land
     * tile and the direction for Surf), and what is missing (the move, the badge).
     */
    fun hint(failure: RouteFailure.NeedsFieldMove, access: FieldMoveAccess?): String {
        val what = when (failure.move) {
            FieldMoveKind.STRENGTH -> "a boulder blocks the way at ${failure.x},${failure.y}"
            FieldMoveKind.CUT -> "a small tree blocks the way at ${failure.x},${failure.y}"
            FieldMoveKind.ROCK_SMASH -> "a cracked rock blocks the way at ${failure.x},${failure.y}"
            FieldMoveKind.SURF -> "the way goes over water at ${failure.x},${failure.y}"
            FieldMoveKind.WHIRLPOOL -> "a whirlpool blocks the way at ${failure.x},${failure.y}"
            FieldMoveKind.WATERFALL -> "a waterfall is on the way at ${failure.x},${failure.y}"
            FieldMoveKind.ROCK_CLIMB -> "a rocky wall is on the way at ${failure.x},${failure.y}"
        }
        val from = failure.from
        val where = if (from != null && failure.facing != null) " (use it from ${from.x},${from.y} facing ${failure.facing.name.lowercase()})" else ""
        val missing = when (access) {
            FieldMoveAccess.NoPokemon -> "no Pokémon of the party knows ${failure.move.label()}"
            is FieldMoveAccess.NoBadge -> "${failure.move.label()} needs the ${access.badge} Badge"
            // Usable, but the route still can't use it here (Strength puzzle beyond the search, a whirlpool that
            // isn't crossed in line...): say how to do it by hand.
            is FieldMoveAccess.Usable -> "use ${failure.move.label()} there by hand (${access.monName} knows it)"
            FieldMoveAccess.Unknown, null -> "it needs ${failure.move.label()}"
        }
        return "$what$where: $missing"
    }

    /** The move's name as agents read it. */
    fun FieldMoveKind.label(): String = when (this) {
        FieldMoveKind.SURF -> "Surf"
        FieldMoveKind.CUT -> "Cut"
        FieldMoveKind.ROCK_SMASH -> "Rock Smash"
        FieldMoveKind.STRENGTH -> "Strength"
        FieldMoveKind.WHIRLPOOL -> "Whirlpool"
        FieldMoveKind.WATERFALL -> "Waterfall"
        FieldMoveKind.ROCK_CLIMB -> "Rock Climb"
    }

    private val Direction.button
        get() = when (this) {
            Direction.NORTH -> Button.UP
            Direction.SOUTH -> Button.DOWN
            Direction.WEST -> Button.LEFT
            Direction.EAST -> Button.RIGHT
        }

    private const val TURN_FRAMES = 8
    private const val MAX_TURN_TRIES = 3

    /** Messages before the question ("The water is dyed a deep blue..."): a few pages at most. */
    private const val QUESTION_PRESSES = 6

    /** The move's messages and animation (a waterfall climb lasts a few seconds). */
    private const val ANIMATION_PRESSES = 30
}
