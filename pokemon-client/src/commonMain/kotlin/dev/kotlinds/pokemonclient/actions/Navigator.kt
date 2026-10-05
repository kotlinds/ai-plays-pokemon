package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.sameAs
import kotlin.reflect.KClass

/**
 * Moves cursors and confirms choices, the only place where menu presses happen, so the verification rule holds
 * everywhere: read where the cursor is, move one tap at a time re-reading after each tap, and confirm only when the
 * cursor is on the target. After [RetryPolicy.maxCorrections] unexpected moves it gives up with an explicit error
 * (what was expected, what is on screen) instead of confirming something else.
 */
class Navigator(
    private val scope: ActionScope,
    private val game: PokemonGame,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    /** The current state, decoded from this frame's RAM. */
    fun state(): GameState = game.state(scope.memory())

    /**
     * Waits until the game expects input (two consecutive polls), at most [maxFrames] frames, and returns the state.
     * Text being printed is left to print: only [Awaiting.INPUT] counts.
     */
    fun settle(maxFrames: Int = 600, stablePolls: Int = 2, pollFrames: Int = 2): GameState {
        var state = state()
        var ready = 0
        var waited = 0
        while (waited < maxFrames) {
            ready = if (state.screen.awaiting == Awaiting.INPUT) ready + 1 else 0
            if (ready >= stablePolls) return state
            scope.step(pollFrames)
            waited += pollFrames
            state = state()
        }
        return state
    }

    /**
     * Moves the cursor of the current screen (which must be a [S]) onto the first entry matching [target].
     * Returns the screen with the cursor on it, or a typed error.
     */
    fun <S : Screen.Selectable> select(expect: KClass<S>, description: String, target: (Entry) -> Boolean): Step<S> {
        var corrections = 0
        var lastIndex: Int? = null
        repeat(MAX_TAPS) {
            val screen = settle().screen
            if (!expect.isInstance(screen)) return Step.Failed(ActionError.UnexpectedScreen(expect.simpleName ?: "?", screen.kind))
            @Suppress("UNCHECKED_CAST")
            screen as S
            val goal = screen.entries.indexOfFirst(target)
            if (goal < 0) return Step.Failed(ActionError.NotOnScreen(description, screen.kind, screen.entries.map { it.label }))
            if (!screen.entries[goal].selectable) return Step.Failed(ActionError.NotSelectable(description, screen.entries[goal].label))
            val cursor = screen.cursor
            if (cursor is Cursor.At && cursor.index == goal) return Step.Done(screen)
            // An unexpected move (the cursor isn't where the previous tap should have put it) counts as a correction.
            if (lastIndex != null && cursor is Cursor.At && cursor.index != lastIndex) corrections++
            if (corrections > retry.maxCorrections) {
                return Step.Failed(ActionError.VerificationFailed(description, expected = screen.entries[goal].label, actual = screen.currentLabel(), attempts = corrections))
            }
            val from = (cursor as? Cursor.At)?.index
            // A touch-only screen (no cursor, the D-pad moves nothing: Platinum's intro YES / NO, its Poké Ball): a key
            // press would not reveal a cursor (the game may even answer "use the touch screen"), so touch the entry.
            if (from == null && screen.entries[goal].touch != null && screen.touchOnly()) {
                return Step.Failed(ActionError.Unreachable(description, screen.entries[goal].label))
            }
            val button = if (from == null) REVEAL_BUTTON else firstStep(screen, from, goal)
                ?: return Step.Failed(ActionError.Unreachable(description, screen.entries[goal].label))
            lastIndex = from?.let { screen.topology.next(it, button) }
            scope.tap(button)
        }
        return Step.Failed(ActionError.VerificationFailed(description, expected = description, actual = "cursor never reached it", attempts = MAX_TAPS))
    }

    /**
     * Confirms the highlighted entry of a [Screen.Selectable] whose cursor must be on an entry matching [target]
     * (checked again right before pressing), then waits for the next screen.
     */
    fun confirm(description: String, target: (Entry) -> Boolean, button: Button = Button.A): Step<GameState> {
        val screen = settle().screen
        val selectable = screen as? Screen.Selectable ?: return Step.Failed(ActionError.UnexpectedScreen("a menu", screen.kind))
        val cursor = selectable.cursor
        val current = (cursor as? Cursor.At)?.let { selectable.entries.getOrNull(it.index) }
        if (current == null || !target(current)) {
            return Step.Failed(ActionError.VerificationFailed(description, expected = description, actual = current?.label ?: "no highlighted entry", attempts = 0))
        }
        scope.tap(button)
        awaitChange(screen)
        return Step.Done(settle())
    }

    /**
     * After a confirmation, lets the game run until the screen differs from [before] (a new screen, another cursor,
     * other entries), at most [maxFrames]: menus often stay drawn, still "waiting for input", while the next screen
     * fades in, and settling right away would return the old menu.
     */
    fun awaitChange(before: Screen, maxFrames: Int = CHANGE_FRAMES) {
        var waited = 0
        while (waited < maxFrames) {
            scope.step(2)
            waited += 2
            if (!state().screen.sameAs(before)) return
        }
    }

    /**
     * Selects then confirms: the common "pick this entry" step. An entry the D-pad can't reach but that has a touch
     * point (the Pokégear's Close, touch-only buttons) is touched instead, see [touchEntry].
     */
    fun <S : Screen.Selectable> choose(expect: KClass<S>, description: String, target: (Entry) -> Boolean): Step<GameState> =
        when (val selected = select(expect, description, target)) {
            is Step.Failed -> if (selected.error is ActionError.Unreachable) touchEntry(expect, description, target, selected.error) else Step.Failed(selected.error)
            is Step.Done -> confirm(description, target)
        }

    /**
     * Touches the entry matching [target] on a [S] screen (an entry only reachable by touch), then checks the touch
     * did something: the screen must change. A touch the game ignored is tried again, at most
     * [RetryPolicy.maxCorrections] times, then fails with an explicit error. Without a touch point, fails with
     * [unreachable].
     */
    fun <S : Screen.Selectable> touchEntry(expect: KClass<S>, description: String, target: (Entry) -> Boolean, unreachable: ActionError): Step<GameState> {
        repeat(retry.maxCorrections + 1) {
            val screen = settle().screen
            if (!expect.isInstance(screen)) return Step.Failed(ActionError.UnexpectedScreen(expect.simpleName ?: "?", screen.kind))
            screen as Screen.Selectable
            val entry = screen.entries.firstOrNull(target) ?: return Step.Failed(ActionError.NotOnScreen(description, screen.kind, screen.entries.map { it.label }))
            if (!entry.selectable) return Step.Failed(ActionError.NotSelectable(description, entry.label))
            val point = entry.touch ?: return Step.Failed(unreachable)
            scope.touch(point)
            awaitChange(screen)
            val after = settle()
            if (!after.screen.sameAs(screen)) return Step.Done(after)
        }
        return Step.Failed(ActionError.VerificationFailed(description, expected = "the screen to react to the touch", actual = "nothing changed", attempts = retry.maxCorrections + 1))
    }

    /**
     * Presses A to advance messages until [stop] matches the state, stopping on ANY menu or choice it doesn't
     * expect (never a burst of blind A presses). Returns the final state. [stop] must only look at the state:
     * acting inside it (answering a question...) would leave this loop judging a screen that is gone.
     */
    fun advanceUntil(maxPresses: Int = 60, stop: (GameState) -> Boolean): Step<GameState> {
        repeat(maxPresses) {
            val state = settle()
            if (stop(state)) return Step.Done(state)
            when (val screen = state.screen) {
                is Screen.Dialogue, is Screen.PressToContinue -> scope.tap(Button.A)
                is Screen.Battle, is Screen.Animation, is Screen.Evolution -> scope.step(10)
                else -> return Step.Failed(ActionError.UnexpectedScreen("a message", screen.kind))
            }
        }
        return Step.Failed(ActionError.Timeout("messages didn't end after $maxPresses presses"))
    }

    /** First button of a shortest path from [from] to [to] along the screen's topology (BFS), or null. */
    private fun firstStep(screen: Screen.Selectable, from: Int, to: Int): Button? {
        val first = mutableMapOf<Int, Button>()
        val queue = ArrayDeque(listOf(from))
        val seen = mutableSetOf(from)
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            for (button in DIRECTIONS) {
                val next = screen.topology.next(at, button) ?: continue
                if (!seen.add(next)) continue
                first[next] = first[at] ?: button
                if (next == to) return first[next]
                queue.addLast(next)
            }
        }
        return null
    }

    /** True when no D-pad button moves the cursor anywhere on this screen: it is driven by touch only. */
    private fun Screen.Selectable.touchOnly(): Boolean =
        entries.indices.all { i -> DIRECTIONS.all { topology.next(i, it) == null } }

    private fun Screen.Selectable.currentLabel() =
        (cursor as? Cursor.At)?.let { entries.getOrNull(it.index)?.label } ?: "hidden cursor"

    private companion object {
        val DIRECTIONS = listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT)

        /** When the cursor is hidden, the first D-pad press only shows it (battle menus, touch menus). */
        val REVEAL_BUTTON = Button.UP
        const val MAX_TAPS = 40

        /** Two seconds: long enough for any menu transition, short enough when a confirmation changes nothing. */
        const val CHANGE_FRAMES = 120
    }
}

/** How many unexpected cursor moves the navigator tolerates before giving up (the "3 tries" rule). */
data class RetryPolicy(val maxCorrections: Int = 3)

/** The outcome of a navigation step. */
sealed interface Step<out T> {
    data class Done<T>(val value: T) : Step<T>
    data class Failed(val error: ActionError) : Step<Nothing>
}
