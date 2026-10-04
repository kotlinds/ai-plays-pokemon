package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.TextSource
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

    /**
     * BAG → POKé BALLS → the ball → USE, then follows the throw to its typed [ThrowResult]: caught (then the Pokédex
     * entry, the nickname question answered with [GameAction.ThrowBall.nickname] (none: NO), and the PC transfer when
     * the party is full, up to the overworld), broke free (after how many shakes), or missed. The result is read from
     * the game (the shakes it computed when the ball landed), never guessed from the screens that follow.
     */
    val throwBall = ActionPlan<GameAction.ThrowBall> { action, context ->
        var ballId = ""
        val before = context.state()
        val reached = context.navigator.choose(Screen.BattleCommand::class, "BAG") { it.id == "option:bag" }.andThen {
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
            // USE is confirmed here, not by the navigator: its settling would let the whole throw play unseen.
            when (val use = context.navigator.select(Screen.ContextMenu::class, "USE") { it.id == "option:use" }) {
                is Step.Failed -> Step.Failed(use.error)
                is Step.Done -> Step.Done(context.state())
            }
        }
        if (reached is Step.Failed) return@ActionPlan ActionOutcome.Failed(reached.error)
        context.scope.tap(Button.A)
        val foe = before.battle?.battlers?.firstOrNull { !it.ref.isPlayerSide }
        // The throw, the shakes, then the next turn (it broke free) or the capture's messages and questions.
        var shakes: Int? = null
        var end: GameState? = null
        for (poll in 0 until THROW_FRAMES / 2) {
            val state = context.state()
            state.battle?.ballShakes?.let { shakes = it }
            val screen = state.screen
            val settled = screen.awaiting == Awaiting.INPUT
            if (settled && (screen is Screen.Selectable || screen is Screen.Overworld || screen is Screen.Keyboard)) {
                end = state
                break
            }
            // The Pokédex entry of a new species waits for A; battle messages go on by themselves.
            if (settled && (screen is Screen.PressToContinue || screen is Screen.Dialogue && screen.source != TextSource.BATTLE)) {
                context.scope.tap(Button.A)
                context.navigator.awaitChange(screen, maxFrames = 60)
            } else context.scope.step(2)
        }
        val last = end ?: return@ActionPlan ActionOutcome.Failed(ActionError.Timeout("the throw didn't end"))
        val caught = shakes == BattleState.CAUGHT_SHAKES || last.battle == null
        val result = when {
            caught -> ThrowResult.Caught(foe?.species?.name ?: "the Pokémon", foe?.level)
            shakes != null -> ThrowResult.BrokeFree(shakes!!)
            else -> ThrowResult.Missed
        }
        if (result !is ThrowResult.Caught) return@ActionPlan ActionOutcome.Done("$ballId: ${result.describe()}")
        // Caught: the nickname question (in battle), then the transfer to the PC when the party is full.
        val named = answerNickname(context, last, action.nickname)
        if (named is Step.Failed) return@ActionPlan ActionOutcome.Failed(named.error)
        val after = context.navigator.advanceUntil(AFTER_CATCH_WAITS) { s ->
            s.battle == null && s.screen is Screen.Overworld || s.screen is Screen.Selectable || s.screen is Screen.Keyboard
        }
        val where = (after as? Step.Done)?.value?.let { state ->
            when {
                state.party.size > before.party.size -> "joined the party"
                (state.storage?.mons?.size ?: 0) > (before.storage?.mons?.size ?: 0) ->
                    state.storage?.mons?.firstOrNull { m -> before.storage?.mons?.none { it.id == m.id } == true }?.let { m ->
                        "sent to ${state.storage?.boxes?.firstOrNull { it.index == m.box }?.name ?: "BOX ${m.box + 1}"} (party full)"
                    }
                else -> null
            }
        }
        val nicknamed = action.nickname?.let { " as $it" } ?: ""
        ActionOutcome.Done("$ballId: ${result.describe()}$nicknamed" + (where?.let { ", $it" } ?: ""))
    }

    /** Answers "Give a nickname to the caught X?": YES and types [nickname], or NO. Nothing when no question shows. */
    private fun answerNickname(context: PlanContext, state: GameState, nickname: String?): Step<GameState> {
        val prompt = state.screen as? Screen.YesNo ?: return Step.Done(state)
        if (prompt.entries.none { it.id == "option:yes" } || prompt.entries.none { it.id == "option:no" }) return Step.Done(state)
        if (nickname == null) return context.navigator.choose(Screen.YesNo::class, "NO (no nickname)") { it.id == "option:no" }
        return context.navigator.choose(Screen.YesNo::class, "YES (nickname)") { it.id == "option:yes" }.andThen {
            context.navigator.advanceUntil(NICKNAME_WAITS) { it.screen is Screen.Keyboard }
        }.andThen {
            when (val typed = TextPlans.enterText.run(GameAction.EnterText(nickname), context)) {
                is ActionOutcome.Done -> Step.Done(context.state())
                is ActionOutcome.Failed -> Step.Failed(typed.error)
            }
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
    /** The throw, the shakes, "Gotcha!" and the Pokédex entry up to the nickname question (~1600 frames seen). */
    private const val THROW_FRAMES = 2400
    private const val AFTER_CATCH_WAITS = 40
    private const val NICKNAME_WAITS = 20
    private const val LEARN_WAITS = 40
    private const val CONFIRM_FRAMES = 120
}

/** How a thrown Poké Ball ended. */
sealed interface ThrowResult {
    /** Caught: [species] at [level] (of the wild Pokémon). */
    data class Caught(val species: String, val level: Int?) : ThrowResult

    /** It broke free after [shakes] shakes (0..3). */
    data class BrokeFree(val shakes: Int) : ThrowResult

    /** The ball didn't reach a catch roll (blocked, or the Pokémon can't be caught). */
    data object Missed : ThrowResult

    /** For the action's answer: "caught SPINARAK Lv12", "broke free after 2 shakes", "missed". */
    fun describe(): String = when (this) {
        is Caught -> "caught $species" + (level?.let { " Lv$it" } ?: "")
        is BrokeFree -> "broke free after $shakes shake" + if (shakes == 1) "" else "s"
        Missed -> "missed"
    }
}
