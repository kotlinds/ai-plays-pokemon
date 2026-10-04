package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen

/** Recipes acting on the console / game as a whole (not a screen of the story). */
internal object SystemPlans {

    /** The soft reset of the DS Pokémon games: L + R + START + SELECT held together (src/main.c main loop). */
    private val RESET_COMBO = InputFrame(setOf(Button.L, Button.R, Button.START, Button.SELECT))

    /**
     * Soft reset, then back to the saved game: holds L + R + START + SELECT until the game restarts (it refuses while
     * it saves: `gSystem.softResetDisabled`), skips the intro and the title screen with A, picks CONTINUE on the main
     * menu by its id, and waits until the player can walk. Everything since the last save is lost.
     */
    val softReset = ActionPlan<GameAction.SoftReset> { _, context ->
        val before = context.state().field
        val restarted = context.scope.stepUntil(RESET_FRAMES, RESET_COMBO) { memory ->
            val state = context.game.state(memory)
            state.field == null && state.screen is Screen.Intro
        }
        context.scope.step(RELEASE_FRAMES)
        if (!restarted) {
            return@ActionPlan ActionOutcome.Failed(ActionError.Timeout("the game didn't restart (it refuses while saving): try again in a moment"))
        }
        val start = context.scope.framesUsed
        while (context.scope.framesUsed - start < MAX_FRAMES) {
            val state = context.navigator.settle(maxFrames = ROUND_FRAMES)
            val screen = state.screen
            when {
                screen is Screen.ListMenu && screen.kind == MenuKind.MAIN_MENU -> {
                    val chosen = context.navigator.choose(Screen.ListMenu::class, "CONTINUE") { it.id == CONTINUE }
                    if (chosen is Step.Failed) return@ActionPlan ActionOutcome.Failed(chosen.error)
                }
                // The intro movie and the title screen: A goes on (the title only takes it once its animation is done).
                screen is Screen.Intro && screen.awaiting == Awaiting.INPUT -> {
                    context.scope.tap(Button.A)
                    context.scope.step(AFTER_TAP_FRAMES)
                }
                screen is Screen.Overworld && screen.awaiting == Awaiting.INPUT && state.field != null -> {
                    val field = state.field
                    val was = before?.let { " (you were at ${it.mapName} ${it.x},${it.y})" } ?: ""
                    return@ActionPlan ActionOutcome.Done("reset: back to the last save, at ${field.mapName} ${field.x},${field.y}$was")
                }
                screen is Screen.Selectable || screen is Screen.Dialogue && screen.awaiting == Awaiting.INPUT ->
                    return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the title screen, main menu or overworld", screen.kind))
                else -> context.scope.step(WAIT_FRAMES)
            }
        }
        ActionOutcome.Failed(ActionError.Timeout("the game didn't get back to the saved game after the reset"))
    }

    private const val CONTINUE = "option:continue"

    /** The game reads the keys every frame: it restarts within a few frames of the combo. */
    private const val RESET_FRAMES = 60
    private const val RELEASE_FRAMES = 4
    /** From the reset to the field: about 20 s of intro, title, main menu and loading (60 frames a second). */
    private const val MAX_FRAMES = 3600
    private const val AFTER_TAP_FRAMES = 30
    private const val ROUND_FRAMES = 300
    private const val WAIT_FRAMES = 10
}
