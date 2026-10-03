package dev.kotlinds.pokemonclient.state

import kotlin.test.Test
import kotlin.test.assertEquals

class EventLogTest {

    private fun EventLog.add(n: Int) = repeat(n) { append { seq -> GameEvent.HumanInput(seq, frame = seq * 10) } }

    @Test
    fun sequenceNumbersIncreaseFromOne() {
        val log = EventLog()
        assertEquals(0, log.lastSeq)
        log.add(3)
        assertEquals(listOf(1L, 2L, 3L), log.since(0).map { it.seq })
        assertEquals(3, log.lastSeq)
    }

    @Test
    fun sinceIsExclusiveAndPerConsumer() {
        val log = EventLog()
        log.add(5)
        assertEquals(listOf(4L, 5L), log.since(3).map { it.seq })
        assertEquals(emptyList(), log.since(5))
    }

    @Test
    fun oldEventsAreDroppedButNumbersKeepGoing() {
        val log = EventLog(capacity = 3)
        log.add(5)
        assertEquals(listOf(3L, 4L, 5L), log.since(0).map { it.seq })
        assertEquals(5, log.lastSeq)
        log.add(1)
        assertEquals(6, log.lastSeq)
    }
}
