package me.nathanfallet.aiplayspokemon.agent

import me.nathanfallet.aiplayspokemon.agent.actions.ActionCatalog
import me.nathanfallet.aiplayspokemon.agent.actions.AgentAction
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.emulator.Emulator
import me.nathanfallet.aiplayspokemon.game.Observation
import me.nathanfallet.aiplayspokemon.game.PokemonGame

/**
 * The game as seen by an AI player, independent of who decides: our own loop ([PokemonPlayer]
 * asking a [me.nathanfallet.aiplayspokemon.decision.DecisionModel]) or an external agent through MCP.
 *
 * A turn is: [prepare] (wait for the game, observe, list the options of the current mode, build the
 * request), then [act] (carry out the chosen option and an optional sequence, remember what changed).
 */
class AgentSession(
    emulator: Emulator,
    game: PokemonGame,
    val settings: () -> PlayerSettings,
) {
    val controller = GameController(emulator, game, settings)
    val memory = AgentMemory()

    /** What the player is trying to achieve; editable at any time. */
    @Volatile
    var objective: String = DecisionPrompt.DEFAULT_OBJECTIVE

    /** Goal written by the planner in hybrid mode, shown to the fast decider. */
    @Volatile
    var plannerGoal: String? = null

    /** What the AI sees and can do at one moment. */
    class Turn(val observation: Observation, val actions: List<AgentAction>) {
        /** Turns an option id chosen by a model back into the typed action, or null if it isn't offered now. */
        fun resolve(id: String): AgentAction? = actions.firstOrNull { it.id == id }
    }

    /** Waits until the game expects input, then observes and lists the options of the current mode. */
    suspend fun prepare(): Turn {
        val observation = if (settings().waitForReaction) controller.waitForInput() else controller.observe()
        memory.observeMap(observation)
        return Turn(observation, ActionCatalog.actions(observation, settings().mode))
    }

    fun request(turn: Turn, generative: Boolean, planner: Boolean = false): ChoiceRequest = DecisionPrompt.build(
        objective = objective,
        observation = turn.observation,
        memory = memory,
        actions = turn.actions,
        settings = settings(),
        generative = generative,
        plannerGoal = plannerGoal.takeIf { settings().mode == ControlMode.HYBRID && !planner },
        planner = planner,
    )

    /** What happened when carrying out a choice (and its sequence). */
    data class Report(val after: Observation, val performed: List<AgentAction>, val problem: String?, val skipped: Int)

    /**
     * Carries out [choice], then the options of [then] one by one. Sequence ids are resolved against
     * the situation at that moment (options like "exit_0" depend on what's on screen), and the
     * sequence stops early when something unexpected happens (an action fails, the screen or map
     * changes, a dialogue appears, an id isn't offered any more), since the model chose it for the
     * situation it saw.
     */
    suspend fun act(turn: Turn, choice: AgentAction, then: List<String> = emptyList(), thought: String? = null, note: String? = null): Report {
        memory.updateNote(note)
        val performed = ArrayList<AgentAction>()
        var current = turn
        var action = choice
        var remaining = then
        while (true) {
            val outcome = action.execute(controller)
            memory.record(action, current.observation, outcome.observation, thought.takeIf { performed.isEmpty() }, outcome.problem)
            performed += action
            val interrupted = outcome.problem != null ||
                outcome.observation.mode != current.observation.mode ||
                outcome.observation.location?.mapId != current.observation.location?.mapId ||
                (outcome.observation.dialogue != null && outcome.observation.dialogue != current.observation.dialogue)
            if (remaining.isEmpty() || interrupted) return Report(outcome.observation, performed, outcome.problem, remaining.size)
            current = prepare()
            action = current.resolve(remaining.first()) ?: return Report(current.observation, performed, null, remaining.size)
            remaining = remaining.drop(1)
        }
    }
}
