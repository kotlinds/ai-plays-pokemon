package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.StartMenuFeature
import dev.kotlinds.pokemonclient.state.sameAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The actions that only existed as types: `sell` (Poké Mart), `release` (PC), `drag` (touch screen) and `open_menu`
 * (start menu). Listed with the common actions ([CommonActions.definitions]).
 */
object MoreActions {

    private val both = setOf(ActionMode.PURE, ActionMode.ASSISTED)
    private val assisted = setOf(ActionMode.ASSISTED)

    /** The start menu entries `open_menu` offers (their ids on the menu, language independent). */
    private val START_ENTRIES = listOf("option:pokedex", "option:pokemon", "option:bag", "option:trainer_card", "option:save", "option:options", "option:pokegear")

    val sell = ActionDefinition(GameAction.Sell::class, spec(
        name = "sell",
        description = "Sell items at this Poké Mart (walks to the clerk, SELL, picks the item, sets the number, accepts the price). " +
            "Also works from the clerk's menu or the selling bag. The answer says what was earned.",
        parameters = listOf(
            Parameter("item", ParameterType.STRING, "The item: its id (item:17) or its name."),
            Parameter("quantity", ParameterType.INTEGER, "How many (default 1).", required = false),
        ),
        modes = assisted,
        availability = { state ->
            val atShop = ShopPlans.stage(state).let { it == ShopPlans.Stage.OVERWORLD || it == ShopPlans.Stage.CLERK_MENU } ||
                (state.screen is Screen.Bag && state.field != null && ShopPlans.stage(state.copy(screen = Screen.Overworld(awaiting = Awaiting.INPUT))) != null)
            if (!atShop) return@spec Availability.Hidden
            Availability.Available(mapOf("item" to state.bag.orEmpty().filter { it.name != "key_items" }.flatMap { it.items }
                .map { Choice("item:${it.item.id.value}", "${it.item.name} x${it.quantity}") }))
        },
        parse = { json -> GameAction.Sell(ItemRef(string(json, "item")), json["quantity"]?.jsonPrimitive?.intOrNull?.also { check(it, "quantity", 1..999) } ?: 1) },
    ), ShopPlans.sell)

    val release = ActionDefinition(GameAction.Release::class, spec(
        name = "release",
        description = "Release a Pokémon for good, at the PC of this building (party or box). Dangerous: refused unless " +
            "confirm is true; the Pokémon is named by its id only.",
        parameters = listOf(
            Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…)."),
            Parameter("confirm", ParameterType.BOOLEAN, "Must be true: the release can't be undone."),
        ),
        modes = assisted,
        availability = { state ->
            if (!MovePlans.canWalk(state, hasWorld = true) || state.field?.hasPc == false) return@spec Availability.Hidden
            Availability.Available(mapOf("pokemon" to (state.party.map { Choice(it.id.toString(), "${it.displayName} Lv${it.level} (party)") } +
                state.storage?.boxes.orEmpty().flatMap { box -> box.mons.map { Choice(it.id.toString(), "${it.displayName} (${box.name})") } })), listed = false)
        },
        parse = { json ->
            val raw = string(json, "pokemon")
            GameAction.Release(MonId.parse(raw) ?: throw ActionException(ActionError.InvalidParameter("pokemon", raw)), json["confirm"]?.jsonPrimitive?.booleanOrNull ?: false)
        },
    ), PcPlans.release)

    val drag = ActionDefinition(GameAction.Drag::class, spec(
        name = "drag",
        description = "Drag the stylus on the bottom screen from (x, y) to (to_x, to_y) (pixels of the bottom screen: " +
            "x 0-255, y 0-191), held all along, then lift it: the Ruins of Alph panels, sliders. Says whether the screen changed.",
        parameters = listOf(
            Parameter("x", ParameterType.INTEGER, "Start x."),
            Parameter("y", ParameterType.INTEGER, "Start y."),
            Parameter("to_x", ParameterType.INTEGER, "End x."),
            Parameter("to_y", ParameterType.INTEGER, "End y."),
            Parameter("frames", ParameterType.INTEGER, "How long the move lasts (2-240 frames, default 30).", required = false),
        ),
        modes = both,
        availability = { Availability.Available() },
        parse = { json ->
            GameAction.Drag(
                TouchPoint(int(json, "x", 0..255), int(json, "y", 0..191)),
                TouchPoint(int(json, "to_x", 0..255), int(json, "to_y", 0..191)),
                json["frames"]?.jsonPrimitive?.intOrNull?.also { check(it, "frames", 2..240) } ?: DEFAULT_DRAG_FRAMES,
            )
        },
    ), dragPlan)

    val openMenu = ActionDefinition(GameAction.OpenMenu::class, spec(
        name = "open_menu",
        description = "Open the start menu and pick one of its entries by id (option:bag, option:pokemon, option:pokedex, " +
            "option:trainer_card, option:pokegear, option:options, option:save), checked on the menu itself.",
        parameters = listOf(Parameter("entry", ParameterType.STRING, "The start menu entry.", values = START_ENTRIES)),
        modes = assisted,
        availability = { state ->
            val walking = state.battle == null && (state.screen as? Screen.Overworld)?.awaiting == Awaiting.INPUT
            val inMenu = (state.screen as? Screen.ListMenu)?.kind == MenuKind.START_MENU
            when {
                !walking && !inMenu -> Availability.Hidden
                state.startMenu?.contains(StartMenuFeature.BAG) == false ->
                    Availability.Unavailable(UnavailableReason.NOT_UNLOCKED_YET, "The start menu doesn't open yet", "the story unlocks it (Mom gives it at the start)")
                else -> Availability.Available(listed = false)
            }
        },
        parse = { json ->
            val raw = string(json, "entry").lowercase()
            GameAction.OpenMenu(if (raw.startsWith("option:")) raw else "option:$raw")
        },
    ), openMenuPlan)

    /** The definitions, in the order they are listed to agents. */
    val definitions: List<ActionDefinition<*>> get() = listOf(sell, release, openMenu, drag)

    // region Plans

    /** Holds the stylus from [GameAction.Drag.from] to [GameAction.Drag.to], one small move per frame, then lifts it. */
    private val dragPlan: ActionPlan<GameAction.Drag> get() = ActionPlan { action, context ->
        val before = context.state().screen
        val (from, to, frames) = action
        for (i in 0..frames) {
            val point = TouchPoint(from.x + (to.x - from.x) * i / frames, from.y + (to.y - from.y) * i / frames)
            context.scope.step(1, InputFrame(touch = point))
        }
        context.scope.step(DRAG_HOLD_FRAMES, InputFrame(touch = to))
        context.scope.step(DRAG_RELEASE_FRAMES)
        context.navigator.awaitChange(before, maxFrames = DRAG_REACTION_FRAMES)
        val after = context.navigator.settle(maxFrames = DRAG_REACTION_FRAMES).screen
        ActionOutcome.Done(
            when {
                !after.sameAs(before) -> if (after.kind == before.kind) "the screen changed (still ${after.kind})" else "now: ${after.kind}"
                after is Screen.Unknown -> "dragged; this screen isn't decoded: a screenshot shows what moved"
                else -> "the screen didn't change"
            },
        )
    }

    /** Opens the start menu (X) and picks the entry; checks something else is on screen after. */
    private val openMenuPlan: ActionPlan<GameAction.OpenMenu> get() = ActionPlan { action, context ->
        PartyBagPlans.openStartMenuEntry(context, action.entry).then { state ->
            val screen = state.screen
            if ((screen as? Screen.ListMenu)?.kind == MenuKind.START_MENU) {
                ActionOutcome.Failed(ActionError.UnexpectedScreen("${action.entry} opened", "the start menu still"))
            } else ActionOutcome.Done("now: ${screen.kind}")
        }
    }

    // endregion

    // region Helpers

    private const val DEFAULT_DRAG_FRAMES = 30
    private const val DRAG_HOLD_FRAMES = 4
    private const val DRAG_RELEASE_FRAMES = 4
    private const val DRAG_REACTION_FRAMES = 120

    private fun <A : GameAction> spec(
        name: String,
        description: String,
        parameters: List<Parameter>,
        modes: Set<ActionMode>,
        availability: (GameState) -> Availability,
        parse: (JsonObject) -> A,
    ): ActionSpec<A> = object : ActionSpec<A> {
        override val name = name
        override val description = description
        override val parameters = parameters
        override val modes = modes
        override fun availability(state: GameState) = availability(state)
        override fun parse(json: JsonObject) = parse(json)
        override fun enumerate(state: GameState) = emptyList<A>()
    }

    private fun string(json: JsonObject, key: String): String =
        json[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw ActionException(ActionError.InvalidParameter(key, "missing"))

    private fun int(json: JsonObject, key: String, range: IntRange): Int {
        val value = json[key]?.jsonPrimitive?.intOrNull ?: throw ActionException(ActionError.InvalidParameter(key, "missing"))
        return value.also { check(it, key, range) }
    }

    private fun check(value: Int, key: String, range: IntRange) {
        if (value !in range) throw ActionException(ActionError.InvalidParameter(key, value.toString(), listOf("${range.first}..${range.last}")))
    }

    // endregion
}
