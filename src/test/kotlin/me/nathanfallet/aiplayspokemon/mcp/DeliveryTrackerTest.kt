package me.nathanfallet.aiplayspokemon.mcp

import dev.kotlinds.pokemonclient.runtime.EventFeed
import dev.kotlinds.pokemonclient.state.EventLog
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When an MCP answer counts as delivered, with the event feed it confirms (the `messages_repeated` rule). */
class DeliveryTrackerTest {
    private var clock = 0L
    private val log = EventLog()
    private val feed = EventFeed(log, autoConfirm = false)
    private val tracker = DeliveryTracker(confirm = feed::confirm, now = { clock })

    private fun text(t: String) = log.append { GameEvent.TextShown(it, 0, TextSource.BATTLE, null, t) }

    /** One call arriving at [arrival], lasting [seconds], answering what the feed holds; [cancelled] by the client. */
    private fun call(seconds: Long, cancelled: Boolean = false, progressEvery: Long? = null, arrival: Long = clock): EventFeed.Batch {
        tracker.arrived(arrival)
        val start = clock
        val call = tracker.started()
        if (progressEvery != null) {
            var t = progressEvery
            while (t < seconds) {
                clock = start + t * 1000
                tracker.alive(call)
                t += progressEvery
            }
        }
        clock = start + seconds * 1000
        val batch = feed.take()
        tracker.answered(call, cancelled)
        return batch
    }

    @Test
    fun aLongChainAnsweredWithinTheClientTimeoutIsNotRepeated() {
        text("Go! PILOSWINE!")
        call(55) // a chain that ended in not_done after ~55 s: received
        clock += 5_000
        text("The foe's GOLDUCK fainted!")
        val next = call(2)
        assertFalse(next.repeated)
        assertEquals(listOf("The foe's GOLDUCK fainted!"), next.events.map { (it as GameEvent.TextShown).text })
    }

    @Test
    fun aCallTheClientCancelledIsRepeated() {
        text("ACE used Hyper Potion!")
        call(70, cancelled = true) // notifications/cancelled: the client's timeout
        clock += 3_000
        val next = call(1)
        assertTrue(next.repeated)
        assertEquals(1, next.events.size)
    }

    @Test
    fun anAnswerLaterThanTheClientTimeoutIsRepeated() {
        text("a")
        call(65)
        clock += 1_000
        assertTrue(call(1).repeated)
    }

    @Test
    fun progressNotificationsKeepALongCallAlive() {
        text("a")
        call(80, progressEvery = 5) // a sign of life every 5 s: the client restarted its timeout each time
        clock += 1_000
        assertFalse(call(1).repeated)
    }

    @Test
    fun aCallArrivingWhileThePreviousStillRanMeansTheClientGaveUp() {
        text("a")
        val start = clock
        call(45)
        // The next call had arrived 30 s after the previous one started (the client's own timeout of 30 s), and waited.
        clock += 1_000
        assertTrue(call(1, arrival = start + 30_000).repeated)
    }

    @Test
    fun aCallMadeInParallelDoesNotLoseThePreviousAnswer() {
        text("a")
        val start = clock
        call(20)
        clock += 1_000
        assertFalse(call(1, arrival = start + 200).repeated)
    }

    @Test
    fun theLastAnswerIsOnlyConfirmedByTheNextCall() {
        text("a")
        call(1)
        // No call since: nothing confirmed yet, the feed still holds it.
        assertEquals(1, feed.take().events.size)
    }
}
