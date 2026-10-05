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
import dev.kotlinds.pokemonclient.runtime.Recorder
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionException
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.GameState
import java.nio.file.Path
import kotlin.random.Random

/**
 * The autonomous player: an observe → decide → act → remember loop over an [AgentSession].
 *
 * 1. **Observe**: wait until the game expects input, read it, list the options of the current mode.
 * 2. **Decide**: ask the decision [model] which option to take (and, for generative models, maybe a
 *    short sequence and an updated note). In hybrid mode, the [planner] is asked instead when the
 *    model hesitates, when nothing has progressed for a while, or periodically; its note becomes the
 *    goal the fast model follows.
 * 3. **Act / remember**: the session carries the choice out and records what changed.
 *
 * The game is frozen while the AI thinks (when enabled), and the human keeps the keyboard at all times.
 */
class PokemonPlayer(
    emulator: ConsoleHost,
    game: PokemonGame,
    recorder: Recorder,
    val model: DecisionModel,
    private val planner: DecisionModel?,
    private val scope: CoroutineScope,
    settings: () -> PlayerSettings,
    runsDirectory: Path?,
) {
    val session = AgentSession(emulator, game, recorder, settings)
    private val settings get() = session.settings()
    private val emulator = emulator
    private val stats = RunStats(runsDirectory, "${model.name}${planner?.let { " + planner ${it.name}" } ?: ""}")
    private var job: Job? = null
    private var decisionsSincePlanner = 0

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    var objective: String
        get() = session.objective
        set(value) {
            session.objective = value
        }

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
                _state.update { it.copy(playing = false, thinking = false) }
            }
        }
    }

    fun pause() {
        job?.cancel()
        job = null
    }

    fun toggle() = if (state.value.playing) pause() else play()

    /** One full observe → decide → act → remember iteration. */
    private suspend fun step() {
        // Time only passes while the emulator runs: don't spend API calls on a game paused by the user.
        if (!emulator.status.value.running) {
            delay(100)
            return
        }
        val turn = session.prepare()
        if (state.value.decisions == 0) stats.start(turn.gameState)
        val startedAt = System.nanoTime()
        val (result, byPlanner) = session.thinking {
            _state.update { it.copy(thinking = true) }
            try {
                decide(turn)
            } finally {
                _state.update { it.copy(thinking = false) }
            }
        }
        val latencyMillis = (System.nanoTime() - startedAt) / 1_000_000
        val chosenId = pick(result)
        val choice = turn.resolve(chosenId)
            ?: throw DecisionException("${model.name} chose '$chosenId', which isn't an option now", retryable = true)
        if (byPlanner) result.note?.let { session.plannerGoal = it }

        val report = session.act(turn, choice, if (chosenId == result.choice) result.then else emptyList(), result.thought, result.note)

        val decision = Decision(
            number = state.value.decisions + 1,
            state = turn.gameState,
            actions = report.performed.ifEmpty { listOf(choice.key) },
            confidence = result.confidence,
            probabilities = result.probabilities.entries.sortedByDescending { it.value }.map { it.key to it.value },
            latencyMillis = latencyMillis,
            model = result.model,
            thought = result.thought,
            note = result.note,
            byPlanner = byPlanner,
            problem = report.problem,
        )
        val presses = session.actionsDone
        _state.update {
            val inputTokens = it.inputTokens + result.inputTokens
            val cost = it.costUsd + (result.costUsd ?: 0.0)
            it.copy(
                lastRequestState = it.lastRequestState,
                lastDecision = decision,
                history = (listOf(decision) + it.history).take(HISTORY_SIZE),
                decisions = it.decisions + 1,
                presses = presses,
                inputTokens = inputTokens,
                costUsd = cost,
                totalLatencyMillis = it.totalLatencyMillis + latencyMillis,
                milestones = it.milestones + stats.check(report.after, it.decisions + 1, presses, inputTokens, cost),
                note = session.memory.note,
                plannerGoal = session.plannerGoal,
            )
        }
    }

    /** Asks the model, or in hybrid mode the planner when needed. Returns the result and whether the planner decided. */
    private suspend fun decide(turn: AgentSession.Turn): Pair<ChoiceResult, Boolean> {
        val planner = planner.takeIf { settings.mode == ControlMode.HYBRID }
        val periodic = planner != null && (session.plannerGoal == null || decisionsSincePlanner >= settings.hybridPlannerEvery ||
            session.memory.decisionsWithoutProgress >= STAGNATION_DECISIONS)
        if (planner != null && periodic) return askPlanner(planner, turn)

        val request = session.request(turn, generative = model.generative)
        _state.update { it.copy(lastRequestState = request.state) }
        val result = model.choose(request)
        decisionsSincePlanner++
        val hesitant = (result.confidence ?: 1.0) < settings.hybridConfidenceThreshold
        if (planner != null && hesitant) return askPlanner(planner, turn)
        return result to false
    }

    private suspend fun askPlanner(planner: DecisionModel, turn: AgentSession.Turn): Pair<ChoiceResult, Boolean> {
        val request = session.request(turn, generative = true, planner = true)
        _state.update { it.copy(lastRequestState = request.state) }
        decisionsSincePlanner = 0
        return planner.choose(request) to true
    }

    /** The chosen option: the model's answer, or a sample from its probabilities when enabled. */
    private fun pick(result: ChoiceResult): String {
        if (!settings.sampleProbabilities || result.confidence == null) return result.choice
        var remaining = Random.nextDouble()
        for ((option, probability) in result.probabilities) {
            remaining -= probability
            if (remaining <= 0) return option
        }
        return result.choice
    }

    private companion object {
        const val HISTORY_SIZE = 50
        const val STAGNATION_DECISIONS = 10
    }
}

/** Everything the UI shows about the player. */
data class PlayerState(
    val playing: Boolean = false,
    /** True while the model is thinking (the game is frozen if "pause while thinking" is on). */
    val thinking: Boolean = false,
    /** The full state sent with the last request (screen + memory), exactly as the model received it. */
    val lastRequestState: JsonObject? = null,
    val lastDecision: Decision? = null,
    /** Most recent decisions first. */
    val history: List<Decision> = emptyList(),
    val decisions: Int = 0,
    val presses: Int = 0,
    val inputTokens: Long = 0,
    val costUsd: Double = 0.0,
    val totalLatencyMillis: Long = 0,
    val milestones: List<RunStats.Milestone> = emptyList(),
    val note: String? = null,
    val plannerGoal: String? = null,
    val error: String? = null,
) {
    val averageLatencyMillis: Long get() = if (decisions == 0) 0 else totalLatencyMillis / decisions
}

/** One decision, with the model's probability for every option. */
data class Decision(
    val number: Int,
    /** The state the decision was made on (what the history line summarizes). */
    val state: GameState,
    /** Actions carried out (the choice, then the sequence if any). */
    val actions: List<String>,
    /** How certain the model was (0..1), or null when it can't tell. */
    val confidence: Double?,
    /** Options sorted by probability, most likely first. */
    val probabilities: List<Pair<String, Double>>,
    val latencyMillis: Long,
    val model: String,
    /** The model's own explanation, when it gives one (LLMs). */
    val thought: String? = null,
    val note: String? = null,
    /** Hybrid mode: true when the planner made this decision. */
    val byPlanner: Boolean = false,
    /** Why the action stopped early, if it did. */
    val problem: String? = null,
)
