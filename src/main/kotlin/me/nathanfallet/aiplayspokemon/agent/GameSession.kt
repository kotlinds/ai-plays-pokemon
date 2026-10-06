package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.actions.ActionError
import dev.kotlinds.pokemonclient.actions.ActionOutcome
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.ChainResult
import dev.kotlinds.pokemonclient.actions.ChainRunner
import dev.kotlinds.pokemonclient.actions.ChainStep
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.actions.InterruptionCause
import dev.kotlinds.pokemonclient.runtime.ActionInterruptedException
import dev.kotlinds.pokemonclient.runtime.EventFeed
import dev.kotlinds.pokemonclient.runtime.Recorder
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.AgentOptions
import dev.kotlinds.pokemonclient.view.AgentView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost

/**
 * The game as one agent sees it, independent of how the agent talks to us (MCP, our LLM loop, Jev): observe,
 * act through the typed action registry, and get everything that happened since the previous call.
 *
 * Every observation and action runs in a lease of the [ConsoleHost]: on the console thread, with the game advancing
 * only as the action needs. The [version] protects agents from acting on a screen they haven't seen: an action is
 * refused with STALE_STATE when the screen changed since the state the agent last received.
 */
class GameSession(
    private val host: ConsoleHost,
    private val game: PokemonGame,
    private val recorder: Recorder,
    /**
     * What the agent may know and do (mode, knowledge level, puzzles, hidden destinations), read at each call so a
     * change of the app's settings applies at once: the actions and the view follow the same options.
     */
    val options: () -> AgentOptions,
    val registry: ActionRegistry = ActionRegistry.of(),
    /**
     * When true, what a response told the agent is only forgotten once [confirmDelivered] is called: a response lost
     * on the way (client timeout) is given again with the next one. Our own loop can't lose responses (false).
     */
    private val confirmDelivery: Boolean = false,
) {
    private val _blindUses = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * Raw presses, touches and screenshots made on screens the client doesn't decode yet, by screen hint: the most
     * used ones are the next screens to decode (design §9.3).
     */
    val blindUses: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _blindUses

    /** Counts one blind use when [screen] isn't decoded. */
    fun countBlind(screen: Screen) {
        val unknown = screen as? Screen.Unknown ?: return
        val key = unknown.hint ?: "unknown"
        _blindUses.value = _blindUses.value + (key to (_blindUses.value[key] ?: 0) + 1)
    }

    /** Counts a screenshot when the current screen isn't decoded. */
    suspend fun countScreenshot() {
        val screen = host.observe { memory -> game.state(memory).screen }
        countBlind(screen)
    }

    /** The game's data (for `lookup`), when the ROM provides it. */
    val gameData get() = game.data

    /** What the agent reads, assembled by the library like for every host (the bench too). */
    private val agentView = AgentView(game, registry)

    /** The events the agent hasn't received yet (kept until a response carrying them is delivered). */
    private val feed = EventFeed(recorder.log, autoConfirm = !confirmDelivery)

    /** When the game last made progress (the recorder's clock): long chains and progress notifications use it. */
    val progress: dev.kotlinds.pokemonclient.runtime.ProgressClock get() = recorder.progress

    /** What the acts whose answer was lost did, given back with the next answers ([UnansweredCalls]). */
    private val unanswered = UnansweredCalls()

    /** The last response reached the agent: what it carried won't be repeated (see `confirmDelivery`). */
    fun confirmDelivered() {
        feed.confirm()
        unanswered.delivered()
    }

    /** Version of the last state returned to the agent: actions default to it. */
    private var lastServedVersion: Long? = null

    /** Notes the agent wrote with the `note` action, returned with every full state. */
    val notes = ArrayDeque<String>()

    /** The moves of the player noticed so far: part of [version]. */
    private val stateVersion = StateVersion()

    /**
     * Version of the state: the sequence number of the last screen change seen by the recorder, plus the player's
     * moves noticed ([StateVersion]: walking keeps the same screen kind, but the map the agent saw is then outdated).
     * An agent's action computed on version v is refused if the screen changed or the player moved after v.
     */
    val version: Long get() = stateVersion.of(recorder.log.since(0).lastOrNull { it is GameEvent.ScreenChanged }?.seq ?: 0L)

    /** Decodes the current state (on the console thread). */
    suspend fun state(): GameState = host.observe { memory -> game.state(memory) }

    /** What the agent reads: events since its last call, the screen and state, the actions possible now. */
    suspend fun describe(detail: AgentView.Detail = AgentView.Detail.STANDARD): JsonObject = describeWithState(detail).second

    /**
     * [describe], with the typed state it was made from (one reading of the game): for our own loop, whose memory works
     * on the typed state, and the app's panel.
     */
    suspend fun describeWithState(detail: AgentView.Detail = AgentView.Detail.STANDARD): Pair<GameState, JsonObject> {
        // Observing runs no frame: it works while the game is paused.
        val state = state()
        return state to describe(state, detail)
    }

    /** Keeps a note of the agent (goal, plan), returned with every full state. */
    fun addNote(text: String) {
        notes.addLast(text)
        while (notes.size > MAX_NOTES) notes.removeFirst()
    }

    /**
     * What an [act] did: the [chain]'s result (typed), the [state] after it, and the agent's answer in its two parts:
     * the chain's [outcome] (`ok`, `performed`, `error`, `not_done`... see [outcome]) and the [view] after it
     * (everything that happened meanwhile and the new state: [describe]).
     */
    class Answer(val chain: ChainResult, val state: GameState, val outcome: JsonObject, val view: JsonObject) {
        /** The answer as an agent reads it: the outcome first, then the view. */
        val json: JsonObject
            get() = buildJsonObject {
                outcome.forEach { (k, v) -> put(k, v) }
                view.forEach { (k, v) -> put(k, v) }
            }
    }

    /**
     * Executes [steps] one after the other (the first one checked against [expectedVersion], by default the version
     * of the last state returned to the agent) as one chain, the same for every agent ([ChainRunner.forAgent]: the
     * MCP's typed `then`, our own loop's option keys resolved on the screen reached): it stops at the first failure,
     * when the battle changed under it (the foe replaced or fainted, one of the player's Pokémon fainted), after a
     * failed escape, before a step its screen doesn't offer, or before a step once the limits are reached (no
     * progress for a while, or the safety cap). One answer for the whole sequence: `performed` lists every step done,
     * and the messages / events cover all of them (nothing said by the game between two steps is lost).
     * [onStepDone] is told after each step carried out, with the state then (our own loop's memory).
     */
    suspend fun act(
        steps: List<ChainStep>,
        expectedVersion: Long? = lastServedVersion,
        detail: AgentView.Detail = AgentView.Detail.COMPACT,
        onStepDone: (suspend (action: GameAction, outcome: ActionOutcome, after: GameState) -> Unit)? = null,
    ): Answer {
        require(steps.isNotEmpty()) { "no action" }
        val chain = ChainRunner.forAgent(
            recorder,
            observe = ::state,
            execute = { action, index ->
                execute(action, if (index == 0) expectedVersion else null).also { outcome -> onStepDone?.invoke(action, outcome, state()) }
            },
            settle = ::settleBattle,
        ).runSteps(steps)
        val state = state()
        val after = describe(state, detail)
        val outcome = outcome(chain)
        // Kept until this answer is known to have arrived (our own loop can't lose one).
        if (confirmDelivery) unanswered.answered(steps.map { it.key }, outcome)
        return Answer(chain, state, outcome, after)
    }

    /**
     * What a chain did, as the agent reads it: `ok`, `performed`, `detail`, the failed `action` and its `error`,
     * `not_done` (the steps left when it stopped, and why), `dropped` (battle steps skipped once the battle was over,
     * and how it ended, while the chain went on with its field steps).
     */
    private fun outcome(chain: ChainResult): JsonObject = buildJsonObject {
        put("ok", chain.failed == null)
        putJsonArray("performed") { chain.performed.forEach { add(JsonPrimitive(it)) } }
        if (chain.details.isNotEmpty()) put("detail", chain.details.joinToString("; "))
        chain.failed?.let { (action, error) ->
            put("action", action.key)
            put("error", buildJsonObject {
                put("code", error.code)
                put("message", error.message)
            })
        }
        if (chain.skipped.isNotEmpty()) {
            put("not_done", JsonArray(chain.skipped.map { JsonPrimitive(it.key) }))
            chain.stop?.let { stop ->
                put("not_done_code", stop.code)
                put("not_done_reason", stop.message)
            }
        }
        if (chain.dropped.isNotEmpty()) {
            put("dropped", JsonArray(chain.dropped.map { JsonPrimitive(it.key) }))
            chain.droppedBecause?.let { ended ->
                put("dropped_code", ended.code)
                put("dropped_reason", ended.message)
            }
        }
    }

    /** Carries out one action: checks (version, human, pause), then runs it in a console lease and lets the game settle. */
    private suspend fun execute(action: GameAction, expectedVersion: Long?): ActionOutcome {
        // Where the player stands now (a human may have walked since the last answer): part of the version.
        stateVersion.observe(host.observe { memory -> game.state(memory).field })
        val current = version
        if (expectedVersion != null && expectedVersion < current) return ActionOutcome.Failed(ActionError.StaleState(expectedVersion, current))
        // Only a human really pressing keys (or the Pause button) stops an agent; the free run doesn't.
        ActGate.refusal(humanPlaying = host.humanActivity.isPlaying(), userPaused = host.isUserPaused)
            ?.let { return ActionOutcome.Failed(it) }
        return try {
            // A long action's progress (the tiles of a go_to) goes to the recorder's clock: the MCP server's progress
            // notifications say it, and it counts as progress for a chain's IDLE limit.
            host.lease(action.key, game.inputProbe, onProgress = recorder.progress::report) {
                if (action is GameAction.Press || action is GameAction.Touch) countBlind(game.state(memory()).screen)
                registry.executeAndSettle(action, this, game, options().actionSettings)
            }
        } catch (_: ActionInterruptedException) {
            ActionOutcome.Failed(ActionError.Interrupted(InterruptionCause.HUMAN, action.key))
        }
    }

    /**
     * Lets a battle still playing out between two steps of a chain settle ([ActionRegistry.settleBetweenSteps]) and
     * reads the state then. Nothing runs while a human plays or the game is paused: the state as it is then.
     */
    private suspend fun settleBattle(): GameState {
        if (ActGate.refusal(humanPlaying = host.humanActivity.isPlaying(), userPaused = host.isUserPaused) != null) return state()
        return try {
            host.lease("settle", game.inputProbe, onProgress = recorder.progress::report) {
                ActionRegistry.settleBetweenSteps(this, game)
            }
        } catch (_: ActionInterruptedException) {
            state()
        }
    }

    /**
     * The state for the agent ([AgentView], the same as every host's), framed by what only this app knows: the
     * answers that didn't reach the agent (repeated messages, `previous_calls`) first, its notes (full state) and the
     * [version] last.
     */
    private fun describe(state: GameState, detail: AgentView.Detail): JsonObject = buildJsonObject {
        stateVersion.observe(state.field)
        val batch = feed.take()
        if (batch.repeated) put("messages_repeated", "your previous call seems not to have received its answer (timeout?): what happened since the call before it is given again")
        // The outcome of the acts whose answer was lost: they ran to their end here, whatever the client saw (even
        // without any message to repeat: a go_to says nothing).
        unanswered.describe().forEach { (k, v) -> put(k, v) }
        agentView.describe(this, state, batch.events, options(), detail)
        if (detail == AgentView.Detail.FULL && notes.isNotEmpty()) put("your_notes", JsonArray(notes.map(::JsonPrimitive)))
        put("version", version.also { lastServedVersion = it })
    }

    private companion object {
        const val MAX_NOTES = 20
    }
}
