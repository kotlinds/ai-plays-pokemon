package dev.kotlinds.pokemonclient.state

import dev.kotlinds.pokemonclient.console.TouchPoint

/**
 * A phone call ringing while the player walks: the caller's icon shows on the bottom screen and the call isn't
 * answered yet (the player may still walk; the game hangs up by itself after about 30 seconds).
 */
data class IncomingCall(
    /** Stable id of the caller: `contact:<id>` (the game's phone contact id, language independent). */
    val callerId: String,
    /** The caller's name, for display. */
    val caller: String,
    /** Where to touch the bottom screen to answer (the Pokégear button), null when unknown. */
    val answer: TouchPoint? = null,
)
