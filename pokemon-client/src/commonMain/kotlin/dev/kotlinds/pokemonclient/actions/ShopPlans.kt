package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ShopItem
import dev.kotlinds.pokemonclient.runtime.kind

/**
 * Recipes of buying at a Poké Mart: get to the shop list (talk to the clerk and pick BUY — the first entry of the
 * clerk's menu whatever the language — unless already there), then for each purchase pick the item by its id, set
 * the quantity one press at a time (read back after each press), confirm; finally leave with B (it picks SEE YA!).
 * The result is checked on the bag and the money, never on the clerk's words.
 */
internal object ShopPlans {

    /** Where a purchase can start from. */
    enum class Stage { OVERWORLD, CLERK_MENU, SHOP_LIST, QUANTITY }

    /** The stage of the shop the state is at, or null when no purchase can start from here. */
    fun stage(state: GameState): Stage? = when (val screen = state.screen) {
        is Screen.Shop -> Stage.SHOP_LIST
        is Screen.Quantity -> if (state.field != null && clerkFaced(state) != null) Stage.QUANTITY else null
        is Screen.ListMenu -> if (screen.kind == MenuKind.MULTICHOICE && screen.entries.size == CLERK_MENU_SIZE && clerkFaced(state) != null) Stage.CLERK_MENU else null
        else -> if (MovePlans.canWalk(state, hasWorld = true) && clerk(state) != null) Stage.OVERWORLD else null
    }

    /** What the shop sells: the list on screen when it's open, else the clerk's catalog when the game data knows it. */
    fun catalog(state: GameState): List<ShopItem>? {
        (state.screen as? Screen.Shop)?.let { shop ->
            return shop.entries.filter { it.id.startsWith("item:") }.map { e ->
                val id = e.id.removePrefix("item:").toInt()
                ShopItem(dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.ItemId(id), e.label.substringBefore(" ₽")), e.label.substringAfter(" ₽", "").toIntOrNull())
            }
        }
        return (clerkFaced(state) ?: clerk(state))?.catalog
    }

    val buy = ActionPlan<GameAction.Buy> { action, context ->
        val start = context.state()
        if (action.purchases.isEmpty()) {
            val sold = catalog(start)?.map { "item:${it.item.id.value} (${it.item.name}${it.price?.let { p -> " ₽$p" } ?: ""})" }.orEmpty()
            return@ActionPlan ActionOutcome.Failed(ActionError.InvalidParameter("item", "missing", sold.ifEmpty { listOf("talk to the clerk to see the list") }))
        }
        val opened = openShop(context)
        if (opened is Step.Failed) {
            PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
            return@ActionPlan ActionOutcome.Failed(opened.error)
        }
        val bought = mutableListOf<String>()
        var failure: ActionError? = null
        for (purchase in action.purchases) {
            val before = context.state()
            var itemId = 0
            val step = buyOne(context, purchase) { itemId = it }
            if (step is Step.Failed) {
                failure = step.error
                break
            }
            val after = context.state()
            val gained = count(after, itemId) - count(before, itemId)
            val paid = (before.player?.money ?: 0) - (after.player?.money ?: 0)
            if (gained < purchase.quantity) {
                failure = ActionError.Timeout("the bag got $gained ${purchase.item.raw} instead of ${purchase.quantity}")
                break
            }
            bought += "$gained ${itemName(after, itemId)} (₽$paid)" + bonus(before, after, itemId).joinToString("") { " + bonus: $it" }
        }
        PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
        val money = context.state().player?.money
        val summary = "bought ${bought.joinToString()}" + (money?.let { ", ₽$it left" } ?: "")
        when {
            failure == null -> ActionOutcome.Done(summary)
            bought.isEmpty() -> ActionOutcome.Failed(failure)
            // Some lines were bought: say so, with why the rest wasn't.
            else -> ActionOutcome.Done("$summary; stopped: ${failure.code} ${failure.message}")
        }
    }

    /**
     * What the bag got besides the [bought] item during a purchase: the clerk's gift (a Premier Ball for 10 Poké
     * Balls), as "1 Premier Ball (item:12)".
     */
    internal fun bonus(before: GameState, after: GameState, bought: Int): List<String> {
        val old = before.bag.orEmpty().flatMap { it.items }.groupBy { it.item.id.value }.mapValues { (_, s) -> s.sumOf { it.quantity } }
        return after.bag.orEmpty().flatMap { it.items }.groupBy { it.item.id.value }
            .mapNotNull { (id, stacks) ->
                val gained = stacks.sumOf { it.quantity } - (old[id] ?: 0)
                if (id == bought || gained <= 0) null else "$gained ${stacks.first().item.name} (item:$id)"
            }
    }

    /**
     * Sells [GameAction.Sell.quantity] of an item: talk to the clerk, SELL (the second entry of the clerk's menu
     * whatever the language), pick the item in the bag the game opens, set the number (read back after each press),
     * A, YES to the price; then leave. Checked on the bag and the money.
     */
    val sell = ActionPlan<GameAction.Sell> { action, context ->
        val start = context.state()
        val stack = start.bag.orEmpty().flatMap { it.items }.firstOrNull { matchesRef(action.item.raw, "item", it.item.id.value, it.item.name) }
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_ITEM, "There's no ${action.item.raw} in the bag"))
        if (action.quantity > stack.quantity) {
            return@ActionPlan ActionOutcome.Failed(ActionError.InvalidParameter("quantity", action.quantity.toString(), listOf("1..${stack.quantity}")))
        }
        val price = context.game.data?.item(stack.item.id)?.price
        if (price == 0) return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_EFFECT, "${stack.item.name} can't be sold (no price: key items, ...)"))
        val itemId = stack.item.id.value
        val sold = openSellBag(context).andThen {
            PartyBagPlans.bagItem(context, action.item)
        }.andThen { entry ->
            context.navigator.choose(Screen.Bag::class, entry.label) { it.id == entry.id }
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Quantity || it.screen is Screen.YesNo }
        }.andThen { asked ->
            // A single item skips the number: the price question comes at once.
            if (asked.screen is Screen.YesNo) return@andThen Step.Done(asked)
            setQuantity(context, action.quantity).andThen { quantity ->
                context.scope.tap(Button.A)
                context.navigator.awaitChange(quantity.screen)
                context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.YesNo }
            }
        }.andThen {
            context.navigator.choose(Screen.YesNo::class, "YES (sell)") { it.id == "option:yes" }
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Bag }
        }
        PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
        if (sold is Step.Failed) return@ActionPlan ActionOutcome.Failed(sold.error)
        val after = context.state()
        val gone = count(start, itemId) - count(after, itemId)
        val earned = (after.player?.money ?: 0) - (start.player?.money ?: 0)
        if (gone != action.quantity) return@ActionPlan ActionOutcome.Failed(ActionError.Timeout("the bag lost $gone ${stack.item.name} instead of ${action.quantity}"))
        ActionOutcome.Done("sold $gone ${stack.item.name} for ₽$earned" + (after.player?.money?.let { ", ₽$it now" } ?: ""))
    }

    /** Talks to the clerk (unless the clerk's menu is open) and picks SELL, up to the bag the game opens for selling. */
    private fun openSellBag(context: PlanContext): Step<GameState> {
        var state = context.navigator.settle()
        if (state.screen is Screen.Bag) return Step.Done(state)
        if (stage(state) == Stage.OVERWORLD) {
            val clerk = clerk(state) ?: return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no shop clerk here", "go to a Poké Mart"))
            when (val talk = MovePlans.interact.run(GameAction.Interact(clerk.id), context)) {
                is ActionOutcome.Failed -> return Step.Failed(talk.error)
                is ActionOutcome.Done -> Unit
            }
            val menu = context.navigator.advanceUntil(SHOP_WAITS) { (it.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE }
            if (menu is Step.Failed) return menu
            state = (menu as Step.Done).value
        }
        if (stage(state) != Stage.CLERK_MENU) return Step.Failed(ActionError.UnexpectedScreen("the clerk's menu", state.screen.kind))
        return context.navigator.choose(Screen.ListMenu::class, "SELL") { it.id == "option:$CLERK_SELL" }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Bag }
        }
    }

    /** Sets the number of a quantity screen, then confirms it with A when asked. */
    val setQuantity = ActionPlan<GameAction.SetQuantity> { action, context ->
        setQuantity(context, action.value).then { state ->
            if (!action.confirm) return@then ActionOutcome.Done("quantity ${action.value}")
            context.scope.tap(Button.A)
            context.navigator.awaitChange(state.screen)
            ActionOutcome.Done("quantity ${action.value} confirmed")
        }
    }

    /** From wherever [stage] says, to the shop list. */
    private fun openShop(context: PlanContext): Step<GameState> {
        var state = context.navigator.settle()
        if (stage(state) == Stage.QUANTITY) {
            // B on the quantity goes back to the list.
            context.scope.tap(Button.B)
            context.navigator.awaitChange(state.screen)
            state = context.navigator.settle()
        }
        if (stage(state) == Stage.OVERWORLD) {
            val clerk = clerk(state) ?: return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no shop clerk here", "go to a Poké Mart"))
            when (val talk = MovePlans.interact.run(GameAction.Interact(clerk.id), context)) {
                is ActionOutcome.Failed -> return Step.Failed(talk.error)
                is ActionOutcome.Done -> Unit
            }
            val menu = context.navigator.advanceUntil(SHOP_WAITS) { (it.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE }
            if (menu is Step.Failed) return menu
            state = (menu as Step.Done).value
        }
        if ((state.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE) {
            val picked = context.navigator.choose(Screen.ListMenu::class, "BUY") { it.id == "option:$CLERK_BUY" }
            if (picked is Step.Failed) return picked
        }
        return context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Shop }
    }

    /** One line, from the shop list back to the shop list. */
    private fun buyOne(context: PlanContext, purchase: Purchase, onItem: (Int) -> Unit): Step<GameState> {
        val state = context.navigator.settle()
        val shop = state.screen as? Screen.Shop ?: return Step.Failed(ActionError.UnexpectedScreen("the shop list", state.screen.toString()))
        val entry = shop.entries.firstOrNull { it.id.startsWith("item:") && matchesRef(purchase.item.raw, "item", it.id.removePrefix("item:").toInt(), it.label.substringBefore(" ₽")) }
            ?: return Step.Failed(ActionError.InvalidParameter("item", purchase.item.raw, shop.entries.filter { it.id.startsWith("item:") }.map { "${it.id} (${it.label})" }))
        val price = entry.label.substringAfter(" ₽", "").toIntOrNull()
        if (!entry.selectable || (price != null && price.toLong() * purchase.quantity > shop.money)) {
            return Step.Failed(ActionError.Unavailable(UnavailableReason.NOT_ENOUGH_MONEY,
                "${purchase.quantity} × ${entry.label} cost ₽${(price ?: 0).toLong() * purchase.quantity}, you have ₽${shop.money}"))
        }
        onItem(entry.id.removePrefix("item:").toInt())
        return context.navigator.choose(Screen.Shop::class, entry.label) { it.id == entry.id }.andThen {
            setQuantity(context, purchase.quantity)
        }.andThen { quantity ->
            // The quantity was just read back: A confirms it.
            context.scope.tap(Button.A)
            context.navigator.awaitChange(quantity.screen)
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.YesNo || it.screen is Screen.Shop }
        }.andThen { asked ->
            if (asked.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (buy)") { it.id == "option:yes" } else Step.Done(asked)
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Shop }
        }
    }

    /**
     * Brings the quantity shown to [quantity]: RIGHT / LEFT (±10) while far from it, then UP / DOWN (±1), reading the
     * value back after every press. A press that doesn't move the value as expected makes it fall back to ±1 (the
     * quantity screens of other menus may not take ±10).
     */
    private fun setQuantity(context: PlanContext, quantity: Int): Step<GameState> {
        var tens = true
        repeat(MAX_QUANTITY_PRESSES) {
            val state = context.navigator.settle()
            val screen = state.screen as? Screen.Quantity ?: return Step.Failed(ActionError.UnexpectedScreen("the quantity", state.screen.toString()))
            if (quantity !in screen.min..screen.max) {
                return Step.Failed(ActionError.Unavailable(UnavailableReason.NOT_ENOUGH_MONEY, "The quantity can go from ${screen.min} to ${screen.max}, not $quantity"))
            }
            if (screen.value == quantity) return Step.Done(state)
            val diff = quantity - screen.value
            val (button, expected) = when {
                tens && diff >= TEN -> Button.RIGHT to screen.value + TEN
                tens && diff <= -TEN -> Button.LEFT to screen.value - TEN
                diff > 0 -> Button.UP to screen.value + 1
                else -> Button.DOWN to screen.value - 1
            }
            context.scope.tap(button)
            context.navigator.awaitChange(screen, maxFrames = QUANTITY_CHANGE_FRAMES)
            val now = (context.navigator.settle().screen as? Screen.Quantity)?.value
            if ((button == Button.RIGHT || button == Button.LEFT) && now != expected) tens = false
        }
        return Step.Failed(ActionError.Timeout("the quantity never reached $quantity"))
    }

    /** The clerk of this map (the nearest one). */
    private fun clerk(state: GameState): FieldObject? {
        val field = state.field ?: return null
        return field.objects.filter { it.role == PersonRole.CLERK }.minByOrNull { kotlin.math.abs(it.x - field.x) + kotlin.math.abs(it.y - field.y) }
    }

    /** The clerk the player faces (next to them, or across the counter: two tiles ahead). */
    private fun clerkFaced(state: GameState): FieldObject? {
        val field = state.field ?: return null
        val facing = field.facing ?: return null
        return field.objects.firstOrNull { o ->
            o.role == PersonRole.CLERK && (1..2).any { d -> o.x == field.x + facing.dx * d && o.y == field.y + facing.dy * d }
        }
    }

    private fun count(state: GameState, itemId: Int) =
        state.bag.orEmpty().flatMap { it.items }.filter { it.item.id.value == itemId }.sumOf { it.quantity }

    private fun itemName(state: GameState, itemId: Int) =
        state.bag.orEmpty().flatMap { it.items }.firstOrNull { it.item.id.value == itemId }?.item?.name ?: "item:$itemId"

    /** BUY / SELL / SEE YA!: BUY is always first. */
    private const val CLERK_BUY = 0
    private const val CLERK_SELL = 1
    private const val CLERK_MENU_SIZE = 3
    private const val SHOP_WAITS = 40
    private const val MAX_QUANTITY_PRESSES = 60
    private const val QUANTITY_CHANGE_FRAMES = 20
    private const val TEN = 10
    private const val CLOSE_PRESSES = 12
}
