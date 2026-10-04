package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen

/** Recipes of the battle actions beyond attacking and running: switching, throwing a ball, learning a move. */
internal object BattlePlans {

    /**
     * Sends [GameAction.Switch.mon] in: from the command menu (POKéMON), from "Will you switch Pokémon?" (SWITCH),
     * or on the forced replacement grid after a K.O.; then SHIFT on the Pokémon.
     */
    val switch = ActionPlan<GameAction.Switch> { action, context ->
        val state = context.navigator.settle()
        val toGrid = when {
            state.screen is Screen.PartyGrid -> Step.Done(state)
            state.screen is Screen.BattleCommand -> context.navigator.choose(Screen.BattleCommand::class, "POKéMON") { it.id == "option:pokemon" }
            (state.screen as? Screen.ListMenu)?.kind == MenuKind.BATTLE_SWITCH_OR_KEEP ->
                context.navigator.choose(Screen.ListMenu::class, "SWITCH") { it.id == "option:switch" }
            state.screen is Screen.YesNo && (state.screen as Screen.YesNo).entries.any { it.id == "option:next" } ->
                context.navigator.choose(Screen.YesNo::class, "USE NEXT POKéMON") { it.id == "option:next" }
            else -> Step.Failed(ActionError.UnexpectedScreen("the battle menu or the party", state.screen.toString()))
        }
        toGrid.andThen {
            context.navigator.settle().let { grid ->
                if (grid.screen !is Screen.PartyGrid) return@andThen Step.Failed(ActionError.UnexpectedScreen("the party", grid.screen.toString()))
            }
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon to send in") { it.id == action.mon.toString() }
        }.andThen { after ->
            if (after.screen is Screen.ContextMenu) context.navigator.choose(Screen.ContextMenu::class, "SHIFT") { it.id == "option:shift" } else Step.Done(after)
        }.then { ActionOutcome.Done() }
    }

    /** BAG → POKé BALLS → the ball → USE, then waits for the outcome (caught, or it broke free). */
    val throwBall = ActionPlan<GameAction.ThrowBall> { action, context ->
        var ballId = ""
        context.navigator.choose(Screen.BattleCommand::class, "BAG") { it.id == "option:bag" }.andThen {
            context.navigator.choose(Screen.Bag::class, "POKé BALLS") { it.id == BALLS_POCKET }
        }.andThen { state ->
            val bag = state.screen as? Screen.Bag ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the balls pocket", state.screen.toString()))
            val ball = bag.entries.firstOrNull { e ->
                val id = e.id.removePrefix("item:").toIntOrNull() ?: return@firstOrNull false
                e.id.startsWith("item:") && matchesRef(action.ball.raw, "item", id, e.label.substringBeforeLast(" x"))
            } ?: return@andThen Step.Failed(ActionError.NotOnScreen(action.ball.raw, "the balls pocket", bag.entries.filter { it.id.startsWith("item:") }.map { it.label }))
            ballId = ball.id
            context.navigator.choose(Screen.Bag::class, ball.label) { it.id == ball.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen {
            // The throw, the shakes, then: the next turn (it broke free), or the capture's messages and questions.
            context.navigator.advanceUntil(THROW_WAITS) { it.screen is Screen.Selectable || it.screen is Screen.Overworld || it.screen is Screen.Keyboard }
        }.then { state ->
            // The bag in RAM only changes when the battle ends: the outcome is told by the screens that follow.
            val caught = state.battle == null || (state.screen as? Screen.YesNo)?.entries?.any { it.id == "option:yes" } == true && state.screen !is Screen.BattleCommand
            ActionOutcome.Done(if (state.screen is Screen.BattleCommand) "$ballId: it broke free" else if (caught) "$ballId: caught" else "$ballId: now ${state.screen}")
        }
    }

    /**
     * After "X wants to learn Y" (in or after a battle, or when evolving): forgets [GameAction.LearnMove.forget], or
     * gives up learning the new move when it's null.
     */
    val learnMove = ActionPlan<GameAction.LearnMove> { action, context ->
        val outcome = learnMoveSteps.run(action, context)
        // Learning from the field (Rare Candy, TM): the bag / party menus the item was used from are still open.
        if (outcome is ActionOutcome.Done) closeFieldMenus(context)
        outcome
    }

    /** Out of battle, closes the bag / party menus left open behind a finished prompt (never during an evolution). */
    private fun closeFieldMenus(context: PlanContext) {
        val state = context.navigator.settle()
        if (state.battle != null) return
        val menus = state.screen is Screen.Bag || state.screen is Screen.PartyGrid || state.screen is Screen.ContextMenu ||
            (state.screen as? Screen.Dialogue)?.source == dev.kotlinds.pokemonclient.state.TextSource.MENU
        if (menus) PartyBagPlans.closeToOverworld(context)
    }

    private val learnMoveSteps = ActionPlan<GameAction.LearnMove> { action, context ->
        // Messages may come first ("Which move should be forgotten?"): read them up to the question or the list.
        val reached = context.navigator.advanceUntil(LEARN_WAITS) { it.screen is Screen.Selectable }
        if (reached is Step.Failed) return@ActionPlan ActionOutcome.Failed(reached.error)
        val state = (reached as Step.Done).value
        val forget = action.forget
        val prompt = state.screen as? Screen.YesNo
        val toList = when {
            state.screen is Screen.MoveSelect -> Step.Done(state)
            prompt != null && forgetAnswer(prompt) != null ->
                if (forget == null) context.navigator.choose(Screen.YesNo::class, "KEEP OLD MOVES") { it.id == keepAnswer(prompt) }
                else context.navigator.choose(Screen.YesNo::class, "FORGET A MOVE") { it.id == forgetAnswer(prompt) }
            else -> Step.Failed(ActionError.UnexpectedScreen("the question about the new move", state.screen.toString()))
        }
        if (forget == null) {
            // "Give up on learning Y?" → yes.
            return@ActionPlan toList.andThen {
                context.navigator.advanceUntil(LEARN_WAITS) { s -> (s.screen as? Screen.YesNo)?.let(::giveUpAnswer) != null }
            }.andThen { s ->
                val answer = (s.screen as Screen.YesNo).let(::giveUpAnswer)
                context.navigator.choose(Screen.YesNo::class, "GIVE UP") { it.id == answer }
            }.then { ActionOutcome.Done("kept the old moves") }
        }
        toList.andThen {
            context.navigator.advanceUntil(LEARN_WAITS) { it.screen is Screen.MoveSelect }
        }.andThen { listState ->
            val list = listState.screen as Screen.MoveSelect
            val entry = list.entries.firstOrNull { e ->
                val id = e.id.removePrefix("move:").toIntOrNull() ?: return@firstOrNull false
                e.id != "move:${list.newMove?.id?.value}" && matchesRef(forget.raw, "move", id, e.label.substringBefore(" ("))
            } ?: return@andThen Step.Failed(ActionError.InvalidParameter("forget", forget.raw, list.entries.filter { it.id.startsWith("move:") }.map { it.label }))
            // The only moves these lists refuse are HMs ("HM moves can't be forgotten now").
            if (!entry.selectable) return@andThen Step.Failed(ActionError.HmCannotForget(entry.label.substringBefore(" (")))
            context.navigator.choose(Screen.MoveSelect::class, entry.label) { it.id == entry.id }
        }.andThen {
            // In battle, "FORGET <move>" / CANCEL confirms the choice (it appears a few frames after the list).
            var confirm: GameState? = null
            for (frame in 0 until CONFIRM_FRAMES step 2) {
                val now = context.state()
                if (now.screen is Screen.ContextMenu) {
                    confirm = now
                    break
                }
                if (now.screen !is Screen.MoveSelect && now.screen.awaiting == dev.kotlinds.pokemonclient.state.Awaiting.INPUT) break
                context.scope.step(2)
            }
            if (confirm != null) context.navigator.choose(Screen.ContextMenu::class, "FORGET") { it.id == "option:forget" } else Step.Done(context.state())
        }.then { after ->
            if (after.screen is Screen.MoveSelect) ActionOutcome.Failed(ActionError.Timeout("still on the move list"))
            else ActionOutcome.Done("forgot ${forget.raw}")
        }
    }

    /**
     * The entry answering "forget a move" on a learn prompt: `option:forget` in battle, YES on the field's plain YES /
     * NO question ([dev.kotlinds.pokemonclient.state.LearnQuestion.FORGET_A_MOVE]). Null when it isn't such a prompt.
     */
    private fun forgetAnswer(prompt: Screen.YesNo): String? = when {
        prompt.entries.any { it.id == "option:forget" } -> "option:forget"
        prompt.learning?.question == dev.kotlinds.pokemonclient.state.LearnQuestion.FORGET_A_MOVE && prompt.entries.any { it.id == "option:yes" } -> "option:yes"
        else -> null
    }

    private fun keepAnswer(prompt: Screen.YesNo): String = if (prompt.entries.any { it.id == "option:keep" }) "option:keep" else "option:no"

    /** The entry giving up the new move: `option:give_up` in battle, YES on the field's "stop trying to teach?". */
    private fun giveUpAnswer(prompt: Screen.YesNo): String? = when {
        prompt.entries.any { it.id == "option:give_up" } -> "option:give_up"
        prompt.learning?.question == dev.kotlinds.pokemonclient.state.LearnQuestion.GIVE_UP && prompt.entries.any { it.id == "option:yes" } -> "option:yes"
        else -> null
    }

    /** The screens [learnMove] starts from. */
    fun isLearnPrompt(state: GameState): Boolean = when (val s = state.screen) {
        is Screen.YesNo -> forgetAnswer(s) != null
        is Screen.MoveSelect -> s.context != MoveContext.BATTLE
        else -> false
    }

    /** The screens [switch] starts from. */
    fun canSwitch(state: GameState): Boolean = when (val s = state.screen) {
        is Screen.BattleCommand -> true
        is Screen.PartyGrid -> s.purpose == PartyPurpose.BATTLE_SWITCH || s.purpose == PartyPurpose.BATTLE_REPLACE_FAINTED
        is Screen.ListMenu -> s.kind == MenuKind.BATTLE_SWITCH_OR_KEEP
        is Screen.YesNo -> s.entries.any { it.id == "option:next" }
        else -> false
    }

    private const val BALLS_POCKET = "pocket:poke_balls"
    private const val THROW_WAITS = 200
    private const val LEARN_WAITS = 40
    private const val CONFIRM_FRAMES = 120
}
