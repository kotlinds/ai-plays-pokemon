package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.game.Observation

/**
 * Turns "what the player can perceive" into a decision request: which button to press next.
 *
 * The state is what a human would get from the screen (read from RAM: the text-rendered map, the
 * dialogue text, the party, the battle...) plus the short history of their own presses. The options
 * are always all the buttons: the model is never told which one is "right".
 */
object DecisionPrompt {

    const val DEFAULT_OBJECTIVE =
        "Play Pokémon HeartGold like a good player: follow the story, talk to people to learn where to go, " +
            "explore new places, win battles, catch Pokémon and keep the team healthy."

    private const val INSTRUCTIONS =
        "You are playing a Pokémon game on a Nintendo DS, holding the controller. " +
            "`game` describes what is on screen right now (the map around you as text when in the overworld, " +
            "the current dialogue, menus, battle, your team), and `recent_presses` lists the buttons you just " +
            "pressed and what changed after each one. Choose the next button to press to make progress " +
            "towards `objective`."

    fun build(objective: String, observation: Observation, memory: AgentMemory) = ChoiceRequest(
        state = buildJsonObject {
            put("objective", objective)
            put("game", observation.state)
            put("recent_presses", memory.describe())
        },
        instructions = INSTRUCTIONS,
        options = ButtonPress.entries.associate { it.id to it.description },
    )
}
