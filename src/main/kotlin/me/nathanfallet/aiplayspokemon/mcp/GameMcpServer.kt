package me.nathanfallet.aiplayspokemon.mcp

import dev.kotlinds.pokemonclient.data.LookupKind
import dev.kotlinds.pokemonclient.actions.ActionException
import dev.kotlinds.pokemonclient.actions.ChainRunner
import dev.kotlinds.pokemonclient.actions.ChainStep
import dev.kotlinds.pokemonclient.view.AgentView
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
    private val pauseWhileThinking: () -> Boolean,
) {
    private val mutex = Mutex()

    /** Runs the calls that read or change the game: delivery of their answers, progress notifications ([ToolCalls]). */
    private val calls = ToolCalls(session.progress, DeliveryTracker(confirm = session::confirmDelivered, uncertain = session::confirmEventsOnly))
    private var engine: EmbeddedServer<*, *>? = null

    private val _activity = MutableStateFlow(AgentActivity())

    /** Calls made by the external agent, shown in the UI. */
    val activity: StateFlow<AgentActivity> = _activity.asStateFlow()


    val url get() = "http://localhost:$port/mcp"

    /** See [GameSession.blindUses]. */
    val blindUses get() = session.blindUses

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            mcpStreamableHttp(path = "/mcp") { createServer() }
        }.start(wait = false)
        // No pause here: the game runs at launch, until an agent shows up (see [agentArrived]).
    }

    /** Set once an agent called a tool: from then on the game is frozen while it thinks (if enabled). */
    @Volatile
    private var agentSeen = false

    /**
     * The first call of an agent: freezes the game for its thinking (when [pauseWhileThinking]), so what it reads
     * stays true until it acts. Before that, the game simply runs (the app doesn't start paused).
     */
    private fun agentArrived() {
        if (agentSeen) return
        agentSeen = true
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
            val arrival = System.currentTimeMillis()
            val detail = if (request.arguments?.get("detail")?.jsonPrimitive?.contentOrNull == "full") AgentView.Detail.FULL else AgentView.Detail.STANDARD
            agentArrived()
            mutex.withLock {
                calls.run(this, request, arrival) { text(session.describe(detail).encode()) }
            }
        }
        addTool(
            name = "act",
            description = "Carry out one action, e.g. {\"type\":\"attack\",\"move\":\"move:33\"}, {\"type\":\"choose\",\"entry\":\"option:yes\"}, " +
                "{\"type\":\"press\",\"button\":\"a\"}. The `actions` of get_state list what is possible now. Menus are navigated and " +
                "checked for you; an error says exactly why an action didn't happen. Always give a short first-person `reasoning`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("action", session.registry.jsonSchema(session.options().mode))
                    putJsonObject("then") {
                        put("type", "array")
                        put("description", "Optional further actions done right after, only when sure (max ${ChainRunner.MAX_THEN}). Stops at the first problem, when the battle changes under it " +
                            "(FOE_CHANGED: the foe switched or a new one was sent, even of the same species; FOE_FAINTED; OWN_FAINTED), after a `run` that couldn't escape " +
                            "(ESCAPE_FAILED), or when nothing has happened for a while (IDLE / TIME_CAP): `not_done` lists the steps left and `not_done_code` why. " +
                            "Once the battle is over, only its battle steps are dropped (attack, switch, keep_battling, run, throw_ball, use_item of a battle-only " +
                            "item like X Attack): `dropped` lists them and `dropped_code` how it ended (BATTLE_WON / BATTLE_OVER / BATTLE_LOST); every other step " +
                            "(walking, interact, a Potion or Repel, learning a move, reading messages) goes on. The answer covers every step: `performed` and all the messages.")
                    }
                    putJsonObject("note") {
                        put("type", "string")
                        put("description", "Optional note to yourself (goal, plan), given back with the full state.")
                    }
                    putJsonObject("detail") {
                        put("type", "string")
                        put("enum", JsonArray(listOf("compact", "full").map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        put("description", "compact (default): the screen, messages, errors, position and battle; the team only when it changed, the map only when you moved, action names only. full: everything, like get_state.")
                    }
                    putJsonObject("reasoning") {
                        put("type", "string")
                        put("description", "One short sentence: why you do this (shown to the people watching).")
                    }
                },
                required = listOf("action"),
            ),
        ) { request ->
            val arrival = System.currentTimeMillis()
            val arguments = request.arguments ?: JsonObject(emptyMap())
            val reasoning = arguments["reasoning"]?.jsonPrimitive?.contentOrNull
            mutex.withLock {
                val json = listOfNotNull(arguments["action"] as? JsonObject) + (arguments["then"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(ChainRunner.MAX_THEN)
                if (json.isEmpty()) return@withLock error("`action` must be an object like {\"type\": \"press\", \"button\": \"a\"}")
                arguments["note"]?.jsonPrimitive?.contentOrNull?.let { session.addNote(it) }
                val actions = json.map { step ->
                    session.registry.parse(step, session.options().mode).getOrElse { error ->
                        val message = (error as? ActionException)?.error?.message ?: error.message ?: "invalid action"
                        return@withLock error("$message. Call get_state for the valid actions.")
                    }
                }
                val label = actions.joinToString(" → ") { it.key }
                // Shown as in progress right away: the comment appears while the action happens.
                _activity.update { it.started(label, reasoning) }
                // One response for the whole chain: every step's messages, every step performed.
                val detail = if (arguments["detail"]?.jsonPrimitive?.contentOrNull == "full") AgentView.Detail.STANDARD else AgentView.Detail.COMPACT
                val result = calls.run(this, request, arrival) { running { session.act(actions.map { ChainStep.Planned(it) }, detail = detail).json } }
                val ok = result["ok"]?.jsonPrimitive?.contentOrNull == "true"
                _activity.update { it.finished(ok, if (ok) null else result["error"]?.let { e -> (e as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull ?: e.toString() }) }
                text(result.encode())
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
                        put("description", "species:25, move:85, item:17, TM01, fire... or a name; for encounters a map's name or map:<id> (omit: the current map).")
                    }
                },
                required = listOf("kind"),
            ),
        ) { request ->
            val arguments = request.arguments ?: JsonObject(emptyMap())
            val kind = arguments["kind"]?.jsonPrimitive?.contentOrNull?.let { k -> LookupKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) } }
                ?: return@addTool error("`kind` must be one of ${LookupKind.entries.joinToString { it.name.lowercase() }}")
            // Only encounters has a default (the current map).
            val id = arguments["id"]?.jsonPrimitive?.contentOrNull ?: if (kind == LookupKind.ENCOUNTERS) "" else return@addTool error("`id` is missing")
            session.lookup(kind, id).fold({ text(it.encode()) }, { error(it.message ?: "lookup failed") })
        }
        val screens = host.info.platform
        addTool(
            name = "screenshot",
            description = "A picture of both screens (top, then the bottom touch screen), for anything the state doesn't describe. " +
                "It is ${screens.screenWidth}x${screens.screenHeight * 2}: to touch something seen at (x, y) on the bottom half, touch (x, y - ${screens.screenHeight}).",
        ) {
            val arrival = System.currentTimeMillis()
            agentArrived()
            val frame = host.frames.value ?: return@addTool error("No frame yet")
            mutex.withLock {
                // A call: the previous answer reached the agent (or not).
                calls.delivery.arrived(arrival)
                session.countScreenshot()
            }
            CallToolResult(content = listOf(ImageContent(data = png(frame), mimeType = "image/png")))
        }
    }

    /** Runs [block] with the free run allowed, then freezes the game again while the agent thinks (if enabled). */
    private suspend fun <T> running(block: suspend () -> T): T {
        agentSeen = true
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

        const val INSTRUCTIONS =
            "You are playing a Pokémon game on a Nintendo DS through this server. Call get_state to see the screen (decoded from " +
                "the game's memory: menus with their entries, dialogues, battle, team, a text map), what happened since your last " +
                "call, and the actions you can take now. Then call act with one typed action. Menus are navigated and checked " +
                "for you; when something can't be done you get an explicit error. Things are named by stable ids (mon:…, move:…, " +
                "item:…, option:…). Raw buttons (press) are always there as a last resort. Play to progress through the story."
    }
}
