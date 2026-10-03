package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen

/**
 * Recipe of buying at a Poké Mart: talk to the clerk, BUY (first entry of the clerk's menu, whatever the language),
 * pick the item by its id, set the quantity one press at a time (read back after each press), confirm, then leave
 * with B (it picks SEE YA!). The result is checked on the bag and the money, never on the clerk's words.
 */
internal object ShopPlans {

    val buy = ActionPlan<GameAction.Buy> { action, context ->
        val clerk = context.state().field?.objects?.firstOrNull { it.role == PersonRole.CLERK }
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no shop clerk here", "go to a Poké Mart"))
        val before = context.state()
        when (val talk = MovePlans.interact.run(GameAction.Interact(clerk.id), context)) {
            is ActionOutcome.Failed -> return@ActionPlan talk
            is ActionOutcome.Done -> Unit
        }
        var itemId = 0
        val result = context.navigator.advanceUntil(SHOP_WAITS) { (it.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE }.andThen {
            context.navigator.choose(Screen.ListMenu::class, "BUY") { it.id == "option:$CLERK_BUY" }
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Shop }
        }.andThen { state ->
            val shop = state.screen as Screen.Shop
            val entry = shop.entries.firstOrNull { it.id.startsWith("item:") && matchesRef(action.item.raw, "item", it.id.removePrefix("item:").toInt(), it.label.substringBefore(" ₽")) }
                ?: return@andThen Step.Failed(ActionError.NotOnScreen(action.item.raw, "this shop", shop.entries.filter { it.selectable }.map { it.label }))
            itemId = entry.id.removePrefix("item:").toInt()
            context.navigator.choose(Screen.Shop::class, entry.label) { it.id == entry.id }
        }.andThen { setQuantity(context, action.quantity) }.andThen { state ->
            // The quantity was just read back: A confirms it.
            context.scope.tap(Button.A)
            context.navigator.awaitChange(state.screen)
            Step.Done(context.state())
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.YesNo || it.screen is Screen.Shop }
        }.andThen { state ->
            if (state.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (buy)") { it.id == "option:yes" } else Step.Done(state)
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Shop }
        }
        PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
        result.then {
            val after = context.state()
            val gained = count(after, itemId) - count(before, itemId)
            val paid = (before.player?.money ?: 0) - (after.player?.money ?: 0)
            if (gained >= action.quantity) ActionOutcome.Done("bought $gained for ₽$paid")
            else ActionOutcome.Failed(ActionError.Timeout("the bag got $gained instead of ${action.quantity}"))
        }
    }

    /** Presses UP / DOWN until the quantity shown is [quantity] (UP adds one, DOWN removes one, both wrap). */
    private fun setQuantity(context: PlanContext, quantity: Int): Step<GameState> {
        repeat(MAX_QUANTITY_PRESSES) {
            val state = context.navigator.settle()
            val screen = state.screen as? Screen.Quantity ?: return Step.Failed(ActionError.UnexpectedScreen("the quantity", state.screen.toString()))
            if (quantity !in screen.min..screen.max) {
                return Step.Failed(ActionError.Unavailable(UnavailableReason.NOT_ENOUGH_MONEY, "You can buy at most ${screen.max}"))
            }
            if (screen.value == quantity) return Step.Done(state)
            context.scope.tap(if (screen.value < quantity) Button.UP else Button.DOWN)
            context.navigator.awaitChange(screen, maxFrames = 20)
        }
        return Step.Failed(ActionError.Timeout("the quantity never reached $quantity"))
    }

    private fun count(state: GameState, itemId: Int) =
        state.bag.orEmpty().flatMap { it.items }.filter { it.item.id.value == itemId }.sumOf { it.quantity }

    /** BUY / SELL / SEE YA!: BUY is always first. */
    private const val CLERK_BUY = 0
    private const val SHOP_WAITS = 40
    private const val MAX_QUANTITY_PRESSES = 120
    private const val CLOSE_PRESSES = 12
}
