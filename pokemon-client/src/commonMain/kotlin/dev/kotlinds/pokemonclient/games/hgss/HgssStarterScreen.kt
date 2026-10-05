package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.StarterStage
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A

/**
 * Prof. Elm's machine (the choose_starter app, src/choose_starter_app.c): `curSelection` is the ball in front (an
 * index into `sSpecies`: Chikorita, Cyndaquil, Totodile), `state` how far the choice went (SELECT_STATE_NULL /
 * INSPECT / CONFIRM, getInput). The game waits for input in CHOOSE_STARTER_STATE_HANDLE_INPUT only.
 */
internal object HgssStarterScreen : HgssScreenDecoder {

    /** `sSpecies` (src/choose_starter_app.c): the order of `curSelection`. */
    private val SPECIES = listOf(152, 155, 158)

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (state.mode != GameMode.APP || state.modeDetail != "choose_starter") return null
        val om = HgssScreenMemory.fieldSubApp(mem) ?: return null
        val work = mem.ptr(om + A.OM_DATA) ?: return null
        val front = mem.u32(work + A.CS_CUR_SELECTION).toInt().takeIf { it in SPECIES.indices } ?: return null
        val stage = StarterStage.entries.getOrNull(mem.u32(work + A.CS_SELECT_STATE).toInt()) ?: return null
        val waiting = mem.s32(om + A.OM_EXEC_STATE) == 2 && mem.s32(om + A.OM_PROC_STATE) == A.CS_PROC_HANDLE_INPUT
        return Screen.StarterChoice(
            starters = SPECIES.map { Named(SpeciesId(it), HgssData.speciesName(it)) },
            front = front,
            stage = stage,
            awaiting = if (waiting) Awaiting.INPUT else Awaiting.ANIMATION,
        )
    }
}
