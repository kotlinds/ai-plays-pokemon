package dev.kotlinds.pokemonclient.games.hgss

/**
 * Hides the save's older party for the few frames between the end of a battle and the moment the game writes its
 * copy back.
 *
 * During a battle (and its end: evolutions...) the game works on a copy of the party ([HgssState.party] comes from
 * it), and the save keeps the party as it was before the battle ([HgssState.partyBeforeWriteBack]). When the battle
 * app ends, the save's party shows for ~2 frames before the copy is written back (verified live after Elite Four
 * Will: HO-OH back to 164/164, PILOSWINE back to Lv39, AMPHAROS to Lv36, then the real values again). That stale
 * reading would make the team jump back and forth (older HP, levels, moves, species). It is recognised as the very
 * party the save held during the battle, read again within [WRITE_BACK_FRAMES] of the last copy: the last copy is
 * shown instead. Anything else (the written-back party, a battle whose party isn't written back once the window is
 * over) is shown as read.
 */
internal class HgssPartyWriteBack {

    /** The save's party read while the game worked on a copy. */
    private var held: List<PartyMon>? = null

    /** The last reading of the copy, and its frame. */
    private var copy: List<PartyMon>? = null
    private var copyFrame = 0L

    /** The party to show for [state]: its own, or the battle's copy while the save still holds the older party. */
    fun party(state: HgssState): List<PartyMon> {
        state.partyBeforeWriteBack?.let { save ->
            held = save
            copy = state.party
            copyFrame = state.frame
            return state.party
        }
        val old = held ?: return state.party
        val last = copy
        val elapsed = state.frame - copyFrame
        if (last == null || elapsed !in 0..WRITE_BACK_FRAMES || state.party.isEmpty() || state.party != old || last == old) {
            held = null
            copy = null
            return state.party
        }
        return last
    }

    companion object {
        /** Frames after the last reading of the copy during which the save's older party is still expected. */
        const val WRITE_BACK_FRAMES = 30L
    }
}
