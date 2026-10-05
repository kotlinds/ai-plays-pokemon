package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PcMode
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.games.hgss.HgssKeyboardPcShopAddresses as K

/**
 * Naming keyboard, PC storage, Poké Mart, save prompts, trade and egg hatch scenes (design/screens/keyboard-pc-shop.md).
 *
 * Every screen is recognised from structures and function pointers, never from displayed text (the game may run in
 * another language): entry ids come from key codes, layout tables, item ids and Pokémon identities.
 */
internal object HgssKeyboardPcShopScreens : HgssScreenDecoder {

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        val version = HgssKeyboardPcShopVersion.forGameCode(mem.version.gameCode) ?: return null
        // The keyboard first: it runs nested inside other apps (PC box, battle, hatch), so it must win over them.
        HgssNamingKeyboard.decode(mem, version)?.let { return it }
        val fs = fieldSystem(mem) ?: return null
        val subApp = mem.ptr(fs + A.FS_SUB0)?.let { mem.ptr(it + A.FSS0_SUB_APP) }
        if (subApp != null) {
            return when (mem.s32(subApp + A.OM_OVY_ID)) {
                K.OVY_PC_BOX -> HgssPcBox.decode(mem, version, subApp)
                // overlay 71 reads no key and prints at instant speed (asm/overlay_71.s): nothing to do but wait.
                K.OVY_TRADE -> Screen.Animation(AnimationKind.TRADE)
                K.OVY_HATCH_EGG -> HgssHatch.decode(mem, subApp)
                else -> null
            }
        }
        HgssMart.decode(mem, version, fs, state)?.let { return it }
        return HgssTouchSave.decode(mem, version, fs)
    }

    /** The FieldSystem, only while the field is the running top-level app (sFieldSysPtr dangles otherwise). */
    private fun fieldSystem(mem: HgssMemory): Long? {
        val v = mem.version
        val om = mem.ptr(v.mainAppState + A.MAIN_APP_OVERLAY_MANAGER) ?: return null
        val init = mem.fn(om + A.OM_INIT)
        if (init != v.fnFieldContinueAppInit && init != v.fnFieldNewGameAppInit) return null
        return mem.ptr(om + A.OM_DATA) ?: mem.ptr(v.fieldSystemPtr)
    }
}

// region Shared helpers

/** Reads a bottom-screen `YesNoPrompt` (src/yes_no_prompt.c): cursor, touch mode and the two buttons' rects. */
internal object HgssYesNoPrompt {

    /**
     * A [Screen.YesNo] from the prompt at [prompt]. After a touch the first key press only leaves touch mode
     * (yes_no_prompt.c:144-175), hence [Cursor.Hidden] then. B answers NO at once ([CancelBehavior.CLOSES]).
     */
    fun screen(mem: HgssMemory, prompt: Long, question: String?, yesIsDangerous: Boolean = false): Screen.YesNo {
        val touchMode = mem.u8(prompt + K.YNP_TOUCH_MODE) and 1 != 0
        val cursor = mem.u8(prompt + K.YNP_CURSOR).coerceIn(0, 1)
        val yes = hitboxCenter(mem, prompt + K.YNP_HITBOXES)
        val no = hitboxCenter(mem, prompt + K.YNP_HITBOXES + K.HITBOX_SIZE)
        return Screen.YesNo(
            question = question,
            entries = listOf(
                Entry("option:yes", "YES", dangerous = yesIsDangerous, touch = yes),
                Entry("option:no", "NO", touch = no),
            ),
            cursor = if (touchMode) Cursor.Hidden else Cursor.At(cursor),
            // UP and DOWN both toggle between the two buttons.
            topology = Topology.vertical(2, wrap = true),
            cancel = CancelBehavior.CLOSES,
        )
    }
}

/** Center of a `TouchscreenHitbox {top, bottom, left, right}` (right 0 = 256); null for an empty rect. */
internal fun hitboxCenter(mem: HgssMemory, hitbox: Long): TouchPoint? {
    val top = mem.u8(hitbox)
    val bottom = mem.u8(hitbox + 1)
    val left = mem.u8(hitbox + 2)
    val right = mem.u8(hitbox + 3).let { if (it == 0) 256 else it }
    if (top == K.HITBOX_LIST_END || bottom <= top || right <= left) return null
    return TouchPoint((left + right) / 2, (top + bottom) / 2)
}

/** What the agent needs to know about a stored Pokémon: its identity and the name shown. */
internal data class StoredMon(val id: MonId, val name: String, val level: Int?, val heldItem: Int, val isEgg: Boolean) {
    val label get() = if (level != null && !isEgg) "$name Lv$level" else name

    companion object {
        /** Decodes the box or party Pokémon at [addr] ([size] 0x88 or 0xEC); null for an empty or unreadable slot. */
        fun read(mem: HgssMemory, addr: Long, size: Int): StoredMon? {
            val raw = mem.bytes(addr, size) ?: return null
            val mon = HgssPokemon.decode(raw, HgssMonCheck::isPlausible) ?: return null
            if (mon.species == 0 || mon.species > PartyMon.MAX_SPECIES) return null
            val name = when {
                mon.isEgg -> "Egg"
                else -> HgssText.decode(mon.nicknameChars).ifEmpty { HgssData.speciesName(mon.species) }
            }
            return StoredMon(MonId(mon.personality, mon.otId), name, mon.level.takeIf { mon.party != null && it > 0 }, mon.heldItem, mon.isEgg)
        }
    }
}

/** Precomputes a [Topology] over `0 until size` (memory buffers are reused per frame: never read RAM lazily). */
internal fun precomputedTopology(size: Int, next: (Int, Button) -> Int?): Topology {
    val links = (0 until size).associateWith { from ->
        DIRECTIONS.mapNotNull { b -> next(from, b)?.takeIf { it in 0 until size && it != from }?.let { b to it } }.toMap()
    }
    return Topology.of(links)
}

internal val DIRECTIONS = listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT)

// endregion

// region Naming keyboard

/**
 * The naming keyboard of HeartGold / SoulSilver: the Gen 4 decoder ([dev.kotlinds.pokemonclient.games.gen4.Gen4NamingKeyboard])
 * with the HG/SS addresses (vblank callback, `sAppData`) and palette fade.
 */
internal object HgssNamingKeyboard {

    fun decode(mem: HgssMemory, version: HgssKeyboardPcShopVersion): Screen? =
        dev.kotlinds.pokemonclient.games.gen4.Gen4NamingKeyboard.decode(
            mem, mem.version.gSystem,
            dev.kotlinds.pokemonclient.games.gen4.Gen4NamingAddresses(version.namingVBlankCallback, version.namingAppData),
            fading = mem.u16(mem.version.paletteFadeActive) != 0,
        )

    fun topology(cells: IntArray): Topology = dev.kotlinds.pokemonclient.games.gen4.Gen4NamingKeyboard.topology(cells)
}

// endregion

// region PC storage

/**
 * The 11 cursor layouts of the PC app (hitbox / d-pad table pairs `ov14_021F8B10`), told apart by the hitbox table
 * the GridInputHandler points to. [menu] is the range of context-menu entries (the last one is its EXIT).
 */
enum class HgssPcLayout(val menu: IntRange?) {
    /** Deposit: party 0-5, 6 RETURN; menu DEPOSIT / SUMMARY / MARKING / RELEASE / EXIT. */
    DEPOSIT(7..11),
    /** "Deposit where?": 6 box tabs, 6/7 arrows (touch), 8 DEPOSIT POKéMON, 9 EXIT. */
    DEPOSIT_BOX_PICKER(null),
    /** Withdraw: box 0-29, 30 title, 31/32 arrows, 33 RETURN; menu WITHDRAW / SUMMARY / MARKING / RELEASE / EXIT. */
    WITHDRAW(34..38),
    /** Move Pokémon: box 0-29, 30 title, 31/32 arrows, 33 PARTY PKMN, 34 MOVE, 35 RETURN; menu MOVE / SUMMARY / HELD ITEMS / MARKING / RELEASE / EXIT. */
    MOVE(36..41),
    /** Move Pokémon with the party panel on the right (decomp only, not decoded). */
    MOVE_BOX_AND_PARTY(null),
    /** Move Pokémon, party panel: party 0-5, 6 SWITCH, 7 EXIT; same menu as [MOVE]. */
    MOVE_PARTY(8..13),
    /** Move items: box 0-29, 30 title, 31/32 arrows, 33 PARTY PKMN, 34 SORT ITEMS, 35 RETURN; menu GIVE or TAKE / EXIT. */
    ITEMS(36..37),
    /** Move items, party panel: party 0-5, 6 SWITCH, 7 EXIT; menu GIVE or TAKE / EXIT (decomp only). */
    ITEMS_PARTY(8..9),
    /** Small party panel on the right (decomp only, not decoded). */
    SMALL_PARTY(null),
    /** Box title menu: 6 box tabs, 6/7 arrows (touch), 8 CHANGE BOX, 9 WALLPAPER, 10 NAME, 11 EXIT. */
    BOX_TITLE_MENU(null),
    /** Wallpaper picker (decomp only, not decoded). */
    WALLPAPER(null),
}

/** The PC box app (overlay 14, asm/overlay_14.s). */
internal object HgssPcBox {

    /** OverlayManager proc states where the GridInputHandler reads input (verified live per mode). */
    private val INPUT_STATES = setOf(0x0C, 0x24, 0x3D, 0x51, 0x5B, 0x61, 0x75, K.PC_STATE_MOVE_FREE, K.PC_STATE_MOVE_HOLDING)

    /** Questions of the PC yes / no prompts (`PCBoxWork+0x438`, msg_0025), display only. */
    private val QUESTIONS = mapOf(0 to "Take this item?", 1 to "Release this Pokémon?", 2 to "Put away the item?", 3 to "Exit the Box?", 4 to "Continue Box operations?")
    private const val QUESTION_RELEASE = 1

    fun decode(mem: HgssMemory, version: HgssKeyboardPcShopVersion, om: Long): Screen {
        if (mem.s32(om + A.OM_EXEC_STATE) != 2) return Screen.Animation(AnimationKind.TRANSITION)
        val data = mem.ptr(om + A.OM_DATA) ?: return Screen.Animation(AnimationKind.TRANSITION)
        val work = mem.ptr(data + K.PCB_WORK) ?: return Screen.Animation(AnimationKind.TRANSITION)
        val proc = mem.s32(om + A.OM_PROC_STATE)
        when {
            proc == K.PC_STATE_YES_NO -> {
                val id = mem.u16(work + K.PCW_YES_NO_ID)
                val prompt = mem.ptr(work + K.PCW_YES_NO) ?: return Screen.Animation(AnimationKind.TRANSITION)
                return HgssYesNoPrompt.screen(mem, prompt, QUESTIONS[id], yesIsDangerous = id == QUESTION_RELEASE)
            }
            proc == K.PC_STATE_MESSAGE -> return Screen.PressToContinue(ContinueReason.MESSAGE)
            proc in K.PC_STATES_ANIMATION -> return Screen.Animation(AnimationKind.TRANSITION)
            proc !in INPUT_STATES -> return Screen.Unknown("pc_box state 0x${proc.toString(16)}", Awaiting.ANIMATION)
        }
        val grid = mem.ptr(work + K.PCW_GRID) ?: return Screen.Animation(AnimationKind.TRANSITION)
        val layout = version.pcLayoutHitboxes.indexOf(mem.u32(grid + K.GRID_HITBOXES)).let { HgssPcLayout.entries.getOrNull(it) }
            ?: return Screen.Unknown("pc_box layout ${mem.u32(grid + K.GRID_HITBOXES).toString(16)}", Awaiting.INPUT)
        val ctx = PcContext(mem, data, grid, layout)
        return when (layout) {
            HgssPcLayout.DEPOSIT_BOX_PICKER, HgssPcLayout.BOX_TITLE_MENU -> boxPicker(ctx)
            HgssPcLayout.MOVE_BOX_AND_PARTY -> moveWithParty(ctx, holding = proc == K.PC_STATE_MOVE_HOLDING)
            HgssPcLayout.SMALL_PARTY, HgssPcLayout.WALLPAPER ->
                Screen.Unknown("pc_box ${layout.name.lowercase()}", Awaiting.INPUT)
            else -> {
                val menu = layout.menu
                if (menu != null && ctx.cursor in menu) contextMenu(ctx, menu) else box(ctx, menu?.first ?: ctx.size)
            }
        }
    }

    /** What every PC screen reads once. */
    private class PcContext(val mem: HgssMemory, val data: Long, val grid: Long, val layout: HgssPcLayout) {
        val cursor = mem.u8(grid + K.GRID_CURSOR)
        val remembered = mem.u8(grid + K.GRID_REMEMBERED)
        val enabled = mem.u32(grid + K.GRID_ENABLED) or (mem.u32(grid + K.GRID_ENABLED + 4) shl 32)
        val dpad = mem.u32(grid + K.GRID_DPAD)
        val hitboxes = mem.u32(grid + K.GRID_HITBOXES)
        val size = (0 until 64).firstOrNull { mem.u8(hitboxes + it * K.HITBOX_SIZE) == K.HITBOX_LIST_END } ?: 0
        val mode = mem.ptr(data + K.PCB_ARGS)?.let { mem.s32(it + K.PCARGS_MODE) } ?: -1
        val shownBox = mem.u8(data + K.PCB_SHOWN_BOX).coerceIn(0, K.BOX_COUNT - 1)
        val storage = mem.ptr(data + K.PCB_STORAGE)
        val party = mem.ptr(data + K.PCB_PARTY)

        /** Neighbours of every index, read now (see [neighbour]). */
        private val links = IntArray(size * 4) { mem.u8(dpad + (it / 4) * K.DPAD_BOX_SIZE + K.DPAD_NEIGHBOURS + it % 4) }

        fun touch(index: Int) = hitboxCenter(mem, hitboxes + index * K.HITBOX_SIZE)

        fun boxMon(box: Int, slot: Int): StoredMon? =
            storage?.let { StoredMon.read(mem, it + box * K.PCS_BOX_STRIDE + slot * K.BOX_MON_SIZE, K.BOX_MON_SIZE) }

        fun partyMon(slot: Int): StoredMon? = party?.let { p ->
            if (slot >= mem.s32(p + A.PARTY_CUR_COUNT)) null
            else StoredMon.read(mem, p + A.PARTY_MONS + slot * A.POKEMON_SIZE, A.POKEMON_SIZE.toInt())
        }

        fun boxName(box: Int) = storage?.let { mem.inlineText(it + K.PCS_BOX_NAMES + box * K.PCS_BOX_NAME_CHARS * 2L, K.PCS_BOX_NAME_CHARS) }
            ?.takeIf { it.isNotBlank() } ?: "BOX ${box + 1}"

        fun boxCount(box: Int) = (0 until K.BOX_SLOTS).count { boxMon(box, it) != null }

        private fun isEnabled(index: Int) = index in 0 until 64 && (enabled ushr index) and 1L == 1L

        private fun neighbour(index: Int, direction: Int) = if (index in 0 until size) links[index * 4 + direction] else K.DPAD_NONE

        /**
         * `GridInputHandler_HandleDpad` (src/unk_02019BA4.c:197): a neighbour with bit 0x80 goes back to the
         * remembered index (`+0x0F`) when there is one; disabled targets are skipped in the same direction. The
         * remembered index is only known for the current cursor: from other entries the plain neighbour is used,
         * which the navigator corrects by re-reading the screen after each press.
         */
        fun move(from: Int, button: Button): Int? {
            val direction = DIRECTIONS.indexOf(button).takeIf { it >= 0 } ?: return null
            var input = neighbour(from, direction)
            if (input == K.DPAD_NONE) return null
            if (input and K.DPAD_REMEMBERED_BIT != 0) {
                input = if (from == cursor && remembered != K.DPAD_NONE) remembered else input xor K.DPAD_REMEMBERED_BIT
            }
            var guard = 0
            while (!isEnabled(input) && guard++ < size) {
                val next = neighbour(input, direction) and 0x7F
                if (next == input || next == from) return null
                input = next
            }
            return input.takeIf { it != from }
        }
    }

    /** The box grid or party panel with its buttons (everything before the context menu). */
    private fun box(ctx: PcContext, count: Int): Screen.PcBox {
        val entries = (0 until count).map { entry(ctx, it) }
        return Screen.PcBox(
            box = ctx.shownBox,
            boxName = ctx.boxName(ctx.shownBox),
            entries = entries,
            cursor = if (ctx.cursor in 0 until count) Cursor.At(ctx.cursor) else Cursor.Hidden,
            // LEFT / RIGHT on the title change the box (the d-pad table links the title to itself, the app catches the
            // keys, state 0xC asm:12174): the cursor stays, the shown box changes.
            topology = precomputedTopology(count) { from, button -> ctx.move(from, button) },
            cancel = CancelBehavior.CLOSES,
            mode = when (ctx.layout) {
                HgssPcLayout.DEPOSIT -> PcMode.DEPOSIT
                HgssPcLayout.WITHDRAW -> PcMode.WITHDRAW
                HgssPcLayout.MOVE, HgssPcLayout.MOVE_PARTY, HgssPcLayout.MOVE_BOX_AND_PARTY -> PcMode.MOVE
                HgssPcLayout.ITEMS, HgssPcLayout.ITEMS_PARTY -> PcMode.MOVE_ITEMS
                else -> null
            },
        )
    }

    private fun entry(ctx: PcContext, index: Int): Entry {
        val layout = ctx.layout
        val partyPanel = layout == HgssPcLayout.DEPOSIT || layout == HgssPcLayout.MOVE_PARTY || layout == HgssPcLayout.ITEMS_PARTY
        if (partyPanel) {
            return when (index) {
                in 0 until K.PARTY_SLOTS -> ctx.partyMon(index)?.let { Entry(it.id.toString(), it.label) }
                    ?: Entry("slot:party$index", "-", selectable = false)
                6 -> if (layout == HgssPcLayout.DEPOSIT) Entry("option:return", "RETURN") else Entry("option:switch", "SWITCH")
                else -> Entry("option:return", "EXIT")
            }
        }
        return when (index) {
            in 0 until K.BOX_SLOTS -> ctx.boxMon(ctx.shownBox, index)?.let { Entry(it.id.toString(), it.label) }
                ?: Entry("slot:$index", "-")
            30 -> Entry("option:box", ctx.boxName(ctx.shownBox))
            31 -> Entry("option:prev_box", "◀", touch = ctx.touch(index))
            32 -> Entry("option:next_box", "▶", touch = ctx.touch(index))
            33 -> if (layout == HgssPcLayout.WITHDRAW) Entry("option:return", "RETURN") else Entry("option:party", "PARTY PKMN")
            34 -> if (layout == HgssPcLayout.ITEMS) Entry("option:sort_items", "SORT ITEMS") else Entry("option:move", "MOVE")
            else -> Entry("option:return", "RETURN")
        }
    }

    /**
     * MOVE POKéMON with the party panel and the box tabs, the layout used once a Pokémon is picked up (proc state
     * 0x73, holding) and after it is put down (0x29, free: A on a Pokémon picks it up at once, without a menu).
     * Grid indices (hitbox table read live): 0-29 box slots, 30-35 party slots (two columns), 36 EXIT of the party
     * panel, 37-42 the six visible box tabs (A on a tab while holding puts the Pokémon in that box), 43 / 44 the tab
     * arrows (touch), 45 SUMMARY.
     *
     * The tabs scroll over the 18 boxes: while the cursor is on a tab, the highlighted box is `PCBoxData+0x25` and the
     * visible tabs are named `box:N`; elsewhere their box isn't known and they are `tab:K`. LEFT / RIGHT on the tabs
     * move the highlighted box by one (scrolling at the ends), verified live.
     */
    private fun moveWithParty(ctx: PcContext, holding: Boolean): Screen.PcBox {
        val onTab = ctx.cursor in MP_TABS
        val firstTabBox = if (onTab) ctx.mem.u8(ctx.data + K.PCB_PICKER_BOX) - (ctx.cursor - MP_TABS.first) else null
        val entries = (0 until ctx.size).map { i ->
            when (i) {
                in 0 until K.BOX_SLOTS -> ctx.boxMon(ctx.shownBox, i)?.let { Entry(it.id.toString(), it.label) } ?: Entry("slot:$i", "-")
                in MP_PARTY -> (i - MP_PARTY.first).let { p -> ctx.partyMon(p)?.let { Entry(it.id.toString(), it.label) } ?: Entry("slot:party$p", "-") }
                MP_EXIT -> Entry("option:close_party", "EXIT")
                in MP_TABS -> {
                    val box = firstTabBox?.let { (it + i - MP_TABS.first).mod(K.BOX_COUNT) }
                    if (box != null) Entry("box:$box", "${ctx.boxName(box)} (${ctx.boxCount(box)}/${K.BOX_SLOTS})") else Entry("tab:${i - MP_TABS.first}", "box tab")
                }
                MP_PREV -> Entry("option:prev_box", "◀", touch = ctx.touch(i))
                MP_NEXT -> Entry("option:next_box", "▶", touch = ctx.touch(i))
                else -> Entry("option:summary", "SUMMARY")
            }
        }
        val selected = ctx.mem.u8(ctx.data + K.PCB_SELECTED_SLOT)
        val held = if (!holding) null else if (selected < K.PC_PARTY_SLOT_BASE) ctx.boxMon(ctx.shownBox, selected) else ctx.partyMon(selected - K.PC_PARTY_SLOT_BASE)
        return Screen.PcBox(
            box = ctx.shownBox,
            boxName = ctx.boxName(ctx.shownBox),
            entries = entries,
            cursor = if (ctx.cursor in entries.indices) Cursor.At(ctx.cursor) else Cursor.Hidden,
            topology = precomputedTopology(entries.size) { from, button -> ctx.move(from, button) },
            cancel = CancelBehavior.CLOSES,
            mode = PcMode.MOVE,
            holding = held?.id,
        )
    }

    private val MP_PARTY = 30..35
    private const val MP_EXIT = 36
    private val MP_TABS = 37..42
    private const val MP_PREV = 43
    private const val MP_NEXT = 44

    /** The context menu opened on a slot (same GridInputHandler, cursor in the menu's index range). */
    private fun contextMenu(ctx: PcContext, menu: IntRange): Screen.ContextMenu {
        val selected = ctx.mem.u8(ctx.data + K.PCB_SELECTED_SLOT)
        val owner = if (selected < K.PC_PARTY_SLOT_BASE) ctx.boxMon(ctx.shownBox, selected) else ctx.partyMon(selected - K.PC_PARTY_SLOT_BASE)
        val actions = when (ctx.layout) {
            HgssPcLayout.DEPOSIT -> listOf("deposit" to "DEPOSIT", "summary" to "SUMMARY", "marking" to "MARKING", "release" to "RELEASE")
            HgssPcLayout.WITHDRAW -> listOf("withdraw" to "WITHDRAW", "summary" to "SUMMARY", "marking" to "MARKING", "release" to "RELEASE")
            HgssPcLayout.ITEMS, HgssPcLayout.ITEMS_PARTY ->
                listOf(if ((owner?.heldItem ?: 0) != 0) "take" to "TAKE" else "give" to "GIVE")
            else -> listOf("move" to "MOVE", "summary" to "SUMMARY", "held_items" to "HELD ITEMS", "marking" to "MARKING", "release" to "RELEASE")
        }
        val entries = actions.map { (id, label) -> Entry("option:$id", label, dangerous = id == "release") } + Entry("option:cancel", "EXIT")
        val first = menu.first
        return Screen.ContextMenu(
            owner = owner?.id,
            entries = entries,
            cursor = Cursor.At(ctx.cursor - first),
            topology = precomputedTopology(entries.size) { from, button -> ctx.move(from + first, button)?.minus(first) },
            cancel = CancelBehavior.CLOSES,
        )
    }

    /**
     * The box pickers ("Deposit where?" and the box title menu): six visible box tabs scrolling over the 18 boxes,
     * then action buttons. Shown as one entry per box (the highlighted box is `PCBoxData+0x25`) plus the actions;
     * LEFT / RIGHT scroll through the boxes (6 RIGHT presses from box 1 reach box 7, verified live), UP from the
     * actions comes back to the highlighted box (the app overrides the table there, verified live).
     */
    private fun boxPicker(ctx: PcContext): Screen.ListMenu {
        val deposit = ctx.layout == HgssPcLayout.DEPOSIT_BOX_PICKER
        val actions = if (deposit) {
            listOf(Entry("option:deposit", "DEPOSIT"), Entry("option:cancel", "EXIT"))
        } else {
            listOf(Entry("option:change_box", "CHANGE BOX"), Entry("option:wallpaper", "WALLPAPER"), Entry("option:name", "NAME"), Entry("option:cancel", "EXIT"))
        }
        val boxes = (0 until K.BOX_COUNT).map { b ->
            val count = ctx.boxCount(b)
            Entry("box:$b", "${ctx.boxName(b)} ($count/${K.BOX_SLOTS})", selectable = !deposit || count < K.BOX_SLOTS)
        }
        val highlighted = ctx.mem.u8(ctx.data + K.PCB_PICKER_BOX).coerceIn(0, K.BOX_COUNT - 1)
        val firstAction = 8
        val cursor = when {
            ctx.cursor < 6 -> highlighted
            ctx.cursor >= firstAction -> K.BOX_COUNT + ctx.cursor - firstAction
            else -> -1
        }
        val entries = boxes + actions
        val topology = precomputedTopology(entries.size) { from, button ->
            if (from < K.BOX_COUNT) {
                when (button) {
                    Button.LEFT -> (from - 1).mod(K.BOX_COUNT)
                    Button.RIGHT -> (from + 1).mod(K.BOX_COUNT)
                    Button.DOWN -> K.BOX_COUNT
                    else -> null
                }
            } else {
                when (button) {
                    Button.UP -> if (from == K.BOX_COUNT) highlighted else from - 1
                    Button.DOWN -> (from + 1).takeIf { it < entries.size }
                    else -> null
                }
            }
        }
        return Screen.ListMenu(
            kind = MenuKind.PC,
            entries = entries,
            cursor = if (cursor >= 0) Cursor.At(cursor) else Cursor.Hidden,
            topology = topology,
            cancel = CancelBehavior.CLOSES,
        )
    }
}

// endregion

// region Poké Mart

/** The Poké Mart buy screens (src/overlay_03/shop_menu.c `Task_Mart`, bottom screen overlay 31). */
internal object HgssMart {

    fun decode(mem: HgssMemory, version: HgssKeyboardPcShopVersion, fs: Long, state: HgssState): Screen? {
        val task = mem.ptr(fs + A.FS_TASKMAN) ?: return null
        if (mem.fn(task + A.TM_FUNC) != version.fnTaskMart) return null
        val mart = mem.ptr(task + A.TM_ENV) ?: return null
        // SELL hands over to the Bag app (shop_menu.c:1520): decoded by the bag family.
        if (mem.u8(mart + K.MART_BUY_SELL) != K.MART_BUY) return null
        return when (val st = mem.u8(mart + K.MART_STATE)) {
            K.MART_STATE_GRID -> grid(mem, mart, state.player?.money ?: 0L)
            K.MART_STATE_QUANTITY -> Screen.Quantity(
                value = mem.s16(mart + K.MART_QUANTITY),
                min = 1,
                max = mem.u16(mart + K.MART_MAX_QUANTITY),
            )
            K.MART_STATE_YES_NO -> yesNo(mem, fs, mart)
            else -> {
                val reason = if (st == K.MART_STATE_BONUS) ContinueReason.SHOP_BONUS else ContinueReason.MESSAGE
                when (printerWaiting(mem, mart)) {
                    true -> Screen.PressToContinue(reason, message(mem, mart))
                    false -> Screen.Animation(AnimationKind.TRANSITION)
                    // Printer done: states 13-15 wait for A / B / touch (ov03_02257F24 / FF8 / 8078), the others run alone.
                    null -> if (st in K.MART_STATES_MESSAGE) Screen.PressToContinue(reason, message(mem, mart)) else Screen.Animation(AnimationKind.TRANSITION)
                }
            }
        }
    }

    /** The clerk's last message (`MartData.string`, expanded before printing). */
    /**
     * The clerk's message when `MartData.string` still holds it. The purchase and bonus lines aren't readable there
     * (the string is empty and the printer gone while they wait for A): their content reaches the agent as events
     * (items received, `ShopBonus`) and as `buy`'s result.
     */
    private fun message(mem: HgssMemory, mart: Long): String? = mem.gameString(mem.ptr(mart + K.MART_STRING))?.takeIf { it.isNotBlank() }

    /**
     * The clerk's top-screen message printer (`MartData.printerID`): true when it waits at a page break for A (the
     * purchase message "Here you are!\rThank you!" waits there while the task stays in state 12, verified live),
     * false while it prints, null once it is done.
     */
    private fun printerWaiting(mem: HgssMemory, mart: Long): Boolean? {
        val id = mem.u8(mart + K.MART_PRINTER_ID)
        val printer = mem.ptr(mem.version.textPrinterTasks + 4L * id)?.let { mem.ptr(it + A.SYSTASK_DATA) } ?: return null
        return mem.u8(printer + A.TP_STATE) in A.TEXT_PRINTER_WAIT_STATES
    }

    /**
     * Every item of the list, page by page (6 per page; the last page is padded with empty, non-selectable slots
     * because the cursor keeps its slot when the page turns), then CANCEL. Entry index = `pageOffset + slot`.
     */
    private fun grid(mem: HgssMemory, mart: Long, money: Long): Screen.Shop {
        val count = mem.u8(mart + K.MART_COUNT)
        val itemsPtr = mem.ptr(mart + K.MART_ITEMS, align = 2)
        val pageOffset = mem.u8(mart + K.MART_PAGE_OFFSET)
        val pages = ((count + K.MART_PAGE_SIZE - 1) / K.MART_PAGE_SIZE).coerceAtLeast(1)
        val slots = pages * K.MART_PAGE_SIZE
        val entries = (0 until slots).map { i ->
            val item = if (i < count && itemsPtr != null) mem.u16(itemsPtr + 2L * i) else 0
            if (item == 0) {
                Entry("slot:$i", "-", selectable = false)
            } else {
                val price = HgssItemPrices.price(item)
                Entry("item:$item", HgssData.itemName(item) + (price?.let { " ₽$it" } ?: ""), selectable = price == null || price <= money)
            }
        } + Entry("option:cancel", "CANCEL")
        val cancel = slots
        val raw = mem.s32(mart + K.MART_CURSOR)
        val cursor = if (raw == K.MART_CURSOR_CANCEL) cancel else pageOffset + raw
        return Screen.Shop(
            money = money,
            entries = entries,
            cursor = if (cursor in entries.indices) Cursor.At(cursor) else Cursor.Hidden,
            topology = precomputedTopology(entries.size) { from, button -> move(from, button, cancel, pageOffset, count) },
            cancel = CancelBehavior.CLOSES,
        )
    }

    /**
     * `ov03_02257510` + table `ov03_0225947A` (shop_menu.c:549-612): slots `0 1 / 2 3 / 4 5`, CANCEL under 5 (and
     * above 1). LEFT on the left column / RIGHT on the right column turn the page when there is one, keeping the slot.
     * CANCEL leads back into the page shown now.
     */
    private fun move(from: Int, button: Button, cancel: Int, pageOffset: Int, count: Int): Int? {
        val size = K.MART_PAGE_SIZE
        if (from == cancel) {
            return when (button) {
                Button.UP -> pageOffset + 5
                Button.DOWN -> pageOffset + 1
                else -> null
            }
        }
        val page = from / size * size
        val slot = from % size
        val left = slot % 2 == 0
        return when (button) {
            Button.UP -> if (slot == 1) cancel else page + if (slot < 2) slot + 4 else slot - 2
            Button.DOWN -> if (slot == 5) cancel else page + if (slot >= 4) slot - 4 else slot + 2
            Button.LEFT -> if (left) (from - size).takeIf { page > 0 } else from - 1
            Button.RIGHT -> if (left) from + 1 else (from + size).takeIf { page + size < count }
            else -> null
        }
    }

    private fun yesNo(mem: HgssMemory, fs: Long, mart: Long): Screen {
        val bsm = mem.ptr(fs + A.FS_BOTTOM_SCREEN_TASK)?.let { mem.ptr(it + A.SYSTASK_DATA) } ?: return Screen.Animation(AnimationKind.TRANSITION)
        if (mem.u8(bsm + A.BSM_APP_ID) != K.BOTTOM_APP_MART) return Screen.Animation(AnimationKind.TRANSITION)
        val work = mem.ptr(bsm + A.BSM_APP_TASK)?.let { mem.ptr(it + A.SYSTASK_DATA) } ?: return Screen.Animation(AnimationKind.TRANSITION)
        val prompt = mem.ptr(work + K.OV31_YES_NO) ?: return Screen.Animation(AnimationKind.TRANSITION)
        val item = mem.u16(mart + K.MART_ITEM)
        val quantity = mem.s16(mart + K.MART_QUANTITY)
        val total = mem.s32(mart + K.MART_COST).toLong() * quantity
        return HgssYesNoPrompt.screen(mem, prompt, "${HgssData.itemName(item)} ×$quantity: ₽$total, OK?")
    }
}

// endregion

// region Save prompt

/** Start menu SAVE: the bottom-screen touch save app (src/touch_save_app.c), bottom-screen app 1. */
internal object HgssTouchSave {

    fun decode(mem: HgssMemory, version: HgssKeyboardPcShopVersion, fs: Long): Screen? {
        val bsm = mem.ptr(fs + A.FS_BOTTOM_SCREEN_TASK)?.let { mem.ptr(it + A.SYSTASK_DATA) } ?: return null
        if (mem.u8(bsm + A.BSM_APP_ID) != K.BOTTOM_APP_SAVE) return null
        val task = mem.ptr(bsm + A.BSM_APP_TASK) ?: return null
        if (mem.fn(task + K.SYSTASK_FUNC) != version.fnTouchSaveApp) return null
        val app = mem.ptr(task + A.SYSTASK_DATA) ?: return null
        val question = when (mem.s32(app + K.SAVE_STATE)) {
            K.SAVE_STATE_ASK -> "Would you like to save the game?"
            K.SAVE_STATE_OVERWRITE -> "There is already a saved file. Is it OK to overwrite it?"
            // Printing, creating the buttons, saving (a long blocking frame), "saved the game": nothing to press.
            else -> return Screen.Animation(AnimationKind.TRANSITION)
        }
        val prompt = mem.ptr(app + K.SAVE_YES_NO) ?: return Screen.Animation(AnimationKind.TRANSITION)
        return HgssYesNoPrompt.screen(mem, prompt, question)
    }
}

// endregion

// region Egg hatch

/** The egg hatching scene (overlay 95), then its "nickname it?" question (decomp only: not reproduced live). */
internal object HgssHatch {

    fun decode(mem: HgssMemory, om: Long): Screen {
        val data = mem.ptr(om + A.OM_DATA) ?: return Screen.Animation(AnimationKind.EGG_HATCH)
        if (mem.s32(data + K.HATCH_STATE) != K.HATCH_STATE_NICKNAME) return Screen.Animation(AnimationKind.EGG_HATCH)
        val yesNo = mem.ptr(data + K.HATCH_YES_NO) ?: return Screen.Animation(AnimationKind.EGG_HATCH)
        // ov95_021E7450: UP selects YES, DOWN selects NO (no wrap), A confirms, B answers NO; touch rects ov95_021E7820.
        val selection = mem.u8(yesNo + K.HATCH_YN_SELECTION)
        return Screen.YesNo(
            question = "Nickname the newly hatched Pokémon?",
            entries = listOf(
                Entry("option:yes", "YES", touch = TouchPoint(127, 71)),
                Entry("option:no", "NO", touch = TouchPoint(127, 119)),
            ),
            cursor = Cursor.At(if (selection == 2) 1 else 0),
            topology = Topology.vertical(2),
            cancel = CancelBehavior.CLOSES,
        )
    }
}

// endregion
