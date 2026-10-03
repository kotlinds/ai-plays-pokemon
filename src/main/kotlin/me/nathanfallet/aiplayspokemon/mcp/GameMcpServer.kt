package me.nathanfallet.aiplayspokemon.mcp

import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.data.Lookup
import dev.kotlinds.pokemonclient.data.LookupKind
import dev.kotlinds.pokemonclient.actions.ActionException
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.console.Frame
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.nathanfallet.aiplayspokemon.agent.GameSession
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Exposes the game as an MCP server, so an external agent (Claude Code, another MCP client...) plays it.
 *
 * Four tools, always the same (so clients can cache them):
 * - `get_state`: the screen, team, battle, position, what happened since the previous call, the actions possible
 *   now with their valid values, and the version of the state;
 * - `act`: carries out one typed action (`{"type": "attack", "move": "move:33"}`...), optionally followed by a short
 *   list of further actions, and returns what happened and the new state;
 * - `screenshot`: the console's screens as a picture, for what isn't decoded yet;
 * - (game knowledge lookups come with phase 4).
 *
 * The game is frozen between calls when "pause while thinking" is on: nothing changes while the agent thinks.
 */
class GameMcpServer(
    private val session: GameSession,
    private val host: ConsoleHost,
    private val port: Int,
    private val mode: () -> ActionMode,
    private val pauseWhileThinking: () -> Boolean,
    private val knowledge: () -> KnowledgeLevel,
) {
    private val mutex = Mutex()
    private var engine: EmbeddedServer<*, *>? = null

    private val _activity = MutableStateFlow(Activity())

    /** Calls made by the external agent, shown in the UI. */
    val activity: StateFlow<Activity> = _activity.asStateFlow()

    data class Activity(val calls: Int = 0, val lastAction: String? = null, val lastReasoning: String? = null)

    val url get() = "http://localhost:$port/mcp"

    /** See [GameSession.blindUses]. */
    val blindUses get() = session.blindUses

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            mcpStreamableHttp(path = "/mcp") { createServer() }
        }.start(wait = false)
        if (pauseWhileThinking()) host.pause()
    }

    fun stop() {
        engine?.stop(500, 1_000)
        engine = null
        host.resume()
    }

    private fun createServer() = Server(
        Implementation(name = "ai-plays-pokemon", version = "0.2.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = INSTRUCTIONS,
    ) {
        addTool(
            name = "get_state",
            description = "What is on screen, your team, the battle, where you are (with a text map), everything shown since " +
                "your previous call, and the actions you can take now with their valid values. Call it first.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("detail") {
                        put("type", "string")
                        put("enum", JsonArray(listOf("compact", "full").map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        put("description", "full adds each action's description and your notes.")
                    }
                },
            ),
        ) { request ->
            val full = request.arguments?.get("detail")?.jsonPrimitive?.contentOrNull == "full"
            mutex.withLock { text(session.describe(full).encode()) }
        }
        addTool(
            name = "act",
            description = "Carry out one action, e.g. {\"type\":\"attack\",\"move\":\"move:33\"}, {\"type\":\"choose\",\"entry\":\"option:yes\"}, " +
                "{\"type\":\"press\",\"button\":\"a\"}. The `actions` of get_state list what is possible now. Menus are navigated and " +
                "checked for you; an error says exactly why an action didn't happen. Always give a short first-person `reasoning`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("action", session.registry.jsonSchema(mode()))
                    putJsonObject("then") {
                        put("type", "array")
                        put("description", "Optional further actions done right after, only when sure (max 8); stops at the first problem.")
                    }
                    putJsonObject("note") {
                        put("type", "string")
                        put("description", "Optional note to yourself (goal, plan), given back with the full state.")
                    }
                    putJsonObject("reasoning") {
                        put("type", "string")
                        put("description", "One short sentence: why you do this (shown to the people watching).")
                    }
                },
                required = listOf("action"),
            ),
        ) { request ->
            val arguments = request.arguments ?: JsonObject(emptyMap())
            val reasoning = arguments["reasoning"]?.jsonPrimitive?.contentOrNull
            mutex.withLock {
                val actions = listOfNotNull(arguments["action"] as? JsonObject) + (arguments["then"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(MAX_THEN)
                if (actions.isEmpty()) return@withLock error("`action` must be an object like {\"type\": \"press\", \"button\": \"a\"}")
                arguments["note"]?.jsonPrimitive?.contentOrNull?.let { session.act(GameAction.Note(it)) }
                var result: JsonObject? = null
                val performed = mutableListOf<String>()
                for (json in actions) {
                    val action = session.registry.parse(json, mode()).getOrElse { error ->
                        val message = (error as? ActionException)?.error?.message ?: error.message ?: "invalid action"
                        return@withLock error("$message. Call get_state for the valid actions.")
                    }
                    result = running { session.act(action) }
                    performed += action.key
                    if (result["ok"]?.jsonPrimitive?.contentOrNull != "true") break
                }
                _activity.update { it.copy(calls = it.calls + 1, lastAction = performed.joinToString(" → "), lastReasoning = reasoning) }
                text(result!!.encode())
            }
        }
        addTool(
            name = "lookup",
            description = "Game knowledge, within the knowledge level of this run: " +
                LookupKind.entries.joinToString("; ") { "${it.name.lowercase()} = ${it.description}" } +
                ". Ids like species:25, move:85, item:17, TM01, or a name.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("kind") {
                        put("type", "string")
                        put("enum", JsonArray(LookupKind.entries.map { kotlinx.serialization.json.JsonPrimitive(it.name.lowercase()) }))
                    }
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "species:25, move:85, item:17, TM01, fire... or a name.")
                    }
                },
                required = listOf("kind", "id"),
            ),
        ) { request ->
            val arguments = request.arguments ?: JsonObject(emptyMap())
            val kind = arguments["kind"]?.jsonPrimitive?.contentOrNull?.let { k -> LookupKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) } }
                ?: return@addTool error("`kind` must be one of ${LookupKind.entries.joinToString { it.name.lowercase() }}")
            val id = arguments["id"]?.jsonPrimitive?.contentOrNull ?: return@addTool error("`id` is missing")
            val data = session.gameData ?: return@addTool error("No game data for this game")
            Lookup(data, knowledge()).lookup(kind, id).fold({ text(it.encode()) }, { error(it.message ?: "lookup failed") })
        }
        addTool(
            name = "screenshot",
            description = "A picture of both screens (top, then the bottom touch screen), for anything the state doesn't describe.",
        ) {
            val frame = host.frames.value ?: return@addTool error("No frame yet")
            mutex.withLock { session.countScreenshot() }
            CallToolResult(content = listOf(ImageContent(data = png(frame), mimeType = "image/png")))
        }
    }

    /** Runs [block] with the free run allowed, then freezes the game again while the agent thinks (if enabled). */
    private suspend fun <T> running(block: suspend () -> T): T {
        host.resume()
        try {
            return block()
        } finally {
            if (pauseWhileThinking()) host.pause()
        }
    }

    private fun JsonObject.encode() = json.encodeToString(JsonObject.serializer(), this)
    private fun text(value: String) = CallToolResult(content = listOf(TextContent(value)))
    private fun error(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

    private fun png(frame: Frame): String {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private companion object {
        val json = Json { prettyPrint = false }
        const val MAX_THEN = 8

        const val INSTRUCTIONS =
            "You are playing a Pokémon game on a Nintendo DS through this server. Call get_state to see the screen (decoded from " +
                "the game's memory: menus with their entries, dialogues, battle, team, a text map), what happened since your last " +
                "call, and the actions you can take now. Then call act with one typed action. Menus are navigated and checked " +
                "for you; when something can't be done you get an explicit error. Things are named by stable ids (mon:…, move:…, " +
                "item:…, option:…). Raw buttons (press) are always there as a last resort. Play to progress through the story."
    }
}
