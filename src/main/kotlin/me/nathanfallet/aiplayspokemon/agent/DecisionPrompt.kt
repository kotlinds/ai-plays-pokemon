package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.actions.ChainLimits
import dev.kotlinds.pokemonclient.actions.ChainRunner
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest

/**
 * Turns "what the player can perceive and remember" into a decision request.
 *
 * The state is what a human would get from the screen (read from RAM) plus their memory, and the
 * general knowledge any player of the series has (how the controls behave). It never says which
 * option is right: with the story goal assist off, the AI has to work out where to go by itself.
 */
object DecisionPrompt {

    /** The default objective for the game named [gameName] (the detected ROM's name, e.g. "Pokémon Platinum (USA)"). */
    fun defaultObjective(gameName: String): String =
        "Play ${gameName.substringBefore(" (")} like a good player: follow the story, talk to people to learn where to go, " +
            "explore new places, win battles, catch Pokémon and keep the team healthy."

    /** What any player of the series knows about the controls; not specific to the situation. */
    private const val CONVENTIONS =
        "How the game works: a D-pad press turns you to face that direction if you weren't already, " +
            "otherwise it walks one tile. A talks to or examines what you are facing and advances text; " +
            "a message box must be advanced with A until it closes. B cancels and backs out of menus and screens. " +
            "Walking onto stairs, doors and ladders (exits) takes you to another place; for exit mats at the edge " +
            "of a room, stand on them and press towards the edge. People walk around and block the way. " +
            "PCs, TVs and bookshelves are optional; the story moves forward by talking to people and going to new places."

    /**
     * How a sequence (the options after the choice: `then`) runs: the same chain as the MCP agents' (`ChainRunner.forAgent`,
     * its stop rules and [ChainLimits.AGENT]), told so the model knows why its sequence may end early and where to read
     * why (`not_done_code`, `dropped_code` in the next state).
     */
    private val SEQUENCE_RULES =
        "Options you add after your choice run one after the other, each on the screen the previous one led to (at most " +
            "${ChainRunner.MAX_THEN}). The sequence stops early, the options left not done (the next state says which and why: " +
            "`not_done`, `not_done_code`), when: an opponent is replaced or faints, or one of your battling Pokémon faints; an " +
            "escape fails; an option isn't offered on the screen reached; nothing happened in the game for " +
            "${ChainLimits.AGENT.idle.inWholeSeconds} s, or after ${ChainLimits.AGENT.total.inWholeSeconds} s in all. When the battle " +
            "ends, its battle options left are dropped (`dropped`, `dropped_code`) and the others go on."

    private fun instructions(settings: PlayerSettings, planner: Boolean, gameName: String, sequences: Boolean): String = buildString {
        append("You are playing ${gameName.substringBefore(" (")} on a Nintendo DS. ")
        append(
            when (settings.mode) {
                ControlMode.PURE -> "You hold the controller: each option is a button to press (or waiting). "
                else -> "Each option is either an action your hands carry out for you (walking to a place, talking " +
                    "to someone, choosing a menu option, exploring in a direction) or a single button press. "
            }
        )
        append("`game` describes what is on screen right now, and `memory` is what you remember: your recent ")
        append("actions and exactly what changed after each one, where you have been, what you read")
        if (settings.exploredMap) append(", the map of what you have explored")
        append(". ")
        append(CONVENTIONS)
        append(" Choose the next option that makes the most progress towards `objective`")
        if (settings.knowledge.allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)) append(" and `game.story_goals` (when there are several open goals, any of them)")
        append(". If your recent actions changed nothing, do something different.")
        if (sequences) append(" ").append(SEQUENCE_RULES)
        if (planner) append(" You are the planner: also write in `note` the goal and plan the fast decision model should follow next.")
    }

    fun build(
        objective: String,
        turn: AgentSession.Turn,
        memory: AgentMemory,
        settings: PlayerSettings,
        generative: Boolean,
        plannerGoal: String? = null,
        planner: Boolean = false,
        /** The game being played (the detected ROM's name): the prompt names it, whatever the game. */
        gameName: String,
    ) = ChoiceRequest(
        state = buildJsonObject {
            put("objective", objective)
            plannerGoal?.let { put("goal_from_planner", it) }
            put("game", turn.state)
            put("memory", memory.describe(settings.exploredMap, turn.gameState.field))
        },
        instructions = instructions(settings, planner, gameName, sequences = generative && settings.allowSequences),
        options = turn.options.associate { it.key to it.description },
        allowSequence = generative && settings.allowSequences,
        reasoning = settings.reasoning,
        allowNote = generative && (settings.modelNotes || planner),
    )
}
