package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PcMode
import dev.kotlinds.pokemonclient.state.Screen

/**
 * Recipes of the Pokémon storage system: walk to the nearest PC, boot it, open the storage, then deposit or
 * withdraw one Pokémon (found by its id, never by name) and switch the PC off.
 *
 * The PC's two menus are multichoices whose entries are always in the same order, whatever the language: storage
 * first in the top menu (SOMEONE'S / BILL'S PC), then DEPOSIT, WITHDRAW, MOVE, MOVE ITEMS, SEE YA! in the storage
 * menu. Leaving needs only B: B picks the last entry (SEE YA!, SWITCH OFF) of these menus and confirms it.
 */
internal object PcPlans {

    val deposit = ActionPlan<GameAction.Deposit> { action, context ->
        val state = context.state()
        if (state.party.none { it.id == action.mon }) return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_POKEMON, "${action.mon} isn't in the party"))
        if (state.party.count { !it.isEgg && !it.fainted } <= 1 && state.party.first { it.id == action.mon }.let { !it.isEgg && !it.fainted }) {
            return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.LAST_POKEMON, "It's your last Pokémon able to battle"))
        }
        val result = openStorage(context, PcMode.DEPOSIT).andThen {
            context.navigator.choose(Screen.PcBox::class, "the Pokémon to deposit") { it.id == action.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "DEPOSIT") { it.id == "option:deposit" }
        }.andThen { picker ->
            // "Deposit in which box?": the first box with room (full boxes aren't selectable), then DEPOSIT POKéMON.
            val boxes = picker.screen as? Screen.ListMenu ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the box picker", picker.screen.toString()))
            val box = boxes.entries.firstOrNull { it.id.startsWith("box:") && it.selectable }
                ?: return@andThen Step.Failed(ActionError.Unavailable(UnavailableReason.PARTY_FULL, "Every box is full"))
            context.navigator.select(Screen.ListMenu::class, box.label) { it.id == box.id }.andThen {
                context.navigator.choose(Screen.ListMenu::class, "DEPOSIT POKéMON") { it.id == "option:deposit" }
            }
        }
        PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
        result.then {
            if (context.state().party.none { it.id == action.mon }) ActionOutcome.Done("deposited")
            else ActionOutcome.Failed(ActionError.Timeout("${action.mon} is still in the party"))
        }
    }

    val withdraw = ActionPlan<GameAction.Withdraw> { action, context ->
        if (context.state().party.size >= PARTY_SIZE) return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.PARTY_FULL, "The party is full"))
        val result = openStorage(context, PcMode.WITHDRAW).andThen { findInBoxes(context, action.mon.toString()) }.andThen {
            context.navigator.choose(Screen.PcBox::class, "the Pokémon to withdraw") { it.id == action.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "WITHDRAW") { it.id == "option:withdraw" }
        }
        PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
        result.then {
            if (context.state().party.any { it.id == action.mon }) ActionOutcome.Done("withdrawn")
            else ActionOutcome.Failed(ActionError.Timeout("${action.mon} didn't join the party"))
        }
    }

    /** Walks to the PC, boots it and opens the storage in [mode] (DEPOSIT or WITHDRAW). */
    private fun openStorage(context: PlanContext, mode: PcMode): Step<GameState> {
        when (val booted = MovePlans.interact.run(GameAction.Interact(MovePlans.PC), context)) {
            is ActionOutcome.Failed -> return Step.Failed(booted.error)
            is ActionOutcome.Done -> Unit
        }
        return pcMenu(context).andThen {
            context.navigator.choose(Screen.ListMenu::class, "the storage system") { it.id == "option:$TOP_STORAGE" }
        }.andThen {
            pcMenu(context)
        }.andThen {
            val entry = if (mode == PcMode.DEPOSIT) STORAGE_DEPOSIT else STORAGE_WITHDRAW
            context.navigator.choose(Screen.ListMenu::class, mode.name) { it.id == "option:$entry" }
        }.andThen {
            context.navigator.advanceUntil(PC_WAITS) { (it.screen as? Screen.PcBox)?.mode == mode }
        }
    }

    /** Reads messages until the next PC multichoice. */
    private fun pcMenu(context: PlanContext) =
        context.navigator.advanceUntil(PC_WAITS) { (it.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE }

    /** Shows each box in turn (touching the next-box arrow) until one holds [monId]. */
    private fun findInBoxes(context: PlanContext, monId: String): Step<GameState> {
        repeat(BOXES) {
            val state = context.navigator.settle()
            val box = state.screen as? Screen.PcBox ?: return Step.Failed(ActionError.UnexpectedScreen("a PC box", state.screen.toString()))
            if (box.entries.any { it.id == monId }) return Step.Done(state)
            val next = box.entries.firstOrNull { it.id == "option:next_box" }?.touch
                ?: return Step.Failed(ActionError.NotOnScreen("the next box arrow", "the PC box", box.entries.map { it.id }))
            context.scope.touch(next)
            context.navigator.awaitChange(box)
        }
        return Step.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_POKEMON, "$monId isn't in any box"))
    }

    private const val TOP_STORAGE = 0
    private const val STORAGE_DEPOSIT = 0
    private const val STORAGE_WITHDRAW = 1
    private const val PC_WAITS = 40
    private const val BOXES = 18
    private const val PARTY_SIZE = 6
    private const val CLOSE_PRESSES = 16
}
