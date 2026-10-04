package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.StartMenuFeature
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MovementMode
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

    /**
     * Takes the item held by a Pokémon: party → mon → ITEM → TAKE. For Mail (MAIL → TAKE), the game then asks "Send the
     * removed Mail to your PC?": YES keeps the written message in the PC's mailbox (NO would erase it).
     */
    val takeItem = ActionPlan<GameAction.TakeItem> { action, context ->
        openParty(context).andThen {
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == action.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "ITEM") { it.id == "option:item" || it.id == "option:mail" }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "TAKE") { it.id == "option:take" }
        }.andThen { after ->
            if (after.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (send the Mail to the PC)") { it.id == "option:yes" } else Step.Done(after)
        }.then {
            closeToOverworld(context)
            val held = context.state().party.firstOrNull { it.id == action.mon }?.heldItem
            if (held == null) ActionOutcome.Done() else ActionOutcome.Failed(ActionError.Timeout("${held.name} is still held"))
        }
    }

    /**
     * Gives an item from the bag: bag → item → GIVE → the Pokémon (answers yes to swapping a held item). Mail is refused
     * before anything is pressed: giving it opens the mail editor, where the game wants a written message (an empty one
     * is refused) and that editor isn't decoded.
     */
    val giveItem = ActionPlan<GameAction.GiveItem> { action, context ->
        val mail = context.state().bag.orEmpty().firstOrNull { it.name == MAIL_POCKET }?.items.orEmpty()
            .firstOrNull { matchesRef(action.item.raw, "item", it.item.id.value, it.item.name) }
        if (mail != null) {
            return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.MAIL_NEEDS_WRITING,
                "Giving ${mail.item.name} opens the mail editor to write a message, which isn't supported", "give another item"))
        }
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

    /**
     * Uses one or several items ([GameAction.UseItem.uses]) in one bag session: bag → item → USE → the Pokémon when it
     * asks for one → the move for a PP restoring item ("Restore which move?"), then A through the effect's message (it
     * would otherwise close on its own after a few seconds) and on to the next item, the bag still open. Each use is
     * checked on the item's quantity (consumed = it worked; "It won't have any effect" keeps it). In battle, the
     * battle recipe ([BattleItemPlans.useItem]) is used instead.
     */
    val useItem = ActionPlan<GameAction.UseItem> { action, context ->
        if (context.state().battle != null) return@ActionPlan BattleItemPlans.useItem.run(action, context)
        val done = mutableListOf<String>()
        for ((index, use) in action.uses.withIndex()) {
            when (val outcome = useOneItem(use, context)) {
                is ActionOutcome.Done -> done += use.key + (outcome.detail?.let { ": $it" } ?: "")
                is ActionOutcome.Failed -> {
                    closeToOverworld(context)
                    val error = if (action.uses.size == 1) outcome.error else ActionError.BatchStepFailed(index, use.key, done, outcome.error)
                    return@ActionPlan ActionOutcome.Failed(error)
                }
            }
        }
        closeToOverworld(context)
        ActionOutcome.Done(done.joinToString("; "))
    }

    /** One field item use, from the overworld or from the bag left open by the previous use. */
    private fun useOneItem(use: ItemUse, context: PlanContext): ActionOutcome {
        val countBefore = quantity(context.state(), use.item)
        val reached = bagItem(context, use.item).andThen { itemEntry ->
            context.navigator.choose(Screen.Bag::class, itemEntry.label) { it.id == itemEntry.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen { after ->
            val grid = after.screen as? Screen.PartyGrid ?: return@andThen Step.Done(after)
            val mon = use.target
                ?: return@andThen Step.Failed(ActionError.InvalidParameter("target", "none", grid.entries.filter { it.id.startsWith("mon:") && it.selectable }.map { it.id }))
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == mon.toString() }
        }.andThen { after ->
            // Ether, PP Up...: "Restore which move?".
            if (!isMoveList(after.screen)) return@andThen Step.Done(after)
            chooseMove(context, after.screen as Screen.Selectable, use.move)
        }
        if (reached is Step.Failed) return ActionOutcome.Failed(reached.error)
        // The effect: A through its messages, until the bag (or the field) is back, or the party grid when the game
        // refused the item. Never a burst of blind presses: only messages are answered. A whole-party item (Sacred
        // Ash: one HP bar and one message per fainted Pokémon) takes longer, but never more than [EFFECT_MAX_FRAMES]:
        // the agent's call must answer in time.
        val effectStart = context.scope.frame
        effect@ for (press in 0 until EFFECT_PRESSES) {
            if (context.scope.frame - effectStart > EFFECT_MAX_FRAMES) {
                return ActionOutcome.Failed(ActionError.Timeout("${use.item.raw}'s effect still runs after ${EFFECT_MAX_FRAMES / 60} s: call get_state, then go on"))
            }
            val state = context.navigator.settle(maxFrames = EFFECT_SETTLE_FRAMES)
            when (val screen = state.screen) {
                is Screen.Dialogue, is Screen.PressToContinue -> {
                    context.scope.tap(Button.A)
                    context.navigator.awaitChange(screen, maxFrames = 60)
                }
                is Screen.Animation -> context.scope.step(4)
                else -> break@effect
            }
        }
        val countAfter = quantity(context.state(), use.item)
        return when {
            countAfter < countBefore -> ActionOutcome.Done("${countBefore - countAfter} used, $countAfter left")
            else -> ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_EFFECT, "${use.item.raw} had no effect", "the item stays in the bag"))
        }
    }

    /** "Restore which move?" lists: the field one (moves + QUIT) and the battle one (moves + CANCEL). */
    internal fun isMoveList(screen: Screen): Boolean =
        screen is Screen.ListMenu && screen.entries.any { it.id.startsWith("move:") } && screen.entries.all { it.id.startsWith("move:") || it.id.startsWith("option:") || it.id.startsWith("slot:") }

    /** Picks [move] on a "Restore which move?" list (typed error listing the moves when it's missing or unknown). */
    internal fun chooseMove(context: PlanContext, list: Screen.Selectable, move: MoveRef?): Step<GameState> {
        val moves = list.entries.filter { it.id.startsWith("move:") }
        val entry = move?.let { ref ->
            moves.firstOrNull { e -> matchesRef(ref.raw, "move", e.id.removePrefix("move:").toIntOrNull() ?: -1, e.label.substringBefore(" (")) }
        } ?: return Step.Failed(ActionError.InvalidParameter("move", move?.raw ?: "none", moves.map { "${it.id} = ${it.label}" }))
        return context.navigator.choose(list::class, entry.label) { it.id == entry.id }
    }

    /** Enough for Sacred Ash on six fainted Pokémon (an HP bar and a message each). */
    private const val EFFECT_PRESSES = 30

    /** About 20 s: with the session's settling, the agent's call stays well under its client's timeout. */
    private const val EFFECT_MAX_FRAMES = 1200
    private const val EFFECT_SETTLE_FRAMES = 240

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

    /**
     * Uses a key item (Bicycle, Itemfinder, a rod...): see [activateKeyItem]. The Bicycle is checked on the player's
     * movement: where the map forbids cycling (indoors...) or while surfing it's refused without pressing anything,
     * and when the game still says no ("There's a time and place for everything!", mud, tall grass...) the movement
     * hasn't changed: the message is closed and the refusal is a typed error, never Done.
     */
    val useKeyItem = ActionPlan<GameAction.UseKeyItem> { action, context ->
        val before = context.navigator.settle()
        val bicycle = isBicycle(context, before, action.item)
        val field = before.field
        if (bicycle && field != null && field.movement != MovementMode.BIKE) {
            val why = when {
                field.bikeAllowed == false -> "cycling isn't allowed on ${field.mapName}"
                field.movement == MovementMode.SURF -> "the player is surfing"
                else -> null
            }
            if (why != null) return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.CANNOT_USE_HERE, "${action.item.raw} can't be used here: $why", "walk, or ride outdoors"))
        }
        activateKeyItem(context, action.item).then { how ->
            var state = context.navigator.settle()
            if (bicycle && field != null) {
                // Mounting or dismounting changes the movement; the game's refusal is only a message.
                var polls = 0
                while (state.field?.movement == field.movement && state.screen !is Screen.Dialogue && polls++ < BIKE_WAITS) {
                    context.scope.step(BIKE_POLL_FRAMES)
                    state = context.navigator.settle()
                }
                if (state.field?.movement == field.movement) {
                    closeToOverworld(context)
                    return@then ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.CANNOT_USE_HERE, "the game refused ${action.item.raw} here (used $how)", "walk, or ride where cycling is allowed"))
                }
            }
            ActionOutcome.Done("used $how, now: ${state.screen.kind}" + (state.field?.let { ", ${it.movement.name.lowercase()}" } ?: ""))
        }
    }

    /** True when [item] is the game's bicycle ([dev.kotlinds.pokemonclient.PokemonGame.bicycleItem]). */
    private fun isBicycle(context: PlanContext, state: GameState, item: ItemRef): Boolean {
        val id = context.game.bicycleItem ?: return false
        val name = state.bag.orEmpty().flatMap { it.items }.firstOrNull { it.item.id.value == id }?.item?.name ?: ""
        return matchesRef(item.raw, "item", id, name)
    }

    /** Polls of [BIKE_POLL_FRAMES] frames to wait for the bicycle's effect (the mount, or the refusal message). */
    private const val BIKE_WAITS = 15
    private const val BIKE_POLL_FRAMES = 4

    /**
     * Starts using a key item and returns as soon as the game reacts (without waiting for what follows: a rod's
     * cast must be watched frame by frame). With Y when it's the item registered there, else bag → the item → USE.
     * The value says which way it went.
     */
    fun activateKeyItem(context: PlanContext, item: ItemRef): Step<String> {
        quickUse(context, item)?.let { return Step.Done(it) }
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
     * Uses [item] without the bag when it's registered and the player stands in the field: Y for the first registered
     * item, the game's touch button for the second one ([dev.kotlinds.pokemonclient.PokemonGame.registeredItemTouch]).
     * Returns how ("with Y"...), or null (nothing pressed) otherwise.
     */
    fun quickUse(context: PlanContext, item: ItemRef): String? {
        val state = context.navigator.settle()
        if (state.screen !is Screen.Overworld || state.screen.awaiting != Awaiting.INPUT) return null
        val slot = state.registeredItems.indexOfFirst { id -> id != null && matchesRef(item.raw, "item", id.value, state.itemName(id.value)) }
        val how = when (slot) {
            0 -> "with Y".also { context.scope.tap(Button.Y) }
            1 -> "with its touch button".also { context.scope.touch(context.game.registeredItemTouch(1) ?: return null) }
            else -> return null
        }
        context.navigator.awaitChange(state.screen)
        return how
    }

    private fun GameState.itemName(id: Int) = bag.orEmpty().flatMap { it.items }.firstOrNull { it.item.id.value == id }?.item?.name ?: ""

    private const val TEACH_WAITS = 40

    /** The bag pocket of the Mail items (pocket ids are the same in every language). */
    private const val MAIL_POCKET = "mail"

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
                // A question about learning a move (Rare Candy...) is the agent's to answer: B would give the move up.
                is Screen.YesNo, is Screen.MoveSelect -> if (state.battle == null && BattlePlans.isLearnPrompt(state)) return else context.scope.tap(Button.B)
                is Screen.Dialogue, is Screen.PressToContinue -> context.scope.tap(Button.A)
                // The Pokégear has no B: its Close button is touched.
                is Screen.Viewer -> state.screen.exit.touch?.let { context.scope.touch(it) } ?: context.scope.tap(state.screen.exit.button ?: Button.B)
                else -> context.scope.tap(Button.B)
            }
            context.navigator.awaitChange(state.screen, maxFrames = 60)
        }
    }

    // endregion

    private const val MAX_CLOSE_PRESSES = 8

    /**
     * Field actions need the player free to act: walking around, or already in a field menu (start menu, the party,
     * the bag and their menus). Not in the PC's menus: their actions (DEPOSIT, MARKING...) aren't the party's.
     */
    fun inField(state: GameState): Boolean = state.battle == null && when (val s = state.screen) {
        // X opens nothing before the bag is given (the start of a new game).
        is Screen.Overworld -> s.awaiting == Awaiting.INPUT && state.startMenu?.contains(StartMenuFeature.BAG) != false
        is Screen.ListMenu -> s.kind == MenuKind.START_MENU
        is Screen.PartyGrid -> true
        is Screen.Bag -> !s.inBattle
        is Screen.ContextMenu -> !isPcMenu(s)
        else -> false
    }

    /**
     * True for the menu opened on a PC box slot: it has entries only the PC offers (DEPOSIT, WITHDRAW, MARKING,
     * RELEASE, HELD ITEMS), or is the MOVE ITEMS menu (a single GIVE / TAKE then EXIT).
     */
    fun isPcMenu(menu: Screen.ContextMenu): Boolean =
        menu.item == null && (menu.entries.any { it.id in PC_ONLY_ENTRIES } ||
            (menu.entries.size == 2 && menu.entries[0].id in setOf("option:give", "option:take")))

    private val PC_ONLY_ENTRIES = setOf("option:deposit", "option:withdraw", "option:marking", "option:release", "option:held_items")

    fun owns(state: GameState, mon: MonId) = state.party.any { it.id == mon }
}

/** Chains a navigation step into another one. */
internal inline fun <T, R> Step<T>.andThen(next: (T) -> Step<R>): Step<R> = when (this) {
    is Step.Done -> next(value)
    is Step.Failed -> this
}
