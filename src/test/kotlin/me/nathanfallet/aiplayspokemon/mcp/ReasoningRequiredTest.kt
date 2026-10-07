package me.nathanfallet.aiplayspokemon.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `reasoning` of `act` (PlayerSettings.reasoning, on by default): required in the tool's schema and reminded in the
 * answer when missing (Claude stopped filling it after its third compaction while it was optional); off, optional and
 * never reminded.
 */
class ReasoningRequiredTest {

    @Test
    fun requiredByDefaultSettingIsInTheSchemaAndAMissingOneIsReminded() {
        assertEquals(listOf("action", "reasoning"), GameMcpServer.actRequired(reasoningRequired = true))
        assertEquals(GameMcpServer.REASONING_REMINDER, GameMcpServer.reasoningReminder(null, required = true))
        assertEquals(GameMcpServer.REASONING_REMINDER, GameMcpServer.reasoningReminder("  ", required = true))
        // Given: nothing added to the answer.
        assertNull(GameMcpServer.reasoningReminder("I heal before Red.", required = true))
    }

    @Test
    fun theOptionOffKeepsItOptional() {
        assertEquals(listOf("action"), GameMcpServer.actRequired(reasoningRequired = false))
        assertNull(GameMcpServer.reasoningReminder(null, required = false))
    }

    /**
     * The schema reads the option when a client connects, the way [GameMcpServer] builds its tools (one server per MCP
     * session, `mcpStreamableHttp`): switched off, a client connecting afterwards no longer gets `reasoning` as
     * required; a session opened before keeps the schema it listed (the SDK lists the tools registered on the
     * session's server: see GameMcpServer.reasoningRequired), and only the reminder follows the option there.
     */
    @Test
    fun theSchemaFollowsTheOptionForEachClientThatConnects() {
        var required = true
        val port = ServerSocket(0).use { it.localPort }
        val engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            mcpStreamableHttp(path = "/mcp") {
                Server(Implementation("test", "1"), ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))) {
                    addTool(name = "act", description = "act", inputSchema = ToolSchema(required = GameMcpServer.actRequired(required))) { CallToolResult(content = emptyList()) }
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
            fun connect(): String {
                val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", null)
                return init.headers().firstValue("mcp-session-id").orElseThrow().also { post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", it) }
            }
            fun requiredOf(session: String): List<String> {
                val listed = Json.parseToJsonElement(post("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""", session).body()).jsonObject
                val act = listed["result"]!!.jsonObject["tools"]!!.jsonArray.single().jsonObject
                return act["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            }
            val before = connect()
            assertEquals(listOf("action", "reasoning"), requiredOf(before))
            required = false
            assertEquals(listOf("action"), requiredOf(connect()))
            // The session opened before keeps what it listed.
            assertEquals(listOf("action", "reasoning"), requiredOf(before))
        } finally {
            engine.stop(100, 500)
        }
    }
}
