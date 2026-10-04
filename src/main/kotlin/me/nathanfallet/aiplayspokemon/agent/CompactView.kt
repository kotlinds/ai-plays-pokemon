package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What an `act` answer keeps of the state view ([dev.kotlinds.pokemonclient.view.StateView.state]) when it is
 * compact: the team only when it changed since the previous answer, never the money and badges (`get_state` has
 * them); everything else as is. Remembers the team and position it last saw, compact or not.
 */
internal class CompactView {
    private var lastTeam: JsonElement? = null
    private var lastPosition: JsonElement? = null

    /** The entries of [view] to send ([compact] or full), and whether the player moved since the previous view. */
    fun take(view: JsonObject, compact: Boolean): Taken {
        val moved = view[POSITION] != lastPosition
        val teamChanged = view[TEAM] != lastTeam
        val entries = view.entries.mapNotNull { (k, v) ->
            when {
                !compact -> k to v
                k == TEAM -> k to (if (teamChanged) v else JsonPrimitive(TEAM_UNCHANGED))
                k in LEFT_OUT -> null
                else -> k to v
            }
        }
        lastTeam = view[TEAM]
        lastPosition = view[POSITION]
        return Taken(entries, moved)
    }

    /** The view entries to send, and whether the player moved (a compact answer then skips the map). */
    data class Taken(val entries: List<Pair<String, JsonElement>>, val moved: Boolean)

    companion object {
        const val TEAM = "team"
        const val POSITION = "position"
        const val TEAM_UNCHANGED = "unchanged since your last call"

        /** Never in a compact answer. */
        val LEFT_OUT = setOf("money", "badges")
    }
}
