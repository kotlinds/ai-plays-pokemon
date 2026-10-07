package me.nathanfallet.aiplayspokemon.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A real MCP server over HTTP (the SDK's Streamable HTTP endpoint, like GameMcpServer) with the [tools] of a test, and
 * a client speaking raw JSON-RPC to it as Claude Code's SDK does: initialized, with its standalone SSE stream open
 * (where the notifications about a request arrive). The one harness of the tests that need the wire.
 */
internal class McpTestServer(tools: Server.() -> Unit) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val port = ServerSocket(0).use { it.localPort }
    private val engine: EmbeddedServer<*, *> = embeddedServer(CIO, port = port, host = "127.0.0.1") {
        mcpStreamableHttp(path = "/mcp") {
            Server(Implementation("test", "1"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))) { tools() }
        }
    }.start(wait = false)
    private val http = HttpClient.newHttpClient()
    private val url = URI("http://127.0.0.1:$port/mcp")

    /** Every message received on the standalone SSE stream, with when it arrived. */
    val events = CopyOnWriteArrayList<Pair<Long, String>>()

    private val session: String

    init {
        val init = http.send(request("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", null), HttpResponse.BodyHandlers.ofString())
        session = init.headers().firstValue("mcp-session-id").orElseThrow()
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        http.sendAsync(
            HttpRequest.newBuilder(url).GET().header("Accept", "text/event-stream").header("mcp-session-id", session).header("mcp-protocol-version", PROTOCOL).build(),
            HttpResponse.BodyHandlers.ofLines(),
        ).thenAccept { response -> response.body().forEach { line -> if (line.startsWith("data:")) events += System.currentTimeMillis() to line.removePrefix("data:").trim() } }
        Thread.sleep(300)
    }

    private fun request(body: String, session: String?) = HttpRequest.newBuilder(url).POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .apply { session?.let { header("mcp-session-id", it); header("mcp-protocol-version", PROTOCOL) } }.build()

    /** Sends a raw JSON-RPC message of the session and waits for its HTTP answer. */
    fun post(body: String): HttpResponse<String> = http.send(request(body, session), HttpResponse.BodyHandlers.ofString())

    /** `tools/call` of [tool] as request [id], with progress token [token] when given. */
    fun call(id: Int, tool: String, token: String? = null, arguments: String = "{}"): HttpResponse<String> = post(callBody(id, tool, token, arguments))

    /** [call] without waiting for its answer. */
    fun callAsync(id: Int, tool: String, token: String? = null, arguments: String = "{}"): CompletableFuture<HttpResponse<String>> =
        http.sendAsync(request(callBody(id, tool, token, arguments), session), HttpResponse.BodyHandlers.ofString())

    private fun callBody(id: Int, tool: String, token: String?, arguments: String) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$arguments${token?.let { ""","_meta":{"progressToken":"$it"}""" } ?: ""}}}"""

    /** The `notifications/progress` received for [token], with when each arrived, in order (the stream's priming event skipped). */
    fun progress(token: String): List<Pair<Long, JsonObject>> = events
        .mapNotNull { (at, data) -> runCatching { at to json.parseToJsonElement(data).jsonObject }.getOrNull() }
        .filter { (_, message) -> message["method"]?.jsonPrimitive?.content == "notifications/progress" }
        .map { (at, message) -> at to message["params"]!!.jsonObject }
        .filter { (_, params) -> params["progressToken"]?.jsonPrimitive?.content == token }

    override fun close() {
        engine.stop(100, 500)
    }

    private companion object {
        const val PROTOCOL = "2025-06-18"
    }
}
