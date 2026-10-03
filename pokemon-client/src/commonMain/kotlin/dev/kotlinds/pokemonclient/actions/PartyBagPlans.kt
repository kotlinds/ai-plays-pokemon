package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen

/**
 * Recipes of the party and bag actions out of battle (start menu → POKéMON / BAG → ...). Every step goes through the
 * navigator (cursor read, one tap at a time, confirm only on the target), and entries are found by their ids.
 */
internal object PartyBagPlans {

    /** Moves [GameAction.ReorderParty.mon] to a position: party → mon → SWITCH → the mon at that position. */
    val reorderParty = ActionPlan<GameAction.ReorderParty> { action, context ->
        val state = context.state()
        val target = state.party.getOrNull(action.position - 1)
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.InvalidParameter("position", action.position.toString(), (1..state.party.size).map(Int::toString)))
        if (target.id == action.mon) return@ActionPlan ActionOutcome.Done("already at position ${action.position}")
        openParty(context).andThen {
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon to move") { it.id == action.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "SWITCH") { it.id == "option:switch" }
        }.andThen {
            context.navigator.choose(Screen.PartyGrid::class, "the position") { it.id == target.id.toString() }
        }.then {
            closeToOverworld(context)
            val moved = context.state().party.getOrNull(action.position - 1)?.id
            if (moved == action.mon) ActionOutcome.Done() else ActionOutcome.Failed(ActionError.Timeout("the party order didn't change as expected"))
        }
    }

    /** Takes the item held by a Pokémon: party → mon → ITEM → TAKE. */
    val takeItem = ActionPlan<GameAction.TakeItem> { action, context ->
        openParty(context).andThen {
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == action.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "ITEM") { it.id == "option:item" || it.id == "option:mail" }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "TAKE") { it.id == "option:take" }
        }.then {
            closeToOverworld(context)
            val held = context.state().party.firstOrNull { it.id == action.mon }?.heldItem
            if (held == null) ActionOutcome.Done() else ActionOutcome.Failed(ActionError.Timeout("${held.name} is still held"))
        }
    }

    /** Gives an item from the bag: bag → item → GIVE → the Pokémon (answers yes to swapping a held item). */
    val giveItem = ActionPlan<GameAction.GiveItem> { action, context ->
        bagItem(context, action.item).andThen { itemEntry ->
            context.navigator.choose(Screen.Bag::class, itemEntry.label) { it.id == itemEntry.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "GIVE") { it.id == "option:give" }
        }.andThen {
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == action.mon.toString() }
        }.andThen { after ->
            if (after.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES") { it.id == "option:yes" } else Step.Done(after)
        }.then {
            closeToOverworld(context)
            val held = context.state().party.firstOrNull { it.id == action.mon }?.heldItem
            if (held != null && matchesRef(action.item.raw, "item", held.id.value, held.name)) ActionOutcome.Done()
            else ActionOutcome.Failed(ActionError.Timeout("the Pokémon holds ${held?.name ?: "nothing"} instead of ${action.item.raw}"))
        }
    }

    /** Uses an item out of battle: bag → item → USE → the Pokémon when it asks for one. */
    val useItem = ActionPlan<GameAction.UseItem> { action, context ->
        val countBefore = quantity(context.state(), action.item)
        bagItem(context, action.item).andThen { itemEntry ->
            context.navigator.choose(Screen.Bag::class, itemEntry.label) { it.id == itemEntry.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen { after ->
            val grid = after.screen as? Screen.PartyGrid
            if (grid != null) {
                val mon = action.target
                    ?: return@andThen Step.Failed(ActionError.InvalidParameter("target", "none", grid.entries.filter { it.id.startsWith("mon:") }.map { it.id }))
                context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == mon.toString() }
            } else Step.Done(after)
        }.then {
            closeToOverworld(context)
            // Consumable items leave the bag when they work; "It won't have any effect" keeps them (checked on the
            // quantity, never on the message's words).
            val countAfter = quantity(context.state(), action.item)
            when {
                countAfter < countBefore -> ActionOutcome.Done("${countBefore - countAfter} used, $countAfter left")
                else -> ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_EFFECT, "${action.item.raw} had no effect", "the item stays in the bag"))
            }
        }
    }

    /** How many of [item] the bag holds. */
    private fun quantity(state: GameState, item: ItemRef): Int = state.bag.orEmpty().flatMap { it.items }
        .filter { matchesRef(item.raw, "item", it.item.id.value, it.item.name) }.sumOf { it.quantity }

    /**
     * Teaches a TM / HM from the bag: bag → the machine → USE → "Teach X?" YES → the Pokémon; when it already knows
     * four moves, answers "forget a move?" and forgets [GameAction.Teach.forget] (refused when null). The result is
     * checked on the Pokémon's moves.
     */
    val teach = ActionPlan<GameAction.Teach> { action, context ->
        val mon = context.state().party.firstOrNull { it.id == action.mon }
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_POKEMON, "${action.mon} isn't in the party"))
        val result = bagItem(context, action.item).andThen { machine ->
            context.navigator.choose(Screen.Bag::class, machine.label) { it.id == machine.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen {
            context.navigator.advanceUntil(TEACH_WAITS) { it.screen is Screen.YesNo || it.screen is Screen.PartyGrid }
        }.andThen { state ->
            if (state.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (teach)") { it.id == "option:yes" } else Step.Done(state)
        }.andThen {
            context.navigator.advanceUntil(TEACH_WAITS) { it.screen is Screen.PartyGrid }
        }.andThen {
            context.navigator.choose(Screen.PartyGrid::class, mon.displayName) { it.id == action.mon.toString() }
        }.andThen {
            // Four moves already: "Should a move be forgotten?" then the summary list.
            context.navigator.advanceUntil(TEACH_WAITS) { s -> s.screen is Screen.YesNo || s.screen is Screen.MoveSelect || s.screen is Screen.Bag || s.screen is Screen.PartyGrid || s.screen is Screen.Overworld }
        }.andThen { state ->
            if (state.screen !is Screen.YesNo && state.screen !is Screen.MoveSelect) return@andThen Step.Done(state)
            val forget = action.forget ?: return@andThen Step.Failed(ActionError.InvalidParameter("forget", "none", mon.moves.map { it.move.name }))
            if (state.screen is Screen.YesNo) {
                val yes = context.navigator.choose(Screen.YesNo::class, "YES (forget a move)") { it.id == "option:yes" }
                if (yes is Step.Failed) return@andThen yes
            }
            when (val forgot = BattlePlans.learnMove.run(GameAction.LearnMove(forget), context)) {
                is ActionOutcome.Failed -> Step.Failed(forgot.error)
                is ActionOutcome.Done -> Step.Done(context.state())
            }
        }
        closeToOverworld(context, maxPresses = 12)
        result.then {
            val machineMove = context.state().party.firstOrNull { it.id == action.mon }?.moves.orEmpty()
            if (machineMove.size != mon.moves.size || machineMove.map { it.move.id } != mon.moves.map { it.move.id }) ActionOutcome.Done("${mon.displayName} learned it")
            else ActionOutcome.Failed(ActionError.Timeout("${mon.displayName}'s moves didn't change"))
        }
    }

    /** Uses a key item (Bicycle, Itemfinder, a rod...): see [activateKeyItem]. */
    val useKeyItem = ActionPlan<GameAction.UseKeyItem> { action, context ->
        activateKeyItem(context, action.item).then { how ->
            val state = context.navigator.settle()
            ActionOutcome.Done("used $how, now: ${state.screen.kind}" + (state.field?.let { ", ${it.movement.name.lowercase()}" } ?: ""))
        }
    }

    /**
     * Starts using a key item and returns as soon as the game reacts (without waiting for what follows: a rod's
     * cast must be watched frame by frame). With Y when it's the item registered there, else bag → the item → USE.
     * The value says which way it went.
     */
    fun activateKeyItem(context: PlanContext, item: ItemRef): Step<String> {
        if (quickUse(context, item)) return Step.Done("with Y")
        return bagItem(context, item).andThen { entry ->
            context.navigator.choose(Screen.Bag::class, entry.label) { it.id == entry.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen { Step.Done("from the bag") }
    }

    /** Bag → the key item → REGISTER. The first free slot gets it: the first one is Y. */
    val registerItem = ActionPlan<GameAction.RegisterItem> { action, context ->
        val before = context.state().registeredItems
        bagItem(context, action.item).andThen { item ->
            context.navigator.choose(Screen.Bag::class, item.label) { it.id == item.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "REGISTER") { it.id == "option:register" }
        }.then {
            closeToOverworld(context)
            val after = context.state().registeredItems
            val slot = after.indexOfFirst { id -> id != null && matchesRef(action.item.raw, "item", id.value, context.state().itemName(id.value)) }
            when {
                slot == 0 -> ActionOutcome.Done("registered on Y")
                slot == 1 -> ActionOutcome.Done("registered on the second touch button (Y keeps ${before.firstOrNull()?.let { context.state().itemName(it.value) }})")
                else -> ActionOutcome.Failed(ActionError.Timeout("the item isn't registered"))
            }
        }
    }

    /**
     * Uses [item] with Y when it's the first registered item and the player stands in the field. Returns false
     * (nothing pressed) otherwise.
     */
    fun quickUse(context: PlanContext, item: ItemRef): Boolean {
        val state = context.navigator.settle()
        val onY = state.registeredItems.firstOrNull() ?: return false
        if (!matchesRef(item.raw, "item", onY.value, state.itemName(onY.value))) return false
        if (state.screen !is Screen.Overworld || state.screen.awaiting != Awaiting.INPUT) return false
        context.scope.tap(Button.Y)
        context.navigator.awaitChange(state.screen)
        return true
    }

    private fun GameState.itemName(id: Int) = bag.orEmpty().flatMap { it.items }.firstOrNull { it.item.id.value == id }?.item?.name ?: ""

    private const val TEACH_WAITS = 40

    // region Routes

    /** Opens the start menu (X) from the overworld and picks [entryId], or does nothing if already there. */
    fun openStartMenuEntry(context: PlanContext, entryId: String): Step<GameState> {
        var state = context.navigator.settle()
        if (state.screen is Screen.Overworld) {
            context.scope.tap(Button.X)
            context.navigator.awaitChange(state.screen)
            state = context.navigator.settle()
        }
        if ((state.screen as? Screen.ListMenu)?.kind != MenuKind.START_MENU) {
            return Step.Failed(ActionError.UnexpectedScreen("the start menu", state.screen.toString()))
        }
        return context.navigator.choose(Screen.ListMenu::class, entryId) { it.id == entryId }
    }

    /** The field party grid (from the overworld, or already open). */
    fun openParty(context: PlanContext): Step<GameState> {
        val state = context.navigator.settle()
        if ((state.screen as? Screen.PartyGrid)?.purpose == PartyPurpose.FIELD) return Step.Done(state)
        return openStartMenuEntry(context, "option:pokemon")
    }

    /**
     * Opens the bag on the pocket holding [item] and turns pages until the item is on screen; returns its entry.
     */
    fun bagItem(context: PlanContext, item: ItemRef): Step<Entry> {
        val owned = context.state().bag.orEmpty().flatMap { pocket -> pocket.items.map { pocket.name to it } }
            .firstOrNull { (_, stack) -> matchesRef(item.raw, "item", stack.item.id.value, stack.item.name) }
            ?: return Step.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_ITEM, "There's no ${item.raw} in the bag"))
        val (pocket, stack) = owned
        val opened = (context.state().screen as? Screen.Bag)?.let { Step.Done(context.state()) } ?: openStartMenuEntry(context, "option:bag")
        if (opened is Step.Failed) return opened
        var bag = context.navigator.settle().screen as? Screen.Bag ?: return Step.Failed(ActionError.UnexpectedScreen("the bag", context.state().screen.toString()))
        if (bag.pocket != pocket) {
            val tab = "pocket:$pocket"
            when (val switched = context.navigator.choose(Screen.Bag::class, pocket) { it.id == tab }) {
                is Step.Failed -> return switched
                is Step.Done -> bag = switched.value.screen as? Screen.Bag ?: return Step.Failed(ActionError.UnexpectedScreen("the bag", switched.value.screen.toString()))
            }
        }
        val itemId = "item:${stack.item.id.value}"
        repeat(bag.pages.coerceAtLeast(1)) {
            bag.entries.firstOrNull { it.id == itemId }?.let { return Step.Done(it) }
            // Not on this page: turn the page with the (touch) arrow, then look again.
            val next = bag.entries.firstOrNull { it.id == "page:next" }?.touch ?: return@repeat
            val before = bag
            context.scope.touch(next)
            context.navigator.awaitChange(before)
            bag = context.navigator.settle().screen as? Screen.Bag ?: return Step.Failed(ActionError.UnexpectedScreen("the bag", context.state().screen.toString()))
        }
        return Step.Failed(ActionError.NotOnScreen(stack.item.name, "the $pocket pocket", bag.entries.map { it.label }))
    }

    /** Presses B until the player can walk again (at most a few times), reading every message on the way. */
    fun closeToOverworld(context: PlanContext, maxPresses: Int = MAX_CLOSE_PRESSES) {
        repeat(maxPresses) {
            val state = context.navigator.settle()
            when (state.screen) {
                is Screen.Overworld -> return
                is Screen.Dialogue, is Screen.PressToContinue -> context.scope.tap(Button.A)
                else -> context.scope.tap(Button.B)
            }
            context.navigator.awaitChange(state.screen, maxFrames = 60)
        }
    }

    // endregion

    private const val MAX_CLOSE_PRESSES = 8

    /** Field actions need the player free to act: walking around, or already in a field menu. */
    fun inField(state: GameState): Boolean = state.battle == null && when (val s = state.screen) {
        is Screen.Overworld -> s.awaiting == Awaiting.INPUT
        is Screen.ListMenu -> s.kind == MenuKind.START_MENU
        is Screen.PartyGrid, is Screen.Bag, is Screen.ContextMenu -> true
        else -> false
    }

    fun owns(state: GameState, mon: MonId) = state.party.any { it.id == mon }
}

/** Chains a navigation step into another one. */
internal inline fun <T, R> Step<T>.andThen(next: (T) -> Step<R>): Step<R> = when (this) {
    is Step.Done -> next(value)
    is Step.Failed -> this
}
