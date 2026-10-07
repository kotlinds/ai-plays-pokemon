package me.nathanfallet.aiplayspokemon.mcp

import dev.kotlinds.pokemonclient.data.LookupKind
import dev.kotlinds.pokemonclient.actions.ActionException
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.actions.ChainRunner
import dev.kotlinds.pokemonclient.actions.ChainStep
import dev.kotlinds.pokemonclient.view.AgentView
import dev.kotlinds.pokemonclient.console.Frame
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
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
 * - `lookup`: game knowledge (species, moves, items, types, encounters...) within the run's knowledge level;
 * - `screenshot`: the console's screens as a picture, for what isn't decoded yet.
 *
 * Every call goes through one circuit: the call lock (one call at a time on the game, in arrival order) and
 * [ToolCalls]: the verdict on the previous answer's delivery when it arrives, then the work (get_state and act with
 * progress notifications and their own delivery tracked, [ToolCalls.run]; lookup and screenshot, whose answers carry
 * no message, event or outcome, [ToolCalls.aside], with heartbeats while they wait for the lock, [ToolCalls.locked]).
 *
 * The mode (Pure / Assisted, [GameSession.options]) is read at every call: `get_state`'s actions and `act`'s
 * validation ([actSteps]) follow a change at once, within a session too. `act`'s JSON schema lists the actions of
 * the mode of the moment the client connected ([createServer], one server per MCP session: like
 * [reasoningRequired]); a client connected before a change keeps the schema it listed (the server never sends
 * `tools/list_changed`, see [reasoningRequired]), and an action of the other mode is refused by the validation with
 * the actions of the current one.
 *
 * The game is frozen between calls when "pause while thinking" is on: nothing changes while the agent thinks.
 */
class GameMcpServer(
    private val session: GameSession,
    private val host: ConsoleHost,
    private val port: Int,
    private val pauseWhileThinking: () -> Boolean,
    /**
     * Whether `act` requires its `reasoning` (PlayerSettings.reasoning): required in the tool's schema, and reminded in
     * the answer of a call without it.
     * - The schema reads it when a client connects: the SDK builds a [Server] per MCP session ([createServer], called
     *   by `mcpStreamableHttp` on each `initialize`), so a client connecting after the option changed gets the new
     *   schema. It can't follow a change within a session: the SDK answers `tools/list` from the tools registered on
     *   the session's server (no hook to build them on each listing), and changing them mid-session would need a
     *   `tools/list_changed` that clients caching their tools (the four tools are always the same, see above) may
     *   ignore.
     * - The reminder reads it at every call: switched within a session, it is what follows the option at once.
     */
    private val reasoningRequired: () -> Boolean = { true },
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
                calls.run(this, request, arrival, label = "get_state") { text(session.describe(detail).encode()) }
            }
        }
        addTool(
            name = "act",
            description = "Carry out one action, e.g. {\"type\":\"attack\",\"move\":\"move:33\"}, {\"type\":\"choose\",\"entry\":\"option:yes\"}, " +
                "{\"type\":\"press\",\"button\":\"a\"}. The `actions` of get_state list what is possible now. Menus are navigated and " +
                "checked for you; an error says exactly why an action didn't happen. Always give a short first-person `reasoning`.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    // The mode when this client connects (one server per session): validation follows later changes.
                    put("action", session.registry.jsonSchema(session.options().mode))
                    putJsonObject("then") {
                        put("type", "array")
                        put("description", "Optional further actions done right after, only when sure (max ${ChainRunner.MAX_THEN}). Stops at the first problem, when the battle changes under it " +
                            "(FOE_CHANGED: the foe switched or a new one was sent, even of the same species; FOE_FAINTED; OWN_FAINTED), after a `run` that couldn't escape " +
                            "(ESCAPE_FAILED), before a step naming an id of the map you started on (person:, warp:, item:, exit:...) once an earlier step took you to another map " +
                            "(TARGET_ON_OTHER_MAP: ids belong to their map), or when nothing has happened for a while (IDLE / TIME_CAP): `not_done` lists the steps left and `not_done_code` why. " +
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
                // Read now, when this client connects (one server per session): see [reasoningRequired].
                required = actRequired(reasoningRequired()),
            ),
        ) { request ->
            val arrival = System.currentTimeMillis()
            val arguments = request.arguments ?: JsonObject(emptyMap())
            val reasoning = arguments["reasoning"]?.jsonPrimitive?.contentOrNull
            mutex.withLock {
                arguments["note"]?.jsonPrimitive?.contentOrNull?.let { session.addNote(it) }
                val actions = actSteps(session.registry, arguments, session.options().mode).getOrElse { return@withLock error(it.message ?: "invalid action") }
                val label = actions.joinToString(" → ") { it.key }
                // Shown as in progress right away: the comment appears while the action happens.
                _activity.update { it.started(label, reasoning) }
                // One response for the whole chain: every step's messages, every step performed.
                val detail = if (arguments["detail"]?.jsonPrimitive?.contentOrNull == "full") AgentView.Detail.STANDARD else AgentView.Detail.COMPACT
                val result = calls.run(this, request, arrival, label = label) { running { session.act(actions.map { ChainStep.Planned(it) }, detail = detail).json } }
                val ok = result["ok"]?.jsonPrimitive?.contentOrNull == "true"
                _activity.update { it.finished(ok, if (ok) null else result["error"]?.let { e -> (e as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull ?: e.toString() }) }
                // The action ran all the same (refusing it would cost a call and the moment): the answer reminds.
                val reminder = reasoningReminder(reasoning, reasoningRequired())
                text((if (reminder == null) result else JsonObject(result + ("reminder" to kotlinx.serialization.json.JsonPrimitive(reminder)))).encode())
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
            val arrival = System.currentTimeMillis()
            agentArrived()
            lookupCall(calls, mutex, this, request, arrival) { kind, id -> session.lookup(kind, id) }
        }
        val screens = host.info.platform
        addTool(
            name = "screenshot",
            description = "A picture of both screens (top, then the bottom touch screen), for anything the state doesn't describe. " +
                "It is ${screens.screenWidth}x${screens.screenHeight * 2}: to touch something seen at (x, y) on the bottom half, touch (x, y - ${screens.screenHeight}).",
        ) { request ->
            val arrival = System.currentTimeMillis()
            agentArrived()
            screenshotCall(calls, mutex, this, request, arrival, count = session::countScreenshot, frame = { host.frames.value }) { png(it) }
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

    private fun png(frame: Frame): String {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    internal companion object {
        val json = Json { prettyPrint = false }

        /**
         * The actions of one `act` call, read from its [arguments] (`action`, then at most [ChainRunner.MAX_THEN] of
         * `then`) and validated against [mode], the mode at the time of the call (not the one `act`'s schema was listed
         * with): an action of another mode is refused with the actions of this one. What the `act` tool runs; the
         * failure's message is the one sent to the agent.
         */
        fun actSteps(registry: ActionRegistry, arguments: JsonObject, mode: ActionMode): Result<List<GameAction>> = runCatching {
            val steps = listOfNotNull(arguments["action"] as? JsonObject) + (arguments["then"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(ChainRunner.MAX_THEN)
            require(steps.isNotEmpty()) { "`action` must be an object like {\"type\": \"press\", \"button\": \"a\"}" }
            steps.map { step ->
                registry.parse(step, mode).getOrElse { error ->
                    val message = (error as? ActionException)?.error?.message ?: error.message ?: "invalid action"
                    throw IllegalArgumentException("$message. Call get_state for the valid actions.")
                }
            }
        }

        /**
         * The `lookup` tool: its arguments read ([lookupRequest]), then the common circuit of a call without anything to
         * deliver: the call lock, with heartbeats while it waits ([ToolCalls.locked]), and the verdict on the previous
         * answer ([ToolCalls.aside]); [lookup] answers (it reads the game: the party, where the player stands).
         */
        suspend fun lookupCall(
            calls: ToolCalls, lock: Mutex, connection: ClientConnection, request: CallToolRequest, arrival: Long,
            lookup: suspend (LookupKind, String) -> Result<JsonObject>,
        ): CallToolResult {
            val (kind, id) = lookupRequest(request.arguments ?: JsonObject(emptyMap())).getOrElse { return error(it.message ?: "invalid lookup") }
            return calls.locked(connection, request, lock, label = "lookup") {
                calls.aside(arrival) { lookup(kind, id).fold({ text(json.encodeToString(JsonObject.serializer(), it)) }, { error(it.message ?: "lookup failed") }) }
            }
        }

        /**
         * The `screenshot` tool: the same circuit as [lookupCall]; the [frame] is read once the lock is held (after an
         * act queued before it: the picture shows the screen it led to), the screenshot [count]ed for the blind uses,
         * and the verdict on the previous answer given even when there is no frame yet. [png] encodes the frame.
         */
        suspend fun screenshotCall(
            calls: ToolCalls, lock: Mutex, connection: ClientConnection, request: CallToolRequest, arrival: Long,
            count: suspend () -> Unit, frame: () -> Frame?, png: (Frame) -> String,
        ): CallToolResult = calls.locked(connection, request, lock, label = "screenshot") {
            calls.aside(arrival) {
                val shown = frame() ?: return@aside error("No frame yet")
                count()
                CallToolResult(content = listOf(ImageContent(data = png(shown), mimeType = "image/png")))
            }
        }

        /** A tool's text answer. */
        fun text(value: String) = CallToolResult(content = listOf(TextContent(value)))

        /** A tool's error answer, [message] sent to the agent. */
        fun error(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

        /** The `kind` and `id` of a `lookup` call, or the error sent to the agent (`id` defaults to the current map for encounters only). */
        fun lookupRequest(arguments: JsonObject): Result<Pair<LookupKind, String>> {
            val kind = arguments["kind"]?.jsonPrimitive?.contentOrNull?.let { k -> LookupKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) } }
                ?: return Result.failure(IllegalArgumentException("`kind` must be one of ${LookupKind.entries.joinToString { it.name.lowercase() }}"))
            val id = arguments["id"]?.jsonPrimitive?.contentOrNull ?: if (kind == LookupKind.ENCOUNTERS) "" else return Result.failure(IllegalArgumentException("`id` is missing"))
            return Result.success(kind to id)
        }

        /** The required arguments of `act`: its `action`, and its `reasoning` when [reasoningRequired]. */
        fun actRequired(reasoningRequired: Boolean): List<String> = if (reasoningRequired) listOf("action", "reasoning") else listOf("action")

        /**
         * The reminder added to the answer of an `act` without [reasoning] while it is required (null: given, or
         * optional). The action is carried out anyway.
         */
        fun reasoningReminder(reasoning: String?, required: Boolean): String? =
            if (required && reasoning.isNullOrBlank()) REASONING_REMINDER else null

        const val REASONING_REMINDER = "`reasoning` is missing: give one short first-person sentence with every act " +
            "(why you do this; it is shown to the people watching). The action was carried out."

        const val INSTRUCTIONS =
            "You are playing a Pokémon game on a Nintendo DS through this server. Call get_state to see the screen (decoded from " +
                "the game's memory: menus with their entries, dialogues, battle, team, a text map), what happened since your last " +
                "call, and the actions you can take now. Then call act with one typed action. Menus are navigated and checked " +
                "for you; when something can't be done you get an explicit error. Things are named by stable ids (mon:…, move:…, " +
                "item:…, option:…). Raw buttons (press) are always there as a last resort. Play to progress through the story."
    }
}
