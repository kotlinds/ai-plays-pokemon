package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen

/**
 * Using an item in battle, in one action: BAG → the battle pocket holding the item (the battle bag sorts items its own
 * way: a Revive is in STATUS HEALERS, an Ether in HP/PP RESTORE) → the item → USE → the Pokémon → the move for a PP
 * restoring item. Every step goes through the navigator.
 *
 * The bag in RAM doesn't change until the battle ends, so the outcome is told by the screens: the bag closes and the
 * turn goes on (used), or the bag / party screen shows its own message and stays open ("It won't have any effect":
 * the recipe closes it back to the command menu and reports [UnavailableReason.NO_EFFECT]).
 */
internal object BattleItemPlans {

    val useItem = ActionPlan<GameAction.UseItem> { action, context ->
        if (action.batch.isNotEmpty()) {
            return@ActionPlan ActionOutcome.Failed(ActionError.InvalidParameter("items", "${action.uses.size} items", listOf("one item per turn in battle")))
        }
        val start = context.navigator.settle()
        val command = start.screen as? Screen.BattleCommand
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the battle command menu", start.screen.toString()))
        if (command.entries.none { it.id == BAG }) {
            return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "This battle has no bag"))
        }
        val itemId = resolveItem(start, action.item)
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_ITEM, "There's no ${action.item.raw} in the bag"))
        var target = action.target
        val reached = context.navigator.choose(Screen.BattleCommand::class, "BAG") { it.id == BAG }.andThen {
            context.navigator.settle().screen as? Screen.Bag ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the battle bag", context.state().screen.toString()))
            val bag = context.state().screen as Screen.Bag
            val pocket = bag.pocketContents.entries.firstOrNull { (_, items) -> itemId in items }?.key
                ?: return@andThen Step.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_ITEM, "${action.item.raw} can't be used in battle (it's in no battle pocket)"))
            if (pocket == BALLS_POCKET) return@andThen Step.Failed(ActionError.InvalidParameter("item", action.item.raw, listOf("use throw_ball for a Poké Ball")))
            context.navigator.choose(Screen.Bag::class, pocket) { it.id == pocket }
        }.andThen {
            context.navigator.choose(Screen.Bag::class, action.item.raw) { it.id == "item:${itemId.value}" }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen { after ->
            val grid = after.screen as? Screen.PartyGrid ?: return@andThen Step.Done(after)
            val candidates = grid.entries.filter { it.id.startsWith("mon:") && it.selectable }
            val mon = target?.toString() ?: candidates.singleOrNull()?.id
                ?: return@andThen Step.Failed(ActionError.InvalidParameter("target", "none", candidates.map { "${it.id} = ${it.label}" }))
            target = target ?: dev.kotlinds.pokemonclient.state.MonId.parse(mon)
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == mon }
        }.andThen { after ->
            if (!PartyBagPlans.isMoveList(after.screen)) return@andThen Step.Done(after)
            PartyBagPlans.chooseMove(context, after.screen as Screen.Selectable, action.move)
        }
        if (reached is Step.Failed) {
            backToCommand(context)
            return@ActionPlan ActionOutcome.Failed(reached.error)
        }
        // The bag / party screen prints its message ("PP was restored.", "It won't have any effect.") and waits for A.
        // Then: used, the bag closes and the turn plays; refused, the item screens are back.
        var said: String? = null
        repeat(MAX_MESSAGES) {
            val state = context.navigator.settle()
            val screen = state.screen
            when {
                screen is Screen.Dialogue && screen.awaiting == Awaiting.INPUT -> {
                    said = screen.text.replace('\n', ' ')
                    context.scope.tap(Button.A)
                    context.navigator.awaitChange(screen, maxFrames = 90)
                }
                stillInBag(screen) -> {
                    backToCommand(context)
                    return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_EFFECT, "${action.item.raw} had no effect" + (said?.let { ": $it" } ?: ""), "the turn wasn't used"))
                }
                else -> return@ActionPlan ActionOutcome.Done("used item:${itemId.value}" + (target?.let { " on $it" } ?: "") + (said?.let { ": $it" } ?: ""))
            }
        }
        ActionOutcome.Failed(ActionError.Timeout("the bag didn't close after using ${action.item.raw}"))
    }

    /** The item screens still open: the game refused the item (back to the bag, the item's USE menu or the party). */
    private fun stillInBag(screen: Screen): Boolean = when (screen) {
        is Screen.Bag -> true
        is Screen.PartyGrid -> screen.purpose == PartyPurpose.BATTLE_USE_ITEM
        is Screen.ContextMenu -> screen.entries.any { it.id == "option:use" }
        is Screen.ListMenu -> PartyBagPlans.isMoveList(screen)
        else -> false
    }

    /** Leaves the bag / party screens (A on their messages, B elsewhere) until the command menu is back. */
    private fun backToCommand(context: PlanContext) {
        repeat(MAX_BACK_PRESSES) {
            val state = context.navigator.settle()
            when (state.screen) {
                is Screen.BattleCommand -> return
                is Screen.Dialogue -> if (state.screen.awaiting == Awaiting.INPUT) context.scope.tap(Button.A) else return
                is Screen.Bag, is Screen.PartyGrid, is Screen.ContextMenu, is Screen.ListMenu -> context.scope.tap(Button.B)
                else -> return
            }
            context.navigator.awaitChange(state.screen, maxFrames = 90)
        }
    }

    /** The item's id, from the bag (by id or name). */
    private fun resolveItem(state: GameState, item: ItemRef): ItemId? =
        state.bag.orEmpty().flatMap { it.items }.firstOrNull { matchesRef(item.raw, "item", it.item.id.value, it.item.name) }?.item?.id
            ?: item.raw.removePrefix("item:").toIntOrNull()?.let(::ItemId)

    /** The screens [useItem] starts from. */
    fun canUse(state: GameState): Boolean =
        state.battle != null && (state.screen as? Screen.BattleCommand)?.entries?.any { it.id == BAG } == true

    private const val BAG = "option:bag"
    private const val BALLS_POCKET = "pocket:poke_balls"
    private const val MAX_BACK_PRESSES = 8
    private const val MAX_MESSAGES = 6
}
