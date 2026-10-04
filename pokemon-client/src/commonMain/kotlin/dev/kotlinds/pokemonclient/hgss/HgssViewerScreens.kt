package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.ViewerExit
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.hgss.HgssTextAddresses as T

/**
 * The applications with nothing to choose, as [Screen.Viewer]s, from the running sub-application (the app names of
 * [HgssAddresses.APP_BY_OVERLAY] / [HgssVersion.appByInit]): the Pokédex (overlay 18), the trainer card (50), a
 * Pokémon's summary (`PokemonSummary_Init`, viewing only: the forget-a-move summary is [HgssPostBattleScreens]'),
 * the Pokégear's map and radio (overlay 100, `PokegearAppData.app`), the Hall of Fame (63 register, 64 view).
 *
 * Ways out, verified live: B leaves the Pokédex, the trainer card and the summary (back to the menu they were opened
 * from); the Pokégear has no B: its Close button on the app bar ([GEAR_CLOSE]) closes it.
 */
internal object HgssViewerScreens : HgssScreenDecoder {

    /** The Pokégear app bar's Close button (the same as the phone's `option:cancel`). */
    val GEAR_CLOSE = TouchPoint(230, 176)

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (state.mode != GameMode.APP || state.fading) return null
        val awaiting = if (state.awaitingInput) Awaiting.INPUT else Awaiting.ANIMATION
        val back = ViewerExit(button = Button.B)
        return when (state.modeDetail) {
            "pokedex" -> Screen.Viewer(ViewerApp.POKEDEX, back, awaiting)
            "trainer_card" -> Screen.Viewer(ViewerApp.TRAINER_CARD, back, awaiting)
            "pokemon_summary" -> Screen.Viewer(ViewerApp.SUMMARY, back, awaiting)
            "pokegear" -> gearApp(mem)?.let { Screen.Viewer(it, ViewerExit(touch = GEAR_CLOSE), awaiting) }
            // The team being registered is the party (the game copies it into the Hall of Fame).
            "hall_of_fame_register" -> Screen.Viewer(ViewerApp.HALL_OF_FAME_REGISTER, ViewerExit(button = Button.A), awaiting, team(state))
            "hall_of_fame" -> Screen.Viewer(ViewerApp.HALL_OF_FAME, back, awaiting)
            else -> null
        }
    }

    /** The Pokégear's map or radio (the phone is [HgssPhoneScreens]'), from `PokegearAppData.app`. */
    private fun gearApp(mem: HgssMemory): ViewerApp? {
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        val gear = mem.ptr(fs + A.FS_SUB0)?.let { mem.ptr(it + A.FSS0_SUB_APP) } ?: return null
        if (mem.fn(gear + A.OM_INIT) != T.FN_POKEGEAR_INIT) return null
        val data = mem.ptr(gear + A.OM_DATA) ?: return null
        return when (mem.u8(data + T.GEAR_APP)) {
            T.GEAR_APP_MAP -> ViewerApp.POKEGEAR_MAP
            T.GEAR_APP_RADIO -> ViewerApp.POKEGEAR_RADIO
            else -> null
        }
    }

    private fun team(state: HgssState): List<String> =
        state.party.filter { !it.isEgg }.map { mon -> (mon.nickname ?: mon.speciesName) + " Lv" + mon.level }
}
