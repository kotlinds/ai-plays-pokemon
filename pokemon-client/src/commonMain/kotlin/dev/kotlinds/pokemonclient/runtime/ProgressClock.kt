package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.state.GameState
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * When the game last made progress: new text, a menu or its cursor moving, a step, HP changing... ([progressed],
 * called by the [Recorder] on every change it sees, and by the action loop at each step).
 *
 * It lets a long action be judged on inactivity rather than on its total length ([idle]: a chain goes on while things
 * keep happening, see `ChainRunner`), and tells a remote agent that its call is still alive ([ticks], [note]: the MCP
 * server turns them into progress notifications).
 *
 * Written on the console thread, read from others: plain volatile fields (a stale read only delays a tick).
 */
class ProgressClock(private val timeSource: TimeSource = TimeSource.Monotonic) {

    @Volatile
    private var last = timeSource.markNow()

    /** Number of progress events so far (only grows). */
    @Volatile
    var ticks: Long = 0
        private set

    /** What the last progress was, when it says something to a person ("Go! PILOSWINE!", "step 2/3: attack"). */
    @Volatile
    var note: String? = null
        private set

    /** The game made progress now; [what] describes it when it is worth showing. */
    fun progressed(what: String? = null) {
        last = timeSource.markNow()
        ticks++
        if (what != null) note = what
    }

    /** How long nothing has progressed. */
    val idle: Duration get() = last.elapsedNow()

    companion object {
        /**
         * What a player would see change between two readings: the screen (text, menu, cursor), the position, the
         * battle (who is out, HP, the message) and the party's HP. Two states with the same key look the same.
         */
        fun key(state: GameState): Any = listOf(
            state.screen,
            state.field?.let { Triple(it.mapId, it.x, it.y) },
            state.battle?.let { b -> b.message to b.battlers.map { Triple(it.ref, it.species.id, it.hp) } },
            state.party.map { it.hp },
        )
    }
}
