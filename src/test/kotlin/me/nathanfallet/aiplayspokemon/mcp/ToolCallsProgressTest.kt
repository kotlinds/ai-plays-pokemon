package me.nathanfallet.aiplayspokemon.mcp

import dev.kotlinds.pokemonclient.runtime.ActionProgress
import dev.kotlinds.pokemonclient.runtime.ProgressClock
import dev.kotlinds.pokemonclient.runtime.ProgressUnit
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
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

    private val json = Json { ignoreUnknownKeys = true }

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
    private fun progressOf(clock: ProgressClock, label: String? = null, work: suspend () -> Unit): List<kotlinx.serialization.json.JsonObject> {
        val port = ServerSocket(0).use { it.localPort }
        val calls = ToolCalls(clock, DeliveryTracker(confirm = {}), period = 100.milliseconds)
        val engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            mcpStreamableHttp(path = "/mcp") {
                Server(Implementation("test", "1"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))) {
                    addTool(name = "slow", description = "a long action") { request ->
                        calls.run(this, request, System.currentTimeMillis(), label) {
                            work()
                            CallToolResult(content = listOf(TextContent("done")))
                        }
                    }
                }
            }
        }.start(wait = false)
        try {
            val http = HttpClient.newHttpClient()
            val url = URI("http://127.0.0.1:$port/mcp")
            fun post(body: String, session: String?): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(url).POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .apply { session?.let { header("mcp-session-id", it); header("mcp-protocol-version", "2025-06-18") } }.build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", null)
            val session = init.headers().firstValue("mcp-session-id").orElseThrow()
            post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session)
            // The client's standalone SSE stream: notifications not sent as part of an answer arrive there.
            val events = CopyOnWriteArrayList<Pair<Long, String>>()
            http.sendAsync(
                HttpRequest.newBuilder(url).GET().header("Accept", "text/event-stream").header("mcp-session-id", session).header("mcp-protocol-version", "2025-06-18").build(),
                HttpResponse.BodyHandlers.ofLines(),
            ).thenAccept { response -> response.body().forEach { line -> if (line.startsWith("data:")) events += System.currentTimeMillis() to line.removePrefix("data:").trim() } }
            Thread.sleep(300)
            val answer = post("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"slow","arguments":{},"_meta":{"progressToken":"tok-1"}}}""", session)
            val answeredAt = System.currentTimeMillis()
            assertTrue("done" in answer.body(), answer.body())
            // A stream starts with an empty priming event (its id, for resumption).
            val progress = events.mapNotNull { (at, data) -> runCatching { at to json.parseToJsonElement(data).jsonObject }.getOrNull() }
                .filter { (_, message) -> message["method"]?.jsonPrimitive?.content == "notifications/progress" }
            progress.forEach { (at, message) ->
                assertEquals("tok-1", message["params"]!!.jsonObject["progressToken"]!!.jsonPrimitive.content)
                assertTrue(at <= answeredAt + 50, "a progress notification after the answer")
            }
            val values = progress.map { it.second["params"]!!.jsonObject["progress"]!!.jsonPrimitive.double }
            assertEquals(values.sorted(), values, "progress only grows")
            return progress.map { it.second["params"]!!.jsonObject }
        } finally {
            engine.stop(100, 500)
        }
    }

    @Test
    fun aCallTheClientCancelsRunsToItsEndAndItsAnswerCountsAsLost() {
        val port = ServerSocket(0).use { it.localPort }
        var confirmed = 0
        val calls = ToolCalls(ProgressClock(), DeliveryTracker(confirm = { confirmed++ }), period = 100.milliseconds)
        val finished = java.util.concurrent.CountDownLatch(1)
        val engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            mcpStreamableHttp(path = "/mcp") {
                Server(Implementation("test", "1"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))) {
                    addTool(name = "slow", description = "a long action") { request ->
                        calls.run(this, request, System.currentTimeMillis()) {
                            delay(800)
                            finished.countDown()
                            CallToolResult(content = listOf(TextContent("done")))
                        }
                    }
                    addTool(name = "fast", description = "a short call") { request ->
                        calls.run(this, request, System.currentTimeMillis()) { CallToolResult(content = listOf(TextContent("ok"))) }
                    }
                }
            }
        }.start(wait = false)
        try {
            val http = HttpClient.newHttpClient()
            val url = URI("http://127.0.0.1:$port/mcp")
            fun request(body: String, session: String?) = HttpRequest.newBuilder(url).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .apply { session?.let { header("mcp-session-id", it); header("mcp-protocol-version", "2025-06-18") } }.build()
            val init = http.send(request("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", null), HttpResponse.BodyHandlers.ofString())
            val session = init.headers().firstValue("mcp-session-id").orElseThrow()
            http.send(request("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session), HttpResponse.BodyHandlers.ofString())
            http.sendAsync(request("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"slow","arguments":{}}}""", session), HttpResponse.BodyHandlers.ofString())
            Thread.sleep(300)
            // The client's timeout: it cancels the request (what the TypeScript SDK sends).
            http.send(request("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2,"reason":"timeout"}}""", session), HttpResponse.BodyHandlers.ofString())
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS), "the cancelled call still ran to its end")
            Thread.sleep(100)
            val next = http.send(request("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"fast","arguments":{}}}""", session), HttpResponse.BodyHandlers.ofString())
            assertTrue("ok" in next.body(), next.body())
            assertEquals(0, confirmed, "the cancelled call's answer was never delivered")
        } finally {
            engine.stop(100, 500)
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
        val port = ServerSocket(0).use { it.localPort }
        var confirmed = 0
        var uncertain = 0
        val delivery = DeliveryTracker(confirm = { confirmed++ }, uncertain = { uncertain++ }, clientTimeoutMillis = 300, minClientTimeoutMillis = 100)
        val calls = ToolCalls(clock, delivery, period = 100.milliseconds)
        val engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            mcpStreamableHttp(path = "/mcp") {
                Server(Implementation("test", "1"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))) {
                    addTool(name = "slow", description = "a long action") { request ->
                        calls.run(this, request, System.currentTimeMillis()) {
                            work()
                            CallToolResult(content = listOf(TextContent("done")))
                        }
                    }
                    addTool(name = "fast", description = "a short call") { request ->
                        calls.run(this, request, System.currentTimeMillis()) { CallToolResult(content = listOf(TextContent("ok"))) }
                    }
                }
            }
        }.start(wait = false)
        try {
            val http = HttpClient.newHttpClient()
            val url = URI("http://127.0.0.1:$port/mcp")
            fun post(body: String, session: String?): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(url).POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .apply { session?.let { header("mcp-session-id", it); header("mcp-protocol-version", "2025-06-18") } }.build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", null)
            val session = init.headers().firstValue("mcp-session-id").orElseThrow()
            post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session)
            http.sendAsync(
                HttpRequest.newBuilder(url).GET().header("Accept", "text/event-stream").header("mcp-session-id", session).header("mcp-protocol-version", "2025-06-18").build(),
                HttpResponse.BodyHandlers.ofLines(),
            ).thenAccept { response -> response.body().forEach { } }
            Thread.sleep(300)
            val slow = post("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"slow","arguments":{},"_meta":{"progressToken":"tok-1"}}}""", session)
            assertTrue("done" in slow.body(), slow.body())
            val next = post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"fast","arguments":{}}}""", session)
            assertTrue("ok" in next.body(), next.body())
            return Verdicts(confirmed, uncertain)
        } finally {
            engine.stop(100, 500)
        }
    }
}
