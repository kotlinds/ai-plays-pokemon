package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.nathanfallet.aiplayspokemon.agent.PokemonPlayer
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.emulator.Button
import me.nathanfallet.aiplayspokemon.emulator.Emulator
import me.nathanfallet.aiplayspokemon.emulator.InputSource
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.PokemonGame
import me.nathanfallet.aiplayspokemon.game.RamMemory
import me.nathanfallet.aiplayspokemon.decision.DecisionBackend
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.decision.jev.JevClient
import me.nathanfallet.aiplayspokemon.decision.jev.JevDecisionModel
import me.nathanfallet.aiplayspokemon.decision.llm.LlmDecisionModel
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider

/**
 * Glue between the UI and the rest of the app: owns the [PokemonPlayer], turns keyboard events into
 * emulator input, and keeps a live [observation] of the game for display (even while a human plays).
 */
class AppController(
    val emulator: Emulator,
    val game: PokemonGame?,
    private val config: AppConfig,
    private val scope: CoroutineScope,
) {
    private val humanButtons = mutableSetOf<Button>()

    private val _backend = MutableStateFlow(config.backend)

    /** Which kind of AI drives the player: Jev, or an LLM. */
    val backend: StateFlow<DecisionBackend> = _backend.asStateFlow()

    private val _llm = MutableStateFlow(LlmSettings(config.llmProvider, config.llmModel(config.llmProvider)))

    /** The LLM used when [backend] is [DecisionBackend.LLM]. */
    val llm: StateFlow<LlmSettings> = _llm.asStateFlow()

    private val _player = MutableStateFlow<PokemonPlayer?>(null)

    /**
     * The autonomous player, or null when it can't be created: unsupported game, or the selected
     * model needs an API key that isn't configured yet.
     */
    val player: StateFlow<PokemonPlayer?> = _player.asStateFlow()

    private val _observation = MutableStateFlow<Observation?>(null)
    val observation: StateFlow<Observation?> = _observation.asStateFlow()

    init {
        createPlayer()
        scope.launch { refreshObservation() }
    }

    /** Switches the decision model: the current player is stopped and replaced. */
    fun selectBackend(backend: DecisionBackend) {
        if (backend == _backend.value) return
        _backend.value = backend
        config.backend = backend
        createPlayer()
    }

    /** Switches the LLM provider (and loads the model last used with it). */
    fun selectLlmProvider(provider: LlmProvider) {
        config.llmProvider = provider
        _llm.value = LlmSettings(provider, config.llmModel(provider))
        createPlayer()
    }

    fun setLlmModel(model: String) {
        if (model.isBlank()) return
        config.saveLlmModel(_llm.value.provider, model.trim())
        _llm.value = _llm.value.copy(model = model.trim())
        createPlayer()
    }

    /** Stores the API key of the current model (Jev or LLM provider) in the config file. */
    fun setApiKey(apiKey: String) {
        if (apiKey.isBlank()) return
        when (_backend.value) {
            DecisionBackend.JEV -> config.jevApiKey = apiKey.trim()
            DecisionBackend.LLM -> config.saveLlmApiKey(_llm.value.provider, apiKey.trim())
        }
        createPlayer()
    }

    /** What the API key form should ask for, or null when the current model is ready. */
    val missingApiKey: String?
        get() = when (_backend.value) {
            DecisionBackend.JEV -> "TypeSafe (console.typesafe.ai/keys)".takeIf { _player.value == null }
            DecisionBackend.LLM -> _llm.value.provider.takeIf { it.needsApiKey && config.llmApiKey(it) == null }?.label
        }

    private fun createPlayer() {
        _player.value?.pause()
        val game = game
        val model = createModel()
        _player.value = if (game != null && model != null) PokemonPlayer(emulator, game, model, scope) else null
    }

    private fun createModel(): DecisionModel? = when (_backend.value) {
        DecisionBackend.JEV -> {
            // TypeSafe's cloud needs a key; a custom (e.g. local) endpoint may not.
            if (config.jevApiKey == null && config.jevEndpoint == JevClient.DEFAULT_ENDPOINT) null
            else JevDecisionModel(JevClient(config.jevApiKey, config.jevModel, config.jevEndpoint))
        }

        DecisionBackend.LLM -> {
            val (provider, model) = _llm.value
            val apiKey = config.llmApiKey(provider)
            if (provider.needsApiKey && apiKey == null) null else LlmDecisionModel(provider, model, apiKey)
        }
    }

    /** Polls the RAM a few times per second so the panel shows what the AI would see right now. */
    private suspend fun refreshObservation() {
        val game = game ?: return
        while (scope.isActive) {
            _observation.value = runCatching { game.observe(RamMemory(emulator.readMainRam())) }.getOrNull()
            delay(250)
        }
    }

    /** Handles a window key event. Returns true when the key was used. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        KeyboardMapping.buttons[event.key]?.let { button ->
            if (down) humanButtons += button else humanButtons -= button
            emulator.setButtons(InputSource.HUMAN, humanButtons.toSet())
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
            else -> return false
        }
        return true
    }

    fun togglePause() = if (emulator.status.value.running) emulator.pause() else emulator.resume()
}

/** Which LLM to use: a provider and one of its model ids. */
data class LlmSettings(val provider: LlmProvider, val model: String)
