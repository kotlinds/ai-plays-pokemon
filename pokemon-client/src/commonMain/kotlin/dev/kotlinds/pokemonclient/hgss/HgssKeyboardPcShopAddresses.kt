package dev.kotlinds.pokemonclient.hgss

/**
 * RAM layout used by [HgssKeyboardPcShopScreens]: naming keyboard, PC storage, Poké Mart, touch save app, trade and
 * egg hatch. Offsets come from pret/pokeheartgold (file:line in each comment) and were checked live on HeartGold (USA)
 * unless marked "decomp only".
 *
 * Absolute addresses differ per ROM and live in [HgssKeyboardPcShopVersion].
 */
object HgssKeyboardPcShopAddresses {

    // region gSystem (include/system.h:21)

    /** `void (*vBlankIntr)(void *)`: the naming screen installs its own (naming_screen.c:504), cleared on exit (:745). */
    const val SYS_VBLANK_INTR = 0x00L

    // endregion

    // region Naming screen: NamingScreenAppData (src/naming_screen.c:83, size 0x5D4)

    /** `NameScreenType` (include/launch_application.h:50), see [NameScreenType]. */
    const val NS_TYPE = 0x00L
    /** Species, for the Pokémon nickname prompt. */
    const val NS_SPECIES = 0x04L
    /** Max characters: 7 player / rival, 10 Pokémon, 8 box. */
    const val NS_MAX_LEN = 0x0CL
    const val NS_CURSOR_X = 0x1CL
    const val NS_CURSOR_Y = 0x20L
    /** TRUE for a few frames once the buffer is full, until the cursor is moved to OK by the game (:1892). */
    const val NS_IGNORE_INPUT = 0x34L
    /** `u16 keyboard[6][13]`: the live layout of the current page, row 0 = tabs / BACK / OK. */
    const val NS_KEYBOARD = 0x3AL
    /** `u16 entryBuf[32]`: typed characters, valid in `[0, textCursorPos)`. */
    const val NS_ENTRY_BUF = 0xD8L
    const val NS_TEXT_CURSOR_POS = 0x158L
    /** 0..3 page slide animation, 4 idle, 5..7 "transferred to the PC" message (battle catch, full party). */
    const val NS_PAGE_SWITCH_STATE = 0x45CL
    /** 0 UPPER, 1 lower, 2 Others, 3 JP (unused), 4 number pad. */
    const val NS_PAGE = 0x460L

    const val NS_PAGE_SWITCH_IDLE = 4
    const val KEYBOARD_COLUMNS = 13
    const val KEYBOARD_ROWS = 6
    const val ENTRY_BUF_SIZE = 32

    /** Codes of the non-character cells of `keyboard` (naming_screen.c:36-48). */
    const val KEY_SKIP = 0xD004
    const val KEY_BUTTON_START = 0xE001
    const val KEY_PAGE_UPPER = 0xE002
    const val KEY_PAGE_LOWER = 0xE003
    const val KEY_PAGE_OTHERS = 0xE004
    const val KEY_BACK = 0xE007
    const val KEY_OK = 0xE008

    // endregion

    // region Sub-applications launched from the field (OverlayManager.template.ovy_id)

    const val OVY_PC_BOX = 14
    const val OVY_TRADE = 71
    const val OVY_HATCH_EGG = 95

    // endregion

    // region PC storage: PCBoxData = OverlayManager.data (asm/overlay_14.s PCBox_Init, size 0x38)

    /** `PCBoxArgs *`: `+0x08 int mode` (0 deposit, 1 withdraw, 2 move Pokémon, 3 move items). */
    const val PCB_ARGS = 0x00L
    const val PCARGS_MODE = 0x08L
    /** `PCStorage *` (save array 41). */
    const val PCB_STORAGE = 0x04L
    /** `Party *` of the save. */
    const val PCB_PARTY = 0x08L
    /** Box shown (PCStorage.curBox is only written back when the app exits). */
    const val PCB_SHOWN_BOX = 0x1FL
    /** Slot the context menu was opened on: 0..29 box slot, 30..35 party slot (verified live). */
    const val PCB_SELECTED_SLOT = 0x21L
    /** Box highlighted in the box pickers ("Deposit where?", box title menu), 0..17 (verified live). */
    const val PCB_PICKER_BOX = 0x25L
    /** `PCBoxWork *` (0x88E0 bytes). */
    const val PCB_WORK = 0x34L

    /** `GridInputHandler *` driving every cursor of the app (include/unk_02019BA4.h). */
    const val PCW_GRID = 0x2CL
    /** `YesNoPrompt *` and the id of the question asked (table ov14_021F7D74). */
    const val PCW_YES_NO = 0x434L
    const val PCW_YES_NO_ID = 0x438L

    /** OverlayManager proc state of the PC app: a message waits for A / B / touch, a yes / no prompt waits. */
    const val PC_STATE_MESSAGE = 6
    const val PC_STATE_YES_NO = 7
    /** MOVE POKéMON with the party panel: nothing held (0x29) / a Pokémon held by the cursor (0x73), verified live. */
    const val PC_STATE_MOVE_FREE = 0x29
    const val PC_STATE_MOVE_HOLDING = 0x73
    /** Proc states 2..5 are fades and delays (ov14_021EAF8C state table). */
    val PC_STATES_ANIMATION = 2..5

    /** GridInputHandler (include/unk_02019BA4.h). */
    const val GRID_HITBOXES = 0x00L
    const val GRID_DPAD = 0x04L
    const val GRID_CURSOR = 0x0DL
    /** Index to come back to when a neighbour has bit 0x80 set (0xFF = none). */
    const val GRID_REMEMBERED = 0x0FL
    const val GRID_ENABLED = 0x10L

    /** DpadMenuBox {x, y, w, h, up, down, left, right} (include/unk_02020A0C.h:6). */
    const val DPAD_BOX_SIZE = 8L
    const val DPAD_NEIGHBOURS = 4L
    const val DPAD_REMEMBERED_BIT = 0x80
    const val DPAD_NONE = 0xFF

    /** TouchscreenHitbox {top, bottom, left, right} (include/touchscreen.h); a right of 0 means 256. */
    const val HITBOX_SIZE = 4L
    const val HITBOX_LIST_END = 0xFF

    /** PCStorage (include/pokemon_storage_system.h). */
    const val PCS_BOX_STRIDE = 0x1000L
    const val PCS_BOX_NAMES = 0x12008L
    const val PCS_BOX_NAME_CHARS = 20
    const val BOX_MON_SIZE = 0x88
    const val BOX_SLOTS = 30
    const val BOX_COUNT = 18
    const val PARTY_SLOTS = 6
    /** First "slot" number of the party in [PCB_SELECTED_SLOT]. */
    const val PC_PARTY_SLOT_BASE = 30

    // endregion

    // region YesNoPrompt (include/yes_no_prompt.h:47)

    /** `TouchscreenHitbox hitboxes[2]`: YES then NO. */
    const val YNP_HITBOXES = 0x04L
    /** Bit 0: the last input was a touch; the next key press only leaves touch mode (src/yes_no_prompt.c:144). */
    const val YNP_TOUCH_MODE = 0x74L
    /** 0 = YES, 1 = NO. */
    const val YNP_CURSOR = 0x75L

    // endregion

    // region Poké Mart: MartData (include/overlay_03.h:44), env of Task_Mart

    const val MART_ITEMS = 0x268L
    const val MART_COUNT = 0x270L
    const val MART_PAGE_OFFSET = 0x271L
    const val MART_STATE = 0x272L
    /** 0 buy, 1 sell (sell runs the Bag app). */
    const val MART_BUY_SELL = 0x273L
    /** `String *`: the clerk's message being printed. */
    const val MART_STRING = 0x274L
    /** u8 id of the top-screen message printer (`sTextPrinterTasks` index). */
    const val MART_PRINTER_ID = 0x280L
    const val MART_ITEM = 0x284L
    const val MART_QUANTITY = 0x286L
    const val MART_MAX_QUANTITY = 0x288L
    const val MART_COST = 0x28CL
    /** 0..5 grid slot, 8 = CANCEL. */
    const val MART_CURSOR = 0x290L

    const val MART_STATE_GRID = 3
    const val MART_STATE_QUANTITY = 7
    const val MART_STATE_YES_NO = 11
    /** "Here you are! Thank you!", "You don't have enough money.", "...a Premier Ball as an added bonus.": A / B / touch. */
    val MART_STATES_MESSAGE = setOf(13, 14, 15)
    const val MART_PAGE_SIZE = 6
    const val MART_CURSOR_CANCEL = 8
    const val MART_BUY = 0

    /** Bottom-screen app 2 (overlay 31) work: `+0x170 YesNoPrompt *` of the "OK?" question (asm/overlay_31.s:2193). */
    const val OV31_YES_NO = 0x170L

    // endregion

    // region Bottom screen manager (FieldSystem+0xD8 -> SysTask -> data)

    const val SYSTASK_FUNC = 0x14L
    const val BOTTOM_APP_SAVE = 1
    const val BOTTOM_APP_MART = 2

    // endregion

    // region Touch save app: TouchSaveAppData (src/touch_save_app.c:50)

    const val SAVE_STATE = 0x0CL
    const val SAVE_YES_NO = 0x48L
    /** "Would you like to save the game?" / "There is already a saved file. Is it OK to overwrite it?" waiting. */
    const val SAVE_STATE_ASK = 4
    const val SAVE_STATE_OVERWRITE = 7

    // endregion

    // region Egg hatch (overlay 95): OverlayManager.data (decomp only)

    const val HATCH_STATE = 0x60L
    const val HATCH_YES_NO = 0x88L
    /** 1 = YES (initial), 2 = NO (asm/overlay_95.s:3416). */
    const val HATCH_YN_SELECTION = 0x19L
    const val HATCH_STATE_NICKNAME = 12

    // endregion
}

/**
 * Version-specific absolute addresses of [HgssKeyboardPcShopScreens] (xMAP of the decomp build).
 * Only HeartGold (USA) is known; other versions decode nothing from this family.
 */
data class HgssKeyboardPcShopVersion(
    /** `NamingScreen_VBlankCB` (naming_screen.o), without the Thumb bit. */
    val namingVBlankCallback: Long,
    /** `static NamingScreenAppData *sAppData` (naming_screen.o): set at init, never cleared. */
    val namingAppData: Long,
    /** `Task_Mart` (overlay 3), without the Thumb bit. */
    val fnTaskMart: Long,
    /** `ov30_0225D700`: the touch save app's SysTask function. */
    val fnTouchSaveApp: Long,
    /** The 11 hitbox tables of the PC app (`ov14_021F8B10` column 0), index = [HgssPcLayout.ordinal]. */
    val pcLayoutHitboxes: List<Long>,
) {
    companion object {
        val HEARTGOLD_US = HgssKeyboardPcShopVersion(
            namingVBlankCallback = 0x02083140L,
            namingAppData = 0x021D43B0L,
            fnTaskMart = 0x02256E2CL,
            fnTouchSaveApp = 0x0225D700L,
            pcLayoutHitboxes = listOf(
                0x021F881CL, 0x021F87F0L, 0x021F8C30L, 0x021F8CD0L, 0x021F8D7CL, 0x021F8884L,
                0x021F8B94L, 0x021F87C4L, 0x021F877CL, 0x021F8850L, 0x021F87A0L,
            ),
        )

        fun forGameCode(code: String): HgssKeyboardPcShopVersion? = when (code) {
            "IPKE" -> HEARTGOLD_US
            else -> null
        }
    }
}
