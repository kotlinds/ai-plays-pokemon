package me.nathanfallet.aiplayspokemon.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.nathanfallet.aiplayspokemon.agent.AgentSession
import me.nathanfallet.aiplayspokemon.emulator.Emulator

/**
 * Exposes the game as an MCP server, so an external agent (Claude Code, another MCP client...) plays
 * instead of our own decision loop. The agent keeps its own context, memory and reasoning between
 * calls; the tools give it exactly what our loop gives a model:
 *
 * - `get_state`: what is on screen + memory + the options of the current mode (pure / assisted);
 * - `act`: carry out one option (and optionally a sequence), returning what changed and the new state.
 *
 * The game is frozen between calls when "pause while thinking" is on, as for our own loop.
 * Connect with e.g. `claude mcp add --transport http pokemon http://localhost:3333/mcp`.
 */
class GameMcpServer(
    private val session: AgentSession,
    private val emulator: Emulator,
    private val port: Int,
) {
    private val mutex = Mutex()
    private var turn: AgentSession.Turn? = null
    private var engine: EmbeddedServer<*, *>? = null

    private val _activity = MutableStateFlow(Activity())

    /** Calls made by the external agent, shown in the UI. */
    val activity: StateFlow<Activity> = _activity.asStateFlow()

    data class Activity(val calls: Int = 0, val lastAction: String? = null, val lastReasoning: String? = null)

    val url get() = "http://localhost:$port/mcp"

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            mcpStreamableHttp(path = "/mcp") { createServer() }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(500, 1_000)
        engine = null
        if (!emulator.status.value.running) emulator.resume()
    }

    private fun createServer() = Server(
        Implementation(name = "ai-plays-pokemon", version = "0.1.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = INSTRUCTIONS,
    ) {
        addTool(
            name = "get_state",
            description = "Returns what is on screen right now, what you remember (recent actions and what they changed, " +
                "places, dialogues, explored map) and the options you can choose from. Call it first, then `act`.",
        ) {
            val state = mutex.withLock { currentState() }
            text(state)
        }
        addTool(
            name = "act",
            description = "Carries out one of the options listed by get_state (by id), optionally followed by a short " +
                "sequence of further option ids (`then`), and returns what changed and the new state with its options.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("choice") {
                        put("type", "string")
                        put("description", "The id of the option to carry out, e.g. \"a\", \"up\", \"exit_0\".")
                    }
                    putJsonObject("then") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Optional further option ids to carry out right after, only when sure (max 8). Stops early if something unexpected happens.")
                    }
                    putJsonObject("note") {
                        put("type", "string")
                        put("description", "Optional note to yourself (goal/plan), shown back in your memory.")
                    }
                    putJsonObject("reasoning") {
                        put("type", "string")
                        put("description", "Optional short reasoning, displayed in the app.")
                    }
                },
                required = listOf("choice"),
            ),
        ) { request ->
            val arguments = request.arguments ?: JsonObject(emptyMap())
            val choice = arguments["choice"]?.jsonPrimitive?.contentOrNull ?: return@addTool error("`choice` is required")
            val then = (arguments["then"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().take(8)
            val reasoning = arguments["reasoning"]?.jsonPrimitive?.contentOrNull
            val result = mutex.withLock {
                val current = turn ?: running { session.prepare() }
                val action = current.resolve(choice) ?: return@withLock null
                val report = running {
                    session.act(current, action, then, reasoning, arguments["note"]?.jsonPrimitive?.contentOrNull)
                }
                _activity.update { it.copy(calls = it.calls + 1, lastAction = report.performed.joinToString(" → ") { it.id }, lastReasoning = reasoning) }
                buildJsonObject {
                    put("performed", JsonArray(report.performed.map { JsonPrimitive(it.id) }))
                    report.problem?.let { put("problem", it) }
                    if (report.skipped > 0) put("skipped", "${report.skipped} option(s) of the sequence were skipped because something unexpected happened")
                    put("new_state", json.parseToJsonElement(currentState()))
                }
            }
            if (result == null) error("Unknown option `$choice`: call get_state to see the current options.")
            else text(json.encodeToString(JsonObject.serializer(), result))
        }
    }

    /** Prepares a turn (waiting for the game) and describes it with its options. */
    private suspend fun currentState(): String {
        val current = running { session.prepare() }
        turn = current
        val request = session.request(current, generative = true)
        val state = buildJsonObject {
            request.state.forEach { (key, value) -> put(key, value) }
            put("mode", session.settings().mode.label)
            put("options", buildJsonArray {
                current.actions.forEach { action ->
                    add(buildJsonObject {
                        put("id", action.id)
                        put("description", action.description)
                    })
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), state)
    }

    /**
     * Runs [block] with the game running, then freezes it again while the agent thinks (when
     * "pause while thinking" is on).
     */
    private suspend fun <T> running(block: suspend () -> T): T {
        if (!emulator.status.value.running) emulator.resume()
        try {
            return block()
        } finally {
            if (session.settings().pauseWhileThinking) emulator.pause()
        }
    }

    private fun text(value: String) = CallToolResult(content = listOf(TextContent(value)))
    private fun error(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

    private companion object {
        val json = Json { prettyPrint = false }

        const val INSTRUCTIONS =
            "You are playing Pokémon HeartGold on a Nintendo DS through this server. Call get_state to see the screen " +
                "(read from the game's memory: map around you as text, dialogue, menus, battle, team), your memory and the " +
                "options available; then call act with the id of an option. Options are either single button presses " +
                "(like holding the controller) or, in assisted mode, actions carried out for you (walking to a place, talking " +
                "to someone, choosing a menu option). Take notes with `note` to remember your goals. Play to progress through the story."
    }
}
