package me.nathanfallet.aiplayspokemon.agent

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

    const val DEFAULT_OBJECTIVE =
        "Play Pokémon HeartGold like a good player: follow the story, talk to people to learn where to go, " +
            "explore new places, win battles, catch Pokémon and keep the team healthy."

    /** What any player of the series knows about the controls; not specific to the situation. */
    private const val CONVENTIONS =
        "How the game works: a D-pad press turns you to face that direction if you weren't already, " +
            "otherwise it walks one tile. A talks to or examines what you are facing and advances text; " +
            "a message box must be advanced with A until it closes. B cancels and backs out of menus and screens. " +
            "Walking onto stairs, doors and ladders (exits) takes you to another place; for exit mats at the edge " +
            "of a room, stand on them and press towards the edge. People walk around and block the way. " +
            "PCs, TVs and bookshelves are optional; the story moves forward by talking to people and going to new places."

    private fun instructions(settings: PlayerSettings, planner: Boolean): String = buildString {
        append("You are playing Pokémon HeartGold on a Nintendo DS. ")
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
        if (settings.knowledge.allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)) append(" and `story_goal`")
        append(". If your recent actions changed nothing, do something different.")
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
    ) = ChoiceRequest(
        state = buildJsonObject {
            put("objective", objective)
            if (settings.knowledge.allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)) turn.observation.storyGoal?.let { put("story_goal", it) }
            plannerGoal?.let { put("goal_from_planner", it) }
            put("game", turn.state)
            put("memory", memory.describe(settings.exploredMap, turn.observation))
        },
        instructions = instructions(settings, planner),
        options = turn.options.associate { it.key to it.description },
        allowSequence = generative && settings.allowSequences,
        reasoning = settings.reasoning,
        allowNote = generative && (settings.modelNotes || planner),
    )
}
