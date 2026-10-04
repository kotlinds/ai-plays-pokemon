package dev.kotlinds.pokemonclient.actions

/**
 * Choices of the application running the agents (not of the agent: no action parameter changes them) that change how
 * the recipes act. Passed to [ActionRegistry.execute]; the defaults are the most helpful behaviour.
 */
data class ActionSettings(
    /**
     * Movement puzzles solved by the walks themselves (`go_to`, `interact`... through [MovePlans.walkTo]): Strength
     * boulders and ice blocks pushed out of the way, the Blackthorn Gym platforms ridden, the Violet Gym lift taken.
     * When false, walks only walk: they never push a boulder or an ice block, never step on a platform trigger or a
     * lift (unless it is the destination itself, which the agent asked for); a way that needs one of them fails with
     * [dev.kotlinds.pokemonclient.world.NeedsMechanism] ([UnavailableReason.PUZZLE_LEFT_TO_AGENT]) naming
     * the mechanism, for the agent to operate itself (`step`, `interact`, `push`). What is already in place (a
     * platform where it is, a lift at the player's floor) is still walked on.
     */
    val solvePuzzles: Boolean = true,
    /**
     * Actions may use what the player hasn't discovered: hidden items as targets (`hidden_item:N`, and listed among
     * the valid targets of an error). Walkthrough knowledge: false unless the agent is allowed a walkthrough.
     */
    val revealHidden: Boolean = true,
)
