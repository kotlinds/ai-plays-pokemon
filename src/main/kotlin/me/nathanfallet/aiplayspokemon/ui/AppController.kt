package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.agent.PokemonPlayer
import me.nathanfallet.aiplayspokemon.config.AppConfig
import dev.kotlinds.pokemonclient.console.Button
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.Recorder
import dev.kotlinds.pokemonclient.view.StateView
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import me.nathanfallet.aiplayspokemon.decision.DecisionBackend
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.decision.jev.JevClient
import me.nathanfallet.aiplayspokemon.decision.jev.JevDecisionModel
import me.nathanfallet.aiplayspokemon.agent.ControlMode
import me.nathanfallet.aiplayspokemon.agent.PlayerSettings
import me.nathanfallet.aiplayspokemon.mcp.GameMcpServer
import me.nathanfallet.aiplayspokemon.agent.GameSession
import me.nathanfallet.aiplayspokemon.decision.claudecode.ClaudeCodeDecisionModel
import me.nathanfallet.aiplayspokemon.decision.llm.LlmDecisionModel
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider

/**
 * Glue between the UI and the rest of the app: owns the [PokemonPlayer], turns keyboard events into
 * emulator input, and keeps a live [panel] of what the agents see, for display (even while a human plays).
 */
class AppController(
    val emulator: ConsoleHost,
    val game: PokemonGame?,
    private val config: AppConfig,
    private val scope: CoroutineScope,
) {
    private val humanButtons = mutableSetOf<Button>()

    private val _backend = MutableStateFlow(config.backend)

    /** Which kind of AI drives the player: Jev, or an LLM. */
    val backend: StateFlow<DecisionBackend> = _backend.asStateFlow()

    private val _llm = MutableStateFlow(LlmSettings(config.llmProvider, config.llmModel(config.llmProvider), config.llmThinking))

    /** The LLM used when [backend] is [DecisionBackend.LLM]. */
    val llm: StateFlow<LlmSettings> = _llm.asStateFlow()

    private val _settings = MutableStateFlow(config.playerSettings)

    /** The experiment options of the player; changes apply from the next decision. */
    val settings: StateFlow<PlayerSettings> = _settings.asStateFlow()

    private val _player = MutableStateFlow<PokemonPlayer?>(null)

    /**
     * The autonomous player, or null when it can't be created: unsupported game, or the selected
     * model needs an API key that isn't configured yet.
     */
    val player: StateFlow<PokemonPlayer?> = _player.asStateFlow()

    private val _mcp = MutableStateFlow<GameMcpServer?>(null)

    /** The MCP server when enabled: an external agent plays through it instead of our own loop. */
    val mcp: StateFlow<GameMcpServer?> = _mcp.asStateFlow()

    /**
     * Watches every frame and logs what happens (texts, battle messages, captures...), so agents get everything
     * shown since their last call. Null for unsupported games.
     */
    val recorder: Recorder? = game?.let { Recorder(it) }

    /**
     * The session the "What the AI sees" panel reads, with the same options as the agents' sessions (MCP, our loop) and
     * its own event cursor: what the panel shows is exactly what an agent would get, and it never takes the events
     * meant for the playing agent. Null for unsupported games.
     */
    private val panelSession: GameSession? = game?.let { session(it, confirmDelivery = false) }

    /** One reading of [panelSession] at a time (the panel's refresh and the F12 snapshot), off the UI thread. */
    private val panelLock = Mutex()

    private suspend fun <T> withPanelSession(block: suspend (GameSession) -> T): T? {
        val session = panelSession ?: return null
        return withContext(Dispatchers.Default) { panelLock.withLock { block(session) } }
    }

    private val _panel = MutableStateFlow<PanelView?>(null)

    /** What an agent would get from the game right now ([GameSession.describe]), refreshed a few times per second. */
    val panel: StateFlow<PanelView?> = _panel.asStateFlow()

    init {
        emulator.musicDuringPauses = _settings.value.musicDuringPausesSettings
        recorder?.let { r ->
            emulator.frameListener = r::onFrame
            emulator.humanInputListener = { r.humanInput(emulator.status.value.frameCount) }
        }
        createPlayer()
        updateMcpServer()
        scope.launch { refreshPanel() }
    }

    fun updateSettings(transform: (PlayerSettings) -> PlayerSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        config.playerSettings = updated
        emulator.musicDuringPauses = updated.musicDuringPausesSettings
    }

    /** Runs the MCP server only while the MCP tab is selected (one player at a time). */
    private fun updateMcpServer() {
        val wanted = _backend.value == DecisionBackend.MCP && game != null
        val running = _mcp.value
        if (wanted && running == null) {
            val session = session(game, confirmDelivery = true)
            val server = GameMcpServer(session, emulator, config.mcpPort, pauseWhileThinking = { _settings.value.pauseWhileThinking })
            server.start()
            _mcp.value = server
        } else if (!wanted && running != null) {
            running.stop()
            _mcp.value = null
        }
    }

    /** A session of [game] with the app's current options (read at each call, so changes apply at once). */
    private fun session(game: PokemonGame, confirmDelivery: Boolean) =
        GameSession(emulator, game, recorder!!, options = { _settings.value.agentOptions }, confirmDelivery = confirmDelivery)

    /** Switches the decision model: the current player is stopped and replaced. */
    fun selectBackend(backend: DecisionBackend) {
        if (backend == _backend.value) return
        _backend.value = backend
        config.backend = backend
        if (backend == DecisionBackend.MCP && _settings.value.mode == ControlMode.HYBRID) {
            updateSettings { it.copy(mode = ControlMode.ASSISTED) } // hybrid needs our own loop
        }
        createPlayer()
        updateMcpServer()
    }

    /** Switches the LLM provider (and loads the model last used with it). */
    fun selectLlmProvider(provider: LlmProvider) {
        config.llmProvider = provider
        _llm.value = _llm.value.copy(provider = provider, model = config.llmModel(provider))
        createPlayer()
    }

    fun setLlmModel(model: String) {
        if (model.isBlank()) return
        config.saveLlmModel(_llm.value.provider, model.trim())
        _llm.value = _llm.value.copy(model = model.trim())
        createPlayer()
    }

    /** Lets reasoning models think before each press (smarter, slower). */
    fun setLlmThinking(thinking: Boolean) {
        config.llmThinking = thinking
        _llm.value = _llm.value.copy(thinking = thinking)
        createPlayer()
    }

    /** Port of the MCP server: one app per agent, each on its own port, to compare agents side by side. */
    val mcpPort: Int get() = config.mcpPort

    /** Changes the MCP server port and restarts the server on it. */
    fun setMcpPort(port: Int) {
        if (port !in 1024..65535 || port == config.mcpPort) return
        config.mcpPort = port
        _mcp.value?.stop()
        _mcp.value = null
        updateMcpServer()
    }

    /** Stores the API key of the current model (Jev or LLM provider) in the config file. */
    fun setApiKey(apiKey: String) {
        if (apiKey.isBlank()) return
        when (_backend.value) {
            DecisionBackend.JEV -> config.jevApiKey = apiKey.trim()
            DecisionBackend.LLM -> config.saveLlmApiKey(_llm.value.provider, apiKey.trim())
            DecisionBackend.MCP -> return // the external agent brings its own model
        }
        createPlayer()
    }

    /** What the API key form should ask for, or null when the current model is ready. */
    val missingApiKey: String?
        get() = when (_backend.value) {
            DecisionBackend.JEV -> "TypeSafe (console.typesafe.ai/keys)".takeIf { _player.value == null }
            DecisionBackend.LLM -> _llm.value.provider.takeIf { it.needsApiKey && config.llmApiKey(it) == null }?.label
            DecisionBackend.MCP -> null
        }

    private fun createPlayer() {
        _player.value?.pause()
        val game = game
        val model = createModel(_backend.value)
        _player.value = if (game != null && model != null) {
            // In hybrid mode, the LLM settings define the planner (it may be the same as the decider).
            PokemonPlayer(
                emulator, game, recorder!!, model,
                planner = createModel(DecisionBackend.LLM),
                scope = scope,
                settings = { _settings.value },
                runsDirectory = config.dataDirectory.resolve("runs"),
            )
        } else null
    }

    private fun createModel(backend: DecisionBackend): DecisionModel? = when (backend) {
        DecisionBackend.JEV -> {
            // TypeSafe's cloud needs a key; a custom (e.g. local) endpoint may not.
            if (config.jevApiKey == null && config.jevEndpoint == JevClient.DEFAULT_ENDPOINT) null
            else JevDecisionModel(JevClient(config.jevApiKey, config.jevModel, config.jevEndpoint))
        }

        DecisionBackend.LLM -> {
            val (provider, model, thinking) = _llm.value
            val apiKey = config.llmApiKey(provider)
            when {
                provider == LlmProvider.CLAUDE_CODE -> ClaudeCodeDecisionModel(model, effort = if (thinking) "high" else "low")
                provider.needsApiKey && apiKey == null -> null
                else -> LlmDecisionModel(provider, model, apiKey, thinking)
            }
        }

        DecisionBackend.MCP -> null // the external agent decides
    }

    /** Reads the game a few times per second so the panel shows what an agent would get right now. */
    private suspend fun refreshPanel() {
        if (panelSession == null) return
        while (scope.isActive) {
            _panel.value = runCatching { withPanelSession { it.describeWithState() } }.getOrNull()?.let { (state, view) -> PanelView(StateView.summary(state), view) }
            delay(250)
        }
    }

    /**
     * Saves the current RAM and what the agents see ([GameSession.describe], as the panel shows it) into
     * `<data>/snapshots/`, to debug or extend the game reader on a precise situation (the RAM can be loaded back with
     * `RamMemory`).
     */
    private suspend fun saveSnapshot() {
        val ram = emulator.readMainRam()
        val directory = Files.createDirectories(config.dataDirectory.resolve("snapshots"))
        val name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        Files.write(directory.resolve("$name.ram"), ram)
        withPanelSession { it.describe() }?.let { view ->
            Files.writeString(directory.resolve("$name.json"), prettyJson.encodeToString(JsonObject.serializer(), view))
        }
        println("Snapshot saved: ${directory.resolve(name)}")
    }

    /** Handles a window key event. Returns true when the key was used. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        KeyboardMapping.buttons[event.key]?.let { button ->
            if (down) humanButtons += button else humanButtons -= button
            emulator.setButtons(humanButtons.toSet())
            return true
        }
        if (!down) return false
        KeyboardMapping.stateSlots[event.key]?.let { slot ->
            scope.launch { if (event.isShiftPressed) emulator.saveState(slot) else emulator.loadState(slot) }
            return true
        }
        when (event.key) {
            Key.Spacebar -> player.value?.toggle()
            Key.P -> togglePause()
            Key.F -> emulator.setFastForward(!emulator.status.value.fastForward)
            Key.M -> emulator.setMuted(!emulator.status.value.muted)
            Key.F12 -> scope.launch { saveSnapshot() }
            else -> return false
        }
        return true
    }

    fun togglePause() = emulator.setUserPaused(emulator.status.value.running)
}

/**
 * What the control panel shows of the game at one moment: what an agent gets ([GameSession.describe], the library's
 * [dev.kotlinds.pokemonclient.view.AgentView]) and a one-line [summary] of it.
 */
data class PanelView(val summary: String, val view: JsonObject)

/** Which LLM to use: a provider, one of its model ids, and whether it thinks before answering. */
data class LlmSettings(val provider: LlmProvider, val model: String, val thinking: Boolean)

/** JSON printed for people (the panel, the snapshots): indented. */
internal val prettyJson = Json { prettyPrint = true }
