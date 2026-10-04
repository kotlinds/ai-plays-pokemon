package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PartyPurpose
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

    /** Presses A through messages, stopping at the first choice, menu or back in control (on a choice already: nothing). */
    val advanceDialogue = ActionPlan<GameAction.AdvanceDialogue> { _, context ->
        if (context.state().screen is Screen.Selectable) return@ActionPlan ActionOutcome.Done("already on a choice: nothing to read")
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
        context.navigator.choose(Screen.Selectable::class, action.entry) { it.id == action.entry }.then { after ->
            // Battle party grid (switch, replacement after a K.O.): picking a Pokémon only opens SHIFT / SUMMARY...;
            // sending it in is what the choice means, so SHIFT is confirmed too.
            val grid = screen as? Screen.PartyGrid
            val menu = after.screen as? Screen.ContextMenu
            if (grid != null && (grid.purpose == PartyPurpose.BATTLE_SWITCH || grid.purpose == PartyPurpose.BATTLE_REPLACE_FAINTED) &&
                action.entry.startsWith("mon:") && menu?.entries?.any { it.id == SHIFT } == true
            ) {
                context.navigator.choose(Screen.ContextMenu::class, "SHIFT") { it.id == SHIFT }.then { ActionOutcome.Done("sent in (SHIFT)") }
            } else {
                ActionOutcome.Done()
            }
        }
    }

    private const val SHIFT = "option:shift"

    /** Flees: RUN on the battle command menu. */
    val run = ActionPlan<GameAction.Run> { _, context ->
        context.navigator.choose(Screen.BattleCommand::class, "RUN") { it.id == "option:run" }
            .then { ActionOutcome.Done() }
    }

    /**
     * "Switch Pokémon?" → KEEP BATTLING. Called while the messages before the question still scroll (EXP, level
     * up), it first reads them up to the question; when no question comes (the foe sent its next Pokémon at once,
     * the battle ended) it stops there and says so.
     */
    val keepBattling = ActionPlan<GameAction.KeepBattling> { _, context ->
        fun switchPrompt(state: GameState) = (state.screen as? Screen.ListMenu)?.kind == MenuKind.BATTLE_SWITCH_OR_KEEP
        val reached = context.navigator.advanceUntil { state ->
            switchPrompt(state) || state.screen is Screen.Selectable || state.battle == null ||
                state.screen is Screen.Battle && state.screen.awaiting == Awaiting.INPUT
        }
        reached.then { state ->
            when {
                switchPrompt(state) -> context.navigator.choose(Screen.ListMenu::class, "KEEP BATTLING") { it.id == "option:keep" }
                    .then { ActionOutcome.Done() }
                state.battle == null -> ActionOutcome.Done("no switch question: the battle is over")
                state.screen is Screen.BattleCommand -> ActionOutcome.Done("no switch question: the foe sent its next Pokémon")
                else -> ActionOutcome.Failed(ActionError.UnexpectedScreen("the switch-or-keep question", state.screen.kind))
            }
        }
    }

    /**
     * FIGHT, then the move (checked by id / name), then the target in doubles. The move is checked against the active
     * Pokémon's moves BEFORE anything is pressed (a move it doesn't know, or without PP, leaves the menu untouched).
     * On the target screen, a move with a single possible choice (spread moves: "all targets") is confirmed without
     * asking for a target.
     */
    val attack = ActionPlan<GameAction.Attack> { action, context ->
        val start = context.state()
        val battle = start.battle
        val actor = battle?.battlers?.firstOrNull { it.ref == ((start.screen as? Screen.BattleCommand)?.actor ?: battle.actor ?: BattlerRef.PLAYER_LEFT) }
        if (actor != null && actor.moves.isNotEmpty()) {
            val known = actor.moves.firstOrNull { matchesRef(action.move.raw, "move", it.move.id.value, it.move.name) }
                ?: return@ActionPlan ActionOutcome.Failed(ActionError.InvalidParameter("move", action.move.raw, actor.moves.map { "move:${it.move.id.value} = ${it.move.name}" }))
            if (known.pp == 0) return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PP, "${known.move.name} has no PP left"))
        }
        context.navigator.choose(Screen.BattleCommand::class, "FIGHT") { it.id == "option:fight" }.then {
            val moves = context.state().screen as? Screen.MoveSelect
                ?: return@then ActionOutcome.Failed(ActionError.UnexpectedScreen("the move list", context.state().screen.toString()))
            val wanted = moves.entries.firstOrNull { entry ->
                val id = entry.id.removePrefix("move:").toIntOrNull() ?: return@firstOrNull false
                matchesRef(action.move.raw, "move", id, entry.label.substringBefore(" ("))
            } ?: return@then backOut(context, ActionError.InvalidParameter("move", action.move.raw, moves.entries.filter { it.id.startsWith("move:") }.map { it.label.substringBefore(" (") }))
            context.navigator.choose(Screen.MoveSelect::class, wanted.label) { it.id == wanted.id }.then { after ->
                val targets = after.screen as? Screen.TargetSelect
                if (targets == null) {
                    ActionOutcome.Done()
                } else {
                    val choices = targets.entries.filter { it.selectable && it.id != CANCEL }
                    val target = when {
                        // Spread moves: one "all targets" entry, whatever target was given.
                        choices.size == 1 && choices.single().id == ALL_TARGETS -> ALL_TARGETS
                        action.target != null -> action.target.wire
                        choices.size == 1 -> choices.single().id
                        else -> return@then backOut(context, ActionError.InvalidParameter("target", "none", choices.map { "${it.id} = ${it.label}" }))
                    }
                    when (val chosen = context.navigator.choose(Screen.TargetSelect::class, target) { it.id == target }) {
                        is Step.Done -> ActionOutcome.Done(if (target == ALL_TARGETS && action.target != null) "this move hits every target" else null)
                        is Step.Failed -> backOut(context, chosen.error)
                    }
                }
            }
        }
    }

    /** Leaves the move / target screens (B) back to the command menu, then fails with [error]. */
    private fun backOut(context: PlanContext, error: ActionError): ActionOutcome {
        repeat(3) {
            val screen = context.navigator.settle().screen
            if (screen !is Screen.MoveSelect && screen !is Screen.TargetSelect) return ActionOutcome.Failed(error)
            context.scope.tap(Button.B)
            context.navigator.awaitChange(screen, maxFrames = 60)
        }
        return ActionOutcome.Failed(error)
    }

    private const val CANCEL = "option:cancel"
    private const val ALL_TARGETS = "target:all"

    private const val REACTION_FRAMES = 300

    /** A press usually shows its effect within a few frames; opening an app takes up to a second. */
    private const val PRESS_CHANGE_FRAMES = 60
    private const val MAX_WAIT_FRAMES = 1800

    /** Buttons a press may use (all of them). */
    val buttons = Button.entries
}
