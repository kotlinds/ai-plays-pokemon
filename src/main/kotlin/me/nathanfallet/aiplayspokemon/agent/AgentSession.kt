package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.actions.ActionOutcome
import dev.kotlinds.pokemonclient.actions.ChainRunner
import dev.kotlinds.pokemonclient.actions.ChainStep
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.runtime.Recorder
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.AgentView
import dev.kotlinds.pokemonclient.world.Area
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost

/**
 * The game as seen by our own AI player (LLM through Koog, Claude Code, Jev), on top of the [GameSession] shared
 * with MCP agents: the same typed actions, the same state, the same verification, the same chains. A turn is:
 * [prepare] (the state, the possible actions as options with stable keys, the request), then [act] (the chosen
 * action and its sequence carried out as one chain, what changed remembered). The next turn's state is read anew and
 * carries what the act's answer told (its outcome, everything the game said during those actions: [UnreadAnswer]).
 */
class AgentSession(
    private val host: ConsoleHost,
    private val game: PokemonGame,
    recorder: Recorder,
    val settings: () -> PlayerSettings,
) {
    val gameSession = GameSession(host, game, recorder, options = { settings().agentOptions })
    val memory = AgentMemory()

    /** Number of actions carried out. */
    var actionsDone = 0
        private set

    /** What the player is trying to achieve; editable at any time. */
    @Volatile
    var objective: String = DecisionPrompt.defaultObjective(game.name)

    /** Goal written by the planner in hybrid mode, shown to the fast decider. */
    @Volatile
    var plannerGoal: String? = null

    /** The last [act]'s answer, until the next turn's state carries it to the model ([UnreadAnswer]). */
    private val unread = UnreadAnswer()

    /** One possible action, as offered to models: a stable key and a description. */
    data class Option(val key: String, val description: String, val action: GameAction)

    /**
     * What the AI sees and can do at one moment: [state] is what the model reads ([GameSession.describe]), made from the
     * typed [gameState] (what the memory and the run's statistics work on).
     */
    class Turn(val gameState: GameState, val state: JsonObject, val options: List<Option>) {
        /** Turns a key chosen by a model back into the typed action, or null if it isn't offered now. */
        fun resolve(key: String): Option? = options.firstOrNull { it.key == key }
    }

    /**
     * The state of this turn, always read from the game now (the game may have moved on since the last act: the free
     * run, a human), with what the last act's answer told that the model hasn't read yet (its outcome, the messages
     * and events of its actions: [UnreadAnswer]), once. Its options.
     */
    suspend fun prepare(): Turn {
        val (gameState, fresh) = gameSession.describeWithState()
        val state = unread.into(fresh)
        memory.observeMap(gameState.field, area(gameState))
        val options = options(gameState).values.map { Option(it.key, describe(it, gameState.screen), it) }
        return Turn(gameState, state, options)
    }

    /** The options of [state] by key, as models pick them (the session's mode). */
    private fun options(state: GameState): Map<String, GameAction> = gameSession.enumerate(state)

    fun request(turn: Turn, generative: Boolean, planner: Boolean = false): ChoiceRequest = DecisionPrompt.build(
        objective = objective,
        turn = turn,
        memory = memory,
        settings = settings(),
        generative = generative,
        plannerGoal = plannerGoal.takeIf { settings().mode == ControlMode.HYBRID && !planner },
        planner = planner,
        gameName = game.name,
    )

    /**
     * What happened when carrying out a choice (and its sequence): the [problem] when a step failed; [stop] when the
     * sequence ended early by one of its rules without anything failing (a step not offered on the screen reached,
     * the battle changed, nothing happening for a while: [dev.kotlinds.pokemonclient.actions.ChainStop]), with the
     * [skipped] steps.
     */
    data class Report(val after: GameState, val performed: List<String>, val problem: String?, val stop: String?, val skipped: Int)

    /**
     * Carries out [choice], then the options of [then] (each resolved on the screen reached when its turn comes), as one
     * chain run like the MCP agents' ([GameSession.act]: the same stop rules and limits, at most
     * [ChainRunner.MAX_THEN] further steps). Every step is remembered with what it changed.
     */
    suspend fun act(turn: Turn, choice: Option, then: List<String> = emptyList(), thought: String? = null, note: String? = null): Report {
        memory.updateNote(note)
        val steps = listOf<ChainStep>(ChainStep.Planned(choice.action)) +
            then.take(ChainRunner.MAX_THEN).map { key -> ChainStep.ByKey(key) { state -> options(state)[key] } }
        var before = turn.gameState
        var first = true
        val answer = gameSession.act(steps, detail = AgentView.Detail.STANDARD) { action, outcome, after ->
            actionsDone++
            val problem = (outcome as? ActionOutcome.Failed)?.error?.message
            memory.record(action.key, before, after, area(after), thought.takeIf { first }, problem)
            first = false
            before = after
        }
        unread.keep(answer.outcome, answer.view)
        val chain = answer.chain
        return Report(answer.state, chain.performed, chain.failed?.second?.message, chain.stop?.message, chain.skipped.size)
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

    /** The ROM's map of where the player is in [state], when the game has one (for the explored map). */
    private fun area(state: GameState): Area? = state.field?.let { game.world?.areaOf(it.mapId) }

    /** A one-line description of an action for models that read option lists ([screen]: the typed screen it is on). */
    private fun describe(action: GameAction, screen: Screen): String = when (action) {
        is GameAction.Press -> "Press ${action.button.name}."
        is GameAction.Wait -> "Wait until the game needs you again."
        is GameAction.AdvanceDialogue -> "Read the messages to the end (stops at a choice)."
        is GameAction.Choose -> "Choose “${(screen as? Screen.Selectable)?.entries?.firstOrNull { it.id == action.entry }?.label ?: action.entry}”."
        is GameAction.Attack -> "Attack with ${action.move.raw}${action.target?.let { " on ${it.wire}" } ?: ""}."
        is GameAction.Run -> "Run away from the battle."
        is GameAction.KeepBattling -> "Keep your Pokémon in."
        else -> action.key
    }
}

/**
 * The answer of an [AgentSession.act] the model hasn't read yet: the next turn's state is always a new reading of the
 * game ([AgentSession.prepare]), which must not lose what that answer told. It is carried into the next reading once
 * ([into]), then forgotten: nothing piles up from turn to turn.
 */
internal class UnreadAnswer {
    private var outcome: JsonObject? = null
    private var view: JsonObject? = null

    /** The answer of an act: the chain's [outcome] and the [view] after it ([GameSession.Answer]). */
    fun keep(outcome: JsonObject, view: JsonObject) {
        this.outcome = outcome
        this.view = view
    }

    /**
     * [fresh], a new reading, with what the unread answer told: its outcome first (ok, performed, error, not_done...,
     * unless the reading says the same key), and its messages and events before the new ones. Once: the answer is
     * forgotten afterwards ([fresh] alone the next time).
     */
    fun into(fresh: JsonObject): JsonObject {
        val outcome = outcome ?: return fresh
        val view = view.orEmptyObject()
        this.outcome = null
        this.view = null
        return buildJsonObject {
            outcome.forEach { (k, v) -> if (k !in fresh) put(k, v) }
            fresh.forEach { (k, v) ->
                if (k in CARRIED_LISTS) put(k, JsonArray(((view[k] as? JsonArray).orEmpty()) + ((v as? JsonArray).orEmpty())))
                else put(k, v)
            }
            CARRIED_LISTS.filter { it !in fresh && it in view }.forEach { put(it, view[it]!!) }
        }
    }

    private companion object {
        /** What the game said, which a new reading must not lose. */
        val CARRIED_LISTS = listOf(AgentView.MESSAGES, AgentView.EVENTS)
    }
}

private fun JsonObject?.orEmptyObject(): JsonObject = this ?: JsonObject(emptyMap())

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList() ?: emptyList()
