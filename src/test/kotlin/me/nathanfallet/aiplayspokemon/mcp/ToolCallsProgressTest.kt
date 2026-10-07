package me.nathanfallet.aiplayspokemon.mcp

import dev.kotlinds.pokemonclient.runtime.ActionProgress
import dev.kotlinds.pokemonclient.runtime.ProgressClock
import dev.kotlinds.pokemonclient.runtime.ProgressUnit
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Real time, over HTTP like an MCP client (raw JSON-RPC, as Claude Code's SDK sends it): a long tool call run through
 * [ToolCalls] sends `notifications/progress` with the client's token while it runs (on the standalone SSE stream the
 * client opened), before its answer.
 */
class ToolCallsProgressTest {

    @Test
    fun aLongCallSendsProgressNotificationsBeforeItsAnswer() {
        val clock = ProgressClock()
        val progress = progressOf(clock) {
            repeat(6) { i ->
                delay(250)
                clock.progressed("message $i")
            }
        }
        assertTrue(progress.size >= 3, "progress notifications: $progress")
        assertTrue(progress.any { it["message"]?.jsonPrimitive?.content?.startsWith("message") == true })
    }

    @Test
    fun aLongGoToSaysHowManyTilesItHasWalkedAndWhere() {
        // A go_to surfing with nothing on screen: only its tiles change (reported by the library's walk).
        val clock = ProgressClock()
        val progress = progressOf(clock) {
            for (tile in 1..12) {
                delay(50)
                clock.report(ActionProgress("go_to Seafoam Islands 1F", tile * 10, 480, ProgressUnit.TILES, if (tile < 8) "Route 21" else "Route 20"))
            }
        }
        val messages = progress.mapNotNull { it["message"]?.jsonPrimitive?.content }
        assertTrue(messages.size >= 3, "progress notifications: $progress")
        assertTrue(messages.all { Regex("go_to Seafoam Islands 1F: \\d+/480 tiles, Route 2[01]").matches(it) }, messages.toString())
    }

    @Test
    fun aLongCallWithNothingNewOnScreenStillSendsHeartbeats() {
        // An attack's turn, a cutscene, the Hall of Fame: nothing a reading sees changes for a while (race Claude vs
        // Codex: "The operation timed out." on `attack`, Ho-Oh's cutscene, `watch_hall_of_fame`).
        val progress = progressOf(ProgressClock(), label = "attack(move:33)") { delay(700) }
        val messages = progress.mapNotNull { it["message"]?.jsonPrimitive?.content }
        assertTrue(messages.size >= 3, "heartbeats: $progress")
        assertTrue(messages.all { Regex("attack\\(move:33\\): still running, \\d+ s").matches(it) }, messages.toString())
    }

    /**
     * Runs [work] as a tool call with a progress token, over HTTP like a client with its standalone SSE stream, and
     * returns the params of the progress notifications received before the answer (checked: same token, growing).
     */
    private fun progressOf(clock: ProgressClock, label: String = "slow", work: suspend () -> Unit): List<kotlinx.serialization.json.JsonObject> {
        val calls = ToolCalls(clock, DeliveryTracker(confirm = {}), period = 100.milliseconds)
        McpTestServer {
            addTool(name = "slow", description = "a long action") { request ->
                calls.run(this, request, System.currentTimeMillis(), label) {
                    work()
                    CallToolResult(content = listOf(TextContent("done")))
                }
            }
        }.use { mcp ->
            val answer = mcp.call(2, "slow", token = "tok-1")
            val answeredAt = System.currentTimeMillis()
            assertTrue("done" in answer.body(), answer.body())
            val progress = mcp.progress("tok-1")
            progress.forEach { (at, _) -> assertTrue(at <= answeredAt + 50, "a progress notification after the answer") }
            val values = progress.map { it.second["progress"]!!.jsonPrimitive.double }
            assertEquals(values.sorted(), values, "progress only grows")
            return progress.map { it.second }
        }
    }

    @Test
    fun aCallTheClientCancelsRunsToItsEndAndItsAnswerCountsAsLost() {
        var confirmed = 0
        val calls = ToolCalls(ProgressClock(), DeliveryTracker(confirm = { confirmed++ }), period = 100.milliseconds)
        val finished = java.util.concurrent.CountDownLatch(1)
        McpTestServer {
            addTool(name = "slow", description = "a long action") { request ->
                calls.run(this, request, System.currentTimeMillis(), label = "slow") {
                    delay(800)
                    finished.countDown()
                    CallToolResult(content = listOf(TextContent("done")))
                }
            }
            addTool(name = "fast", description = "a short call") { request ->
                calls.run(this, request, System.currentTimeMillis(), label = "fast") { CallToolResult(content = listOf(TextContent("ok"))) }
            }
        }.use { mcp ->
            mcp.callAsync(2, "slow")
            Thread.sleep(300)
            // The client's timeout: it cancels the request (what the TypeScript SDK sends).
            mcp.post("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2,"reason":"timeout"}}""")
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS), "the cancelled call still ran to its end")
            Thread.sleep(100)
            val next = mcp.call(3, "fast")
            assertTrue("ok" in next.body(), next.body())
            assertEquals(0, confirmed, "the cancelled call's answer was never delivered")
        }
    }

    /**
     * A call answered long after its last sign of life (here 300 ms, the client's timeout in this test) is taken as
     * lost when only heartbeats were sent meanwhile: a heartbeat goes out every period to the end of every call, so
     * counting it kept every answer alive and an answer the client gave up on (an HTTP timeout, no cancel) was never
     * repeated.
     */
    @Test
    fun heartbeatsAloneDoNotKeepAnAnswerAlive() {
        val verdicts = verdictsOf(ProgressClock()) { delay(800) }
        assertEquals(Verdicts(confirmed = 0, uncertain = 0), verdicts, "the answer counts as lost")
    }

    /** The other side: progress of the game (a go_to's tiles) keeps the call alive, its answer is not lost. */
    @Test
    fun progressOfTheGameKeepsAnAnswerAlive() {
        val clock = ProgressClock()
        val verdicts = verdictsOf(clock) {
            repeat(8) { i ->
                delay(100)
                clock.progressed("message $i")
            }
        }
        // Answered more than the timeout after its start but alive to the end: uncertain, not lost.
        assertEquals(Verdicts(confirmed = 0, uncertain = 1), verdicts)
    }

    private data class Verdicts(val confirmed: Int, val uncertain: Int)

    /**
     * Runs [work] as a tool call with a progress token (client timeout 300 ms, heartbeat every 100 ms), then a short
     * call (which gives the verdict on the first one), over HTTP like a client; returns the verdicts given.
     */
    private fun verdictsOf(clock: ProgressClock, work: suspend () -> Unit): Verdicts {
        var confirmed = 0
        var uncertain = 0
        val delivery = DeliveryTracker(confirm = { confirmed++ }, uncertain = { uncertain++ }, clientTimeoutMillis = 300, minClientTimeoutMillis = 100)
        val calls = ToolCalls(clock, delivery, period = 100.milliseconds)
        McpTestServer {
            addTool(name = "slow", description = "a long action") { request ->
                calls.run(this, request, System.currentTimeMillis(), label = "slow") {
                    work()
                    CallToolResult(content = listOf(TextContent("done")))
                }
            }
            addTool(name = "fast", description = "a short call") { request ->
                calls.run(this, request, System.currentTimeMillis(), label = "fast") { CallToolResult(content = listOf(TextContent("ok"))) }
            }
        }.use { mcp ->
            val slow = mcp.call(2, "slow", token = "tok-1")
            assertTrue("done" in slow.body(), slow.body())
            val next = mcp.call(3, "fast")
            assertTrue("ok" in next.body(), next.body())
            return Verdicts(confirmed, uncertain)
        }
    }

    /**
     * A `get_state` (or an `act`) queued behind a long act sends heartbeats while it waits for the call lock ("waiting
     * for the call in progress"), then while it runs, one count for the token that only grows: a client restarting its
     * timeout on progress doesn't give up on it (only the act sent progress before).
     */
    @Test
    fun aCallQueuedBehindALongActSendsHeartbeatsWhileItWaitsThenWhileItRuns() {
        for (queued in listOf("get_state", "act")) {
            val calls = ToolCalls(ProgressClock(), DeliveryTracker(confirm = {}), period = 100.milliseconds)
            val lock = Mutex()
            val first = java.util.concurrent.atomic.AtomicBoolean(true)
            McpTestServer {
                for (tool in listOf("get_state", "act")) addTool(name = tool, description = tool) { request ->
                    val arrival = System.currentTimeMillis()
                    calls.locked(this, request, lock, label = tool) {
                        // The first call is the long act (an attack's turn); the queued one runs 350 ms.
                        val long = first.getAndSet(false)
                        calls.run(this, request, arrival, label = if (long) "attack(move:33)" else tool) {
                            delay(if (long) 700 else 350)
                            CallToolResult(content = listOf(TextContent("done")))
                        }
                    }
                }
            }.use { mcp ->
                val act = mcp.callAsync(2, "act", token = "tok-act")
                Thread.sleep(50)
                val answer = mcp.call(3, queued, token = "tok-queued")
                assertTrue("done" in answer.body(), answer.body())
                assertTrue("done" in act.get().body())
                val beats = mcp.progress("tok-queued").map { it.second }
                val messages = beats.mapNotNull { it["message"]?.jsonPrimitive?.content }
                val waiting = messages.takeWhile { it.startsWith("$queued: waiting") }
                val running = messages.drop(waiting.size)
                assertTrue(waiting.size >= 3, "heartbeats while it waits: $messages")
                assertTrue(waiting.all { Regex("$queued: waiting for the call in progress, \\d+ s").matches(it) }, messages.toString())
                assertTrue(running.size >= 2, "heartbeats while it runs: $messages")
                assertTrue(running.all { Regex("$queued: still running, \\d+ s").matches(it) }, messages.toString())
                val values = beats.map { it["progress"]!!.jsonPrimitive.double }
                assertEquals(values.distinct().sorted(), values, "one count for the token, that only grows")
                // The act itself: heartbeats of its own run only.
                assertTrue(mcp.progress("tok-act").mapNotNull { it.second["message"]?.jsonPrimitive?.content }.all { it.startsWith("attack(move:33): still running") })
            }
        }
    }

    /** The other side: a call that finds the lock free and answers quickly sends no notification at all. */
    @Test
    fun aShortCallWithTheLockFreeSendsNoNotification() {
        val calls = ToolCalls(ProgressClock(), DeliveryTracker(confirm = {}), period = 100.milliseconds)
        val lock = Mutex()
        McpTestServer {
            addTool(name = "get_state", description = "the state") { request ->
                val arrival = System.currentTimeMillis()
                calls.locked(this, request, lock, label = "get_state") {
                    calls.run(this, request, arrival, label = "get_state") { CallToolResult(content = listOf(TextContent("state"))) }
                }
            }
        }.use { mcp ->
            assertTrue("state" in mcp.call(2, "get_state", token = "tok-state").body())
            Thread.sleep(250)
            assertTrue(mcp.progress("tok-state").isEmpty())
        }
    }
}
