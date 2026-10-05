package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.ViewerExit
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.games.hgss.HgssTextAddresses as T

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
            "pokegear" -> gearViewer(mem, awaiting)
            // The team being registered is the party (the game copies it into the Hall of Fame).
            "hall_of_fame_register" -> Screen.Viewer(
                ViewerApp.HALL_OF_FAME_REGISTER, ViewerExit(button = Button.A), awaiting, team(state) + hallOfFameProgress(mem, state),
            )
            "hall_of_fame" -> Screen.Viewer(ViewerApp.HALL_OF_FAME, back, awaiting)
            else -> null
        }
    }

    /**
     * The Pokégear's map or radio (the phone is [HgssPhoneScreens]'), from `PokegearAppData.app`, with the app bar's
     * buttons ([Screen.Viewer.apps]) and, for the radio, its dial and programme ([HgssRadio]). The radio waits for
     * input only once its own input loop runs (not while it slides in).
     */
    private fun gearViewer(mem: HgssMemory, awaiting: Awaiting): Screen.Viewer? {
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        val gear = mem.ptr(fs + A.FS_SUB0)?.let { mem.ptr(it + A.FSS0_SUB_APP) } ?: return null
        if (mem.fn(gear + A.OM_INIT) != T.FN_POKEGEAR_INIT) return null
        val data = mem.ptr(gear + A.OM_DATA) ?: return null
        val app = when (mem.u8(data + T.GEAR_APP)) {
            T.GEAR_APP_MAP -> ViewerApp.POKEGEAR_MAP
            T.GEAR_APP_RADIO -> ViewerApp.POKEGEAR_RADIO
            else -> return null
        }
        val apps = HgssPhoneScreens.appBar(mem, data)?.buttons?.map { it.entry }.orEmpty()
        val exit = ViewerExit(touch = GEAR_CLOSE)
        if (app != ViewerApp.POKEGEAR_RADIO) return Screen.Viewer(app, exit, awaiting, apps = apps)
        val (radioApp, ready) = HgssRadio.radioApp(mem, gear, data) ?: return Screen.Viewer(app, exit, awaiting, apps = apps)
        val radio = HgssRadio.decode(mem, radioApp, inAppBar = mem.u8(data + T.GEAR_CURSOR_IN_BAR) == 1)
        return Screen.Viewer(app, exit, if (ready) awaiting else Awaiting.ANIMATION, radio = radio, apps = apps)
    }

    // RegisterHallOfFameData (src/register_hall_of_fame.c).
    private const val HOF_NUM_MONS = 0x13048L
    private const val HOF_SCENE = 0x1304CL
    private const val HOF_CUR_MON = 0x13056L
    private const val HOF_SCENE_INDIV_LAST = 4  // REGHOF_SCENE_INDIV_MONS_EXIT
    private const val HOF_SCENE_WHOLE_FIRST = 5 // REGHOF_SCENE_WHOLE_PARTY_INIT

    /**
     * What the Hall of Fame shows now (`RegisterHallOfFameData.currentScene` / `curMonIndex`): each Pokémon in
     * turn, then the whole team (it waits for A). Changes as the animation goes on, so waiting "until something
     * changes" sees it.
     */
    private fun hallOfFameProgress(mem: HgssMemory, state: HgssState): List<String> {
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return emptyList()
        val app = mem.ptr(fs + A.FS_SUB0)?.let { mem.ptr(it + A.FSS0_SUB_APP) } ?: return emptyList()
        val data = mem.ptr(app + A.OM_DATA) ?: return emptyList()
        val count = mem.u32(data + HOF_NUM_MONS).toInt().takeIf { it in 1..6 } ?: return emptyList()
        val scene = mem.s32(data + HOF_SCENE)
        val index = mem.u16(data + HOF_CUR_MON)
        val team = team(state)
        return when {
            scene in 0..HOF_SCENE_INDIV_LAST && index < count ->
                listOf("presenting ${index + 1}/$count: ${team.getOrElse(index) { "?" }}")
            scene >= HOF_SCENE_WHOLE_FIRST -> listOf("the whole team is shown (press A once it waits for you)")
            else -> emptyList()
        }
    }

    private fun team(state: HgssState): List<String> =
        state.party.filter { !it.isEgg }.map { mon -> (mon.nickname ?: mon.speciesName) + " Lv" + mon.level }
}
