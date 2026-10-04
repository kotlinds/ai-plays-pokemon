package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.MoveOffer
import dev.kotlinds.pokemonclient.state.LearnQuestion
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.hgss.HgssPartyBagAddresses as P

/**
 * The field party menu (START → POKéMON, and the "use / give / teach on which Pokémon?" grids opened from the bag)
 * and the field bag (START → BAG), decoded from their application data (spec: design/screens/party-bag.md).
 *
 * Both are sub-applications of the field: `FieldSystem → unk0 → unk4` is their `OverlayManager`. Their screens are
 * only decoded while the app runs its main loop (`OverlayManager.exec state == 2`), because the structures are half
 * built while it opens or closes. Every screen here was checked live on HeartGold (US) with single taps.
 */
internal object HgssPartyBagScreens : HgssScreenDecoder {

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (state.mode != GameMode.APP) return null
        val app = when (state.modeDetail) {
            APP_PARTY_MENU, APP_BAG -> subApp(mem) ?: return null
            else -> return null
        }
        if (mem.s32(app + A.OM_EXEC_STATE) != OM_EXEC_MAIN) return null
        return when (state.modeDetail) {
            APP_PARTY_MENU -> HgssPartyMenuScreen.decode(mem, state, app)
            else -> HgssBagScreen.decode(mem, state, app)
        }
    }

    /** The `OverlayManager` of the application launched from the field (`FieldSystem.unk0->unk4`). */
    private fun subApp(mem: HgssMemory): Long? {
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        val sub0 = mem.ptr(fs + A.FS_SUB0) ?: return null
        return mem.ptr(sub0 + A.FSS0_SUB_APP)
    }

    private const val APP_PARTY_MENU = "party_menu"
    private const val APP_BAG = "bag"

    /** `OverlayManager_Run` exec state of an app running its main function (src/overlay_manager.c). */
    private const val OM_EXEC_MAIN = 2
}

// =====================================================================================================================
// Party menu
// =====================================================================================================================

/** The party menu app (src/party_menu.c, `PartyMenu` include/party_menu.h:313). */
internal object HgssPartyMenuScreen {

    fun decode(mem: HgssMemory, state: HgssState, app: Long): Screen? {
        val pm = mem.ptr(app + A.OM_DATA) ?: return null
        val args = mem.ptr(pm + P.PM_ARGS) ?: return null
        val context = PartyContext.of(mem.u8(args + P.ARGS_CONTEXT))
        val procState = mem.s32(app + A.OM_PROC_STATE)
        // A pressed / touched button plays its animation first: input is ignored meanwhile (party_menu.c:1504).
        if (mem.u32(pm + P.PM_BUTTON_ANIM_ACTIVE) != 0L) return Screen.Animation(AnimationKind.TRANSITION)
        return when (procState) {
            P.STATE_MAIN, P.STATE_USE_ITEM_SELECT_MON, P.STATE_GIVE_ITEM_SELECT_MON, P.STATE_USE_TMHM,
            P.STATE_SELECT_SWITCH_MON, P.STATE_SOFTBOILED -> grid(mem, state, pm, context, procState)

            P.STATE_CONTEXT_MENU -> contextMenu(mem, state, pm, submenu = false)
            P.STATE_SUBCONTEXT_MENU -> contextMenu(mem, state, pm, submenu = true)
            P.STATE_YES_NO, P.STATE_YESNO_SWITCH_ITEMS -> yesNo(mem, state, pm)
            P.STATE_SELECT_MOVE -> selectMove(mem, state, pm)
            // An item's effect (HP bar, then "X's HP was restored..."): the message is shown while its printer runs; A
            // ends it at once (else the menu waits for the text's own delay before closing, ~2-3 s).
            P.STATE_ITEM_USE_CB -> levelUpPanel(mem, state, pm)
                ?: if (HgssScreenMemory.textPrinting(mem)) message(mem, pm) else Screen.Animation(AnimationKind.TRANSITION)
            in P.MESSAGE_STATES -> message(mem, pm)
            // Sacred Ash revives every fainted Pokémon in turn (PartyMenu_Subtask_SacredAsh): an HP bar filling one
            // point a frame, then "X regained health." waiting for A, then the next one.
            P.STATE_SACRED_ASH -> if (mem.u8(pm + P.PM_AFTER_TEXT_PRINTER_STATE) == P.SACRED_ASH_STEP_MESSAGE) message(mem, pm)
                else Screen.Animation(AnimationKind.TRANSITION)
            else -> Screen.Animation(AnimationKind.TRANSITION)
        }
    }

    /** What a party menu was opened for: `PartyMenuArgs.context` (include/party_menu.h:75). */
    enum class PartyContext(val raw: Int, val purpose: PartyPurpose) {
        /** START → POKéMON (launch_application.c:252), also after the Fly map is cancelled. */
        NORMAL(0, PartyPurpose.FIELD),

        /** Bag USE on a medicine, a vitamin... (field_use_item.c:237). */
        USE_ITEM(5, PartyPurpose.USE_ITEM),

        /** Bag USE on a TM / HM (field_use_item.c:359). */
        TM_HM(6, PartyPurpose.TEACH),

        /** Bag GIVE (start_menu.c:1046). */
        GIVE_ITEM(9, PartyPurpose.GIVE_ITEM),

        /** Bag USE on an evolution stone (field_use_item.c:550). */
        EVO_STONE(16, PartyPurpose.USE_ITEM),

        /** Scripts (daycare, trades, mailbox...) and the automatic "give" after ITEM → GIVE (context 10). */
        OTHER(-1, PartyPurpose.OTHER);

        companion object {
            fun of(raw: Int) = entries.firstOrNull { it.raw == raw } ?: OTHER
        }
    }

    /**
     * The stats panel after a Rare Candy (`PartyMenu_ItemUseFunc_LevelUpLearnMovesLoop`, src/party_menu_items.c:607):
     * step 1 shows the gains, step 2 the new totals; both wait for A / B (or a touch). Null otherwise. The stats
     * before the level are no longer in RAM by then (the game overwrites `levelUpStatsTmp` with the new ones for the
     * totals panel), so the text gives the new stats on both panels.
     */
    private fun levelUpPanel(mem: HgssMemory, state: HgssState, pm: Long): Screen? {
        if (mem.u32(pm + P.PM_ITEM_USE_CALLBACK) and 1L.inv() != P.FN_LEVEL_UP_LEARN_MOVES_LOOP) return null
        val step = mem.u8(pm + P.PM_LEVEL_UP_LOOP_STATE)
        if (step != 1 && step != 2) return null
        val mon = state.party.firstOrNull { it.slot == mem.u8(pm + P.PM_PARTY_MON_INDEX) } ?: return null
        val now = listOf(mon.maxHp) + listOf("atk", "def", "spAtk", "spDef", "speed").map { mon.stats[it] ?: 0 }
        val names = listOf("Max HP", "Attack", "Defense", "Sp. Atk", "Sp. Def", "Speed")
        val panel = if (step == 1) "gains panel" else "totals panel"
        val stats = names.indices.joinToString(", ") { i -> "${names[i]} ${now[i]}" }
        return Screen.PressToContinue(ContinueReason.LEVEL_UP_STATS, "${mon.nickname ?: mon.speciesName} grew to Lv${mon.level} ($panel): $stats")
    }

    private fun grid(mem: HgssMemory, state: HgssState, pm: Long, context: PartyContext, procState: Int): Screen {
        val active = BooleanArray(PARTY_SIZE) { mem.u8(pm + P.PM_MONS_DRAW_STATE + P.MDS_SIZE * it + P.MDS_ACTIVE) != 0 }
        // Eggs can't receive an item or learn a TM: A on them only plays the error sound (party_menu.c:2355-2385).
        val eggsRefused = context.purpose in setOf(PartyPurpose.USE_ITEM, PartyPurpose.GIVE_ITEM, PartyPurpose.TEACH)
        // SWITCH ("Move to where?"): the Pokémon being moved is `softboiledDonorSlot` (bits 0-5 of +0xC63) while the
        // second cursor flag (bit 6) is set (party_menu_list_items.c:327-341).
        val flags = mem.u8(pm + P.PM_FLAGS_C63)
        val movingSlot = (flags and P.DONOR_SLOT_MASK).takeIf { procState == P.STATE_SELECT_SWITCH_MON && flags and P.FLAG_SECOND_CURSOR != 0 }
        val entries = (0 until PARTY_SIZE).map { slot ->
            val mon = state.party.firstOrNull { it.slot == slot }?.takeIf { active[slot] }
            when {
                mon == null -> Entry("slot:$slot", "-", selectable = false)
                else -> Entry(
                    MonId(mon.personality, mon.otId).toString(),
                    if (mon.isEgg) "EGG" else "${mon.nickname ?: mon.speciesName} Lv${mon.level} ${mon.hp}/${mon.maxHp}",
                    selectable = !(mon.isEgg && eggsRefused),
                )
            }
        } + Entry(ID_CANCEL, "CANCEL", selectable = mem.u8(pm + P.PM_FLAGS_C63) and P.FLAG_CANCEL_DISABLED == 0)
        val raw = mem.u8(pm + P.PM_PARTY_MON_INDEX)
        val cursor = when (raw) {
            in 0 until PARTY_SIZE -> Cursor.At(raw)
            P.SELECTION_CANCEL_BUTTON -> Cursor.At(CANCEL_INDEX)
            else -> Cursor.Hidden
        }
        val purpose = when (procState) {
            // "Move to where?" and Softboiled / Milk Drink targets are field grids with another meaning.
            P.STATE_SOFTBOILED -> PartyPurpose.OTHER
            else -> if (movingSlot != null) PartyPurpose.SWITCH else context.purpose
        }
        return Screen.PartyGrid(
            purpose = purpose,
            moving = movingSlot?.let { slot -> state.party.firstOrNull { it.slot == slot } }?.let { MonId(it.personality, it.otId) },
            entries = entries,
            cursor = cursor,
            topology = gridTopology(active, openedOnRightColumn = mem.u8(pm + P.PM_OPENED_ON_SLOT) and 1 == 1),
            cancel = if (mem.u8(pm + P.PM_FLAGS_C63) and P.FLAG_CANCEL_DISABLED != 0) CancelBehavior.NONE else CancelBehavior.CLOSES,
        )
    }

    /**
     * The grid's D-pad moves (entry index = party slot, [CANCEL_INDEX] = the CANCEL button), from the field
     * `DpadMenuBox` table `_0210140C` (party_menu.c:160) walked like `PartyMenu_GetSelectionInDirection`
     * (party_menu.c:1380): an empty slot is skipped by going on in the same direction until a Pokémon or CANCEL.
     * From CANCEL, UP / DOWN pick the first Pokémon of a fixed order that depends on the column the menu was opened
     * on ([openedOnRightColumn], `unk_C66 & 1`, party_menu.c:1367-1369); LEFT / RIGHT walk the table.
     */
    fun gridTopology(active: BooleanArray, openedOnRightColumn: Boolean): Topology {
        fun isActive(slot: Int) = slot in 0 until PARTY_SIZE && active[slot]
        fun walk(from: Int, button: Button): Int {
            var current = from
            repeat(P.GRID_NEIGHBORS.size) {
                current = P.GRID_NEIGHBORS.getValue(current).getValue(button)
                if (current == P.SELECTION_CANCEL_BUTTON || isActive(current)) return current
            }
            return current
        }
        fun firstActive(order: List<Int>) = order.firstOrNull(::isActive) ?: 0
        val column = if (openedOnRightColumn) 1 else 0
        return Topology { from, button ->
            if (!button.isDirection) return@Topology null
            val raw = if (from == CANCEL_INDEX) P.SELECTION_CANCEL_BUTTON else from
            if (raw != P.SELECTION_CANCEL_BUTTON && !isActive(raw)) return@Topology null
            val target = when {
                raw == P.SELECTION_CANCEL_BUTTON && button == Button.UP -> firstActive(P.CANCEL_UP_ORDER[column])
                raw == P.SELECTION_CANCEL_BUTTON && button == Button.DOWN -> firstActive(P.CANCEL_DOWN_ORDER[column])
                else -> walk(raw, button)
            }
            val index = if (target == P.SELECTION_CANCEL_BUTTON) CANCEL_INDEX else target
            index.takeIf { it != from }
        }
    }

    /**
     * The context menu of a Pokémon (proc state 2) or its ITEM / MAIL submenu (proc state 15). Both use the heap
     * cursor `PartyMenu.contextMenuCursor` (+0x824, party_menu.h:181), only valid in those two states (freed and
     * left dangling otherwise).
     */
    private fun contextMenu(mem: HgssMemory, state: HgssState, pm: Long, submenu: Boolean): Screen? {
        val cursor = mem.ptr(pm + P.PM_CONTEXT_MENU_CURSOR, align = 1) ?: return null
        val count = mem.u8(cursor + P.CMC_NUM_ITEMS)
        if (count !in 2..P.CONTEXT_MENU_MAX) return null
        val items = mem.ptr(cursor + P.CMC_ITEMS) ?: return null
        val functions = P.ContextMenuFunctions.forVersion(mem.version)
        val entries = (0 until count).map { i ->
            val label = mem.gameString(mem.ptr(items + P.LIST_ITEM_SIZE * i + P.LIST_ITEM_TEXT)).orEmpty()
            val value = mem.u32(items + P.LIST_ITEM_SIZE * i + P.LIST_ITEM_VALUE)
            val id = if (value == P.LIST_CANCEL) ID_QUIT else functions?.idOf(value and A.THUMB_MASK) ?: functionId(value and A.THUMB_MASK)
            Entry(id, label)
        }
        val slot = mem.u8(pm + P.PM_PARTY_MON_INDEX)
        val owner = state.party.firstOrNull { it.slot == slot }?.let { MonId(it.personality, it.otId) }
        val selection = mem.u8(cursor + P.CMC_SELECTION)
        return Screen.ContextMenu(
            owner = owner,
            entries = entries,
            cursor = if (selection < count) Cursor.At(selection) else Cursor.Hidden,
            topology = if (submenu) submenuTopology(count) else contextMenuTopology(count),
            // B moves the cursor onto QUIT and closes the menu (party_context_menu.c:1222, :1276).
            cancel = CancelBehavior.CLOSES,
        )
    }

    /**
     * Context menu D-pad (`sDpadNavParam_PartyMenu[n-2][selection] = {UP, DOWN, LEFT/RIGHT}`, party_context_menu.c:217,
     * read by `handlePartyMenuTopLevelDpadInput` :1150): SUMMARY / SWITCH / ITEM and QUIT form a vertical ring in the
     * right column, the field moves (index 4+) a second column on the left; LEFT and RIGHT both cross columns.
     */
    fun contextMenuTopology(count: Int) = Topology { from, button ->
        val row = P.CONTEXT_MENU_NAV.getOrNull(count - 2)?.getOrNull(from) ?: return@Topology null
        val target = when (button) {
            Button.UP -> row[0]
            Button.DOWN -> row[1]
            Button.LEFT, Button.RIGHT -> row[2]
            else -> -1
        }
        target.takeIf { it in 0 until count && it != from }
    }

    /** ITEM / MAIL submenu: a vertical ring, LEFT / RIGHT ignored (`sDpadNavParam_ContextMenu`, party_context_menu.c:183). */
    fun submenuTopology(count: Int) = Topology.vertical(count, wrap = true)

    /**
     * "Restore which move?" / "Boost which move?" (PP restoring items, PP Up: `PartyMenu_SelectMoveForPpRestoreOrPpUp`,
     * party_menu_items.c:927): a list of the Pokémon's moves (list value = move slot) then QUIT, on the context menu
     * cursor, read with the submenu input (a vertical ring; B = QUIT). Entries are `move:<id>` (+ `option:cancel`).
     */
    private fun selectMove(mem: HgssMemory, state: HgssState, pm: Long): Screen? {
        val cursor = mem.ptr(pm + P.PM_CONTEXT_MENU_CURSOR, align = 1) ?: return null
        val count = mem.u8(cursor + P.CMC_NUM_ITEMS)
        if (count !in 2..5) return null
        val items = mem.ptr(cursor + P.CMC_ITEMS) ?: return null
        val slot = mem.u8(pm + P.PM_PARTY_MON_INDEX)
        val mon = state.party.firstOrNull { it.slot == slot } ?: return null
        val entries = (0 until count).map { i ->
            val label = mem.gameString(mem.ptr(items + P.LIST_ITEM_SIZE * i + P.LIST_ITEM_TEXT)).orEmpty().trim()
            val value = mem.u32(items + P.LIST_ITEM_SIZE * i + P.LIST_ITEM_VALUE)
            val move = mon.moves.getOrNull(value.toInt()).takeIf { value in 0L..3L }
            if (move != null) Entry("move:${move.id}", "${move.name} (${move.pp}/${move.maxPp} PP)") else Entry(ID_CANCEL, label.ifEmpty { "QUIT" })
        }
        val selection = mem.u8(cursor + P.CMC_SELECTION)
        return Screen.ListMenu(
            MenuKind.OTHER, entries,
            if (selection < count) Cursor.At(selection) else Cursor.Hidden,
            submenuTopology(count), CancelBehavior.CLOSES,
        )
    }

    /** "Switch items?", "Send the removed Mail to your PC?": a `YesNoPrompt` (include/yes_no_prompt.h). */
    private fun yesNo(mem: HgssMemory, state: HgssState, pm: Long): Screen? {
        val screen = yesNoPrompt(mem, mem.ptr(pm + P.PM_YES_NO_PROMPT), mem.gameString(mem.ptr(pm + P.PM_FORMATTED_STR_BUF))?.trimEnd())
        // The learn-move questions after a Rare Candy (party_menu_items.c:640-750): which one it is comes from the
        // callback YES leads to, the move from PartyMenuArgs.moveId.
        val question = when (mem.u32(pm + P.PM_YES_CALLBACK) and 1L.inv()) {
            P.FN_LEVEL_UP_PROMPT_FORGET_MOVE -> LearnQuestion.FORGET_A_MOVE
            P.FN_LEVEL_UP_DID_NOT_LEARN_MOVE -> LearnQuestion.GIVE_UP
            else -> return screen
        }
        val yesNo = screen as? Screen.YesNo ?: return screen
        val move = mem.ptr(pm + P.PM_ARGS)?.let { mem.u16(it + P.ARGS_MOVE_ID) }?.takeIf { it != 0 } ?: return screen
        val mon = state.party.firstOrNull { it.slot == mem.u8(pm + P.PM_PARTY_MON_INDEX) }
        return yesNo.copy(learning = MoveOffer(
            mon?.let { MonId(it.personality, it.otId) }, mon?.let { it.nickname ?: it.speciesName },
            Named(MoveId(move), HgssData.moveName(move)), question,
        ))
    }

    /** A message printed in the party menu's message box (take item, field move refused, item used...). */
    private fun message(mem: HgssMemory, pm: Long): Screen {
        val string = mem.ptr(pm + P.PM_FORMATTED_STR_BUF)
        val text = mem.gameString(string).orEmpty().trimEnd()
        // While its printer prints (or waits for the heal sound effect), A does nothing: only a printer waiting for a
        // key, or gone, waits for A (verified live: A ~20 frames after "X's HP was restored" is ignored).
        val printed = string?.let { HgssTextPrinter.read(mem, it, mem.u8(pm + P.PM_TEXT_PRINTER_ID)) }
        val awaiting = if (printed != null && printed.printerAlive) printed.awaiting else Awaiting.INPUT
        return Screen.Dialogue(TextSource.MENU, speaker = null, text = text, awaiting = awaiting)
    }

    /**
     * Fallback id for a context menu function missing from the address table (another ROM version): still derived
     * from what the entry does (its function address), never from its label, so it is language-independent.
     */
    private fun functionId(function: Long) = "action:" + function.toString(16)

    const val PARTY_SIZE = 6

    /** Entry index of the CANCEL button (after the six slots). */
    const val CANCEL_INDEX = PARTY_SIZE

    const val ID_CANCEL = "option:cancel"
    const val ID_QUIT = "option:quit"
}

// =====================================================================================================================
// Bag
// =====================================================================================================================

/** The bag app (overlay 15, asm only: asm/overlay_15.s). */
internal object HgssBagScreen {

    /** The pockets, indexed by pocket id = tab position (sAllPockets, launch_application.c:184). */
    enum class Pocket(val id: String, val label: String) {
        ITEMS("items", "ITEMS"),
        MEDICINE("medicine", "MEDICINE"),
        BALLS("balls", "POKé BALLS"),
        TMS_HMS("tms_hms", "TMs & HMs"),
        BERRIES("berries", "BERRIES"),
        MAIL("mail", "MAIL"),
        BATTLE_ITEMS("battle_items", "BATTLE ITEMS"),
        KEY_ITEMS("key_items", "KEY ITEMS"),
    }

    fun decode(mem: HgssMemory, state: HgssState, app: Long): Screen? {
        val work = mem.ptr(app + A.OM_DATA) ?: return null
        val view = mem.ptr(work + P.BAG_VIEW) ?: return null
        return when (mem.s32(app + A.OM_PROC_STATE)) {
            in P.BAG_MAIN_STATES -> main(mem, work, view)
            P.BAG_STATE_ACTION_MENU -> actionMenu(mem, work, view)
            P.BAG_STATE_MESSAGE -> message(mem, work)
            P.BAG_STATE_TM_MESSAGE -> when (mem.u8(work + P.BAG_TM_STEP)) {
                P.BAG_TM_STEP_YES_NO -> yesNoPrompt(mem, mem.ptr(work + P.BAG_YES_NO_PROMPT), bagText(mem, work))
                // Step 0 hasn't written "Booted up a TM." yet: the buffer still holds the TM's power ("100", "---").
                P.BAG_TM_STEP_START -> Screen.Animation(AnimationKind.TRANSITION)
                else -> message(mem, work)
            }
            in P.BAG_BUSY_STATES -> Screen.Animation(AnimationKind.TRANSITION)
            // Selling at a Poké Mart (the bag opened by SELL): "How many?", the number, "I can pay ₽X. OK?", "Turned over...".
            P.BAG_STATE_SELL_ASK_HOW_MANY, P.BAG_STATE_SELL_DONE_PRINTING ->
                Screen.Dialogue(TextSource.MENU, speaker = null, text = bagText(mem, work).orEmpty(), awaiting = Awaiting.TEXT_PRINTING)
            P.BAG_STATE_SELL_QUANTITY -> Screen.Quantity(mem.u16(work + P.BAG_SELL_QUANTITY), 1, mem.u16(work + P.BAG_SELL_MAX).coerceAtLeast(1))
            P.BAG_STATE_SELL_YES_NO -> yesNoPrompt(mem, mem.ptr(work + P.BAG_YES_NO_PROMPT), bagText(mem, work))
                ?: Screen.Dialogue(TextSource.MENU, speaker = null, text = bagText(mem, work).orEmpty(), awaiting = Awaiting.TEXT_PRINTING)
            P.BAG_STATE_SELL_DONE, P.BAG_STATE_SELL_MESSAGE -> message(mem, work)
            in P.BAG_SELL_BUSY_STATES -> Screen.Animation(AnimationKind.TRANSITION)
            else -> null
        }
    }

    /**
     * The bag's message box (state 12: "can't use that here"...; state 13 steps 0-2: "Booted up a TM." then
     * "It contained X. Teach X to a Pokémon?"), text in `String *work+0x5E4`. It waits for A / B or a touch
     * once printed (ov15_021FB700, ov15_021FB830).
     */
    private fun message(mem: HgssMemory, work: Long): Screen =
        Screen.Dialogue(TextSource.MENU, speaker = null, text = bagText(mem, work).orEmpty(), awaiting = Awaiting.INPUT)

    private fun bagText(mem: HgssMemory, work: Long) = mem.gameString(mem.ptr(work + P.BAG_MESSAGE))?.trimEnd()

    private fun pocketBase(view: Long, index: Int) = view + P.BV_POCKETS + P.BV_POCKET_SIZE * index

    /**
     * The main screen: entry index = the raw cursor `work+0x644` (0-7 pocket tabs, 8-13 the six item buttons of the
     * page, 14 / 15 the page arrows (touch only), 16 CANCEL).
     */
    private fun main(mem: HgssMemory, work: Long, view: Long): Screen? {
        val current = mem.u8(view + P.BV_CURRENT_POCKET)
        if (current !in Pocket.entries.indices) return null
        val base = pocketBase(view, current)
        val slots = mem.ptr(base + P.BVP_SLOTS)
        val count = mem.u8(base + P.BVP_COUNT)
        val pageStart = mem.s16(base + P.BVP_PAGE_START).coerceAtLeast(0)
        val pages = ((count + P.ITEMS_PER_PAGE - 1) / P.ITEMS_PER_PAGE).coerceAtLeast(1)
        val available = BooleanArray(Pocket.entries.size) { tab -> tabAvailable(mem, view, tab) }
        val tabs = Pocket.entries.mapIndexed { tab, pocket -> Entry("pocket:${pocket.id}", pocket.label, selectable = available[tab]) }
        val items = (0 until P.ITEMS_PER_PAGE).map { k ->
            val index = pageStart + k
            val id = if (slots != null && index < count) mem.u16(slots + P.ITEM_SLOT_SIZE * index) else 0
            if (id == 0) Entry("slot:$k", "-", selectable = false)
            else Entry("item:$id", "${HgssData.itemName(id)} x${mem.u16(slots!! + P.ITEM_SLOT_SIZE * index + 2)}")
        }
        val multiPage = count > P.ITEMS_PER_PAGE
        val arrows = listOf(
            Entry(ID_PAGE_PREV, "◀", selectable = multiPage, touch = TouchPoint(20, 180)),
            Entry(ID_PAGE_NEXT, "▶", selectable = multiPage, touch = TouchPoint(60, 180)),
        )
        val raw = mem.s32(work + P.BAG_CURSOR)
        val entries = tabs + items + arrows + Entry(ID_CANCEL, "CANCEL")
        return Screen.Bag(
            pocket = Pocket.entries[current].id,
            pockets = Pocket.entries.filterIndexed { tab, _ -> available[tab] }.map { it.id },
            page = pageStart / P.ITEMS_PER_PAGE,
            pages = pages,
            inBattle = false,
            entries = entries,
            cursor = if (raw in entries.indices) Cursor.At(raw) else Cursor.Hidden,
            topology = mainTopology(current, available),
            cancel = CancelBehavior.CLOSES,
        )
    }

    /** A tab can be chosen when some pocket of the view has that id and item slots (ov15_021FA68C). */
    private fun tabAvailable(mem: HgssMemory, view: Long, tab: Int): Boolean =
        Pocket.entries.indices.any { i ->
            val base = pocketBase(view, i)
            mem.u8(base + P.BVP_POCKET_ID) == tab && mem.u32(base + P.BVP_SLOTS) != 0L
        }

    /**
     * D-pad of the main screen, table ov15_02200640 {UP, DOWN, LEFT, RIGHT} read by ov15_021FA1BC:
     * - target 0x11 = the tab of the open pocket;
     * - on the tabs, LEFT / RIGHT go to the previous / next available tab with wrap (ov15_021FA6C0), only moving the
     *   cursor (A opens the pocket);
     * - LEFT from the left item column / RIGHT from the right one turn the page (ov15_021FA578) and the cursor
     *   stays: no move here (the page arrows are touch entries, the [Screen.Bag.page] tells the page);
     * - L / R open the previous / next pocket at once; the cursor follows only when it is on the tabs.
     */
    fun mainTopology(currentPocket: Int, available: BooleanArray): Topology {
        fun nextTab(from: Int, step: Int): Int {
            var tab = from
            repeat(available.size) {
                tab = (tab + step + available.size) % available.size
                if (available[tab]) return tab
            }
            return tab
        }
        return Topology { from, button ->
            if (from !in 0..P.BAG_CURSOR_CANCEL) return@Topology null
            val onTabs = from < P.BAG_TAB_COUNT
            val target = when (button) {
                Button.UP, Button.DOWN -> P.BAG_NAV[from][if (button == Button.UP) 0 else 1]
                Button.LEFT, Button.RIGHT -> when {
                    from == P.BAG_CURSOR_CANCEL -> null
                    onTabs -> nextTab(from, if (button == Button.LEFT) -1 else 1)
                    else -> P.BAG_NAV[from][if (button == Button.LEFT) 2 else 3].takeIf { it != P.BAG_PAGE_PREV && it != P.BAG_PAGE_NEXT }
                }
                Button.L, Button.R -> if (onTabs) nextTab(currentPocket, if (button == Button.L) -1 else 1) else null
                else -> null
            }
            val resolved = if (target == P.BAG_TARGET_CURRENT_TAB) currentPocket else target
            resolved?.takeIf { it != from }
        }
    }

    /**
     * The item action menu (proc state 4): five fixed slots built by ov15_021FB14C — 0 USE (or WALK / CHECK...),
     * 1 TRASH or REGISTER / DESELECT, 2 GIVE, 3 MOVE, 4 CANCEL. A slot without a handler (`work+0x7F0+4*slot == 0`)
     * is drawn empty and A does nothing there (ov15_021FB3F0); the cursor can still stop on it.
     */
    private fun actionMenu(mem: HgssMemory, work: Long, view: Long): Screen {
        val itemId = mem.u16(view + P.BV_SELECTED_ITEM)
        val pocket = mem.u8(pocketBase(view, mem.u8(view + P.BV_CURRENT_POCKET)) + P.BVP_POCKET_ID)
        val entries = (0 until P.ACTION_SLOTS).map { slot ->
            if (slot == P.ACTION_SLOT_CANCEL) return@map Entry(ID_CANCEL, label(mem, work, P.ACTION_ID_CANCEL, "CANCEL"))
            val action = BagAction.of(mem.fn(work + P.BAG_ACTION_HANDLERS + 4L * slot))
            if (action == null) return@map Entry("slot:$slot", "", selectable = false)
            val actionId = when (action) {
                // The USE slot shows WALK on the Bicycle while cycling and CHECK in the Mail pocket (ov15_021FB14C).
                BagAction.USE -> when {
                    pocket == Pocket.MAIL.ordinal -> P.ACTION_ID_CHECK
                    itemId == P.ITEM_BICYCLE && mem.u16(view + P.BV_FLAGS_76) and 1 == 1 -> P.ACTION_ID_WALK
                    else -> P.ACTION_ID_USE
                }
                else -> action.actionId
            }
            Entry(action.id, label(mem, work, actionId, action.fallbackLabel))
        }
        val raw = mem.s32(work + P.BAG_ACTION_CURSOR)
        return Screen.ContextMenu(
            owner = null,
            item = itemId.takeIf { it > 0 }?.let(::ItemId),
            entries = entries,
            cursor = if (raw in entries.indices) Cursor.At(raw) else Cursor.Hidden,
            topology = actionTopology,
            cancel = CancelBehavior.CLOSES,
        )
    }

    /** Label of action [actionId], read from the bag's own strings (`work+0x300+4*id`, msg_0010). */
    private fun label(mem: HgssMemory, work: Long, actionId: Int, fallback: String): String =
        mem.gameString(mem.ptr(work + P.BAG_ACTION_LABELS + 4L * actionId))?.takeIf { it.isNotBlank() } ?: fallback

    /** Action menu D-pad, table ov15_02200528 {UP, DOWN, LEFT, RIGHT} (no auto-repeat: newKeys). */
    val actionTopology = Topology { from, button ->
        val row = P.ACTION_NAV.getOrNull(from) ?: return@Topology null
        val target = when (button) {
            Button.UP -> row[0]
            Button.DOWN -> row[1]
            Button.LEFT -> row[2]
            Button.RIGHT -> row[3]
            else -> return@Topology null
        }
        target.takeIf { it != from }
    }

    /** The bag actions by handler (table ov15_02201368, overlay 15 addresses). */
    enum class BagAction(val handler: Long, val actionId: Int, val id: String, val fallbackLabel: String) {
        USE(0x021FB680L, P.ACTION_ID_USE, "option:use", "USE"),
        TRASH(0x021FBCACL, 5, "option:trash", "TRASH"),
        REGISTER(0x021FC224L, 6, "option:register", "REGISTER"),
        DESELECT(0x021FC37CL, 7, "option:deselect", "DESELECT"),
        GIVE(0x021FC3ECL, 8, "option:give", "GIVE"),
        MOVE(0x021FC3E0L, 12, "option:move", "MOVE");

        companion object {
            fun of(handler: Long) = entries.firstOrNull { it.handler == handler }
        }
    }

    const val ID_CANCEL = "option:cancel"
    const val ID_PAGE_PREV = "page:prev"
    const val ID_PAGE_NEXT = "page:next"
}

/**
 * A `YesNoPrompt` (include/yes_no_prompt.h) shown by the party menu or the bag: YES / NO buttons on the touch screen,
 * UP / DOWN toggle the highlight, A answers it, B answers NO at once (yes_no_prompt.c:144-156).
 */
private fun yesNoPrompt(mem: HgssMemory, prompt: Long?, question: String?): Screen? {
    if (prompt == null) return null
    // Waiting while result == 3 (none yet); after an answer the chosen button blinks for 8 frames (yes_no_prompt.c:173).
    if (mem.u8(prompt + P.YNP_RESULT) and 0xF != P.YNP_RESULT_NONE) return Screen.Animation(AnimationKind.TRANSITION)
    // After a touch the highlight is gone and the first key only brings it back (yes_no_prompt.c:169).
    val touchMode = mem.u8(prompt + P.YNP_FLAGS) and 1 != 0
    val position = mem.u8(prompt + P.YNP_CURSOR_POS)
    return Screen.YesNo(
        question = question,
        entries = listOf(Entry("option:yes", "YES"), Entry("option:no", "NO")),
        cursor = if (touchMode || position > 1) Cursor.Hidden else Cursor.At(position),
        topology = Topology.vertical(2, wrap = true),
        // B answers NO at once (closes the question), it doesn't only move the cursor.
        cancel = CancelBehavior.CLOSES,
    )
}

// =====================================================================================================================
// Addresses
// =====================================================================================================================

/** Offsets and constants of the party menu and bag apps (design/screens/party-bag.md, verified live on IPKE). */
internal object HgssPartyBagAddresses {

    // --- PartyMenu (include/party_menu.h:313, 0xCA8 bytes = OverlayManager.data) ---
    const val PM_ARGS = 0x654L                  // PartyMenuArgs *args
    const val PM_FORMATTED_STR_BUF = 0x7C8L     // String *formattedStrBuf: the last message / question printed
    const val PM_CONTEXT_MENU_CURSOR = 0x824L   // PartyMenuContextMenuCursor * (states 2 and 15 only)
    const val PM_MONS_DRAW_STATE = 0x828L       // monsDrawState[6], 0x30 bytes each
    const val MDS_SIZE = 0x30L
    const val MDS_ACTIVE = 0x2DL                // u8: the slot holds a Pokémon
    const val PM_FLAGS_C63 = 0xC63L             // bits 0-5 donor slot, bit 6 second cursor (switch), bit 7 cancelDisabled
    const val FLAG_CANCEL_DISABLED = 0x80
    const val FLAG_SECOND_CURSOR = 0x40
    const val DONOR_SLOT_MASK = 0x3F
    const val PM_AFTER_TEXT_PRINTER_STATE = 0xC62L // u8 afterTextPrinterState: step of a multi-part item effect
    const val PM_TEXT_PRINTER_ID = 0xC64L       // u8 textPrinterId of the message box (window 34)
    const val PM_PARTY_MON_INDEX = 0xC65L       // u8 grid cursor: 0-5 slot, 7 CANCEL button
    const val PM_ITEM_USE_CALLBACK = 0xC54L     // int (*itemUseCallback)(PartyMenu *)
    const val PM_YES_CALLBACK = 0xC58L          // int (*yesCallback)(PartyMenu *): what YES leads to
    const val ARGS_MOVE_ID = 0x2AL              // PartyMenuArgs.moveId (u16): the move being learned
    /** PartyMenu_ItemUseFunc_LevelUpPromptForgetMove / _LevelUpDidNotLearnMove (xMAP), without the Thumb bit. */
    const val FN_LEVEL_UP_PROMPT_FORGET_MOVE = 0x02081F8CL
    const val FN_LEVEL_UP_DID_NOT_LEARN_MOVE = 0x02082038L
    const val PM_LEVEL_UP_LOOP_STATE = 0xC67L   // u8 levelUpLearnMovesLoopState
    /** PartyMenu_ItemUseFunc_LevelUpLearnMovesLoop (xMAP), without the Thumb bit. */
    const val FN_LEVEL_UP_LEARN_MOVES_LOOP = 0x02081C50L
    const val PM_OPENED_ON_SLOT = 0xC66L        // u8 unk_C66: slot the menu opened on (its parity picks CANCEL's UP/DOWN order)
    const val PM_YES_NO_PROMPT = 0xC88L         // YesNoPrompt *
    const val PM_BUTTON_ANIM_ACTIVE = 0xC9CL    // BOOL contextMenuButtonAnim.active

    // --- PartyMenuArgs (include/party_menu.h:194) ---
    const val ARGS_CONTEXT = 0x24L              // u8 context (purpose)

    // --- PartyMenuContextMenuCursor (include/party_menu.h:181) ---
    const val CMC_SELECTION = 0x01L
    const val CMC_NUM_ITEMS = 0x02L
    const val CMC_ITEMS = 0x04L                 // ListMenuItem *: {String *text; s32 value}
    const val LIST_ITEM_SIZE = 8L
    const val LIST_ITEM_TEXT = 0L
    const val LIST_ITEM_VALUE = 4L
    const val LIST_CANCEL = 0xFFFFFFFEL         // LIST_CANCEL (-2): QUIT
    const val CONTEXT_MENU_MAX = 8

    // --- YesNoPrompt (include/yes_no_prompt.h) ---
    const val YNP_FLAGS = 0x74L                 // bit 0 inTouchMode
    const val YNP_CURSOR_POS = 0x75L            // 0 YES, 1 NO
    const val YNP_RESULT = 0x76L                // low nibble: result, 3 = still waiting
    const val YNP_RESULT_NONE = 3

    // --- PartyMenuState (include/party_menu.h:31) ---
    const val STATE_MAIN = 1
    const val STATE_CONTEXT_MENU = 2
    const val STATE_USE_ITEM_SELECT_MON = 4
    const val STATE_ITEM_USE_CB = 5
    const val STATE_SELECT_MOVE = 6
    const val STATE_SACRED_ASH = 7
    /** `afterTextPrinterState` of [STATE_SACRED_ASH]: 1-2 heal (HP bar), 3 the message waits for its printer (A). */
    const val SACRED_ASH_STEP_MESSAGE = 3
    const val STATE_GIVE_ITEM_SELECT_MON = 8
    const val STATE_YESNO_SWITCH_ITEMS = 10
    const val STATE_SUBCONTEXT_MENU = 15
    const val STATE_USE_TMHM = 21
    const val STATE_YES_NO = 27
    const val STATE_SELECT_SWITCH_MON = 28
    const val STATE_SOFTBOILED = 30

    /** Message states: ask switch items, swap / Griseous Orb / take item messages, error message, text printer. */
    val MESSAGE_STATES = setOf(9, 11, 12, 17, 18, 20, 23, 24)

    /** `partyMonIndex` of the CANCEL button (named PARTY_MON_SELECTION_CONFIRM in the decomp, party_menu.c:1446). */
    const val SELECTION_CANCEL_BUTTON = 7

    /** Field grid `DpadMenuBox _0210140C` (party_menu.c:160): neighbours of each slot and of CANCEL (7). */
    val GRID_NEIGHBORS: Map<Int, Map<Button, Int>> = mapOf(
        0 to dpad(up = 7, down = 2, left = 7, right = 1),
        1 to dpad(up = 7, down = 3, left = 0, right = 2),
        2 to dpad(up = 0, down = 4, left = 1, right = 3),
        3 to dpad(up = 1, down = 5, left = 2, right = 4),
        4 to dpad(up = 2, down = 7, left = 3, right = 5),
        5 to dpad(up = 3, down = 7, left = 4, right = 7),
        7 to dpad(up = 5, down = 1, left = 5, right = 0),
    )

    /** `_021012CC` (party_menu.c:1300): from CANCEL, first Pokémon of this order, by the opening slot's column. */
    val CANCEL_DOWN_ORDER = listOf(listOf(0, 2, 4, 1, 3, 5), listOf(1, 3, 5, 0, 2, 4))
    val CANCEL_UP_ORDER = listOf(listOf(4, 2, 0, 5, 3, 1), listOf(5, 3, 1, 4, 2, 0))

    private fun dpad(up: Int, down: Int, left: Int, right: Int) =
        mapOf(Button.UP to up, Button.DOWN to down, Button.LEFT to left, Button.RIGHT to right)

    /** `sDpadNavParam_PartyMenu[n-2][selection] = {UP, DOWN, LEFT/RIGHT}` (party_context_menu.c:217), -1 = no move. */
    val CONTEXT_MENU_NAV: List<List<IntArray>> = listOf(
        listOf(intArrayOf(1, 1, -1), intArrayOf(0, 0, -1)),
        listOf(intArrayOf(2, 1, -1), intArrayOf(0, 2, -1), intArrayOf(1, 0, -1)),
        listOf(intArrayOf(3, 1, -1), intArrayOf(0, 2, -1), intArrayOf(1, 3, -1), intArrayOf(2, 0, -1)),
        listOf(intArrayOf(3, 1, 4), intArrayOf(0, 2, 4), intArrayOf(1, 3, 4), intArrayOf(2, 0, -1), intArrayOf(-1, -1, 0)),
        listOf(
            intArrayOf(3, 1, 4), intArrayOf(0, 2, 5), intArrayOf(1, 3, 5), intArrayOf(2, 0, -1),
            intArrayOf(5, 5, 0), intArrayOf(4, 4, 1),
        ),
        listOf(
            intArrayOf(3, 1, 4), intArrayOf(0, 2, 5), intArrayOf(1, 3, 6), intArrayOf(2, 0, -1),
            intArrayOf(6, 5, 0), intArrayOf(4, 6, 1), intArrayOf(5, 4, 2),
        ),
        listOf(
            intArrayOf(3, 1, 4), intArrayOf(0, 2, 5), intArrayOf(1, 3, 6), intArrayOf(2, 0, -1),
            intArrayOf(7, 5, 0), intArrayOf(4, 6, 1), intArrayOf(5, 7, 2), intArrayOf(6, 4, 2),
        ),
    )

    /**
     * Context menu functions (`ListMenuItem.value`, thumb bit cleared) → stable entry ids. Main binary addresses
     * from the xMAP of the HeartGold (US) build: other versions fall back on `action:<address>`. Ids never come
     * from the labels, which change with the game's language.
     */
    class ContextMenuFunctions(private val ids: Map<Long, String>) {
        fun idOf(function: Long): String? = ids[function]

        companion object {
            private val HEARTGOLD_US = ContextMenuFunctions(
                mapOf(
                    0x02080768L to "option:summary", 0x0207FB0CL to "option:switch", 0x0207F438L to "option:item",
                    0x0207F73CL to "option:mail", 0x0207F4FCL to "option:give", 0x0207F520L to "option:take",
                    0x0207F800L to "option:read", 0x0207F824L to "option:take",
                    0x020808D0L to "fieldmove:cut", 0x020808E8L to "fieldmove:rocksmash", 0x02080900L to "fieldmove:strength",
                    0x02080918L to "fieldmove:surf", 0x02080930L to "fieldmove:rockclimb", 0x02080948L to "fieldmove:fly",
                    0x02080960L to "fieldmove:waterfall", 0x02080978L to "fieldmove:whirlpool", 0x02080990L to "fieldmove:flash",
                    0x020809A8L to "fieldmove:teleport", 0x020809C0L to "fieldmove:dig", 0x020809D8L to "fieldmove:sweetscent",
                    0x020809F0L to "fieldmove:chatter", 0x02080A08L to "fieldmove:milkdrink", 0x02080A24L to "fieldmove:softboiled",
                    0x02080A40L to "fieldmove:headbutt",
                ),
            )

            fun forVersion(version: HgssVersion): ContextMenuFunctions? =
                if (version.gameCode == HgssVersion.HEARTGOLD_US.gameCode) HEARTGOLD_US else null
        }
    }

    // --- Bag (overlay 15): work = OverlayManager.data (0x94C bytes), BagView (include/bag_types_def.h:66) ---
    const val BAG_VIEW = 0x234L                 // BagView *
    const val BAG_ACTION_LABELS = 0x300L        // String *[16], one per action id (msg_0010)
    const val BAG_MESSAGE = 0x5E4L              // String *: text of the bag's message box
    const val BAG_TM_STEP = 0x67BL              // u8 step of the TM / HM "Booted up a TM" sequence (state 13)
    const val BAG_TM_STEP_START = 0             // writes "Booted up a TM." into BAG_MESSAGE (ov15_021FB830 case 0)
    const val BAG_TM_STEP_YES_NO = 3            // "Teach X to a Pokémon?" yes / no waiting
    const val BAG_YES_NO_PROMPT = 0x804L        // YesNoPrompt *
    const val BAG_CURSOR = 0x644L               // int main cursor: 0-7 tabs, 8-13 items, 14/15 page arrows, 16 CANCEL
    const val BAG_ACTION_CURSOR = 0x66CL        // int action menu cursor 0-4
    const val BAG_ACTION_HANDLERS = 0x7F0L      // handler of each action menu slot (0 = empty)

    const val BV_POCKETS = 0x04L                // pockets[8], indexed by pocket id
    const val BV_POCKET_SIZE = 0x0CL
    const val BVP_SLOTS = 0x00L                 // ItemSlot * into the save bag
    const val BVP_PAGE_START = 0x06L            // s16 index of the first item of the page
    const val BVP_POCKET_ID = 0x08L
    const val BVP_COUNT = 0x09L                 // u8 items in the pocket
    const val BV_CURRENT_POCKET = 0x64L
    const val BV_SELECTED_ITEM = 0x66L          // u16 item of the action menu
    const val BV_FLAGS_76 = 0x76L               // bit 0: cycling (WALK label on the Bicycle)
    const val ITEM_SLOT_SIZE = 4L

    const val ITEMS_PER_PAGE = 6
    const val BAG_TAB_COUNT = 8
    const val BAG_PAGE_PREV = 0x0E
    const val BAG_PAGE_NEXT = 0x0F
    const val BAG_CURSOR_CANCEL = 0x10
    const val BAG_TARGET_CURRENT_TAB = 0x11

    /** Main loops (mode 0 field, 1 give from the party, 2 PC, 3 single pocket) and the action menu (Bag_Main). */
    val BAG_MAIN_STATES = setOf(1, 14, 16, 26)
    const val BAG_STATE_ACTION_MENU = 4
    const val BAG_STATE_MESSAGE = 12
    const val BAG_STATE_TM_MESSAGE = 13

    /**
     * Selling (the bag opened by the clerk's SELL; states found live, overlay 15 is not decompiled): 17 "How many will
     * you sell?" printing, 18 the number (UP / DOWN, A sells), 22 "I can pay ₽X. Would that be OK?" then its YES / NO,
     * 23 "Turned over X and received ₽Y." printing, 24 waiting for A, 21 a message waiting for A; then back to 16.
     */
    const val BAG_STATE_SELL_ASK_HOW_MANY = 17
    const val BAG_STATE_SELL_QUANTITY = 18
    const val BAG_STATE_SELL_MESSAGE = 21
    const val BAG_STATE_SELL_YES_NO = 22
    const val BAG_STATE_SELL_DONE_PRINTING = 23
    const val BAG_STATE_SELL_DONE = 24

    /** Opening the selling box, closing it after the number. */
    val BAG_SELL_BUSY_STATES = setOf(34, 35)
    const val BAG_SELL_QUANTITY = 0x680L        // u16 number to sell
    const val BAG_SELL_MAX = 0x682L             // u16 how many the bag holds
    const val BAG_SELL_UNIT_PRICE = 0x684L      // u16 what the shop pays for one

    /** Pocket change, open / close the action menu, page turn, button animation, exit. */
    val BAG_BUSY_STATES = setOf(0, 2, 27, 28, 29, 30, 31, 35, 36, 37)

    /** Table ov15_02200640 {UP, DOWN, LEFT, RIGHT} for cursor 0..16 (0x11 = the open pocket's tab). */
    val BAG_NAV: List<IntArray> = listOf(
        intArrayOf(0x0C, 0x08, 0x07, 0x01), intArrayOf(0x0C, 0x08, 0x00, 0x02),
        intArrayOf(0x0C, 0x08, 0x01, 0x03), intArrayOf(0x0C, 0x08, 0x02, 0x04),
        intArrayOf(0x10, 0x09, 0x03, 0x05), intArrayOf(0x10, 0x09, 0x04, 0x06),
        intArrayOf(0x10, 0x09, 0x05, 0x07), intArrayOf(0x10, 0x09, 0x06, 0x00),
        intArrayOf(0x11, 0x0A, 0x0E, 0x09), intArrayOf(0x11, 0x0B, 0x08, 0x0F),
        intArrayOf(0x08, 0x0C, 0x0E, 0x0B), intArrayOf(0x09, 0x0D, 0x0A, 0x0F),
        intArrayOf(0x0A, 0x11, 0x0E, 0x0D), intArrayOf(0x0B, 0x10, 0x0C, 0x0F),
        intArrayOf(0x0C, 0x11, 0x10, 0x10), intArrayOf(0x0C, 0x11, 0x10, 0x10),
        intArrayOf(0x0D, 0x11, 0x10, 0x10),
    )

    const val ACTION_SLOTS = 5
    const val ACTION_SLOT_CANCEL = 4

    /** Table ov15_02200528 {UP, DOWN, LEFT, RIGHT} of the action menu slots. */
    val ACTION_NAV: List<IntArray> = listOf(
        intArrayOf(2, 2, 1, 1), intArrayOf(3, 3, 0, 0), intArrayOf(0, 0, 4, 3), intArrayOf(1, 1, 2, 4), intArrayOf(4, 4, 3, 2),
    )

    // Action ids (index of ov15_02201368 and of the label strings).
    const val ACTION_ID_USE = 0
    const val ACTION_ID_WALK = 1
    const val ACTION_ID_CHECK = 2
    const val ACTION_ID_CANCEL = 11

    const val ITEM_BICYCLE = 450
}
