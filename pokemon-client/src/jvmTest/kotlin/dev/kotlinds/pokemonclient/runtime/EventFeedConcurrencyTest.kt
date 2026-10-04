package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.state.EventLog
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The game records on its own thread while the agent's answer is built on another (and the UI thread notes human
 * inputs): every event must reach the agent exactly once, none dropped by a race.
 */
class EventFeedConcurrencyTest {
    @Test
    fun `events recorded while answers are built all reach the agent once`() {
        val log = EventLog(capacity = 1_000_000)
        val feed = EventFeed(log, autoConfirm = false)
        val total = 40_000
        // Two writers, like the console thread (messages) and the UI thread (human inputs).
        val writers = (0 until 2).map { w ->
            thread { repeat(total / 2) { i -> log.append { GameEvent.TextShown(it, 0, TextSource.BATTLE, null, "$w:$i") } } }
        }
        val received = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 20_000 // a lost event would otherwise keep the loop waiting
        while ((writers.any { it.isAlive } || received.size < total) && System.currentTimeMillis() < deadline) {
            received += feed.take().events.filterIsInstance<GameEvent.TextShown>().map { it.text }
            feed.confirm()
            if (writers.none { it.isAlive } && log.lastSeq.toInt() == total && received.size >= total) break
        }
        writers.forEach { it.join() }
        received += feed.take().events.filterIsInstance<GameEvent.TextShown>().map { it.text }
        assertEquals(total, log.lastSeq.toInt(), "an append was lost")
        assertEquals(total, received.size, "events given twice or never")
        assertEquals(total, received.toSet().size)
    }
}
