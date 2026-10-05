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

    /**
     * Runs [work] as a tool call with a progress token, over HTTP like a client with its standalone SSE stream, and
     * returns the params of the progress notifications received before the answer (checked: same token, growing).
     */
    private fun progressOf(clock: ProgressClock, work: suspend () -> Unit): List<kotlinx.serialization.json.JsonObject> {
        val port = ServerSocket(0).use { it.localPort }
        val calls = ToolCalls(clock, DeliveryTracker(confirm = {}), period = 100.milliseconds)
        val engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            mcpStreamableHttp(path = "/mcp") {
                Server(Implementation("test", "1"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))) {
                    addTool(name = "slow", description = "a long action") { request ->
                        calls.run(this, request, System.currentTimeMillis()) {
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
}
