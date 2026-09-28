package me.nathanfallet.aiplayspokemon.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.decision.DecisionException
import me.nathanfallet.aiplayspokemon.emulator.Emulator
import me.nathanfallet.aiplayspokemon.emulator.InputSource
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.PokemonGame
import me.nathanfallet.aiplayspokemon.game.RamMemory

/**
 * The autonomous player: an observe → decide → act loop.
 *
 * 1. **Observe**: snapshot the RAM and let the [game] reader describe the situation.
 * 2. **Decide**: ask the AI ([model]) which [ButtonPress] to make: any button, like a human.
 * 3. **Act**: hold that button on the emulator, release it, then wait for the game to react.
 * 4. **Remember**: record which button was pressed and what changed ([AgentMemory]).
 *
 * The loop only runs while playing ([play] / [pause]). The human keeps the keyboard at all times
 * since the emulator merges both input sources.
 */
class PokemonPlayer(
    private val emulator: Emulator,
    private val game: PokemonGame,
    val model: DecisionModel,
    private val scope: CoroutineScope,
) {
    private val memory = AgentMemory()
    private var job: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** What the player is trying to achieve; editable from the UI at any time. */
    @Volatile
    var objective: String = DecisionPrompt.DEFAULT_OBJECTIVE

    fun play() {
        if (job?.isActive == true) return
        _state.update { it.copy(playing = true, error = null) }
        job = scope.launch {
            try {
                while (isActive) {
                    try {
                        step()
                        _state.update { it.copy(error = null) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        // Network hiccup, rate limit, an answer that couldn't be parsed...: show it, wait, retry.
                        // Errors that won't fix themselves (bad API key, invalid request) stop the loop.
                        if (error is DecisionException && !error.retryable) throw error
                        _state.update { it.copy(error = error.message ?: error.toString()) }
                        delay(2_000)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: error.toString()) }
            } finally {
                emulator.setButtons(InputSource.AGENT, emptySet())
                _state.update { it.copy(playing = false) }
            }
        }
    }

    fun pause() {
        job?.cancel()
        job = null
    }

    fun toggle() = if (state.value.playing) pause() else play()

    private suspend fun observe(): Observation = game.observe(RamMemory(emulator.readMainRam()))

    /** One full observe → decide → act → remember iteration. */
    private suspend fun step() {
        // Time only passes while the emulator runs: don't spend API calls on a paused game.
        if (!emulator.status.value.running) {
            delay(100)
            return
        }

        val before = observe()
        val request = DecisionPrompt.build(objective, before, memory)
        _state.update { it.copy(lastRequestState = request.state) }

        val startedAt = System.nanoTime()
        val result = model.choose(request)
        val latencyMillis = (System.nanoTime() - startedAt) / 1_000_000
        val press = ButtonPress.fromId(result.choice) ?: error("Unknown button '${result.choice}'")

        perform(press)
        val after = observe()
        memory.record(press, before, after, result.thought)

        val decision = Decision(
            number = state.value.decisions + 1,
            observation = before,
            press = press,
            confidence = result.confidence,
            probabilities = result.probabilities.entries.sortedByDescending { it.value }.map { it.key to it.value },
            latencyMillis = latencyMillis,
            model = result.model,
            thought = result.thought,
        )
        _state.update {
            it.copy(
                lastDecision = decision,
                history = (listOf(decision) + it.history).take(HISTORY_SIZE),
                decisions = it.decisions + 1,
                inputTokens = it.inputTokens + result.inputTokens,
                totalLatencyMillis = it.totalLatencyMillis + latencyMillis,
            )
        }
    }

    /** Holds the button, releases it, then lets the game react. */
    private suspend fun perform(press: ButtonPress) {
        emulator.setButtons(InputSource.AGENT, setOf(press.button))
        emulator.awaitFrames(ButtonPress.HOLD_FRAMES)
        emulator.setButtons(InputSource.AGENT, emptySet())
        emulator.awaitFrames(ButtonPress.SETTLE_FRAMES)
    }

    private companion object {
        const val HISTORY_SIZE = 50
    }
}

/** Everything the UI shows about the player. */
data class PlayerState(
    val playing: Boolean = false,
    /** The full state sent with the last request (screen + memory), exactly as the model received it. */
    val lastRequestState: JsonObject? = null,
    val lastDecision: Decision? = null,
    /** Most recent decisions first. */
    val history: List<Decision> = emptyList(),
    val decisions: Int = 0,
    val inputTokens: Long = 0,
    val totalLatencyMillis: Long = 0,
    val error: String? = null,
) {
    val averageLatencyMillis: Long get() = if (decisions == 0) 0 else totalLatencyMillis / decisions
}

/** One decision, with the model's probability for every option. */
data class Decision(
    val number: Int,
    val observation: Observation,
    val press: ButtonPress,
    /** How certain the model was (0..1), or null when it can't tell. */
    val confidence: Double?,
    /** Options sorted by probability, most likely first. */
    val probabilities: List<Pair<String, Double>>,
    val latencyMillis: Long,
    val model: String,
    /** The model's own explanation, when it gives one (LLMs). */
    val thought: String? = null,
)
