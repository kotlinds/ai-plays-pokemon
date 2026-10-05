package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
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
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.hgss.HgssTextAddresses as T

/**
 * Message boxes, sign banners, Pokégear phone calls and the bottom-screen touch menus of field scripts
 * (design/screens/text-banners-phone.md).
 *
 * What it fixes compared with the generic mapping of [HgssState]:
 * - the script wait is read on the **innermost** script context: a `CallStd` caller sits in `ScrNative_WaitStd`
 *   (0x02040BCC) while its child does the real wait, which used to surface as `native(0x2040bcc)`;
 * - a sign banner (shown automatically when walking north into a sign, or with A) is [Screen.Overworld] with its
 *   text, never a blocking scene: any D-pad press closes it (and only turns the player, scrcmd_c.c:910);
 * - message boxes say whether the text is still printing or waits for A (page break or last page);
 * - Pokégear phone calls show the caller and the call text, in-call questions and the contact list;
 * - touch yes/no and multichoice menus get semantic ids, exact topology (overlay 27 navigation tables) and touch points;
 * - the fishing minigame and the "can't use that now" message of a registered key item ([HgssFishing]).
 *
 * Every id is language independent (positions, contact ids, menu values); labels are only for display.
 */
internal object HgssTextScreens : HgssScreenDecoder {

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (mem.version != HgssVersion.HEARTGOLD_US) return null
        if (state.mode == GameMode.NEW_GAME_INTRO) return HgssOakIntroScreens.decode(mem)
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        return when (state.mode) {
            GameMode.APP -> if (state.modeDetail == "pokegear") HgssPhoneScreens.decode(mem, fs) else null
            GameMode.OVERWORLD, GameMode.FIELD_BUSY, GameMode.SCRIPT, GameMode.DIALOGUE -> field(mem, fs, state)
            else -> null
        }
    }

    private fun field(mem: HgssMemory, fs: Long, state: HgssState): Screen? {
        HgssFishing.state(mem)?.let { return fishingScreen(it) }
        val tasks = HgssFieldTasks.chain(mem, fs)
        tasks.firstOrNull { it.function == T.FN_TASK_KEY_ITEM_MESSAGE }?.env?.let { env ->
            return keyItemMessage(mem, env)
        }
        val env = tasks.firstNotNullOfOrNull { task -> task.env?.takeIf { mem.u32(it + A.SE_CHECK) == A.SCRIPT_ENV_MAGIC } }
            ?: return null
        return HgssScriptScreens.decode(mem, fs, env, state)
    }

    /** The message of `Task_PrintRegisteredKeyItemUseMessage` (field_use_item.c:501): A, B or the D-pad close it. */
    private fun keyItemMessage(mem: HgssMemory, env: Long): Screen? {
        val string = mem.ptr(env + T.KEY_ITEM_MSG_STRING) ?: return null
        val text = HgssTextPrinter.read(mem, string, mem.u16(env + T.KEY_ITEM_MSG_PRINTER)) ?: return null
        return Screen.Dialogue(TextSource.FIELD, null, text.visible, text.awaiting)
    }

    private fun fishingScreen(fishing: FishingState): Screen = when (fishing) {
        is FishingState.Bite -> Screen.PressToContinue(ContinueReason.FISHING_BITE)
        is FishingState.Result -> fishing.text?.let { Screen.Dialogue(TextSource.FIELD, null, it, fishing.awaiting) }
            ?: Screen.Animation(AnimationKind.CUTSCENE)
        is FishingState.Refused -> Screen.Dialogue(TextSource.FIELD, null, fishing.text ?: "", fishing.awaiting)
        else -> Screen.Animation(AnimationKind.CUTSCENE)
    }
}

/** One field task (`TaskManager`, include/task.h): its function (without the Thumb bit) and environment. */
internal data class HgssFieldTask(val function: Long, val env: Long?)

internal object HgssFieldTasks {
    /** The field task stack from the top task (`FieldSystem.taskman`), following `TaskManager.prev`. */
    fun chain(mem: HgssMemory, fs: Long): List<HgssFieldTask> {
        val out = mutableListOf<HgssFieldTask>()
        val seen = HashSet<Long>()
        var task = mem.ptr(fs + A.FS_TASKMAN)
        while (task != null && out.size < 16 && seen.add(task)) {
            out += HgssFieldTask(mem.fn(task + A.TM_FUNC), mem.ptr(task + A.TM_ENV))
            task = mem.ptr(task + A.TM_PREV)
        }
        return out
    }
}

/** A text being printed by the global text printers (src/text.c `sTextPrinterTasks`). */
internal data class HgssPrintedText(
    /** The whole text (all pages). */
    val full: String,
    /** What the box shows now (the lines of the current page printed so far). */
    val visible: String,
    /** [Awaiting.TEXT_PRINTING] while characters are printed or scrolled, [Awaiting.INPUT] at a page break or once done. */
    val awaiting: Awaiting,
    /** The printer still exists (printing or waiting at a page break); false once the last page is done. */
    val printerAlive: Boolean,
)

internal object HgssTextPrinter {
    /**
     * Reads the `String` at [string] printed by printer [printerId]. The printer slot is only trusted when its
     * `currentChar` points inside that String (the slots are reused by every text). Without a live printer the text
     * is complete and waits for a key.
     */
    fun read(mem: HgssMemory, string: Long, printerId: Int): HgssPrintedText? {
        val full = mem.gameString(string) ?: return null
        val size = mem.u16(string + A.STR_SIZE).coerceAtMost(2048)
        val data = string + A.STR_DATA
        val chars = mem.chars(data, size)
        val printer = printer(mem, printerId)?.takeIf { p ->
            val current = mem.u32(p + A.TP_CURRENT_CHAR)
            current >= data && current <= data + 2L * size
        }
        if (printer == null) return HgssPrintedText(full, HgssText.visibleLines(chars, null), Awaiting.INPUT, false)
        val printed = ((mem.u32(printer + A.TP_CURRENT_CHAR) - data) / 2).toInt()
        val waiting = mem.u8(printer + A.TP_STATE) in A.TEXT_PRINTER_WAIT_STATES
        return HgssPrintedText(
            full, HgssText.visibleLines(chars, printed),
            if (waiting) Awaiting.INPUT else Awaiting.TEXT_PRINTING, true,
        )
    }

    /** The data of printer [id] (`sTextPrinterTasks[id]`), null when the printer is finished. */
    fun printer(mem: HgssMemory, id: Int): Long? =
        if (id !in 0 until T.TEXT_PRINTER_COUNT) null
        else mem.ptr(mem.version.textPrinterTasks + 4L * id)?.let { mem.ptr(it + A.SYSTASK_DATA) }
}

/** Screens of a running field script: message boxes, sign banners, touch yes/no and multichoice. */
internal object HgssScriptScreens {

    fun decode(mem: HgssMemory, fs: Long, env: Long, state: HgssState): Screen? {
        val v = mem.version
        val context = innermostContext(mem, env)
        val native = context?.let { if (mem.u8(it + A.SC_MODE) == SC_MODE_NATIVE) mem.fn(it + A.SC_NATIVE) else null }
        val boxOpen = mem.u8(env + A.SE_MSGBOX_OPEN) != 0
        val string = mem.ptr(env + A.SE_STRING_BUFFER_0)
        val printerId = mem.u8(env + A.SE_TEXT_PRINTER)

        if (native in T.BANNER_WAITS && bannerShown(mem, fs)) {
            // The slide-in is a few frames where no key is read (and a Trainer Tips text isn't loaded yet: the buffer
            // still holds the previous message); afterwards any D-pad press closes the banner.
            if (native == T.FN_SCR_SIGN_SLIDE_WAIT) return Screen.Overworld(banner = null, awaiting = Awaiting.ANIMATION)
            val text = string?.let { mem.gameString(it) }?.takeIf { it.isNotBlank() }
            return Screen.Overworld(banner = text, awaiting = Awaiting.INPUT)
        }
        val message = if (boxOpen && string != null) HgssTextPrinter.read(mem, string, printerId) else null
        val source = if (isSign(mem, env, state)) TextSource.SIGN else TextSource.FIELD
        val speaker = if (source == TextSource.FIELD) speaker(mem, env) else null
        return when (native) {
            v.fnScrTouchYesNo -> HgssTouchMenus.yesNo(mem, fs, message?.visible)
                ?: Screen.Dialogue(source, speaker, message?.visible ?: "", Awaiting.ANIMATION)
            v.fnScrTouchMenu -> HgssTouchMenus.multichoice(mem, fs)
                ?: Screen.Dialogue(source, speaker, message?.visible ?: "", Awaiting.ANIMATION)
            v.fnScrYesNo -> topYesNo(mem, env, message?.visible)
            // Printer gone while the script still waits for it: it resumes on the next frame.
            v.fnScrWaitTextPrint -> message?.let {
                Screen.Dialogue(source, speaker, it.visible, if (it.printerAlive) it.awaiting else Awaiting.ANIMATION)
            }
            v.fnScrWaitABPress, v.fnScrWaitButton, v.fnScrWaitButtonOrDpad, v.fnScrWaitButtonOrDelay ->
                message?.let { Screen.Dialogue(source, speaker, it.visible, Awaiting.INPUT) }
            // The bottom screen switching between the start-menu icons and the script menu (scrcmd_c.c:4925-4975).
            T.FN_SCR_TOUCH_MENU_HIDE, T.FN_SCR_TOUCH_MENU_SHOW ->
                message?.let { Screen.Dialogue(source, speaker, it.visible, Awaiting.ANIMATION) }
            else -> message?.let { Screen.Dialogue(source, speaker, it.visible, Awaiting.ANIMATION) }
        }
    }

    /**
     * The context doing the real wait: the active one with the highest index, skipping callers parked in
     * `ScrNative_WaitStd` (scrcmd_c.c:367) while their `CallStd` child runs.
     */
    fun innermostContext(mem: HgssMemory, env: Long): Long? {
        // The active count bounds the scan; without it (0 or garbage) all slots are tried (ended contexts are NULL).
        val count = mem.u8(env + A.SE_ACTIVE_CONTEXTS).takeIf { it in 1..MAX_CONTEXTS } ?: MAX_CONTEXTS
        return (count - 1 downTo 0).asSequence()
            .mapNotNull { i -> mem.ptr(env + A.SE_SCRIPT_CONTEXTS + 4L * i) }
            .firstOrNull { ctx ->
                val mode = mem.u8(ctx + A.SC_MODE)
                mode != 0 && !(mode == SC_MODE_NATIVE && mem.fn(ctx + A.SC_NATIVE) == T.FN_SCR_WAIT_STD)
            }
    }

    /** `FieldSystem.unk68 + 0x13` bit 7: the sign banner window exists (overlay_01_021F3D38.s ov01_021F3E10). */
    private fun bannerShown(mem: HgssMemory, fs: Long): Boolean =
        mem.ptr(fs + T.FS_SIGN_BANNER)?.let { mem.u8(it + T.BANNER_FLAGS) and T.BANNER_SHOWN != 0 } == true

    /** A message started by a background event (sign, notice...): no object talked to and a BG event runs this script. */
    private fun isSign(mem: HgssMemory, env: Long, state: HgssState): Boolean {
        if (mem.u32(env + A.SE_LAST_INTERACTED) != 0L) return false
        val script = mem.u16(env + A.SE_ACTIVE_SCRIPT)
        return state.surroundings?.bgEvents?.any { it.scriptId == script && it.type == "sign" } == true
    }

    /**
     * The person talked to (`ScriptEnvironment.lastInteracted`): a trainer is named like the battle names it
     * ("Psychic Eli", from its trainer script, or from the battle its map's script runs for it: Kimono Girls); anyone
     * else from its sprite (display only).
     */
    private fun speaker(mem: HgssMemory, env: Long): String? {
        val obj = mem.ptr(env + A.SE_LAST_INTERACTED) ?: return null
        val script = mem.u16(obj + A.MO_SCRIPT_ID)
        // A common trainer script, else a trainer its map's own scripts battle (Kimono Girls, Elite Four...).
        val trainer = trainerOfScript(script)
            ?: mem.s32(obj + A.MO_MAP_ID).takeIf { it >= 0 }?.let { zone -> HgssTrainers.trainerOf(zone, mem.u16(obj + A.MO_ID), script) }
        trainer?.let { HgssData.gameData?.trainerLabel(it)?.let { label -> return label } }
        val sprite = HgssData.spriteName(mem.s32(obj + A.MO_SPRITE_ID))
        // An invisible object (the Cerulean Gym's Machine Part) speaks for no one: its text is narration.
        if (sprite == HgssExaminables.STOP_SPRITE) return null
        return HgssLabels.person(sprite)
    }

    /**
     * The trainer id of an NPC trainer's script, null for other scripts (src/fieldmap.c ScriptNumToTrainerNum:
     * `std_trainer` scripts 3000.., `std_trainer_2` (second trainer of a double) 5000.., FIRST_TRAINER_INDEX = 1).
     */
    fun trainerOfScript(script: Int): Int? = when (script) {
        in STD_TRAINER until STD_TRAINER_2 -> script - STD_TRAINER + 1
        in STD_TRAINER_2 until STD_TRAINER_2 + MAX_TRAINERS -> script - STD_TRAINER_2 + 1
        else -> null
    }

    private const val STD_TRAINER = 3000
    private const val STD_TRAINER_2 = 5000
    private const val MAX_TRAINERS = 1000

    /** The top-screen yes/no (`ScrCmd_YesNo`, ListMenu2D at `ScriptEnvironment.unk24`). B answers NO. */
    private fun topYesNo(mem: HgssMemory, env: Long, question: String?): Screen {
        val cursor = mem.ptr(env + A.SE_LIST_MENU_2D)?.let { mem.u8(it + A.LM2D_SELECTED) }?.takeIf { it in 0..1 }
        return Screen.YesNo(
            question, HgssTouchMenus.YES_NO_ENTRIES.map { it.copy(touch = null) },
            cursor?.let { Cursor.At(it) } ?: Cursor.Hidden, Topology.vertical(2), CancelBehavior.CONFIRMS_LAST,
        )
    }

    private const val SC_MODE_NATIVE = 2
    private const val MAX_CONTEXTS = 3
}

/**
 * The script menus drawn on the touch screen by overlay 27 (`ScrCmd_GetMenuChoice` yes/no, `ScrCmd_MenuExec`
 * multichoice): FieldSystem.unkD8 -> bottom-screen manager (app 3) -> touch-menu work.
 */
internal object HgssTouchMenus {

    /** YES above NO; touch rects `ov27_0225D120` {top 50-92 / 99-140, x 3-251}. */
    val YES_NO_ENTRIES = listOf(
        Entry("option:yes", "YES", touch = TouchPoint(127, 71)),
        Entry("option:no", "NO", touch = TouchPoint(127, 119)),
    )

    /**
     * The touch yes/no (`ov27_0225CD94`): the cursor is drawn at once on YES; UP/DOWN move without wrapping (verified
     * live); A confirms; B answers NO.
     */
    fun yesNo(mem: HgssMemory, fs: Long, question: String?): Screen? {
        val work = work(mem, fs) ?: return null
        val state = mem.s32(work + A.TOUCH_MENU_STATE)
        if (state != A.TM_STATE_YES_NO_WAIT) return null
        val cursor = mem.s32(work + A.TOUCH_MENU_CURSOR).takeIf { it in 0..1 } ?: return null
        return Screen.YesNo(question, YES_NO_ENTRIES, Cursor.At(cursor), Topology.vertical(2), CancelBehavior.CONFIRMS_LAST)
    }

    /**
     * The touch multichoice (`ov27_0225CA98`): cursor visible at once on the first option; the D-pad follows the
     * navigation table `ov27_0225D480[count - 2]` ({up, down, left, right}, -1 or the same index = no move, no wrap).
     * Ids are option positions (`option:<index>`), labels are the option texts.
     */
    fun multichoice(mem: HgssMemory, fs: Long): Screen? {
        val work = work(mem, fs) ?: return null
        if (mem.s32(work + A.TOUCH_MENU_STATE) != A.TM_STATE_MENU_WAIT) return null
        val menu = mem.ptr(work + A.TOUCH_MENU_FIELD_MENU) ?: return null
        val count = mem.u8(menu + A.FMENU_COUNT)
        val links = T.TOUCH_MENU_NAVIGATION.getOrNull(count - 2) ?: return null
        val rects = T.TOUCH_MENU_RECTS[count - 2]
        val entries = (0 until count).map { i ->
            val label = mem.gameString(mem.ptr(menu + A.FMENU_ITEMS_TOUCH + i * A.LIST_MENU_ITEM_SIZE))?.replace('\n', ' ') ?: "?"
            Entry("option:$i", label, touch = rects[i].center)
        }
        val cursor = mem.s32(work + A.TOUCH_MENU_CURSOR).takeIf { it in 0 until count } ?: return null
        val cancellable = mem.u8(menu + T.FMENU_FLAGS) and 1 != 0
        return Screen.ListMenu(
            MenuKind.MULTICHOICE, entries, Cursor.At(cursor), T.topology(links),
            if (cancellable) CancelBehavior.CONFIRMS_LAST else CancelBehavior.NONE,
        )
    }

    private fun work(mem: HgssMemory, fs: Long): Long? {
        val manager = mem.ptr(fs + A.FS_BOTTOM_SCREEN_TASK)?.let { mem.ptr(it + A.SYSTASK_DATA) } ?: return null
        if (mem.u8(manager + A.BSM_APP_ID) != A.BOTTOM_APP_SCRIPT_MENU) return null
        return mem.ptr(manager + A.BSM_APP_TASK)?.let { mem.ptr(it + A.SYSTASK_DATA) }
    }
}

/**
 * The Pokégear's phone (overlay 101), the field sub-application `Pokegear_Init`: contact list and app bar, the
 * Call / Sort / Quit menu, calls (text, caller, questions) and their end.
 */
internal object HgssPhoneScreens {

    fun decode(mem: HgssMemory, fs: Long): Screen? {
        val gear = mem.ptr(fs + A.FS_SUB0)?.let { mem.ptr(it + A.FSS0_SUB_APP) } ?: return null
        if (mem.fn(gear + A.OM_INIT) != T.FN_POKEGEAR_INIT) return null
        // Opening (an incoming call opens it by itself): no app inside yet, nothing to press.
        val gearData = mem.ptr(gear + A.OM_DATA) ?: return Screen.Animation(AnimationKind.TRANSITION)
        if (mem.s32(gear + A.OM_EXEC_STATE) != 2) return Screen.Animation(AnimationKind.TRANSITION)
        val child = mem.ptr(gearData + T.GEAR_CHILD_APP) ?: return Screen.Animation(AnimationKind.TRANSITION)
        if (mem.u8(gearData + T.GEAR_APP) != T.GEAR_APP_PHONE || mem.fn(child + A.OM_INIT) != T.FN_PHONE_INIT) {
            return null
        }
        if (mem.s32(gear + A.OM_PROC_STATE) != T.GEAR_STATE_RUN_PHONE || mem.s32(child + A.OM_EXEC_STATE) != 2) {
            return Screen.Animation(AnimationKind.TRANSITION)
        }
        val phone = mem.ptr(child + A.OM_DATA) ?: return null
        return when (mem.s32(child + A.OM_PROC_STATE)) {
            T.PHONE_STATE_INPUT_LOOP -> contactList(mem, gearData, phone)
            T.PHONE_STATE_CONTEXT_MENU, T.PHONE_STATE_SORT_MENU ->
                touchMenu(mem, phone, mem.ptr(phone + T.PHONE_MENU), MenuKind.OTHER)
                    ?: Screen.Animation(AnimationKind.TRANSITION)
            T.PHONE_STATE_PLAY_CALL -> mem.ptr(phone + T.PHONE_CALL_CONTEXT)?.let { call(mem, phone, it) }
            else -> Screen.Animation(AnimationKind.TRANSITION)
        }
    }

    /**
     * The contact list and the app bar under it, as one [MenuKind.PHONE_CONTACTS] menu: the contacts in list order
     * (`contact:<contact id>`), then the app bar buttons (`app:<card>`, `option:cancel` for the close button) with
     * `app:phone` last. Which part has the cursor is `PokegearAppData.cursorInAppSwitchZone`:
     * - in the list: UP/DOWN move one contact (no wrap, the list scrolls by itself), LEFT/RIGHT turn a page of 6
     *   keeping the row; A opens Call / Sort / Quit for that contact; B puts the cursor on the phone button (it
     *   "selects" `app:phone`, the last entry) — overlay_101_021F0880.c:132, overlay_101_021F0F48.c:220;
     * - on the bar: LEFT/RIGHT follow the bar's own links (wrapping, read from RAM); A on `app:phone` gives the cursor
     *   back to the list; B closes the Pokégear (overlay_100_021E5900.c:82).
     * There is no D-pad path between the two parts.
     */
    private fun contactList(mem: HgssMemory, gearData: Long, phone: Long): Screen? {
        val list = phone + T.PHONE_CONTACT_LIST
        val count = mem.u8(list + T.CL_COUNT).takeIf { it in 1..T.MAX_CONTACTS } ?: return null
        if (mem.u8(list + T.CL_FLAGS) and T.CL_SCROLLING != 0) return Screen.Animation(AnimationKind.TRANSITION)
        val first = mem.u8(list + T.CL_FIRST)
        val row = mem.u8(list + T.CL_ROW)
        val contacts = (0 until count).map { i ->
            val id = mem.u8(list + T.CL_SLOTS + T.CL_SLOT_SIZE * i + T.CL_SLOT_CONTACT_ID)
            val visibleRow = i - first
            Entry(
                "contact:$id", T.contactName(id),
                touch = if (visibleRow in 0 until T.CONTACT_ROWS) TouchPoint(116, 20 + 24 * visibleRow) else null,
            )
        }
        val bar = appBar(mem, gearData) ?: return null
        // Bar entries keep their grid order but the phone button goes last (B in the list "selects" it).
        val barOrder = bar.buttons.indices.sortedBy { if (bar.buttons[it].appId == T.GEAR_APP_PHONE) 1 else 0 }
        val barIndex = barOrder.withIndex().associate { (position, button) -> button to count + position }
        val entries = contacts + barOrder.map { bar.buttons[it].entry }
        val links = mutableMapOf<Int, Map<Button, Int>>()
        for (i in 0 until count) {
            links[i] = buildMap {
                if (i > 0) put(Button.UP, i - 1)
                if (i < count - 1) put(Button.DOWN, i + 1)
                val r = i - first
                if (r in 0 until T.CONTACT_ROWS && i == first + row) {
                    if (first + T.CONTACT_ROWS < count) put(Button.RIGHT, minOf(first + T.CONTACT_ROWS, count - T.CONTACT_ROWS) + r)
                    if (first > 0) put(Button.LEFT, maxOf(first - T.CONTACT_ROWS, 0) + r)
                }
            }
        }
        bar.buttons.forEachIndexed { i, button ->
            links[barIndex.getValue(i)] = buildMap {
                button.left?.let { put(Button.LEFT, barIndex.getValue(it)) }
                button.right?.let { put(Button.RIGHT, barIndex.getValue(it)) }
            }
        }
        val inBar = mem.u8(gearData + T.GEAR_CURSOR_IN_BAR) == 1
        val cursor = if (inBar) barIndex[bar.cursor] else (first + row).takeIf { it < count }
        return Screen.ListMenu(
            MenuKind.PHONE_CONTACTS, entries, cursor?.let { Cursor.At(it) } ?: Cursor.Hidden, Topology.of(links),
            if (inBar) CancelBehavior.CLOSES else CancelBehavior.SELECTS_LAST,
        )
    }

    internal data class BarButton(val appId: Int, val left: Int?, val right: Int?, val entry: Entry)
    internal data class AppBar(val buttons: List<BarButton>, val cursor: Int)

    /** The app bar cursor (`PokegearCursorManager.lastCursor`, include/application/pokegear/pokegear_internal.h:77). */
    internal fun appBar(mem: HgssMemory, gearData: Long): AppBar? {
        val cursor = mem.ptr(gearData + T.GEAR_CURSOR_MANAGER)?.let { mem.ptr(it + T.CM_LAST_CURSOR) } ?: return null
        val grid = mem.ptr(cursor + T.CURSOR_GRID) ?: return null
        val count = mem.u8(cursor + T.CURSOR_COUNT).takeIf { it in 1..8 } ?: return null
        val cards = mem.u8(gearData + T.GEAR_REGISTERED_CARDS) and 0x7F
        val buttons = (0 until count).map { i ->
            val cell = grid + T.GRID_CELL_SIZE * i
            val appId = mem.u16(cell + T.GRID_APP_ID)
            fun link(offset: Long) = mem.u8(cell + offset).takeIf { it < count && it != i }
            val unlocked = when (appId) {
                T.GEAR_APP_RADIO -> cards and T.GEARCARD_RADIO != 0
                T.GEAR_APP_MAP -> cards and T.GEARCARD_MAP != 0
                else -> true
            }
            val (id, label) = T.GEAR_APPS[appId] ?: ("app:$appId" to "?")
            BarButton(
                appId, link(T.GRID_LEFT), link(T.GRID_RIGHT),
                Entry(id, label, selectable = unlocked, touch = TouchPoint(mem.u8(cell + T.GRID_X), mem.u8(cell + T.GRID_Y))),
            )
        }
        return AppBar(buttons, mem.u8(cursor + T.CURSOR_POS).coerceIn(0, count - 1))
    }

    /**
     * A `TouchscreenListMenu` of the phone (src/touchscreen_list_menu.c): cursor always drawn, UP/DOWN wrap when the
     * template says so, A confirms after a short blink, B cancels. The pointer is never cleared after the menu is
     * destroyed, so it is trusted only when its heap block is still the menu's and it points back at the phone app.
     */
    fun touchMenu(mem: HgssMemory, phone: Long, menu: Long?, kind: MenuKind): Screen.Selectable? {
        if (menu == null || !menuAlive(mem, phone, menu)) return null
        val count = mem.u8(menu + T.TSM_COUNT).takeIf { it in 1..8 } ?: return null
        if (mem.u8(menu + T.TSM_ANIM_ACTIVE) != 0) return null
        val items = mem.ptr(menu + T.TSM_ITEMS) ?: return null
        val hitboxes = mem.ptr(menu + T.TSM_HITBOXES)
        val menuId = (0 until T.PHONE_MENU_COUNT).firstOrNull { mem.u32(phone + T.PHONE_MENU_ITEMS + 4L * it) == items }
        val cursor = Cursor.At(mem.u8(menu + T.TSM_CURSOR).coerceIn(0, count - 1))
        val wrap = mem.u8(menu + T.TSM_TEMPLATE) and 1 != 0
        val entries = (0 until count).map { i ->
            val label = mem.gameString(mem.ptr(items + A.LIST_MENU_ITEM_SIZE * i))?.replace('\n', ' ') ?: "?"
            val touch = hitboxes?.let { h ->
                val rect = h + 4L * i
                TouchPoint((mem.u8(rect + 2) + mem.u8(rect + 3)) / 2, (mem.u8(rect) + mem.u8(rect + 1)) / 2)
            }
            Entry("option:$i", label, touch = touch)
        }
        if (menuId == T.PHONE_MENU_YES_NO && count == 2) {
            return Screen.YesNo(
                null, listOf(entries[0].copy(id = "option:yes"), entries[1].copy(id = "option:no")),
                cursor, Topology.vertical(2, wrap), CancelBehavior.CLOSES,
            )
        }
        return Screen.ListMenu(kind, entries, cursor, Topology.vertical(count, wrap), CancelBehavior.CLOSES)
    }

    private fun menuAlive(mem: HgssMemory, phone: Long, menu: Long): Boolean =
        mem.u16(menu - T.HEAP_BLOCK_HEADER) == T.HEAP_BLOCK_USED &&
            mem.u32(menu - T.HEAP_BLOCK_HEADER + 4) == T.TSM_BLOCK_SIZE &&
            mem.u32(menu + T.TSM_SPAWNER) == mem.u32(phone + T.PHONE_MENU_SPAWNER) &&
            mem.u32(menu + T.TSM_CALLBACK_ARG) == phone

    /**
     * A call (`PhoneCall_Main`, overlay_101_021F1D74.c:491), from `PokegearPhoneCallContext.state.mainState`:
     * 0 ringing, 1 the caller talks (text printed with the global printer `textPrinter`, A/B/touch speed it up and
     * turn pages; questions are touch list menus), 2 last page shown (A/B hang up), 3 "Click!", 255-258 no signal.
     */
    private fun call(mem: HgssMemory, phone: Long, call: Long): Screen {
        val callerId = mem.u8(call + T.CALL_CALLER_ID)
        val speaker = mem.gameString(mem.ptr(call + T.CALL_CONTACT_NAME))?.takeIf { it.isNotBlank() } ?: T.contactName(callerId)
        val text = mem.ptr(call + T.CALL_TEXT)?.let { HgssTextPrinter.read(mem, it, mem.u8(call + T.CALL_PRINTER)) }
        fun dialogue(awaiting: Awaiting) = Screen.Dialogue(TextSource.PHONE, speaker, text?.visible ?: "", awaiting)
        return when (val state = mem.s32(call + T.CALL_MAIN_STATE)) {
            T.CALL_TALKING -> {
                touchMenu(mem, phone, mem.ptr(call + T.CALL_MENU), MenuKind.MULTICHOICE)?.let { return it }
                when {
                    text == null -> dialogue(Awaiting.ANIMATION)
                    text.printerAlive -> dialogue(text.awaiting)
                    // Printed and no printer: the handler moves on by itself (next message, menu or end).
                    else -> dialogue(Awaiting.ANIMATION)
                }
            }
            // The last line stays shown; A, B or a touch hangs up (PhoneCall_WaitButtonBeforeHangup).
            T.CALL_WAIT_HANG_UP, T.CALL_NO_SIGNAL_WAIT -> Screen.PressToContinue(ContinueReason.PHONE_CALL_ENDED, text?.visible)
            T.CALL_NO_SIGNAL_PRINT -> dialogue(text?.awaiting ?: Awaiting.ANIMATION)
            else -> if (state == T.CALL_RINGING) dialogue(Awaiting.ANIMATION) else Screen.Animation(AnimationKind.TRANSITION)
        }
    }
}

/**
 * The touch menus of Professor Oak's intro (overlay 53, src/oaks_speech.c), read from `OakSpeechData` (the main
 * app's data). The vertical menus and the gender pick start **without a highlighted option**: the first press of
 * A / B / UP / DOWN (A / LEFT / RIGHT for the gender pick) only shows it ([Cursor.Hidden], oaks_speech.c:1294 and
 * :1410, verified live on the info menu). Labels are the English ones (display only).
 */
internal object HgssOakIntroScreens {

    fun decode(mem: HgssMemory): Screen? {
        val data = mem.ptr(mem.version.mainAppState + A.MAIN_APP_OVERLAY_MANAGER)?.let { mem.ptr(it + A.OM_DATA) } ?: return null
        val menu = data + T.OAK_MENU
        val state = mem.s32(data + T.OAK_STATE)
        // A confirmed choice blinks for ~20 frames before the intro moves on (the delay byte stays set once the menu
        // is gone: it only counts in the menu states).
        if (state in T.OAK_MENU_STATES && mem.u8(menu + T.OAK_MENU_PRESS_DELAY) != 0) return Screen.Animation(AnimationKind.TRANSITION)
        val shown = mem.u8(menu + T.OAK_MENU_PAD_MODE) != 0
        val position = mem.u8(menu + T.OAK_MENU_CURSOR)
        return when (state) {
            T.OAK_STATE_INFO_MENU -> {
                val entries = T.OAK_INFO_LABELS.mapIndexed { i, label -> Entry("option:$i", label, touch = T.OAK_INFO_RECTS[i].center) }
                Screen.ListMenu(
                    MenuKind.OTHER, entries, if (shown) Cursor.At(position.coerceIn(0, 2)) else Cursor.Hidden,
                    Topology.vertical(entries.size), CancelBehavior.CONFIRMS_LAST,
                )
            }
            T.OAK_STATE_GENDER -> {
                val entries = T.OAK_GENDER_LABELS.mapIndexed { i, label -> Entry("option:$i", label, touch = T.OAK_GENDER_RECTS[i].center) }
                Screen.ListMenu(
                    MenuKind.OTHER, entries, if (shown) Cursor.At(position.coerceIn(0, 1)) else Cursor.Hidden,
                    Topology.horizontal(2), CancelBehavior.NONE,
                )
            }
            T.OAK_STATE_CONFIRM_GENDER, T.OAK_STATE_CONFIRM_NAME -> {
                // Yes / No beside the player's picture: right column for a boy (menu 1), left for a girl (menu 2).
                val rects = if (mem.u16(data + T.OAK_PLAYER_GENDER) == 0) T.OAK_CONFIRM_RECTS_BOY else T.OAK_CONFIRM_RECTS_GIRL
                Screen.YesNo(
                    null,
                    listOf(Entry("option:yes", "Yes", touch = rects[0].center), Entry("option:no", "No", touch = rects[1].center)),
                    if (shown) Cursor.At(position.coerceIn(0, 1)) else Cursor.Hidden, Topology.vertical(2), CancelBehavior.CONFIRMS_LAST,
                )
            }
            T.OAK_STATE_UNDERSTOOD -> {
                // OakSpeechYesNo (oaks_speech_yesnomenu.c): cursor shown at once on YES; B answers NO.
                val yesNo = mem.ptr(data + T.OAK_YES_NO) ?: return null
                val packed = mem.u8(yesNo + T.OAK_YES_NO_STATE)
                if (packed and 0x0F != 0) return Screen.Animation(AnimationKind.TRANSITION)
                val result = packed shr 4
                Screen.YesNo(
                    null,
                    listOf(
                        Entry("option:yes", "YES", touch = TouchPoint(127, 71)),
                        Entry("option:no", "NO", touch = TouchPoint(127, 119)),
                    ),
                    if (result in 1..2) Cursor.At(result - 1) else Cursor.Hidden, Topology.vertical(2), CancelBehavior.CONFIRMS_LAST,
                )
            }
            else -> speech(mem, data)
        }
    }

    /**
     * The professor's messages (OakSpeech_PrintDialogMsg / OakSpeech_PrintAndFadeFullScreenText, src/oaks_speech.c:936,
     * 986). A dialog message can have several pages: while it is printed (state 1) its text printer
     * (`data->textPrinter`) also waits for A at each page break; once printed, state 2 waits for A. A full-screen text
     * waits for A or B (state 3). The text is `data->string`, freed (marked invalid) once printed but still readable
     * until the next message; only the page on screen is shown.
     */
    private fun speech(mem: HgssMemory, data: Long): Screen? {
        val strPtr = mem.ptr(data + A.OAK_STRING)
        val printer = mem.ptr(mem.version.textPrinterTasks + 4L * mem.u8(data + T.OAK_TEXT_PRINTER))?.let { mem.ptr(it + A.SYSTASK_DATA) }
        val awaiting = when {
            mem.s32(data + T.OAK_PRINT_DIALOG_STATE) == T.OAK_DIALOG_PRINTING ->
                if (printer != null && mem.u8(printer + A.TP_STATE) in A.TEXT_PRINTER_WAIT_STATES) Awaiting.INPUT else Awaiting.TEXT_PRINTING
            mem.s32(data + T.OAK_PRINT_DIALOG_STATE) == T.OAK_DIALOG_WAIT_BUTTON -> Awaiting.INPUT
            mem.s32(data + T.OAK_PRINT_FULL_SCREEN_STATE) == T.OAK_FULL_SCREEN_WAIT_BUTTON -> Awaiting.INPUT
            // Every input of the speech is decoded (menus, yes / no, messages): anything else is the professor
            // fading in, a picture moving... nothing to press.
            else -> return Screen.Animation(AnimationKind.TRANSITION)
        }
        val text = strPtr?.takeIf { mem.gameString(it, allowFreed = true) != null }?.let { str ->
            val size = mem.u16(str + A.STR_SIZE).coerceAtMost(2048)
            val chars = mem.chars(str + A.STR_DATA, size)
            // Printing: the page the printer is on (its current char); printed: the last page.
            val printed = printer?.takeIf { awaiting != Awaiting.INPUT || mem.s32(data + T.OAK_PRINT_DIALOG_STATE) == T.OAK_DIALOG_PRINTING }
                ?.let { ((mem.u32(it + A.TP_CURRENT_CHAR) - (str + A.STR_DATA)) / 2).toInt().takeIf { n -> n in 0..size } }
            HgssText.visibleLines(chars, printed).replace('\n', ' ')
        }.orEmpty()
        return Screen.Dialogue(TextSource.INTRO, null, text, awaiting)
    }
}

/** The rod used by the fishing minigame (`FishingRodTaskEnv.rodType`, src/field_use_item.c:426). */
enum class Rod { OLD, GOOD, SUPER }

/**
 * The state of the fishing minigame (overlay 1, `Task_OverworldFish` 0x021FC698 and its minigame task
 * `ov01_021FC798`, asm/overlay_01_021FC66C.s), for a `fish` action that must press A on the first frame of a bite.
 */
sealed interface FishingState {

    /** A state of the minigame itself, with the rod in use. */
    sealed interface WithRod : FishingState {
        val rod: Rod
    }

    /** The rod is cast (states 0-2, 34 frames). */
    data class Casting(override val rod: Rod) : WithRod

    /** Waiting for a bite (states 3-4, or 12-13 when nothing will bite): pressing A now reels in too early. */
    data class Waiting(override val rod: Rod) : WithRod

    /**
     * "!" shown: press A now (a new press, B does nothing). [framesLeft] is the window counter (state 5), which counts
     * down once per minigame update: every 2 frames live, so about `2 * framesLeft` frames remain (35 = Good Rod 30 + the
     * following Pokémon bonus, verified live).
     */
    data class Bite(override val rod: Rod, val framesLeft: Int) : WithRod

    /** A message ends the minigame (or the cleanup runs); [awaiting] tells if it waits for A/B. */
    sealed interface Result : WithRod {
        val text: String?
        val awaiting: Awaiting
    }

    /** Hooked: "Landed a Pokémon!" then the wild battle starts (states 6-9 and the cleanup). */
    data class Hooked(override val rod: Rod, override val text: String?, override val awaiting: Awaiting) : Result

    /**
     * Nothing bit ("Not even a nibble..."). Pressing A during the no-bite wait also ends here (with the "too
     * quickly" text): nothing would have bitten anyway.
     */
    data class NothingBit(override val rod: Rod, override val text: String?, override val awaiting: Awaiting) : Result

    /** The "!" window passed without A ("The Pokémon got away..."). */
    data class GotAway(override val rod: Rod, override val text: String?, override val awaiting: Awaiting) : Result

    /** A was pressed before the "!" of a Pokémon that was going to bite ("Reeled it in too quickly..."). */
    data class TooEarly(override val rod: Rod, override val text: String?, override val awaiting: Awaiting) : Result

    /**
     * The registered item refused to be used ("There's a time and place...": not facing fishable water, or a
     * Pokémon following). Not specific to rods: any registered key item refused with Y gives it.
     */
    data class Refused(val text: String?, val awaiting: Awaiting) : FishingState
}

/** Probe of the fishing minigame (see [FishingState]); null when no fishing (or refusal message) is running. */
object HgssFishing {

    fun state(mem: HgssMemory): FishingState? {
        if (mem.version != HgssVersion.HEARTGOLD_US) return null
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        val tasks = HgssFieldTasks.chain(mem, fs)
        tasks.firstOrNull { it.function == T.FN_TASK_KEY_ITEM_MESSAGE }?.env?.let { env ->
            val string = mem.ptr(env + T.KEY_ITEM_MSG_STRING)
            val text = string?.let { HgssTextPrinter.read(mem, it, mem.u16(env + T.KEY_ITEM_MSG_PRINTER)) }
            return FishingState.Refused(text?.visible, text?.awaiting ?: Awaiting.ANIMATION)
        }
        val work = mem.mainTaskData(T.FN_FISHING_MINIGAME) ?: return null
        val rod = Rod.entries.getOrNull(mem.s32(work + T.FISH_ROD)) ?: return null
        val bite = mem.s32(work + T.FISH_BITE) != 0
        val state = mem.s32(work + T.FISH_STATE)
        fun message(): Pair<String?, Awaiting> {
            val string = mem.ptr(work + T.FISH_STRING) ?: return null to Awaiting.ANIMATION
            // The String is filled when the message starts printing: blank before (and right after) that.
            val text = HgssTextPrinter.read(mem, string, mem.u8(work + T.FISH_PRINTER))?.takeIf { it.full.isNotBlank() }
                ?: return null to Awaiting.ANIMATION
            return text.visible to text.awaiting
        }
        return when (state) {
            in 0..2 -> FishingState.Casting(rod)
            3, 4, 12, 13 -> FishingState.Waiting(rod)
            5 -> FishingState.Bite(rod, mem.s32(work + T.FISH_BITE_WINDOW))
            6, 7, 9 -> FishingState.Hooked(rod, null, Awaiting.ANIMATION)
            8 -> message().let { (text, awaiting) -> FishingState.Hooked(rod, text, awaiting) }
            else -> {
                val (text, awaiting) = if (state in T.FISH_MESSAGE_STATES) message() else null to Awaiting.ANIMATION
                when {
                    mem.s32(work + T.FISH_OUTCOME) == 1 -> FishingState.Hooked(rod, text, awaiting)
                    !bite -> FishingState.NothingBit(rod, text, awaiting)
                    // The window counter only runs down in state 5: left untouched, A came before the "!".
                    mem.s32(work + T.FISH_BITE_WINDOW) > 0 -> FishingState.TooEarly(rod, text, awaiting)
                    else -> FishingState.GotAway(rod, text, awaiting)
                }
            }
        }
    }
}

/**
 * RAM layout used by the text / banner / phone / fishing decoders. Absolute addresses are Pokémon HeartGold (USA)
 * ones (`build/heartgold.us/main.elf.xMAP`); offsets come from the decomp headers named on each value.
 */
internal object HgssTextAddresses {
    // --- Script natives (src/scrcmd_c.c) ---
    /** `sub_020415E0`, native of ScrCmd_060: the sign banner waits for A/B/D-pad (scrcmd_c.c:910). */
    const val FN_SCR_SIGN_WAIT = 0x020415E0L
    /** `sub_02041520`, native of ScrCmd_TrainerTips: banner text printing, a D-pad press closes it (:867). */
    const val FN_SCR_TRAINER_TIPS_WAIT = 0x02041520L
    /** `sub_02041454`, native of ScrCmd_058: waits for the banner slide animation (:836). */
    const val FN_SCR_SIGN_SLIDE_WAIT = 0x02041454L
    /** `ScrNative_WaitStd`: a caller waiting for its `CallStd` child context (:367). */
    const val FN_SCR_WAIT_STD = 0x02040BCCL
    /** `sub_020476E8` / `sub_02047744`: the bottom screen switching to / from the script menu app (:4938, :4953). */
    const val FN_SCR_TOUCH_MENU_HIDE = 0x020476E8L
    const val FN_SCR_TOUCH_MENU_SHOW = 0x02047744L
    val BANNER_WAITS = setOf(FN_SCR_SIGN_WAIT, FN_SCR_TRAINER_TIPS_WAIT, FN_SCR_SIGN_SLIDE_WAIT)

    // --- Field ---
    /** `FieldSystem.unk68`: the sign banner {Window; u16 arrow @0x10; u8 type @0x12; u8 cmd:7|shown:1 @0x13}. */
    const val FS_SIGN_BANNER = 0x68L
    const val BANNER_FLAGS = 0x13L
    const val BANNER_SHOWN = 0x80
    /** `FieldMenu.unk97` bit 0: B picks the last option of a multichoice. */
    const val FMENU_FLAGS = 0x97L
    const val TEXT_PRINTER_COUNT = 8

    /** `Task_PrintRegisteredKeyItemUseMessage` (field_use_item.c:501): env {Window; String* @0x10; u16 printer @0x14}. */
    const val FN_TASK_KEY_ITEM_MESSAGE = 0x0206518CL
    const val KEY_ITEM_MSG_STRING = 0x10L
    const val KEY_ITEM_MSG_PRINTER = 0x14L

    // --- Fishing (asm/overlay_01_021FC66C.s) ---
    /** `ov01_021FC798`: the minigame SysTask on the main queue; data = 0x4C-byte work. */
    const val FN_FISHING_MINIGAME = 0x021FC798L
    const val FISH_BITE = 0x00L          // BOOL bite decided before the cast
    const val FISH_OUTCOME = 0x08L       // 1 = landed
    const val FISH_STATE = 0x0CL         // handler index in ov01_02208DC4
    const val FISH_BITE_WINDOW = 0x18L   // frames left to press A once the "!" shows
    const val FISH_ROD = 0x1CL           // 0 Old, 1 Good, 2 Super
    const val FISH_PRINTER = 0x28L       // u8 text printer id
    const val FISH_STRING = 0x2CL        // String* message
    /** States showing a message: 8 "Landed a Pokémon!", 14 the result message (waits A/B). */
    val FISH_MESSAGE_STATES = setOf(8, 14)

    // --- Pokégear (overlays 100 / 101) ---
    const val FN_POKEGEAR_INIT = 0x021E642CL
    const val FN_PHONE_INIT = 0x021EF848L
    const val GEAR_STATE_RUN_PHONE = 11
    /** `PokegearAppData` (pokegear_internal.h:135). */
    const val GEAR_APP = 0x04L
    const val GEAR_REGISTERED_CARDS = 0x05L
    const val GEAR_CURSOR_IN_BAR = 0x06L
    const val GEAR_CURSOR_MANAGER = 0x7CL
    const val GEAR_CHILD_APP = 0x70L
    const val GEARCARD_RADIO = 1
    const val GEARCARD_MAP = 2
    const val GEAR_APP_RADIO = 1
    const val GEAR_APP_MAP = 2
    const val GEAR_APP_PHONE = 3
    /** App bar buttons by `PokegearCursorGrid.appId`: semantic id and display label. */
    val GEAR_APPS = mapOf(
        0 to ("app:configure" to "Configure"), 1 to ("app:radio" to "Radio"), 2 to ("app:map" to "Map"),
        3 to ("app:phone" to "Phone"), 4 to ("option:cancel" to "Close"),
    )
    const val CM_LAST_CURSOR = 0x08L     // PokegearCursorManager.lastCursor
    const val CURSOR_POS = 0x01L         // PokegearCursor.cursorPos
    const val CURSOR_COUNT = 0x02L
    const val CURSOR_GRID = 0x04L
    const val GRID_CELL_SIZE = 12L       // PokegearCursorGrid {u16 appId; u8 left, right, up, down; u8 x, y; ...}
    const val GRID_APP_ID = 0x00L
    const val GRID_LEFT = 0x02L
    const val GRID_RIGHT = 0x03L
    const val GRID_X = 0x06L
    const val GRID_Y = 0x07L

    /** Phone main states (phone_internal.h:12). */
    const val PHONE_STATE_INPUT_LOOP = 1
    const val PHONE_STATE_PLAY_CALL = 6
    const val PHONE_STATE_CONTEXT_MENU = 7
    const val PHONE_STATE_SORT_MENU = 8
    /** `PokegearPhoneAppData` (phone_internal.h:251). */
    const val PHONE_MENU_SPAWNER = 0xC0L
    const val PHONE_CALL_CONTEXT = 0xC4L
    const val PHONE_CONTACT_LIST = 0xE0L
    const val PHONE_MENU_ITEMS = 0x4E8L  // ListMenuItem *listMenuItems[7], by context menu id
    const val PHONE_MENU = 0x504L
    const val PHONE_MENU_COUNT = 7
    const val PHONE_MENU_YES_NO = 6
    /** `PhoneContactListUI`. */
    const val CL_COUNT = 0x00L
    const val CL_ROW = 0x01L
    const val CL_FIRST = 0x03L
    const val CL_FLAGS = 0x07L
    const val CL_SCROLLING = 1
    const val CL_SLOTS = 0x0CL
    const val CL_SLOT_SIZE = 0x0CL
    const val CL_SLOT_CONTACT_ID = 0x08L
    const val CONTACT_ROWS = 6
    const val MAX_CONTACTS = 75
    /** `PokegearPhoneCallContext` (phone_internal.h:150). */
    const val CALL_MENU = 0x14L
    const val CALL_PRINTER = 0x35L
    const val CALL_TEXT = 0x54L
    const val CALL_CONTACT_NAME = 0x58L
    const val CALL_MAIN_STATE = 0x88L
    const val CALL_CALLER_ID = 0xA0L
    const val CALL_RINGING = 0
    const val CALL_TALKING = 1
    const val CALL_WAIT_HANG_UP = 2
    const val CALL_NO_SIGNAL_PRINT = 257
    const val CALL_NO_SIGNAL_WAIT = 258
    /** `TouchscreenListMenu` (include/touchscreen_list_menu.h:50). */
    const val TSM_SPAWNER = 0x00L
    const val TSM_TEMPLATE = 0x04L       // bit 0 wrapAround
    const val TSM_ITEMS = 0x10L
    const val TSM_COUNT = 0x18L
    const val TSM_HITBOXES = 0x20L       // {top, bottom, left, right} per option
    const val TSM_CURSOR = 0x24L
    const val TSM_ANIM_ACTIVE = 0x25L
    const val TSM_CALLBACK_ARG = 0x34L
    /** Heap block header before every allocation: 'UD' while used, then the block size (0x38 + 0x10 for the menu). */
    const val HEAP_BLOCK_HEADER = 0x20L
    const val HEAP_BLOCK_USED = 0x5544
    const val TSM_BLOCK_SIZE = 0x48L

    /**
     * Display names of the phone contacts by `PHONE_CONTACT_*` id (entry 0 of `sPhoneMessageGmm[id]`,
     * src/phonebook_dat.c:60, English). Display only: ids are `contact:<id>`.
     */
    private val CONTACT_NAMES = listOf(
        "Mother", "Prof. Elm", "Prof. Oak", "Ethan", "Lyra", "Kurt", "Day-C Man", "Day-C Lady", "Buena", "Bill",
        "Joey", "Ralph", "Liz", "Wade", "Anthony", "Bike Shop", "Kenji", "Whitney", "Falkner", "Jack",
        "Chad", "Brent", "Todd", "Arnie", "Baoba", "Irwin", "Janine", "Clair", "Erika", "Misty",
        "Blaine", "Blue", "Chuck", "Brock", "Bugsy", "Sabrina", "Lt. Surge", "Morty", "Jasmine", "Pryce",
        "Huey", "Gaven", "Jamie", "Reena", "Vance", "Parry", "Erin", "Beverly", "Jose", "Gina",
        "Alan", "Dana", "Derek", "Tully", "Tiffany", "Wilton", "Krise", "Ian", "Walt", "Alfred",
        "Doug", "Rob", "Kyle", "Kyler", "Tim & Sue", "Kenny", "Tanner", "Josh", "Torin", "Hillary",
        "Billy", "Kay & Tia", "Reese", "Aiden", "Ernest",
    )

    fun contactName(id: Int): String = CONTACT_NAMES.getOrNull(id) ?: "contact $id"

    // --- Professor Oak's intro (OakSpeechData, include/oaks_speech_internal.h) ---
    const val OAK_STATE = 0x0CL
    const val OAK_PLAYER_GENDER = 0x134L
    const val OAK_MENU = 0x160L          // {unk, numOptions, inPadMode, cursorPos, flashDelay, flashFramesPer, pressDelay, flashState}
    const val OAK_MENU_PAD_MODE = 0x02L
    const val OAK_MENU_CURSOR = 0x03L
    const val OAK_MENU_PRESS_DELAY = 0x06L
    const val OAK_YES_NO = 0x178L        // OakSpeechYesNo *
    const val OAK_YES_NO_STATE = 0x1BL   // low nibble state (0 input), high nibble result (1 YES, 2 NO)
    const val OAK_STATE_INFO_MENU = 3
    const val OAK_STATE_UNDERSTOOD = 24
    const val OAK_STATE_GENDER = 65
    const val OAK_STATE_CONFIRM_GENDER = 69
    const val OAK_STATE_CONFIRM_NAME = 98
    val OAK_MENU_STATES = setOf(OAK_STATE_INFO_MENU, OAK_STATE_GENDER, OAK_STATE_CONFIRM_GENDER, OAK_STATE_CONFIRM_NAME)
    const val OAK_PRINT_DIALOG_STATE = 0x104L       // printDialogMsgState
    const val OAK_TEXT_PRINTER = 0x10CL             // u32 textPrinter (its id)
    const val OAK_PRINT_FULL_SCREEN_STATE = 0x108L  // printAndFadeFullScreenTextState
    const val OAK_DIALOG_PRINTING = 1
    const val OAK_DIALOG_WAIT_BUTTON = 2
    const val OAK_FULL_SCREEN_WAIT_BUTTON = 3
    val OAK_INFO_LABELS = listOf("CONTROL INFO", "ADVENTURE INFO", "NO INFO NEEDED")
    val OAK_GENDER_LABELS = listOf("Boy", "Girl")
    /** `ov53_021E8650` and `sTouchscreenHitboxes_GenderSelect` (src/oaks_speech.c). */
    val OAK_INFO_RECTS = listOf(Rect(20, 50, 50, 213), Rect(76, 106, 50, 213), Rect(132, 162, 50, 213))
    val OAK_CONFIRM_RECTS_BOY = listOf(Rect(26, 83, 138, 253), Rect(108, 164, 138, 253))
    val OAK_CONFIRM_RECTS_GIRL = listOf(Rect(26, 83, 10, 125), Rect(108, 164, 10, 125))
    val OAK_GENDER_RECTS = listOf(Rect(25, 173, 18, 111), Rect(25, 173, 144, 239))

    // --- Overlay 27 touch multichoice ---
    /** `ov27_0225D480[count - 2][option]` = {up, down, left, right}; -1 or the option itself = no move. */
    val TOUCH_MENU_NAVIGATION: List<List<IntArray>> = listOf(
        listOf(intArrayOf(0, 1, -1, -1), intArrayOf(0, 1, -1, -1)),
        listOf(intArrayOf(0, 1, -1, -1), intArrayOf(0, 2, -1, -1), intArrayOf(1, 2, -1, -1)),
        listOf(intArrayOf(0, 1, -1, -1), intArrayOf(0, 2, -1, -1), intArrayOf(1, 3, -1, -1), intArrayOf(2, 3, -1, -1)),
        listOf(intArrayOf(0, 2, 0, 1), intArrayOf(1, 3, 0, 1), intArrayOf(0, 4, 2, 3), intArrayOf(1, 4, 2, 3), intArrayOf(3, 4, -1, -1)),
        listOf(
            intArrayOf(0, 2, 0, 1), intArrayOf(0, 3, 0, 1), intArrayOf(0, 4, 2, 3),
            intArrayOf(1, 5, 2, 3), intArrayOf(2, 4, 4, 5), intArrayOf(3, 5, 4, 5),
        ),
        listOf(
            intArrayOf(0, 2, 0, 1), intArrayOf(1, 3, 0, 1), intArrayOf(0, 4, 2, 3), intArrayOf(1, 5, 2, 3),
            intArrayOf(2, 6, 4, 5), intArrayOf(3, 6, 4, 5), intArrayOf(5, 6, -1, -1),
        ),
        listOf(
            intArrayOf(0, 2, 0, 1), intArrayOf(0, 3, 0, 1), intArrayOf(0, 4, 2, 3), intArrayOf(1, 5, 2, 3),
            intArrayOf(2, 6, 4, 5), intArrayOf(3, 7, 4, 5), intArrayOf(4, 6, 6, 7), intArrayOf(5, 7, 6, 7),
        ),
    )

    /** A touch rectangle {top, bottom, left, right} of the bottom screen. */
    data class Rect(val top: Int, val bottom: Int, val left: Int, val right: Int) {
        val center get() = TouchPoint((left + right) / 2, (top + bottom) / 2)
    }

    /** `ov27_0225D49C[count - 2][option]`: the touch rectangles of the multichoice options. */
    val TOUCH_MENU_RECTS: List<List<Rect>> = run {
        val l = 3 to 123
        val r = 131 to 252
        val w = 3 to 251
        fun row(top: Int, bottom: Int, x: Pair<Int, Int>) = Rect(top, bottom, x.first, x.second)
        listOf(
            listOf(row(50, 92, w), row(99, 140, w)),
            listOf(row(27, 68, w), row(74, 115, w), row(123, 164, w)),
            listOf(row(2, 43, w), row(52, 92, w), row(99, 140, w), row(148, 188, w)),
            listOf(row(25, 68, l), row(25, 68, r), row(75, 115, l), row(75, 115, r), row(123, 163, r)),
            listOf(row(25, 68, l), row(25, 68, r), row(75, 115, l), row(75, 115, r), row(123, 163, l), row(123, 163, r)),
            listOf(
                row(3, 44, l), row(3, 44, r), row(51, 91, l), row(51, 91, r),
                row(100, 139, l), row(100, 139, r), row(147, 188, r),
            ),
            listOf(
                row(3, 44, l), row(3, 44, r), row(51, 91, l), row(51, 91, r),
                row(100, 139, l), row(100, 139, r), row(147, 188, l), row(147, 188, r),
            ),
        )
    }

    /** Builds the topology of one navigation table. */
    fun topology(links: List<IntArray>): Topology = Topology { from, button ->
        val direction = when (button) {
            Button.UP -> 0
            Button.DOWN -> 1
            Button.LEFT -> 2
            Button.RIGHT -> 3
            else -> return@Topology null
        }
        links.getOrNull(from)?.get(direction)?.takeIf { it >= 0 && it != from }
    }
}
