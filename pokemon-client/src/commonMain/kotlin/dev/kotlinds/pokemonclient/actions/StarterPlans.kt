package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.StarterStage

/** The choice of a starter on the professor's machine ([Screen.StarterChoice]). */
internal object StarterPlans {

    /**
     * Turns the machine until the wanted ball is in front (LEFT / RIGHT, each turn checked on the ball in front),
     * looks at it (A), picks it (A: the professor asks to confirm) and confirms (A), then waits for the machine to
     * close. A confirmation asked for another ball is declined first (B). Every press is read back on the machine's
     * state; after a few presses that change nothing, an explicit error.
     */
    val chooseStarter = ActionPlan<GameAction.ChooseStarter> { action, context ->
        val first = context.navigator.settle().screen as? Screen.StarterChoice
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the starter machine", context.state().screen.kind))
        val target = first.starters.indexOfFirst { matchesRef(action.starter, "species", it.id.value, it.name) }
        if (target < 0) {
            return@ActionPlan ActionOutcome.Failed(ActionError.InvalidParameter("starter", action.starter, first.starters.map { "species:${it.id.value}" }))
        }
        val name = first.starters[target].name
        var unchanged = 0
        repeat(MAX_PRESSES) {
            val screen = context.navigator.settle(maxFrames = SETTLE_FRAMES).screen as? Screen.StarterChoice
                ?: return@ActionPlan ActionOutcome.Done("took $name")
            val button = when {
                screen.stage == StarterStage.CONFIRMING && screen.front == target -> Button.A
                screen.stage == StarterStage.CONFIRMING -> Button.B
                // RIGHT turns the machine 0 -> 2 -> 1, LEFT the other way (checked on the ball in front anyway).
                screen.front != target -> if ((target - screen.front + 3) % 3 == 1) Button.LEFT else Button.RIGHT
                else -> Button.A
            }
            context.scope.tap(button)
            context.navigator.awaitChange(screen, maxFrames = CHANGE_FRAMES)
            val after = context.state().screen
            if (after is Screen.StarterChoice && after.front == screen.front && after.stage == screen.stage) {
                if (++unchanged >= MAX_UNCHANGED) {
                    return@ActionPlan ActionOutcome.Failed(ActionError.VerificationFailed("the $name ball", "a change after $button", "${screen.starters[screen.front].name} (${screen.stage.name.lowercase()})", unchanged))
                }
            } else unchanged = 0
        }
        ActionOutcome.Failed(ActionError.Timeout("the machine is still open after $MAX_PRESSES presses"))
    }

    private const val MAX_PRESSES = 12
    private const val MAX_UNCHANGED = 3
    private const val SETTLE_FRAMES = 600
    private const val CHANGE_FRAMES = 120
}
