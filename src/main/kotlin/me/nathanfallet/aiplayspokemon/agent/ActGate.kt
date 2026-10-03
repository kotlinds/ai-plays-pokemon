package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.actions.ActionError

/** Whether an agent's action must be refused right now, and the typed reason given to the agent. */
object ActGate {

    /**
     * [humanPlaying]: a human is really pressing keys (see [me.nathanfallet.aiplayspokemon.emulator.HumanActivity]);
     * [userPaused]: the person watching pressed Pause. The game running freely is no reason to refuse.
     */
    fun refusal(humanPlaying: Boolean, userPaused: Boolean): ActionError? = when {
        userPaused -> ActionError.PausedByHuman
        humanPlaying -> ActionError.HumanDriving
        else -> null
    }
}
