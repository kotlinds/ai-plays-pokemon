package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.state.EventLog
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventFeedTest {
    private fun EventLog.text(text: String) = append { GameEvent.TextShown(it, 0, TextSource.BATTLE, null, text) }
    private fun EventFeed.Batch.texts() = events.filterIsInstance<GameEvent.TextShown>().map { it.text }

    @Test
    fun `events before the feed was created are not given`() {
        val log = EventLog().apply { text("old") }
        val feed = EventFeed(log)
        log.text("new")
        assertEquals(listOf("new"), feed.take().texts())
    }

    @Test
    fun `auto confirmed events are given once`() {
        val log = EventLog()
        val feed = EventFeed(log)
        log.text("a")
        assertEquals(listOf("a"), feed.take().texts())
        assertEquals(emptyList(), feed.take().texts())
    }

    @Test
    fun `unconfirmed events are given again with the next response`() {
        val log = EventLog()
        val feed = EventFeed(log, autoConfirm = false)
        log.text("fainted")
        val lost = feed.take() // the client timed out: never confirmed
        assertEquals(listOf("fainted"), lost.texts())
        assertFalse(lost.repeated)
        log.text("gained EXP")
        val next = feed.take()
        assertEquals(listOf("fainted", "gained EXP"), next.texts())
        assertTrue(next.repeated)
        feed.confirm()
        log.text("money")
        val after = feed.take()
        assertEquals(listOf("money"), after.texts())
        assertFalse(after.repeated)
    }

    @Test
    fun `an unconfirmed empty response is not a repetition`() {
        val log = EventLog()
        val feed = EventFeed(log, autoConfirm = false)
        feed.take()
        log.text("x")
        assertFalse(feed.take().repeated)
    }
}
