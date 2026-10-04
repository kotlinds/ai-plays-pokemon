package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A

/**
 * RAM layout of the main menu shown after the title screen (`MainMenuAppData`, src/application/main_menu/main_menu.c,
 * overlay 74): the data of the main overlay manager.
 */
internal object HgssMainMenuAddresses {
    const val CURRENT_OPTION = 0x54L         // u16 currentOption: button slot highlighted (index into the slots below)
    const val SLOTS = 0xECL                  // u32 unkEC[9]: per button slot, its MainMenu_AppOption, 0 when not shown
    const val SLOT_COUNT = 9
    const val INPUT_STATE = 0x1ACL           // MenuInputStateMgr: 0 buttons (highlight drawn), 1 touch (no highlight)
    const val INPUT_STATE_TOUCH = 1

    /** `MainMenuApp_Main` state 5: MainMenu_HandleInput (keys / touch read). */
    const val PROC_HANDLE_INPUT = 5

    /** `MainMenu_AppOption` values (1..9) → semantic id and the label's line of msg bank 442 (display only). */
    val OPTIONS: Map<Int, Pair<String, Int?>> = mapOf(
        1 to ("option:continue" to 0),
        2 to ("option:new_game" to 1),
        3 to ("option:pokewalker" to 9),
        4 to ("option:mystery_gift" to 2),
        5 to ("option:ranger" to 3),
        6 to ("option:migrate" to null),
        7 to ("option:connect_to_wii" to 11),
        8 to ("option:wfc" to 12),
        9 to ("option:wii_settings" to 10),
    )

    /** English labels, when the ROM's text isn't available. */
    val FALLBACK_LABELS = mapOf(
        "option:continue" to "CONTINUE", "option:new_game" to "NEW GAME", "option:pokewalker" to "CONNECT TO POKéWALKER",
        "option:mystery_gift" to "MYSTERY GIFT", "option:ranger" to "CONNECT TO RANGER", "option:migrate" to "MIGRATE",
        "option:connect_to_wii" to "CONNECT TO WII", "option:wfc" to "NINTENDO WFC SETTINGS", "option:wii_settings" to "WII MESSAGE SETTINGS",
    )

    /** Main menu labels (msg_0442). */
    val LABEL_BANK = dev.kotlinds.pokemonclient.data.TextBankId(442)
}

/**
 * Screens before the game starts: the main menu (CONTINUE / NEW GAME / ...), as a list whose ids are the options the
 * game knows (`option:continue`...), never their text. The intro movie and the title screen stay [Screen.Intro]
 * (any of A / START goes on).
 */
internal object HgssIntroScreens : HgssScreenDecoder {
    private val M = HgssMainMenuAddresses

    override fun decode(mem: HgssMemory, state: HgssState): Screen? =
        if (state.mode == GameMode.MAIN_MENU) mainMenu(mem) else null

    /**
     * The main menu while it reads input (state 5). The cursor is the highlighted button slot; after a touch the
     * highlight isn't drawn and the first key only draws it again ([Cursor.Hidden]). UP / DOWN move between the shown
     * buttons without wrapping (ChangeCurrentAppOption); B goes back to the title screen.
     */
    fun mainMenu(mem: HgssMemory): Screen {
        val om = mem.ptr(mem.version.mainAppState + A.MAIN_APP_OVERLAY_MANAGER) ?: return Screen.Intro("main_menu", Awaiting.ANIMATION)
        val data = mem.ptr(om + A.OM_DATA) ?: return Screen.Intro("main_menu", Awaiting.ANIMATION)
        if (mem.s32(om + A.OM_EXEC_STATE) != 2 || mem.s32(om + A.OM_PROC_STATE) != M.PROC_HANDLE_INPUT) {
            return Screen.Intro("main_menu", Awaiting.ANIMATION)
        }
        val labels = HgssData.gameData?.bank(M.LABEL_BANK)
        val shown = (0 until M.SLOT_COUNT).mapNotNull { slot ->
            val (id, line) = M.OPTIONS[mem.u32(data + M.SLOTS + 4L * slot).toInt()] ?: return@mapNotNull null
            val label = line?.let { labels?.getOrNull(it) }?.takeIf { it.isNotBlank() }?.replace('\n', ' ') ?: M.FALLBACK_LABELS.getValue(id)
            slot to Entry(id, label, dangerous = id == "option:new_game")
        }
        if (shown.isEmpty()) return Screen.Intro("main_menu", Awaiting.ANIMATION)
        val current = shown.indexOfFirst { it.first == mem.u16(data + M.CURRENT_OPTION) }
        val cursor = if (current < 0 || mem.s32(data + M.INPUT_STATE) == M.INPUT_STATE_TOUCH) Cursor.Hidden else Cursor.At(current)
        return Screen.ListMenu(MenuKind.MAIN_MENU, shown.map { it.second }, cursor, Topology.vertical(shown.size), CancelBehavior.CLOSES)
    }
}
