package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen

/**
 * Recipe of typing on the naming keyboard (nicknames, box names...): erase what is there, type each character
 * (switching keyboard page when the character is on another one), check the typed text read from RAM after every
 * key, then OK. Keys are found by the character they type (`key:<char>`), page buttons by `page:<name>`.
 */
internal object TextPlans {

    val enterText = ActionPlan<GameAction.EnterText> { action, context ->
        val start = context.navigator.settle()
        val keyboard = start.screen as? Screen.Keyboard
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the naming keyboard", start.screen.toString()))
        if (action.text.length > keyboard.maxLength) {
            return@ActionPlan ActionOutcome.Failed(ActionError.InvalidParameter("text", action.text, listOf("at most ${keyboard.maxLength} characters")))
        }
        val erased = erase(context)
        if (erased is Step.Failed) return@ActionPlan ActionOutcome.Failed(erased.error)
        for ((i, char) in action.text.withIndex()) {
            val typed = type(context, char, expected = action.text.take(i + 1))
            if (typed is Step.Failed) return@ActionPlan ActionOutcome.Failed(typed.error)
        }
        context.navigator.choose(Screen.Keyboard::class, "OK") { it.id == "option:ok" }.then { ActionOutcome.Done("typed ${action.text}") }
    }

    /** Presses B (delete) until the typed text is empty, checking that each press removed one character. */
    private fun erase(context: PlanContext): Step<GameState> {
        repeat(MAX_KEYS) {
            val state = context.navigator.settle()
            val keyboard = state.screen as? Screen.Keyboard ?: return Step.Failed(ActionError.UnexpectedScreen("the naming keyboard", state.screen.toString()))
            if (keyboard.buffer.isEmpty()) return Step.Done(state)
            context.scope.tap(Button.B)
            context.navigator.awaitChange(keyboard, maxFrames = KEY_FRAMES)
        }
        return Step.Failed(ActionError.Timeout("couldn't erase the current text"))
    }

    /** Types [char], switching page first when needed, and checks the text is then [expected]. */
    private fun type(context: PlanContext, char: Char, expected: String): Step<GameState> {
        val key = "key:$char"
        repeat(PAGES) {
            val state = context.navigator.settle()
            val keyboard = state.screen as? Screen.Keyboard ?: return Step.Failed(ActionError.UnexpectedScreen("the naming keyboard", state.screen.toString()))
            if (keyboard.entries.any { it.id == key && it.selectable }) {
                return context.navigator.choose(Screen.Keyboard::class, "'$char'") { it.id == key }.andThen {
                    val after = context.navigator.settle().screen as? Screen.Keyboard
                    if (after?.buffer == expected) Step.Done(context.state())
                    else Step.Failed(ActionError.VerificationFailed("typing '$char'", expected, after?.buffer ?: "no keyboard", 0))
                }
            }
            // Not on this page: go to the next one (upper → lower → others).
            val next = PAGE_ORDER[(PAGE_ORDER.indexOf(keyboard.page) + 1) % PAGE_ORDER.size]
            val switched = context.navigator.choose(Screen.Keyboard::class, "page $next") { it.id == "page:$next" }
            if (switched is Step.Failed) return switched
            context.navigator.advanceUntil(PAGE_WAITS) { (it.screen as? Screen.Keyboard)?.page == next }
        }
        return Step.Failed(ActionError.NotOnScreen("'$char'", "the keyboard", PAGE_ORDER))
    }

    private val PAGE_ORDER = listOf("upper", "lower", "others")
    private const val PAGES = 3
    private const val MAX_KEYS = 16
    private const val KEY_FRAMES = 30
    private const val PAGE_WAITS = 20
}
