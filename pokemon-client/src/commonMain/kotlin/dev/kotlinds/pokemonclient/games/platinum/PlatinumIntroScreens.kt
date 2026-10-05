package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.games.gen4.Gen4NamingKeyboard
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs as S
import dev.kotlinds.pokemonclient.games.gen4.Gen4Text
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology

/** The top-level application running (`sApplication.currApplication`), told by its template's `main` function. */
internal enum class PlatinumApp { OPENING, TITLE_SCREEN, MAIN_MENU, ROWAN_INTRO, FIELD, LOADING, OTHER, NONE }

/** The running top-level application and its manager (`ApplicationManager *`), when one runs. */
internal data class PlatinumTopApp(val app: PlatinumApp, val manager: Long?) {
    companion object {
        fun read(mem: PlatinumMemory): PlatinumTopApp {
            val v = mem.version
            val manager = mem.ptr(v.application + S.APP_MANAGER) ?: return PlatinumTopApp(PlatinumApp.NONE, null)
            val app = when (mem.fn(manager + S.OM_MAIN)) {
                v.fnOpeningMain -> PlatinumApp.OPENING
                v.fnTitleScreenMain -> PlatinumApp.TITLE_SCREEN
                v.fnMainMenuMain -> PlatinumApp.MAIN_MENU
                v.fnRowanIntroMain -> PlatinumApp.ROWAN_INTRO
                v.fnFieldMain -> PlatinumApp.FIELD
                in v.fnLoadingMains -> PlatinumApp.LOADING
                else -> PlatinumApp.OTHER
            }
            return PlatinumTopApp(app, manager)
        }
    }
}

/**
 * The screens before the player is in the world: the opening movie, the title screen, the main menu and Professor
 * Rowan's new-game intro (with the naming keyboard and the TV programme it launches). Ids are the game's own values
 * (main menu options, list choice indices, genders), never the displayed text; labels are display only.
 *
 * Sources: pret/pokeplatinum src/main.c, src/applications/title_screen.c, src/main_menu/main_menu.c,
 * src/applications/rowan_intro/rowan_intro_app.c and tv_app.c (offsets compiled with the decomp's compiler).
 */
internal object PlatinumIntroScreens {

    /** [text]: the ROM's text banks (null without a ROM: messages read from RAM only, text blocks without text). */
    fun decode(mem: PlatinumMemory, top: PlatinumTopApp, text: PlatinumText?): Screen? {
        val manager = top.manager ?: return null
        return when (top.app) {
            PlatinumApp.OPENING -> openingMovie(mem, manager)
            PlatinumApp.TITLE_SCREEN -> titleScreen(mem, manager)
            PlatinumApp.MAIN_MENU -> mainMenu(mem, manager, text)
            PlatinumApp.ROWAN_INTRO -> rowanIntro(mem, manager, text)
            else -> null
        }
    }

    // region Opening movie

    /** UnkStruct_ov77_021D2E9C (src/game_opening/ov77_021D25B0.c): `unk_08` set once skipped, `unk_2A8` (at 0x2AC). */
    private const val OPENING_SKIPPING = 0x08L
    private const val OPENING_SKIPPABLE = 0x2ACL

    /**
     * The opening movie (gOpeningCutsceneAppTemplate, ov77_021D2D94): A or START skips it to the title screen, once its
     * first scene has set the "skippable" byte; before that (and once skipped) presses do nothing.
     */
    private fun openingMovie(mem: PlatinumMemory, manager: Long): Screen {
        val data = mem.ptr(manager + S.OM_DATA)
        val skippable = data != null && mem.s32(manager + S.OM_EXEC_STATE) == S.OM_EXEC_MAIN &&
            mem.u8(data + OPENING_SKIPPABLE) != 0 && mem.s32(data + OPENING_SKIPPING) == 0
        return Screen.Intro("intro_movie", if (skippable) Awaiting.INPUT else Awaiting.ANIMATION)
    }

    // endregion

    // region Title screen

    /** TITLE_SCREEN_APP_STATE_MAIN: A / START go on once `inputEnableDelay` (30 frames after the intro) is over. */
    private const val TITLE_STATE_MAIN = 3
    private const val TITLE_INPUT_ENABLE_DELAY = 0x4F0L

    private fun titleScreen(mem: PlatinumMemory, manager: Long): Screen {
        val data = mem.ptr(manager + S.OM_DATA)
        val ready = mem.s32(manager + S.OM_EXEC_STATE) == S.OM_EXEC_MAIN && mem.s32(manager + S.OM_PROC_STATE) == TITLE_STATE_MAIN &&
            data != null && mem.s32(data + TITLE_INPUT_ENABLE_DELAY) == 0
        return Screen.Intro("title_screen", if (ready) Awaiting.INPUT else Awaiting.ANIMATION)
    }

    // endregion

    // region Main menu

    /** MainMenuAppData (src/main_menu/main_menu.c:181). */
    private object M {
        const val FOCUSED_OPTION = 0x54L
        /** `enum MainMenuNextApp optionApps[8]`: 0 (NEXT_APP_TITLE_SCREEN) for an option not shown. */
        const val OPTION_APPS = 0xDCL
        const val ALERTS_STATE = 0x12CL
        const val ALERTS_PENDING = 0x130L
        const val ALERTS_DELAY = 0x134L
        const val STATE_SELECT_OPTION = 5
        const val STATE_CONFIRM_NEW_GAME = 6
        const val ALERTS_WAIT_DISMISS = 18
        const val OPTION_COUNT = 8

        /** `enum MainMenuOption` → semantic id (the HGSS ids where the option exists there) and its line of bank 550. */
        val OPTIONS = listOf(
            "option:continue" to 0, "option:new_game" to 1, "option:mystery_gift" to 2, "option:ranger" to 3,
            "option:migrate" to null, "option:connect_to_wii" to 10, "option:wfc" to 11, "option:wii_settings" to null,
        )
        val FALLBACK_LABELS = listOf(
            "CONTINUE", "NEW GAME", "MYSTERY GIFT", "LINK WITH POKéMON RANGER", "MIGRATE FROM GBA", "CONNECT TO Wii",
            "NINTENDO WFC SETTINGS", "Wii MESSAGE SETTINGS",
        )
    }

    /**
     * The main menu while it reads input (MAIN_MENU_STATE_SELECT_OPTION): the options shown are those with a next
     * application; UP / DOWN move between them without wrapping (FocusNextOption); A picks, B goes back to the title.
     * NEW GAME first shows a warning (an alert dismissed with A to go on, B to go back). Without a save the menu is
     * skipped (`isNewGame`): the intro starts at once.
     */
    private fun mainMenu(mem: PlatinumMemory, manager: Long, text: PlatinumText?): Screen {
        val data = mem.ptr(manager + S.OM_DATA) ?: return Screen.Intro("main_menu", Awaiting.ANIMATION)
        if (mem.s32(manager + S.OM_EXEC_STATE) != S.OM_EXEC_MAIN || mem.fading) return Screen.Intro("main_menu", Awaiting.ANIMATION)
        val state = mem.s32(manager + S.OM_PROC_STATE)
        if (mem.s32(data + M.ALERTS_STATE) == M.ALERTS_WAIT_DISMISS) {
            if (mem.s32(data + M.ALERTS_DELAY) != 0) return Screen.Intro("main_menu", Awaiting.ANIMATION)
            val warning = state == M.STATE_CONFIRM_NEW_GAME
            val message = if (warning) text?.line(PlatinumText.MAIN_MENU_ALERTS, PlatinumText.MAIN_MENU_ALERT_NEW_GAME) else null
            return Screen.PressToContinue(ContinueReason.MESSAGE, message?.replace('\n', ' ')?.trim())
        }
        if (state != M.STATE_SELECT_OPTION || mem.s32(data + M.ALERTS_PENDING) != 0) return Screen.Intro("main_menu", Awaiting.ANIMATION)
        val shown = (0 until M.OPTION_COUNT).filter { mem.s32(data + M.OPTION_APPS + 4L * it) != 0 }
        if (shown.isEmpty()) return Screen.Intro("main_menu", Awaiting.ANIMATION)
        val entries = shown.map { option ->
            val (id, line) = M.OPTIONS[option]
            val label = line?.let { text?.line(PlatinumText.MAIN_MENU_OPTIONS, it) }?.takeIf { it.isNotBlank() }?.replace('\n', ' ')
                ?: M.FALLBACK_LABELS[option]
            Entry(id, label, dangerous = id == "option:new_game")
        }
        val focused = shown.indexOf(mem.s32(data + M.FOCUSED_OPTION))
        return Screen.ListMenu(
            MenuKind.MAIN_MENU, entries, if (focused >= 0) Cursor.At(focused) else Cursor.Hidden,
            Topology.vertical(entries.size), CancelBehavior.CLOSES,
        )
    }

    // endregion

    // region Professor Rowan's intro

    /** RowanIntro (src/applications/rowan_intro/rowan_intro_app.c:212). */
    private object R {
        const val STATE = 0x0CL
        /** `ApplicationManager *appMan`: the naming keyboard or the TV programme, run inside the intro. */
        const val CHILD_APP = 0x14L
        const val CHOICE_BOX_STATE = 0x2CL
        const val LIST_MENU = 0x40L
        const val DISPLAY_MESSAGE_STATE = 0x50L
        const val DISPLAY_TEXT_BLOCK_STATE = 0x54L
        const val TEXT_PRINTER_ID = 0x58L
        /** `String *string`: the message printed (freed once printed, still readable until the next one). */
        const val STRING = 0x5CL
        const val PLAYER_GENDER = 0x84L

        // RowanIntro_Main's own state (enum RowanIntroAppState)
        const val APP_RUNNING = 1
        const val APP_CHANGE_APP = 4

        // enum RowanIntroState
        const val INFO_CHOICE_BOX = 7
        const val CONTROL_INFO_WAIT_INPUT = 22
        const val PKBL_WAIT_INPUT = 47
        const val GENDER_CHOICE = 67
        const val GENDER_CONFIRM = 73
        const val NAME_CONFIRM = 80
        const val RIVAL_NAME_CHOICE_BOX = 90
        const val RIVAL_NAME_CONFIRM = 96

        /** Text block states (RowanIntro_DisplayTextBlock) → their line of [PlatinumText.ROWAN_INTRO]. */
        val TEXT_BLOCKS = mapOf(12 to 2, 14 to 3, 16 to 4, 18 to 5, 33 to 10, 34 to 11, 35 to 12, 36 to 13, 37 to 14, 38 to 15)
        const val DM_PRINT = 1
        const val DTB_WAIT_FOR_INPUT = 3
        const val CHOICE_BOX_WAITING = 1
    }

    /** ListMenu (include/list_menu.h): `template.choices` (+0, StringList *), `template.count` (+0x10), listPos, cursorPos. */
    private const val LM_CHOICES = 0x00L
    private const val LM_COUNT = 0x10L
    private const val LM_LIST_POS = 0x2CL
    private const val LM_CURSOR_POS = 0x2EL

    /** RowanIntroTv: `state` (+0xC), RIT_STATE_WAIT_INPUT = 2 (A or B goes on). */
    private const val TV_STATE = 0x0CL
    private const val TV_WAIT_INPUT = 2

    /** The control-info "understood?" YES / NO, touch only (YesNoTouchMenu at tiles (12, 8), buttons 6x4 tiles). */
    private val TOUCH_YES = TouchPoint(120, 80)
    private val TOUCH_NO = TouchPoint(120, 120)

    /** The Poké Ball to open (RowanIntro_WasPokeballOpened: within 16 px of (128, 100)). */
    private val TOUCH_POKE_BALL = TouchPoint(128, 100)

    private fun rowanIntro(mem: PlatinumMemory, manager: Long, text: PlatinumText?): Screen {
        val data = mem.ptr(manager + S.OM_DATA) ?: return Screen.Animation(AnimationKind.TRANSITION)
        if (mem.s32(manager + S.OM_EXEC_STATE) != S.OM_EXEC_MAIN) return Screen.Animation(AnimationKind.TRANSITION)
        val appState = mem.s32(manager + S.OM_PROC_STATE)
        if (appState == R.APP_CHANGE_APP) return childApp(mem, data, text)
        if (appState != R.APP_RUNNING || mem.fading) return Screen.Animation(AnimationKind.TRANSITION)
        val state = mem.s32(data + R.STATE)
        R.TEXT_BLOCKS[state]?.let { line ->
            if (mem.s32(data + R.DISPLAY_TEXT_BLOCK_STATE) != R.DTB_WAIT_FOR_INPUT) return Screen.Animation(AnimationKind.TRANSITION)
            return Screen.PressToContinue(ContinueReason.MESSAGE, text?.line(PlatinumText.ROWAN_INTRO, line)?.replace('\n', ' '))
        }
        return when (state) {
            R.INFO_CHOICE_BOX -> listChoice(mem, data, yesNo = false, cancel = CancelBehavior.NONE)
            R.RIVAL_NAME_CHOICE_BOX -> listChoice(mem, data, yesNo = false, cancel = CancelBehavior.NONE)
            R.GENDER_CONFIRM, R.NAME_CONFIRM, R.RIVAL_NAME_CONFIRM -> listChoice(mem, data, yesNo = true, cancel = CancelBehavior.CONFIRMS_LAST)
            R.CONTROL_INFO_WAIT_INPUT -> Screen.YesNo(
                null,
                listOf(Entry("option:yes", "YES", touch = TOUCH_YES), Entry("option:no", "NO", touch = TOUCH_NO)),
                Cursor.Hidden, NO_DPAD, CancelBehavior.NONE,
            )
            R.PKBL_WAIT_INPUT -> Screen.ListMenu(
                MenuKind.OTHER, listOf(Entry("option:poke_ball", "Poké Ball", touch = TOUCH_POKE_BALL)), Cursor.Hidden, NO_DPAD, CancelBehavior.NONE,
            )
            // LEFT / RIGHT switch between the boy and the girl, A confirms (RI_STATE_GENDR_CHOICE).
            R.GENDER_CHOICE -> Screen.ListMenu(
                MenuKind.OTHER, listOf(Entry("option:0", "Boy"), Entry("option:1", "Girl")),
                Cursor.At(mem.s32(data + R.PLAYER_GENDER).coerceIn(0, 1)),
                Topology { from, button -> if (button == Button.LEFT || button == Button.RIGHT) 1 - from else null },
                CancelBehavior.NONE,
            )
            else -> message(mem, data)
        }
    }

    /** A touch-only screen: the D-pad moves nothing (the game answers a key with "use the touch screen"). */
    private val NO_DPAD = Topology { _, _ -> null }

    /**
     * RowanIntro_ChoiceBox waiting for input (choiceBoxState 1): a [ListMenu] whose StringList entries carry the
     * choice index the intro switches on (1-based, sYesNoChoiceInfos / sInfoChoiceInfos / sRivalNameChoiceInfos). Ids:
     * `option:yes` / `option:no` for the yes / no boxes, `option:<index - 1>` otherwise (the HGSS ids for its info
     * menu). UP / DOWN move without wrapping (ListMenu_ProcessInput).
     */
    private fun listChoice(mem: PlatinumMemory, data: Long, yesNo: Boolean, cancel: CancelBehavior): Screen {
        val menu = mem.ptr(data + R.LIST_MENU)
        if (mem.s32(data + R.CHOICE_BOX_STATE) != R.CHOICE_BOX_WAITING || menu == null) return Screen.Animation(AnimationKind.TRANSITION)
        val count = mem.u16(menu + LM_COUNT).coerceIn(0, 8)
        val list = mem.ptr(menu + LM_CHOICES) ?: return Screen.Animation(AnimationKind.TRANSITION)
        val entries = (0 until count).map { i ->
            val label = mem.gameString(mem.ptr(list + 8L * i))?.replace('\n', ' ').orEmpty()
            val index = mem.s32(list + 8L * i + 4)
            val id = when {
                yesNo && index == 1 -> "option:yes"
                yesNo && index == 2 -> "option:no"
                else -> "option:${index - 1}"
            }
            Entry(id, label)
        }
        val cursor = mem.u16(menu + LM_LIST_POS) + mem.u16(menu + LM_CURSOR_POS)
        return if (yesNo) Screen.YesNo(null, entries, Cursor.At(cursor.coerceIn(0, count - 1)), Topology.vertical(count), cancel)
        else Screen.ListMenu(MenuKind.MULTICHOICE, entries, Cursor.At(cursor.coerceIn(0, count - 1)), Topology.vertical(count), cancel)
    }

    /**
     * The professor's messages (RowanIntro_DisplayMessage): printed with the intro's text printer, which waits for A
     * at each page break; every message ends at once once printed (endEarly), so only the page breaks wait. The text
     * is `string`, freed once printed but still readable; only the page on screen is shown.
     */
    private fun message(mem: PlatinumMemory, data: Long): Screen {
        if (mem.s32(data + R.DISPLAY_MESSAGE_STATE) != R.DM_PRINT) return Screen.Animation(AnimationKind.TRANSITION)
        val printer = mem.textPrinter(mem.version.textPrinterTasks, mem.s32(data + R.TEXT_PRINTER_ID))
            ?: return Screen.Animation(AnimationKind.TRANSITION)
        val awaiting = if (mem.u8(printer + S.TP_STATE) in S.TEXT_PRINTER_WAIT_STATES) Awaiting.INPUT else Awaiting.TEXT_PRINTING
        val text = visiblePage(mem, mem.ptr(data + R.STRING), printer)
        return Screen.Dialogue(TextSource.INTRO, null, text, awaiting)
    }

    /** The intro's child application: the naming keyboard, or the TV programme that ends the intro. */
    private fun childApp(mem: PlatinumMemory, data: Long, text: PlatinumText?): Screen {
        Gen4NamingKeyboard.decode(mem, mem.version.gSystem, mem.version.naming, mem.fading)?.let { return it }
        val child = mem.ptr(data + R.CHILD_APP) ?: return Screen.Animation(AnimationKind.TRANSITION)
        if (mem.fn(child + S.OM_MAIN) == mem.version.fnRowanIntroTvMain && mem.s32(child + S.OM_EXEC_STATE) == S.OM_EXEC_MAIN) {
            val tv = mem.ptr(child + S.OM_DATA)
            if (tv != null && mem.s32(tv + TV_STATE) == TV_WAIT_INPUT && !mem.fading) {
                return Screen.PressToContinue(ContinueReason.MESSAGE, text?.line(PlatinumText.ROWAN_INTRO_TV, 0)?.replace('\n', ' '))
            }
        }
        return Screen.Animation(AnimationKind.TRANSITION)
    }
}

/**
 * The page of [strPtr] a message box shows: up to the text printer's current character while it prints (or waits at
 * a page break), the whole last page once done. Reads a freed string too (the intro frees it once printed).
 */
internal fun visiblePage(mem: PlatinumMemory, strPtr: Long?, printer: Long?): String {
    if (strPtr == null || mem.gameString(strPtr, allowFreed = true) == null) return ""
    val size = mem.u16(strPtr + S.STR_SIZE).coerceAtMost(2048)
    val chars = mem.chars(strPtr + S.STR_DATA, size)
    val printed = printer?.let { ((mem.u32(it + S.TP_CURRENT_CHAR) - (strPtr + S.STR_DATA)) / 2).toInt().takeIf { n -> n in 0..size } }
    return Gen4Text.visibleLines(chars, printed).replace('\n', ' ')
}
