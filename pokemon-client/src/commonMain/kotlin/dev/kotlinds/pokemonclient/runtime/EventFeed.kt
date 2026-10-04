package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.state.EventLog
import dev.kotlinds.pokemonclient.state.GameEvent

/**
 * What one agent has been told of an [EventLog]: [take] hands out every event the agent hasn't received yet, and
 * they are only forgotten once the response carrying them is known to have reached the agent ([confirm]).
 *
 * An agent's call can time out on its side while the action still finishes here (a long `then` chain, a slow
 * menu): the response is then lost, and with it what the game said. Unconfirmed events are given again with the
 * next response ([Batch.repeated]), so nothing shown on screen is ever dropped.
 *
 * With [autoConfirm] (agents that can't lose a response, like our own loop), every [take] is confirmed at once.
 */
class EventFeed(private val log: EventLog, private val autoConfirm: Boolean = true) {
    /** Last event known to have reached the agent. */
    private var confirmed = log.lastSeq

    /** Last event handed out by [take], not confirmed yet (null when everything handed out is confirmed). */
    private var offered: Long? = null

    /** Events handed out at once: [events] oldest first; [repeated] when some were already in an unconfirmed response. */
    data class Batch(val events: List<GameEvent>, val repeated: Boolean)

    /** Every event after the last confirmed one (including those of an unconfirmed previous response). */
    fun take(): Batch {
        val repeated = offered != null && offered!! > confirmed
        val events = log.since(confirmed)
        offered = log.lastSeq
        if (autoConfirm) confirm()
        return Batch(events, repeated)
    }

    /** The last [take] reached the agent: its events won't be given again. */
    fun confirm() {
        offered?.let { confirmed = maxOf(confirmed, it) }
        offered = null
    }
}
