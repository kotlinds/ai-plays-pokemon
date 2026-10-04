package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import kotlinx.serialization.Serializable

/**
 * The experiment knobs of the player. Every option can be switched in the UI so variants can be
 * compared on the same game (see the run stats for milestones reached, time and tokens).
 */
@Serializable
data class PlayerSettings(
    /**
     * What the AI's options are: assisted actions (the default: what every agent uses), raw buttons only, or a fast
     * decider + slow planner.
     */
    val mode: ControlMode = ControlMode.ASSISTED,
    /** Freeze the game while the AI thinks, so it decides on the screen it actually sees. */
    val pauseWhileThinking: Boolean = true,
    /**
     * Press timing: hold a button until the game reacts and wait until the game expects input again
     * before the next decision. When off, every press is a fixed 16-frame hold + 8-frame pause.
     */
    val waitForReaction: Boolean = true,
    /** Generative models may answer with a short sequence of options instead of one. */
    val allowSequences: Boolean = true,
    /** Generative models reason step by step before answering. */
    val reasoning: Boolean = true,
    /** Generative models keep a note (goal/plan) they rewrite at will, shown back every time. */
    val modelNotes: Boolean = true,
    /** Add the map of everything seen so far on the current map (not only the area around the player). */
    val exploredMap: Boolean = true,
    /**
     * What the agent may know beyond the screen: nothing more, the Pokédex (species / move sheets, type chart,
     * estimated effectiveness), or also a walkthrough (next story goal, why a way is blocked...).
     */
    val knowledge: KnowledgeLevel = KnowledgeLevel.POKEDEX,
    /** Pick the action by sampling the model's probabilities (Jev) instead of always the most likely one. */
    val sampleProbabilities: Boolean = false,
    /** Hybrid mode: ask the planner when the decider's confidence is below this. */
    val hybridConfidenceThreshold: Double = 0.5,
    /** Hybrid mode: ask the planner at least every N decisions. */
    val hybridPlannerEvery: Int = 30,
    /**
     * `go_to` solves movement puzzles by itself (pushes Strength boulders and ice blocks, rides the Blackthorn
     * platforms and the Violet lift); off, it only walks and the AI operates them itself (to compare both).
     */
    val solvePuzzles: Boolean = true,
)

enum class ControlMode(val label: String, val description: String) {
    PURE(
        "Pure",
        "The AI only has the 12 DS buttons (+ wait), like a human holding the controller.",
    ),
    ASSISTED(
        "Assisted",
        "The AI also gets actions handled by code: walk to an exit, talk to someone, pick a menu option, go to the next map.",
    ),
    HYBRID(
        "Hybrid",
        "Assisted actions picked by the fast decision model, with an LLM planner called when it hesitates, loops, or periodically.",
    ),
}

/** The actions this mode gives the AI (hybrid uses assisted actions, with a planner on top). */
val ControlMode.actionMode: dev.kotlinds.pokemonclient.actions.ActionMode
    get() = if (this == ControlMode.PURE) dev.kotlinds.pokemonclient.actions.ActionMode.PURE else dev.kotlinds.pokemonclient.actions.ActionMode.ASSISTED
