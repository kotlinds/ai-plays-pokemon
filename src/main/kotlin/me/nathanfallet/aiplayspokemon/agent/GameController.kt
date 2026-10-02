package me.nathanfallet.aiplayspokemon.agent

import me.nathanfallet.aiplayspokemon.emulator.Button
import me.nathanfallet.aiplayspokemon.emulator.Emulator
import me.nathanfallet.aiplayspokemon.emulator.InputSource
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.PokemonGame
import me.nathanfallet.aiplayspokemon.game.RamMemory

/**
 * The agent's hands on the console: reads the game and presses buttons with the right timing.
 *
 * With [PlayerSettings.waitForReaction], a press is held until something visible changes (a step
 * starts, the player turns, a cursor moves, text advances...) and the controller then waits until
 * the game expects input again, so the next observation shows the result of the press instead of an
 * animation in progress. Without it, presses use a fixed timing.
 */
class GameController(
    private val emulator: Emulator,
    private val game: PokemonGame,
    private val settings: () -> PlayerSettings,
) {
    /** Number of button presses made by the agent (a sequence or an assisted action counts each press). */
    var presses = 0
        private set

    suspend fun observe(): Observation = game.observe(RamMemory(emulator.readMainRam()))

    /**
     * Waits until the game expects input (or [maxFrames] elapsed) and returns what's on screen.
     * The game must report it on two consecutive polls: some screens accept input a few frames before
     * they finish drawing (or right before a fade starts).
     */
    suspend fun waitForInput(maxFrames: Int = 600): Observation {
        var observation = observe()
        var waited = 0
        var ready = 0
        while (waited < maxFrames) {
            ready = if (observation.awaitingInput) ready + 1 else 0
            if (ready >= 2) break
            emulator.awaitFrames(POLL_FRAMES)
            waited += POLL_FRAMES
            observation = observe()
        }
        return observation
    }

    /** Presses [button] once and returns the observation after the game reacted. */
    suspend fun press(button: Button): Observation {
        presses++
        if (!settings().waitForReaction) {
            hold(button, FIXED_HOLD_FRAMES)
            emulator.awaitFrames(FIXED_SETTLE_FRAMES)
            return observe()
        }

        val before = observe()
        if (button in DIRECTIONS) {
            // Hold until the player turns/starts a step or the cursor moves, then release: one press = one effect.
            emulator.setButtons(InputSource.AGENT, setOf(button))
            var held = 0
            while (held < MAX_DIRECTION_HOLD_FRAMES) {
                emulator.awaitFrames(POLL_FRAMES)
                held += POLL_FRAMES
                if (observe().let { it.facts != before.facts || it.location != before.location }) break
            }
            emulator.setButtons(InputSource.AGENT, emptySet())
        } else {
            hold(button, TAP_FRAMES)
        }
        emulator.awaitFrames(POLL_FRAMES)
        return waitForInput(MAX_REACTION_FRAMES)
    }

    /** Does nothing for a while (or until the game expects input again). */
    suspend fun idle(frames: Int = WAIT_FRAMES): Observation {
        emulator.awaitFrames(frames)
        return if (settings().waitForReaction) waitForInput(MAX_REACTION_FRAMES) else observe()
    }

    /**
     * Runs [block] (the AI thinking) with the game frozen when [PlayerSettings.pauseWhileThinking]
     * is set, so the AI decides on the screen it saw. A pause made by the user is kept.
     */
    suspend fun <T> thinking(block: suspend () -> T): T {
        if (!settings().pauseWhileThinking || !emulator.status.value.running) return block()
        emulator.pause()
        try {
            return block()
        } finally {
            emulator.resume()
        }
    }

    fun releaseAll() = emulator.setButtons(InputSource.AGENT, emptySet())

    private suspend fun hold(button: Button, frames: Int) {
        emulator.setButtons(InputSource.AGENT, setOf(button))
        emulator.awaitFrames(frames)
        emulator.setButtons(InputSource.AGENT, emptySet())
    }

    private companion object {
        val DIRECTIONS = setOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT)
        const val POLL_FRAMES = 2
        const val TAP_FRAMES = 6
        const val MAX_DIRECTION_HOLD_FRAMES = 20
        const val MAX_REACTION_FRAMES = 300
        const val WAIT_FRAMES = 30
        const val FIXED_HOLD_FRAMES = 16
        const val FIXED_SETTLE_FRAMES = 8
    }
}
