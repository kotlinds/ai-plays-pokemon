package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Screen

/** Recipes of the raw controls and of the generic screen actions. */
internal object BasicPlans {

    /** One self-checking tap, then wait for the game to react. */
    val press = ActionPlan<GameAction.Press> { action, context ->
        val before = context.state().screen
        val tap = context.scope.tap(action.button)
        context.navigator.awaitChange(before, maxFrames = PRESS_CHANGE_FRAMES)
        context.navigator.settle(maxFrames = REACTION_FRAMES)
        if (tap.registered) ActionOutcome.Done() else ActionOutcome.Done("the game didn't read the button (it may be busy)")
    }

    val touch = ActionPlan<GameAction.Touch> { action, context ->
        context.scope.touch(action.point)
        context.navigator.settle(maxFrames = REACTION_FRAMES)
        ActionOutcome.Done()
    }

    /** Runs [GameAction.Wait.frames] frames, or until the game expects input again. */
    val wait = ActionPlan<GameAction.Wait> { action, context ->
        val frames = action.frames
        if (frames != null) context.scope.step(frames) else context.navigator.settle(maxFrames = MAX_WAIT_FRAMES)
        ActionOutcome.Done()
    }

    /** Presses A through messages, stopping at the first choice, menu or back in control. */
    val advanceDialogue = ActionPlan<GameAction.AdvanceDialogue> { _, context ->
        context.navigator.advanceUntil { state ->
            val screen = state.screen
            screen !is Screen.Dialogue && screen !is Screen.PressToContinue && screen !is Screen.Battle && screen !is Screen.Animation ||
                screen is Screen.Battle && screen.awaiting == Awaiting.INPUT
        }.then { ActionOutcome.Done() }
    }

    /** Selects and confirms an entry of the menu on screen, by its stable id. */
    val choose = ActionPlan<GameAction.Choose> { action, context ->
        val screen = context.state().screen as? Screen.Selectable
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "No menu is open"))
        context.navigator.choose(Screen.Selectable::class, action.entry) { it.id == action.entry }.then { ActionOutcome.Done() }
    }

    /** Flees: RUN on the battle command menu. */
    val run = ActionPlan<GameAction.Run> { _, context ->
        context.navigator.choose(Screen.BattleCommand::class, "RUN") { it.id == "option:run" }
            .then { ActionOutcome.Done() }
    }

    /** "Switch Pokémon?" → no: B picks the bottom option (KEEP BATTLING) once the cursor is shown. */
    val keepBattling = ActionPlan<GameAction.KeepBattling> { _, context ->
        context.navigator.choose(Screen.ListMenu::class, "KEEP BATTLING") { it.id == "option:keep" }
            .then { ActionOutcome.Done() }
    }

    /** FIGHT, then the move (checked by id / name), then the target in doubles. */
    val attack = ActionPlan<GameAction.Attack> { action, context ->
        context.navigator.choose(Screen.BattleCommand::class, "FIGHT") { it.id == "option:fight" }.then {
            val moves = context.state().screen as? Screen.MoveSelect
                ?: return@then ActionOutcome.Failed(ActionError.UnexpectedScreen("the move list", context.state().screen.toString()))
            val wanted = moves.entries.firstOrNull { entry ->
                val id = entry.id.removePrefix("move:").toIntOrNull() ?: return@firstOrNull false
                matchesRef(action.move.raw, "move", id, entry.label.substringBefore(" ("))
            } ?: return@then ActionOutcome.Failed(
                ActionError.InvalidParameter("move", action.move.raw, moves.entries.filter { it.id.startsWith("move:") }.map { it.label.substringBefore(" (") }),
            )
            context.navigator.choose(Screen.MoveSelect::class, wanted.label) { it.id == wanted.id }.then { after ->
                val targets = after.screen as? Screen.TargetSelect
                if (targets == null) {
                    ActionOutcome.Done()
                } else {
                    val target = action.target?.wire
                        ?: return@then ActionOutcome.Failed(ActionError.InvalidParameter("target", "none", targets.entries.map { it.id }))
                    context.navigator.choose(Screen.TargetSelect::class, target) { it.id == target }.then { ActionOutcome.Done() }
                }
            }
        }
    }

    private const val REACTION_FRAMES = 300

    /** A press usually shows its effect within a few frames; opening an app takes up to a second. */
    private const val PRESS_CHANGE_FRAMES = 60
    private const val MAX_WAIT_FRAMES = 1800

    /** Buttons a press may use (all of them). */
    val buttons = Button.entries
}
