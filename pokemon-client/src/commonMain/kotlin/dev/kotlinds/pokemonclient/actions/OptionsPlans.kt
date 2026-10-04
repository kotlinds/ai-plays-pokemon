package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.BattleStyle
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSpeed

/**
 * Recipe of the OPTIONS screen: start menu → OPTIONS, then for each setting asked for, the row (UP / DOWN) and the
 * value (RIGHT, wrapping), each press read back from the screen's entry ids (`setting:<row>:<value>`, never the shown
 * text); finally CONFIRM on the last row and A, which leaves saving them. The result is checked on the options read
 * from the save data.
 */
internal object OptionsPlans {

    val setOptions = ActionPlan<GameAction.SetOptions> { action, context ->
        val wanted = buildList {
            action.textSpeed?.let { add("text_speed" to it.name.lowercase()) }
            action.battleScene?.let { add("battle_scene" to if (it) "on" else "off") }
            action.battleStyle?.let { add("battle_style" to it.name.lowercase()) }
        }
        if (wanted.isEmpty()) return@ActionPlan ActionOutcome.Failed(ActionError.InvalidParameter("options", "none", listOf("text_speed", "battle_scene", "battle_style")))
        var step = PartyBagPlans.openStartMenuEntry(context, "option:options").andThen {
            context.navigator.advanceUntil(OPTIONS_WAITS) { isOptionsScreen(it) }
        }
        for ((row, value) in wanted + (EXIT_ROW to CONFIRM)) {
            step = step.andThen { setRow(context, row, value) }
        }
        step = step.andThen {
            context.navigator.confirm("CONFIRM", target = { it.id == "setting:$EXIT_ROW:$CONFIRM" })
        }.andThen {
            context.navigator.advanceUntil(OPTIONS_WAITS) { !isOptionsScreen(it) && (it.screen is Screen.Overworld || it.screen is Screen.ListMenu) }
        }
        PartyBagPlans.closeToOverworld(context)
        step.then {
            val options = context.state().options
            val ok = options == null || (
                (action.textSpeed == null || options.textSpeed == action.textSpeed) &&
                    (action.battleScene == null || options.battleScene == action.battleScene) &&
                    (action.battleStyle == null || options.battleStyle == action.battleStyle)
                )
            if (!ok) ActionOutcome.Failed(ActionError.Timeout("the options read back are $options"))
            else ActionOutcome.Done(options?.let { "text speed ${it.textSpeed.name.lowercase()}, battle scene ${if (it.battleScene) "on" else "off"}, battle style ${it.battleStyle.name.lowercase()}" } ?: "options set")
        }
    }

    fun isOptionsScreen(state: GameState) = (state.screen as? Screen.ListMenu)?.entries?.firstOrNull()?.id?.startsWith("setting:") == true

    /** Brings the cursor on [row], then its value to [value] (RIGHT for the next value, LEFT for CONFIRM), re-reading after every press. */
    private fun setRow(context: PlanContext, row: String, value: String): Step<GameState> =
        context.navigator.select(Screen.ListMenu::class, row) { it.id.startsWith("setting:$row:") }.andThen {
            repeat(MAX_VALUE_PRESSES) {
                val state = context.navigator.settle()
                val menu = state.screen as? Screen.ListMenu ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the options", state.screen.toString()))
                val current = (menu.cursor as? Cursor.At)?.let { menu.entries.getOrNull(it.index) }?.id
                    ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the cursor on $row", menu.toString()))
                if (!current.startsWith("setting:$row:")) return@andThen Step.Failed(ActionError.VerificationFailed(row, row, current, 0))
                if (current == "setting:$row:$value") return@andThen Step.Done(state)
                // On the last row LEFT selects CONFIRM; elsewhere RIGHT cycles through the values.
                context.scope.tap(if (row == EXIT_ROW) Button.LEFT else Button.RIGHT)
                context.navigator.awaitChange(menu, maxFrames = VALUE_FRAMES)
            }
            Step.Failed(ActionError.Timeout("$row never became $value"))
        }

    private const val EXIT_ROW = "exit"
    private const val CONFIRM = "confirm"
    private const val OPTIONS_WAITS = 40
    private const val MAX_VALUE_PRESSES = 6
    private const val VALUE_FRAMES = 20

    /** Wire values of the options, by type. */
    fun textSpeed(raw: String): TextSpeed? = TextSpeed.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    fun battleStyle(raw: String): BattleStyle? = BattleStyle.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
}
