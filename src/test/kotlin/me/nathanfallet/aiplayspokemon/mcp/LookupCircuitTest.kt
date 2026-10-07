package me.nathanfallet.aiplayspokemon.mcp

import dev.kotlinds.pokemonclient.data.LookupKind
import dev.kotlinds.pokemonclient.runtime.EventFeed
import dev.kotlinds.pokemonclient.runtime.ProgressClock
import dev.kotlinds.pokemonclient.state.EventLog
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.console.Frame
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.agent.UnansweredCalls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `lookup` goes through the server's common circuit ([ToolCalls.aside], under the call lock, like `screenshot`): its
 * arrival gives the verdict on the previous answer, and its own answer, which carries no message, event or act's
 * outcome, never confirms (nor repeats) what an earlier answer carried. Its arguments and answers are unchanged.
 */
class LookupCircuitTest {
    private var clock = 0L
    private val log = EventLog()
    private val feed = EventFeed(log, autoConfirm = false)
    private val unanswered = UnansweredCalls()
    private val tracker = DeliveryTracker(confirm = { feed.confirm(); unanswered.delivered() }, uncertain = feed::confirm, now = { clock })
    private val calls = ToolCalls(ProgressClock(), tracker)

    private fun text(t: String) = log.append { GameEvent.TextShown(it, 0, TextSource.FIELD, null, t) }

    /**
     * An act arrived at [arrival] answering what the feed holds (its outcome kept until delivered), lasting [seconds];
     * [cancelled] by the client; [alive]: progress of the game sent to its end (a long go_to).
     */
    private fun act(seconds: Long, cancelled: Boolean = false, arrival: Long = clock, alive: Boolean = false): EventFeed.Batch {
        tracker.arrived(arrival)
        val call = tracker.started()
        clock += seconds * 1000
        if (alive) tracker.alive(call)
        val batch = feed.take()
        unanswered.answered(listOf("save_game"), buildJsonObject { put("ok", true) })
        tracker.answered(call, cancelled)
        return batch
    }

    private fun lookup(arrival: Long = clock): String = runBlocking { calls.aside(arrival) { "species 25" } }.also { clock += 100 }

    @Test
    fun aLookupAfterALostAnswerConfirmsNothing() {
        text("Saving... Don't turn off the power.")
        act(70, cancelled = true) // the client's timeout: the answer never arrived
        clock += 2_000
        assertEquals("species 25", lookup())
        clock += 2_000
        // The next answer gives the save's messages and outcome again: the lookup between carried neither.
        val next = act(1)
        assertTrue(next.repeated)
        assertEquals(1, next.events.size)
    }

    @Test
    fun aLookupArrivingConfirmsTheAnswerBeforeIt() {
        text("a")
        act(2)
        clock += 1_000
        lookup()
        // The act's answer arrived (the client called again): nothing repeated, no previous_calls.
        assertTrue(unanswered.describe().isEmpty())
        clock += 1_000
        assertFalse(act(1).repeated)
    }

    /**
     * The review's case: a lookup made in parallel with a 90 s act (kept alive by progress) waits behind it; the client
     * gives up on the act at 60 s without cancelling and acts again at 65 s. The lookup, arrived before the act's
     * answer, says nothing about it: the next act, arrived during it, takes it as lost and its messages come again.
     */
    @Test
    fun aLookupQueuedBehindAnActLeavesItsVerdictToTheNextCall() {
        text("Saving... Don't turn off the power.")
        val started = clock
        act(90, alive = true) // the lookup (at 1 s) and the next act (at 65 s) wait for the lock meanwhile
        assertEquals("species 25", lookup(arrival = started + 1_000))
        val next = act(1, arrival = started + 65_000)
        assertTrue(next.repeated)
        assertEquals(1, next.events.size)
    }

    /** The other side: the same lookup during an act whose next call came only after it ended: confirmed by that call. */
    @Test
    fun aLookupQueuedBehindAnActLetsTheNextCallConfirmIt() {
        text("a")
        val started = clock
        act(2)
        lookup(arrival = started + 500)
        assertTrue(unanswered.describe().isNotEmpty(), "the act's outcome is still waiting for a verdict")
        clock += 1_000
        assertFalse(act(1).repeated)
    }

    @Test
    fun theLookupArgumentsAreReadAsBefore() {
        fun request(vararg pairs: Pair<String, String>) = GameMcpServer.lookupRequest(JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) }))
        assertEquals(LookupKind.SPECIES to "species:25", request("kind" to "Species", "id" to "species:25").getOrThrow())
        // Encounters alone has a default id (the current map).
        assertEquals(LookupKind.ENCOUNTERS to "", request("kind" to "encounters").getOrThrow())
        assertEquals("`id` is missing", request("kind" to "move").exceptionOrNull()?.message)
        assertEquals("`kind` must be one of ${LookupKind.entries.joinToString { it.name.lowercase() }}", request("kind" to "berry", "id" to "x").exceptionOrNull()?.message)
    }

    // region The tools themselves, over HTTP (GameMcpServer.lookupCall / screenshotCall, what its handlers run)

    /** Verdicts given by a real-time tracker (client timeout 300 ms, no client times out before 100 ms). */
    private class Verdicts {
        @Volatile var confirmed = 0
        @Volatile var uncertain = 0
    }

    /**
     * An MCP server with the server's lock and circuit: `act` (an action of [actMillis], its game moving to its end:
     * a long go_to; it shows [shown] once done), `lookup` and `screenshot` (the frame it reads, counted when there is
     * one) through the server's own tool functions.
     */
    private fun server(verdicts: Verdicts, actMillis: Long, counted: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList(), shown: (Long) -> Frame? = { null }): McpTestServer {
        val clock = ProgressClock()
        val calls = ToolCalls(clock, DeliveryTracker(confirm = { verdicts.confirmed++ }, uncertain = { verdicts.uncertain++ }, clientTimeoutMillis = 300, minClientTimeoutMillis = 100), period = 100.milliseconds)
        val lock = Mutex()
        val frame = java.util.concurrent.atomic.AtomicReference<Frame?>(null)
        val acts = java.util.concurrent.atomic.AtomicLong()
        return McpTestServer {
            addTool(name = "act", description = "an action") { request ->
                val arrival = System.currentTimeMillis()
                lock.withLock {
                    calls.run(this, request, arrival, label = "go_to") {
                        repeat((actMillis / 50).toInt()) { delay(50); clock.progressed("step $it") }
                        frame.set(shown(acts.incrementAndGet()))
                        CallToolResult(content = listOf(TextContent("done")))
                    }
                }
            }
            addTool(name = "lookup", description = "game knowledge") { request ->
                GameMcpServer.lookupCall(calls, lock, this, request, System.currentTimeMillis()) { kind, id -> Result.success(buildJsonObject { put("kind", kind.name); put("id", id) }) }
            }
            addTool(name = "screenshot", description = "the screens") { request ->
                GameMcpServer.screenshotCall(calls, lock, this, request, System.currentTimeMillis(), count = { counted += 1 }, frame = { frame.get() }) { "png:${it.width}" }
            }
        }
    }

    /**
     * The review's case through the real `lookup` tool: made in parallel with a long act (alive to its end), it waits
     * behind it, sending heartbeats meanwhile so its client doesn't time out; the client gives up on the act and acts
     * again: that next act takes it as lost (no verdict from the lookup, arrived before the act's answer).
     */
    @Test
    fun theLookupToolWaitsBehindALongActWithHeartbeatsAndJudgesNothingItArrivedBefore() {
        val verdicts = Verdicts()
        server(verdicts, actMillis = 700).use { mcp ->
            val first = mcp.callAsync(2, "act", token = "tok-act")
            Thread.sleep(30)
            val lookup = mcp.callAsync(3, "lookup", token = "tok-lookup", arguments = """{"kind":"species","id":"species:25"}""")
            Thread.sleep(300)
            // The client gave up on the first act (no cancel) and acts again, while it still runs.
            val again = mcp.callAsync(4, "act")
            assertTrue("done" in first.get().body())
            val answer = lookup.get().body()
            assertTrue("species:25" in answer, answer)
            assertTrue("done" in again.get().body())
            val beats = mcp.progress("tok-lookup").mapNotNull { it.second["message"]?.jsonPrimitive?.content }
            assertTrue(beats.size >= 3, "heartbeats while waiting: $beats")
            assertTrue(beats.all { Regex("lookup: waiting for the call in progress, \\d+ s").matches(it) }, beats.toString())
            // The first act's answer: lost (taken so by the second act), neither confirmed nor uncertain.
            assertEquals(0, verdicts.confirmed)
            assertEquals(0, verdicts.uncertain)
        }
    }

    /** A lookup arriving after the act's answer: the client is there, the act's answer is confirmed (no heartbeat needed). */
    @Test
    fun theLookupToolAfterAnActConfirmsIt() {
        val verdicts = Verdicts()
        server(verdicts, actMillis = 100).use { mcp ->
            assertTrue("done" in mcp.call(2, "act").body())
            assertTrue("species:25" in mcp.call(3, "lookup", token = "tok-lookup", arguments = """{"kind":"species","id":"species:25"}""").body())
            assertEquals(1, verdicts.confirmed)
            assertTrue(mcp.progress("tok-lookup").isEmpty())
        }
    }

    /**
     * `screenshot` queued behind an act shows the screen the act led to (the frame is read once the lock is held), and
     * without any frame yet it still gives the verdict on the previous answer.
     */
    @Test
    fun theScreenshotToolReadsTheFrameAfterTheActQueuedBeforeItAndAlwaysGivesTheVerdict() {
        val counted = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val verdicts = Verdicts()
        server(verdicts, actMillis = 150, counted) { act -> if (act == 1L) null else Frame(act.toInt(), 1, IntArray(act.toInt())) }.use { mcp ->
            // No frame yet: the error, and the act's answer confirmed all the same.
            assertTrue("done" in mcp.call(2, "act").body())
            val none = mcp.call(3, "screenshot").body()
            assertTrue("No frame yet" in none, none)
            assertEquals(1, verdicts.confirmed)
            assertTrue(counted.isEmpty())
            // Queued behind the second act: the frame it shows is the one that act left (width 2), not the one before.
            val act = mcp.callAsync(4, "act")
            Thread.sleep(50)
            val shot = mcp.call(5, "screenshot").body()
            val acted = act.get().body()
            assertTrue("done" in acted, acted)
            assertTrue("png:2" in shot, shot)
            assertEquals(listOf(1), counted)
        }
    }

    // endregion
}
