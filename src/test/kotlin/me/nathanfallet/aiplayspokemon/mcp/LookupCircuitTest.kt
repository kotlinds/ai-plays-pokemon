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
import kotlinx.coroutines.sync.Mutex
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
 * `lookup` and `screenshot` are reads outside `get_state` / `act`: no call lock (they answer at once, even while a long
 * act runs, without waiting behind it), and no part in the delivery tracking (they never give, take or change the
 * verdict on an act's answer: a lost one is still repeated by the next `get_state` / `act`, a delivered one is not).
 * Their arguments and answers are unchanged.
 *
 * Migrated from the circuit where they went through the lock and judged the previous answer (`ToolCalls.aside`,
 * removed): "a lookup after a lost answer confirms nothing" and "a lookup queued behind an act leaves its verdict to
 * the next call" hold more than ever (a lookup now judges nothing); "a lookup arriving confirms the answer before it"
 * no longer exists by decision (only `get_state` / `act` judge), its other side ("the next call confirms it") is kept.
 */
class LookupCircuitTest {
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

    /**
     * What the acts of the test server carried: the game's messages ([log], read through an [EventFeed] kept until
     * delivered, like GameSession's), the acts' outcomes kept until delivered ([unanswered]), and the delivery verdicts.
     */
    private class Game {
        val log = EventLog()
        val feed = EventFeed(log, autoConfirm = false)
        val unanswered = UnansweredCalls()
        val verdicts = java.util.concurrent.CopyOnWriteArrayList<DeliveryVerdict>()
        @Volatile var confirmed = 0
        val counted = java.util.concurrent.atomic.AtomicInteger()
        val frame = java.util.concurrent.atomic.AtomicReference<Frame?>(null)
        val acts = java.util.concurrent.atomic.AtomicInteger()
    }

    /**
     * An MCP server with the server's circuit (client timeout 300 ms, no client times out before 100 ms): `act` under
     * the call lock ([ToolCalls.locked] then [ToolCalls.run]), its first call lasting [firstActMillis] (the game moving
     * to its end: a long go_to), the next ones 50 ms; each shows a message and then a frame of its number's width, and
     * answers whether its messages were repeated and whether earlier outcomes came back (`previous_calls`). `lookup`
     * and `screenshot` through the server's own tool functions.
     */
    private fun server(game: Game, firstActMillis: Long): McpTestServer {
        val clock = ProgressClock()
        val tracker = DeliveryTracker(
            confirm = { game.feed.confirm(); game.unanswered.delivered(); game.confirmed++ },
            uncertain = game.feed::confirm,
            onVerdict = { game.verdicts += it },
            clientTimeoutMillis = 300, minClientTimeoutMillis = 100,
        )
        val calls = ToolCalls(clock, tracker, period = 100.milliseconds)
        val lock = Mutex()
        return McpTestServer {
            addTool(name = "act", description = "an action") { request ->
                val arrival = System.currentTimeMillis()
                calls.locked(this, request, lock, label = "act") {
                    calls.run(this, request, arrival, label = "go_to") {
                        val number = game.acts.incrementAndGet()
                        repeat(((if (number == 1) firstActMillis else 50L) / 50).toInt()) { delay(50); clock.progressed("step $it") }
                        game.log.append { GameEvent.TextShown(it, 0, TextSource.FIELD, null, "act $number") }
                        game.frame.set(Frame(number, 1, IntArray(number)))
                        val batch = game.feed.take()
                        val previous = game.unanswered.describe().isNotEmpty()
                        game.unanswered.answered(listOf("go_to"), buildJsonObject { put("ok", true) })
                        CallToolResult(content = listOf(TextContent("done repeated=${batch.repeated} previous_calls=$previous messages=${batch.events.size}")))
                    }
                }
            }
            addTool(name = "lookup", description = "game knowledge") { request ->
                GameMcpServer.lookupCall(request) { kind, id -> Result.success(buildJsonObject { put("kind", kind.name); put("id", id) }) }
            }
            addTool(name = "screenshot", description = "the screens") { request ->
                GameMcpServer.screenshotCall(count = { game.counted.incrementAndGet() }, frame = { game.frame.get() }) { "png:${it.width}" }
            }
        }
    }

    /** Calls `lookup` and `screenshot` (as request [id] and [id] + 1): their answers, and how long each took. */
    private fun reads(mcp: McpTestServer, id: Int): List<Pair<String, Long>> = listOf(
        id to """{"kind":"species","id":"species:25"}""",
        id + 1 to null,
    ).map { (requestId, arguments) ->
        val asked = System.currentTimeMillis()
        val answer = if (arguments != null) mcp.call(requestId, "lookup", token = "tok-$requestId", arguments = arguments) else mcp.call(requestId, "screenshot", token = "tok-$requestId")
        answer.body() to System.currentTimeMillis() - asked
    }

    /**
     * Made while a long act runs, `lookup` and `screenshot` answer at once (no wait behind the act, no heartbeat
     * needed), the screenshot showing the screen of now (the frame before the act's); the client then gives up on
     * the act (no cancel) and acts again: that act's answer is lost, and the next act gives its messages and outcome
     * again, the reads in between having judged nothing.
     */
    @Test
    fun readsDuringALongActAnswerAtOnceAndALostActAnswerIsStillRepeated() {
        val game = Game()
        game.frame.set(Frame(9, 1, IntArray(9)))
        server(game, firstActMillis = 700).use { mcp ->
            val first = mcp.callAsync(2, "act", token = "tok-act")
            Thread.sleep(50)
            val (lookup, screenshot) = reads(mcp, 3)
            assertFalse(first.isDone, "the act still runs")
            assertTrue("species:25" in lookup.first, lookup.first)
            assertTrue(lookup.second < 250, "lookup answered after ${lookup.second} ms")
            assertTrue("png:9" in screenshot.first, screenshot.first)
            assertTrue(screenshot.second < 250, "screenshot answered after ${screenshot.second} ms")
            assertEquals(1, game.counted.get())
            assertTrue(mcp.progress("tok-3").isEmpty() && mcp.progress("tok-4").isEmpty(), "no wait, no heartbeat")
            Thread.sleep(250)
            // The client gave up on the first act (no cancel) and acts again, while it still runs.
            val again = mcp.call(5, "act").body()
            assertTrue("done" in first.get().body())
            assertTrue("repeated=true previous_calls=true messages=2" in again, again)
            assertEquals(0, game.confirmed)
            assertEquals(listOf(DeliveryVerdict.Reason.NEXT_CALL_WHILE_RUNNING), game.verdicts.map { it.reason })
        }
    }

    /** The other side: reads during and after an act whose answer was delivered: the next act confirms it, nothing repeated. */
    @Test
    fun readsAroundADeliveredActDoNotMakeItRepeated() {
        val game = Game()
        server(game, firstActMillis = 200).use { mcp ->
            val first = mcp.callAsync(2, "act")
            Thread.sleep(50)
            val (lookup, screenshot) = reads(mcp, 3)
            assertTrue("species:25" in lookup.first, lookup.first)
            // No frame yet: the error, nothing counted.
            assertTrue("No frame yet" in screenshot.first, screenshot.first)
            assertEquals(0, game.counted.get())
            assertTrue("repeated=false previous_calls=false" in first.get().body())
            reads(mcp, 5)
            // The reads after the act's answer confirmed nothing: only the next act does.
            assertEquals(0, game.confirmed)
            assertEquals(1, game.counted.get())
            val next = mcp.call(7, "act").body()
            assertTrue("repeated=false previous_calls=false messages=1" in next, next)
            assertEquals(1, game.confirmed)
            assertTrue(game.verdicts.isEmpty())
        }
    }

    /** A lost answer (the client cancelled the act) is not confirmed by reads after it: the next act repeats it. */
    @Test
    fun readsAfterALostAnswerConfirmNothing() {
        val game = Game()
        server(game, firstActMillis = 400).use { mcp ->
            val first = mcp.callAsync(2, "act")
            Thread.sleep(100)
            mcp.post("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2,"reason":"timeout"}}""")
            Thread.sleep(500) // the act runs to its end all the same
            reads(mcp, 3)
            val next = mcp.call(5, "act").body()
            assertTrue("repeated=true previous_calls=true messages=2" in next, next)
            assertEquals(0, game.confirmed)
            assertEquals(listOf(DeliveryVerdict.Reason.CANCELLED), game.verdicts.map { it.reason })
            first.cancel(true)
        }
    }

    // endregion
}
