package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen

/**
 * Rules for running a chain of actions (an agent's action and its `then` steps) as one sequence, the way a player
 * would: without closing a menu only to reopen it, and without stopping on a step that has become pointless.
 */
object ActionChains {

    /**
     * Merges consecutive `use_item` steps into one bag session ([GameAction.UseItem.batch]) when out of battle:
     * the bag stays open between the items instead of being closed and reopened for each one. In battle every item
     * use takes a turn, so they stay separate.
     */
    fun coalesce(actions: List<GameAction>, inBattle: Boolean): List<GameAction> {
        if (inBattle) return actions
        val merged = mutableListOf<GameAction>()
        for (action in actions) {
            val previous = merged.lastOrNull()
            if (action is GameAction.UseItem && previous is GameAction.UseItem) {
                merged[merged.lastIndex] = previous.copy(batch = previous.batch + action.uses)
            } else {
                merged += action
            }
        }
        return merged
    }

    /**
     * True when a failed step of a chain was only a spare `advance_dialogue`: the dialogue ended sooner than the
     * agent expected and the player can walk again. There is nothing left to read, so the chain goes on (e.g. with
     * `save_game`) instead of stopping.
     */
    fun isSpareAdvance(action: GameAction, error: ActionError, after: GameState): Boolean =
        action is GameAction.AdvanceDialogue &&
            error is ActionError.Unavailable &&
            after.field != null &&
            after.screen.let { it is Screen.Overworld && it.awaiting == Awaiting.INPUT }
}
