package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.state.FieldState

/**
 * The version of the state served to agents ([GameSession.version]): it changes when the screen changes AND when the
 * player moves (another tile, another map), so an action computed on a map the agent saw before a move is refused
 * (STALE_STATE). The screen part is the recorder's last screen-change sequence number; the move part counts the
 * moves noticed by [observe] (the session observes before each action and with each answer).
 *
 * The sum of two counters that only grow: it only grows, and it changes whenever one of them does.
 */
internal class StateVersion {

    /** Where the player was when last observed (map, x, y), null outside the field. */
    private var place: Triple<Int, Int, Int>? = null
    private var moves = 0L

    /** Notes where the player stands now: a different place than last time counts one move. */
    fun observe(field: FieldState?) {
        val now = field?.let { Triple(it.mapId, it.x, it.y) } ?: return
        if (place != null && now != place) moves++
        place = now
    }

    /** The version, given the sequence number of the last screen change. */
    fun of(screenSeq: Long): Long = screenSeq + moves
}
