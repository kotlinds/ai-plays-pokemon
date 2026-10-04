package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.runtime.Recorder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost

/**
 * The game as seen by our own AI player (LLM through Koog, Claude Code, Jev), on top of the [GameSession] shared
 * with MCP agents: the same typed actions, the same state, the same verification. A turn is: [prepare] (observe,
 * list the possible actions as options with stable keys, build the request), then [act] (carry the chosen action
 * out, remember what changed).
 */
class AgentSession(
    private val host: ConsoleHost,
    private val game: PokemonGame,
    recorder: Recorder,
    val settings: () -> PlayerSettings,
) {
    val gameSession = GameSession(
        host, game, recorder, mode = { settings().mode.actionMode },
        // The knowledge level chosen in the app, like the MCP agents get (walkthrough: hidden items, puzzle plans...).
        knowledge = { settings().knowledge },
        solvePuzzles = { settings().solvePuzzles },
    )
    val memory = AgentMemory()

    /** Number of actions carried out. */
    var actionsDone = 0
        private set

    /** What the player is trying to achieve; editable at any time. */
    @Volatile
    var objective: String = DecisionPrompt.DEFAULT_OBJECTIVE

    /** Goal written by the planner in hybrid mode, shown to the fast decider. */
    @Volatile
    var plannerGoal: String? = null

    /** One possible action, as offered to models: a stable key and a description. */
    data class Option(val key: String, val description: String, val action: GameAction)

    /** What the AI sees and can do at one moment. */
    class Turn(val observation: Observation, val state: JsonObject, val options: List<Option>) {
        /** Turns a key chosen by a model back into the typed action, or null if it isn't offered now. */
        fun resolve(key: String): Option? = options.firstOrNull { it.key == key }
    }

    /** Observes the game (the agent's view + the legacy observation used by the memory) and lists the options. */
    suspend fun prepare(): Turn {
        val observation = observe()
        memory.observeMap(observation)
        val state = gameSession.describe()
        val gameState = gameSession.state()
        val options = gameSession.registry.enumerate(gameState, settings().mode.actionMode).values.map { Option(it.key, describe(it, state), it) }
        return Turn(observation, state, options)
    }

    fun request(turn: Turn, generative: Boolean, planner: Boolean = false): ChoiceRequest = DecisionPrompt.build(
        objective = objective,
        turn = turn,
        memory = memory,
        settings = settings(),
        generative = generative,
        plannerGoal = plannerGoal.takeIf { settings().mode == ControlMode.HYBRID && !planner },
        planner = planner,
    )

    /** What happened when carrying out a choice (and its sequence). */
    data class Report(val after: Observation, val performed: List<String>, val problem: String?, val skipped: Int)

    /**
     * Carries out [choice], then the options of [then] one by one (each resolved against the situation at that
     * moment), stopping at the first failure or screen change: the model chose them for the screen it saw.
     */
    suspend fun act(turn: Turn, choice: Option, then: List<String> = emptyList(), thought: String? = null, note: String? = null): Report {
        memory.updateNote(note)
        val performed = mutableListOf<String>()
        var current = turn
        var option = choice
        var remaining = then
        while (true) {
            val result = gameSession.act(option.action)
            actionsDone++
            val after = observe()
            val problem = result["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            memory.record(option.key, current.observation, after, thought.takeIf { performed.isEmpty() }, problem)
            performed += option.key
            val changed = after.mode != current.observation.mode || after.location?.mapId != current.observation.location?.mapId
            if (remaining.isEmpty() || problem != null || changed) return Report(after, performed, problem, remaining.size)
            current = prepare()
            option = current.resolve(remaining.first()) ?: return Report(current.observation, performed, null, remaining.size)
            remaining = remaining.drop(1)
        }
    }

    /**
     * Runs [block] (the AI thinking) with the game frozen when [PlayerSettings.pauseWhileThinking] is set, so the AI
     * decides on the screen it saw. A pause made by the user is kept.
     */
    suspend fun <T> thinking(block: suspend () -> T): T {
        if (!settings().pauseWhileThinking || !host.status.value.running) return block()
        host.pause()
        try {
            return block()
        } finally {
            host.resume()
        }
    }

    private suspend fun observe(): Observation = game.observe(RamMemory(host.readMainRam()))

    /** A one-line description of an action for models that read option lists. */
    private fun describe(action: GameAction, state: JsonObject): String = when (action) {
        is GameAction.Press -> "Press ${action.button.name}."
        is GameAction.Wait -> "Wait until the game needs you again."
        is GameAction.AdvanceDialogue -> "Read the messages to the end (stops at a choice)."
        is GameAction.Choose -> "Choose “${entryLabel(state, action.entry) ?: action.entry}”."
        is GameAction.Attack -> "Attack with ${action.move.raw}${action.target?.let { " on ${it.wire}" } ?: ""}."
        is GameAction.Run -> "Run away from the battle."
        is GameAction.KeepBattling -> "Keep your Pokémon in."
        else -> action.key
    }

    private fun entryLabel(state: JsonObject, id: String): String? =
        (state["screen"] as? JsonObject)?.get("entries")?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.map { it.jsonObject }?.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == id }?.get("label")?.jsonPrimitive?.contentOrNull
}
