package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.Serializable

/**
 * The experiment knobs of the player. Every option can be switched in the UI so variants can be
 * compared on the same game (see the run stats for milestones reached, time and tokens).
 */
@Serializable
data class PlayerSettings(
    /** What the AI's options are: raw buttons, assisted actions, or a fast decider + slow planner. */
    val mode: ControlMode = ControlMode.PURE,
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
    /** Add the next story objective read from the game's flags (an assist, off by default). */
    val storyGoal: Boolean = false,
    /** Pick the action by sampling the model's probabilities (Jev) instead of always the most likely one. */
    val sampleProbabilities: Boolean = false,
    /** Hybrid mode: ask the planner when the decider's confidence is below this. */
    val hybridConfidenceThreshold: Double = 0.5,
    /** Hybrid mode: ask the planner at least every N decisions. */
    val hybridPlannerEvery: Int = 30,
)

enum class ControlMode(val label: String, val description: String) {
    PURE(
        "Pure",
        "The AI only has the 12 DS buttons (+ wait), like a human holding the controller.",
    ),
    ASSISTED(
        "Assisted",
        "The AI also gets actions handled by code: walk to an exit, talk to someone, pick a menu option, explore a direction.",
    ),
    HYBRID(
        "Hybrid",
        "Assisted actions picked by the fast decision model, with an LLM planner called when it hesitates, loops, or periodically.",
    ),
}
