package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.state.GameState

/**
 * The recipe of one action: how it is carried out in terms of screens ("open the bag, select the pocket, select
 * the item, USE..."), written once for every game. It never presses blindly: every choice goes through the
 * [Navigator], which reads the cursor, moves one tap at a time and confirms only on the target.
 *
 * A game whose screens really differ provides its own recipe for that action ([PokemonGame]'s overrides).
 */
fun interface ActionPlan<A : GameAction> {
    fun run(action: A, context: PlanContext): ActionOutcome
}

/** What a recipe works with. */
class PlanContext(
    val scope: ActionScope,
    val game: PokemonGame,
    val navigator: Navigator = Navigator(scope, game),
    /** What the application allows the recipes to do by themselves ([ActionSettings]). */
    val settings: ActionSettings = ActionSettings(),
) {
    /** The current state (decoded from this frame). */
    fun state(): GameState = navigator.state()
}

/** The result of an action: done (with an optional detail), or a typed error; plus the state after it. */
sealed interface ActionOutcome {
    data class Done(val detail: String? = null) : ActionOutcome
    data class Failed(val error: ActionError) : ActionOutcome
}

/** Turns a navigation [Step] failure into an outcome, or runs [then] with its value. */
internal inline fun <T> Step<T>.then(then: (T) -> ActionOutcome): ActionOutcome = when (this) {
    is Step.Done -> then(value)
    is Step.Failed -> ActionOutcome.Failed(error)
}
