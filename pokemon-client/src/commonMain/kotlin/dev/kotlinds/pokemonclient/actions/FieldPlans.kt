package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen

/** Recipes of field actions reached from the start menu (save...). */
internal object FieldPlans {

    /** Saves the game: start menu → SAVE → YES (→ YES again to overwrite another save), then waits for the end. */
    val saveGame = ActionPlan<GameAction.SaveGame> { _, context ->
        PartyBagPlans.openStartMenuEntry(context, "option:save").andThen {
            context.navigator.choose(Screen.YesNo::class, "YES (save)") { it.id == "option:yes" }
        }.andThen {
            // Then, after some text: "There is already a saved file. Is it OK to overwrite it?" (YES), the saving
            // animation and "saved the game". It ends on the start menu when it was opened with X, on the overworld
            // when SAVE was touched: both are fine.
            var overwriteAnswered = false
            var result: Step<GameState> = Step.Failed(ActionError.Timeout("the save didn't end"))
            for (round in 0 until SAVE_ROUNDS) {
                val step = context.navigator.advanceUntil(SAVE_WAITS) { it.screen is Screen.YesNo || it.isSaveEnd() }
                if (step is Step.Failed) {
                    result = step
                    break
                }
                val state = (step as Step.Done).value
                if (state.isSaveEnd()) {
                    result = step
                    break
                }
                if (overwriteAnswered) {
                    result = Step.Failed(ActionError.UnexpectedScreen("the end of the save", state.screen.kind))
                    break
                }
                overwriteAnswered = true
                val answered = context.navigator.choose(Screen.YesNo::class, "YES (overwrite)") { it.id == "option:yes" }
                if (answered is Step.Failed) {
                    result = answered
                    break
                }
            }
            result
        }.then {
            PartyBagPlans.closeToOverworld(context)
            ActionOutcome.Done("saved")
        }
    }

    /**
     * Heals the party at a Pokémon Center: talk to the nurse, answer YES to "Would you like to rest your Pokémon?",
     * then read the messages until the player can walk again.
     */
    val heal = ActionPlan<GameAction.Heal> { _, context ->
        val nurse = context.state().field?.objects?.firstOrNull { it.role == PersonRole.NURSE }
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no nurse here", "go to a Pokémon Center"))
        when (val talk = MovePlans.interact.run(GameAction.Interact(nurse.id), context)) {
            is ActionOutcome.Failed -> return@ActionPlan talk
            is ActionOutcome.Done -> Unit
        }
        context.navigator.advanceUntil(HEAL_WAITS) { it.screen is Screen.YesNo || it.screen is Screen.Overworld }.andThen { state ->
            if (state.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (heal)") { it.id == "option:yes" } else Step.Done(state)
        }.andThen {
            context.navigator.advanceUntil(HEAL_WAITS) { it.screen is Screen.Overworld }
        }.then { state ->
            val hurt = state.party.filter { !it.isEgg && it.hp < it.maxHp }
            if (hurt.isEmpty()) ActionOutcome.Done("party healed")
            else ActionOutcome.Failed(ActionError.Timeout("still hurt after the nurse: ${hurt.joinToString { it.displayName }}"))
        }
    }

    private const val HEAL_WAITS = 120

    /**
     * One cast of [GameAction.Fish.rod] (used from the bag) towards the water the player faces. A is pressed on the
     * very frame something bites, never before (too early reels the line in for nothing). Ends hooked (a wild
     * battle starts) or with nothing.
     */
    val fish = ActionPlan<GameAction.Fish> { action, context ->
        val start = context.state()
        val field = start.field ?: return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the overworld", start.screen.kind))
        val facing = field.facing
        val ahead = facing?.let { context.game.world?.areaOf(field.mapId)?.tile(field.x + it.dx, field.y + it.dy)?.kind }
        if (ahead != null && !(ahead is TileKind.Water && ahead.fishable)) {
            return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NOT_FACING_WATER, "The player doesn't face water", "stand at the shore, facing the water"))
        }
        // Like any key item: with Y when the rod is registered there, else through the bag.
        val cast = PartyBagPlans.activateKeyItem(context, action.rod)
        if (cast is Step.Failed) return@ActionPlan ActionOutcome.Failed(cast.error)
        var bitten = false
        var frames = 0
        while (frames < FISH_FRAMES) {
            val state = context.state()
            if (state.battle != null) return@ActionPlan ActionOutcome.Done("hooked a wild Pokémon")
            val screen = state.screen
            when {
                (screen as? Screen.PressToContinue)?.reason == ContinueReason.FISHING_BITE -> {
                    bitten = true
                    context.scope.step(1, InputFrame.of(Button.A))
                }
                // "Not even a nibble" / "It got away": read it, the cast is over.
                screen is Screen.Dialogue && screen.awaiting == Awaiting.INPUT && frames > CAST_FRAMES -> context.scope.tap(Button.A)
                screen is Screen.Overworld && screen.awaiting == Awaiting.INPUT && frames > CAST_FRAMES ->
                    return@ActionPlan ActionOutcome.Done(if (bitten) "it got away" else "nothing bit")
                else -> context.scope.step(1)
            }
            frames++
        }
        ActionOutcome.Failed(ActionError.Timeout("the cast didn't end"))
    }

    private const val FISH_FRAMES = 60 * 30
    /** The cast animation: before it ends, the overworld still shows. */
    private const val CAST_FRAMES = 90

    /**
     * Flies to a visited town: start menu → POKéMON → a Pokémon with Fly → FLY → the town on the map (touched; when
     * it's scrolled off screen, the D-pad moves the map one press at a time until it shows) → YES. Checked on the
     * map the player lands on.
     */
    val fly = ActionPlan<GameAction.Fly> { action, context ->
        val startMap = context.state().field?.mapId
        val result = PartyBagPlans.openParty(context).andThen { state ->
            val grid = state.screen as? Screen.PartyGrid ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the party", state.screen.toString()))
            // Try the Pokémon in party order: the first whose menu has FLY.
            for (entry in grid.entries.filter { it.id.startsWith("mon:") && it.selectable }) {
                val opened = context.navigator.choose(Screen.PartyGrid::class, entry.label) { it.id == entry.id }
                if (opened is Step.Failed) return@andThen opened
                val menu = context.navigator.settle().screen as? Screen.ContextMenu
                if (menu?.entries?.any { it.id == "fieldmove:fly" } == true) {
                    return@andThen context.navigator.choose(Screen.ContextMenu::class, "FLY") { it.id == "fieldmove:fly" }
                }
                context.scope.tap(Button.B)
                context.navigator.awaitChange(menu ?: context.state().screen)
            }
            Step.Failed(ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "No Pokémon in the party can use Fly"))
        }.andThen {
            context.navigator.advanceUntil(FLY_WAITS) { it.screen is Screen.FlyMap }
        }.andThen { state -> flyTarget(context, action.destination, state) }.andThen { target ->
            context.scope.touch(target)
            context.navigator.awaitChange(context.state().screen)
            context.navigator.advanceUntil(FLY_WAITS) { it.screen is Screen.YesNo }
        }.andThen {
            context.navigator.choose(Screen.YesNo::class, "YES (fly)") { it.id == "option:yes" }
        }.andThen {
            context.navigator.advanceUntil(FLY_LANDING_WAITS) { it.screen is Screen.Overworld && it.field?.mapId != startMap }
        }
        result.then { state -> ActionOutcome.Done("landed in ${state.field?.mapName}") }
    }

    /**
     * The touch point of [destination] on the fly map (`fly:<map id>` or the town's name), moving the map with the
     * D-pad when it's off screen (each press re-read). Fails for towns not visited yet.
     */
    private fun flyTarget(context: PlanContext, destination: String, start: GameState): Step<dev.kotlinds.pokemonclient.console.TouchPoint> {
        fun find(state: GameState) = (state.screen as? Screen.FlyMap)?.entries?.firstOrNull { e ->
            e.id == destination || (e.id.startsWith("fly:") && matchesRef(destination, "fly", e.id.removePrefix("fly:").toInt(), e.label))
        }
        val entry = find(start) ?: return Step.Failed(ActionError.InvalidParameter("destination", destination,
            (start.screen as? Screen.FlyMap)?.entries?.filter { it.selectable && it.id.startsWith("fly:") }?.map { "${it.id} (${it.label})" }.orEmpty()))
        if (!entry.selectable) return Step.Failed(ActionError.Unavailable(UnavailableReason.NOT_VISITED, "${entry.label} hasn't been visited yet"))
        entry.touch?.let { return Step.Done(it) }
        for (button in listOf(Button.LEFT, Button.RIGHT, Button.UP, Button.DOWN)) {
            repeat(MAP_SCROLL_PRESSES) {
                val before = context.state().screen
                context.scope.tap(button)
                context.navigator.awaitChange(before, maxFrames = 20)
                find(context.state())?.touch?.let { return Step.Done(it) }
            }
        }
        return Step.Failed(ActionError.NotOnScreen(entry.label, "the fly map", emptyList()))
    }

    private const val FLY_WAITS = 40
    private const val FLY_LANDING_WAITS = 200
    private const val MAP_SCROLL_PRESSES = 16

    private fun GameState.isSaveEnd() = screen is Screen.Overworld || (screen as? Screen.ListMenu)?.kind == MenuKind.START_MENU

    private const val SAVE_ROUNDS = 2
    private const val SAVE_WAITS = 120
}
