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
    /** The outcomes of the acts whose answer isn't known to have arrived (`previous_calls`), like GameSession's. */
    private val unanswered = me.nathanfallet.aiplayspokemon.agent.UnansweredCalls()
    private val verdicts = mutableListOf<DeliveryVerdict>()
    private val tracker = DeliveryTracker(
        confirm = { feed.confirm(); unanswered.delivered() },
        uncertain = feed::confirm,
        onVerdict = { verdicts += it },
        now = { clock },
    )

    private fun text(t: String) = log.append { GameEvent.TextShown(it, 0, TextSource.BATTLE, null, t) }

    /** One call arriving at [arrival], lasting [seconds], answering what the feed holds; [cancelled] by the client. */
    private fun call(seconds: Long, cancelled: Boolean = false, progressEvery: Long? = null, arrival: Long = clock): EventFeed.Batch {
        tracker.arrived(arrival)
        val start = clock
        val call = tracker.started("act")
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
    fun aClientThatGaveUpWithoutCancellingIsNoticedEvenWhileProgressWasSent() {
        text("a")
        val start = clock
        // A 150 s go_to with progress every 5 s; the client's HTTP request timed out at 60 s without a cancellation,
        // and the agent's next call arrived then, waiting for the lock.
        call(150, progressEvery = 5)
        clock += 1_000
        assertTrue(call(1, arrival = start + 60_000).repeated)
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
    fun aLongChainEndedBeforeTheNextCallGivesItsOutcomeAgainButNotItsMessages() {
        // NOTES (map randomizer run): a flee, Teleport through the menus and three go_to; the client timed out (no
        // cancellation), the chain ended ~2 min after it started with progress all along, and the agent's next call came
        // after that: previous_calls was missing. Whether the client got it can't be told: its outcome is given again.
        text("Got away safely!")
        call(130, progressEvery = 5)
        unanswered.answered(listOf("run", "use_field_move(teleport)", "go_to(warp:3)"), kotlinx.serialization.json.buildJsonObject { put("ok", kotlinx.serialization.json.JsonPrimitive(true)) })
        clock += 20_000
        val next = call(1)
        assertFalse(next.repeated, "its messages: most clients got them")
        assertTrue("previous_calls" in unanswered.describe(), "its outcome: given again")
        // The answer after that one arrived: nothing is repeated any more.
        clock += 1_000
        call(1)
        assertTrue(unanswered.describe().isEmpty())
    }

    @Test
    fun theLastAnswerIsOnlyConfirmedByTheNextCall() {
        text("a")
        call(1)
        // No call since: nothing confirmed yet, the feed still holds it.
        assertEquals(1, feed.take().events.size)
    }

    // region The measurement: every verdict "lost" / "uncertain" is logged with the call and its durations

    private fun reasons() = verdicts.map { it.reason }

    @Test
    fun aCancelledCallIsLoggedAsLostWithItsDurations() {
        call(70, cancelled = true)
        clock += 3_000
        call(1)
        assertEquals(listOf(DeliveryVerdict.Reason.CANCELLED), reasons())
        val verdict = verdicts.single()
        assertEquals("act", verdict.tool)
        assertEquals(DeliveryVerdict.Verdict.LOST, verdict.verdict)
        assertEquals(70_000, verdict.ranMillis)
        assertEquals(73_000, verdict.nextCallAfterMillis)
        assertEquals(1, verdict.judged)
    }

    @Test
    fun eachLostRuleAndTheUncertainOneAreLoggedWithTheirReason() {
        call(65) // silent past the client's timeout
        clock += 1_000
        val start = clock
        call(45) // the next call arrived 30 s after its start, while it ran
        clock += 1_000
        call(80, progressEvery = 5, arrival = start + 30_000) // kept alive by progress past the timeout
        clock += 1_000
        call(1)
        assertEquals(
            listOf(DeliveryVerdict.Reason.SILENT_PAST_TIMEOUT, DeliveryVerdict.Reason.NEXT_CALL_WHILE_RUNNING, DeliveryVerdict.Reason.KEPT_ALIVE_PAST_TIMEOUT),
            reasons(),
        )
        assertEquals(30_000, verdicts[1].nextCallAfterMillis)
        assertEquals(DeliveryVerdict.Verdict.UNCERTAIN, verdicts[2].verdict)
        assertEquals(listOf(1, 2, 3), verdicts.map { it.judged })
    }

    @Test
    fun aConfirmedAnswerIsNotLoggedButCounted() {
        call(2)
        clock += 1_000
        call(2)
        clock += 1_000
        call(70, cancelled = true)
        clock += 1_000
        call(1)
        // Only the lost one is logged; the two confirmed before it count in `judged`.
        assertEquals(listOf(3), verdicts.map { it.judged })
    }

    @Test
    fun theDeliveryLogWritesOneJsonLinePerVerdict() {
        val directory = java.nio.file.Files.createTempDirectory("delivery-log")
        try {
            val file = directory.resolve("runs").resolve("delivery.jsonl")
            val log = DeliveryLog(file)
            call(70, cancelled = true)
            clock += 1_000
            call(65)
            clock += 1_000
            call(1)
            verdicts.forEach(log::record)
            val lines = java.nio.file.Files.readAllLines(file)
            assertEquals(2, lines.size)
            val first = kotlinx.serialization.json.Json.decodeFromString(DeliveryVerdict.serializer(), lines[0])
            assertEquals(verdicts[0], first)
            assertTrue("\"reason\":\"SILENT_PAST_TIMEOUT\"" in lines[1], lines[1])
            assertTrue(verdicts[0].summary.startsWith("[delivery] lost (CANCELLED): act ran 70 s"), verdicts[0].summary)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    // endregion
}
