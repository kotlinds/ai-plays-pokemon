package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.games.hgss.HgssBattleAddresses as B

/**
 * RAM layout of the battle input screens (bottom screen during a battle), HeartGold US.
 *
 * Sources: `src/battle/battle_input.c` (BattleInput, DWARF-checked offsets), the hand asm of the player's battle
 * controller (`asm/overlay_12_battle_controller_opponent.s`) and of overlay 8 (battle bag + battle party,
 * `asm/overlay_08.s`). Every value marked "verified live" was checked on a running game (design/screens/battle-menus.md).
 */
internal object HgssBattleAddresses {

    // --- Input tasks of the local human player (OpponentData.unk0[], chosen in ov12_02260EA4, line 16980) ---
    // Function addresses without the Thumb bit. One task exists per pending request: this is the authoritative
    // "what does the battle wait for" signal (curMenuId alone stays stale once the engine moved on: verified live,
    // it still says 11 = FIGHT during the whole turn and the experience gain).

    /** `ov12_0225DAD4`: command menu (FIGHT / BAG / POKéMON / RUN). Data 0x3C bytes. */
    const val FN_COMMAND = 0x0225DAD4L

    /** `ov12_0225E250`: move selection (FIGHT menu). */
    const val FN_MOVE = 0x0225E250L

    /** `ov12_0225E568`: target selection (doubles). */
    const val FN_TARGET = 0x0225E568L

    /** `ov12_0225E830`: controller side of the bag (opens overlay 8 bag, then the party grid for medicine). */
    const val FN_BAG_CONTROLLER = 0x0225E830L

    /** `ov12_0225F4E0`: controller side of the party screen (switch / forced replacement). */
    const val FN_PARTY_CONTROLLER = 0x0225F4E0L

    /** `ov12_0225FA44`: two-option prompt (yes/no, keep/forget, give up, next mon/flee, switch/keep). */
    const val FN_TWO_OPTION = 0x0225FA44L

    /** `ov12_022609F8`: host of the in-battle "forget which move" screen (party screen in learn mode). */
    const val FN_LEARN_MOVE_HOST = 0x022609F8L

    /** `ov08_02222670`: the battle bag itself (overlay 8, data = BattleBag 0x115C bytes). Verified live. */
    const val FN_BAG = 0x02222670L

    /** `ov08_0221BE98`: the battle party screen itself (overlay 8, data = BattleParty 0x2090 bytes). Verified live. */
    const val FN_PARTY = 0x0221BE98L

    // --- Command task data (ov12_0225A524 / ov12_0225DAD4) ---
    const val CMD_BATTLER = 0x09L
    const val CMD_STATE = 0x0AL
    const val CMD_STATE_INPUT = 5

    // --- Move task data (ov12_0225A604, verified live) ---
    const val MOVE_MOVES = 0x0CL             // u16[4]
    const val MOVE_PP = 0x14L                // u8[4]
    const val MOVE_PP_MAX = 0x18L            // u8[4], real max PP (GetMoveMaxPP)
    const val MOVE_BATTLER = 0x1DL
    const val MOVE_STATE = 0x20L
    const val MOVE_STATE_INPUT = 1

    /** u16 bitmask of moves the engine will refuse (StruggleCheck: no PP, Disable, Taunt, Torment, Choice...), request bytes 2..3. */
    const val MOVE_INVALID_MASK = 0x22L

    // --- Target task data (ov12_0225E568) ---
    const val TARGET_BATTLER = 0x0DL
    const val TARGET_STATE = 0x0FL
    const val TARGET_STATE_INPUT = 1

    // --- Two-option task data (ov12_0225FA44, verified live) ---
    const val TWO_BATTLER = 0x0DL
    const val TWO_STATE = 0x0EL
    const val TWO_KIND = 0x0FL
    const val TWO_MOVE = 0x18L               // u16 move of the GIVE_UP prompt (verified live)
    const val TWO_STATE_INPUT = 2

    // --- BattleInput (bs+0x19C), DWARF of battle_input.o ---
    const val BI_MENU = 0x1CL                // union menu (copied at each ChangeMenu)
    const val BI_TARGET_MONS = 0x1CL         // TargetPokemon[4] (8 bytes each, indexed by battlerId)
    const val TARGET_MON_SIZE = 8L
    const val TARGET_MON_FLAGS = 1L          // bit2: battler alive (set only when battleMons[b].hp != 0)
    const val BI_CUR_MENU_ID = 0x68BL        // s8
    const val BI_TARGET_TYPE = 0x68CL        // u8 enum BattleMenuTargetType
    const val BI_TOUCH_DISABLED = 0x68EL     // u8: 1 while the menu slides in
    const val BI_CANCEL_RUN = 0x68FL         // u8: 1 when RUN shows CANCEL (doubles right battler): B = CANCEL
    const val BI_CURSOR = 0x6DCL             // {u8 enabled; s8 y; s8 x}

    // curMenuId values (include/constants/battle_menu.h)
    val MENUS_COMMAND = setOf(1, 2, 3, 4)
    val MENUS_FIGHT_ONLY = setOf(5, 6)
    val MENUS_SAFARI = setOf(7, 8)
    val MENUS_PAL_PARK = setOf(9, 10)
    val MENUS_BUG_CONTEST = setOf(19, 20)
    val MENUS_RUN_IS_CANCEL = setOf(2, 4)
    const val MENU_FIGHT = 11
    const val MENU_TARGET = 12

    // --- BattleSystem / OpponentData ---
    const val BS_OPPONENT_DATA = 0x34L       // OpponentData *[4]
    const val OD_BATTLER_TYPE = 0x195L       // u8: 0 solo player, 2.. (slot of the touch-screen layout)
    const val BATTLE_TYPE_DOUBLES = 0x2L
    const val BATTLE_TYPE_MULTI = 0x8L

    // --- Overlay 8 sub-menu cursor (ov08_02224B64, 0x10 bytes, shared by bag and party; verified live) ---
    const val SC_TABLE = 0x04L               // DpadMenuBox *: 8-byte entries {x1, y1, x2, y2, up, down, left, right}
    const val SC_VISIBLE = 0x08L             // u8: 0 = hidden, the first key press only shows it
    const val SC_INDEX = 0x09L               // u8 current button index
    const val SC_PREVIOUS = 0x0AL            // u8 previous index (0xFF none), target of links with bit 0x80
    const val SC_MASK = 0x0CL                // u32 enabled buttons
    const val DPAD_BOX_SIZE = 8L
    const val DPAD_LINKS = 4L                // up, down, left, right
    const val LINK_PREVIOUS = 0x80

    // --- Battle bag (BattleBag T, param P; ov08_02222670 jump table at 0x02222688, verified live) ---
    const val BAG_PARAM = 0x00L
    const val BAG_STRING = 0x18L             // String * of the bag's own message box
    const val BAG_CURSOR = 0x34L
    const val BAG_ITEMS = 0x3CL              // {u16 id; u16 qty}[5][36]
    const val BAG_POCKET_STRIDE = 0x90L
    const val BAG_ITEM_SIZE = 4L
    const val BAG_STATE = 0x114AL
    const val BAG_SCREEN = 0x114CL           // 0 MENU, 1 ITEM LIST, 2 USE
    const val BAG_POCKET = 0x114DL
    const val BAG_COUNTS = 0x114FL           // u8[5]
    const val BAG_LAST_PAGE = 0x1154L        // u8[5] = page count - 1
    const val BAG_P_LAST_USED = 0x20L        // u16 last used item (0 = none: button disabled)
    const val BAG_P_POS_ON_PAGE = 0x27L      // u8[5] slot picked on the page
    const val BAG_P_PAGE = 0x2CL             // u8[5] current page
    const val BAG_STATE_MENU = 1
    const val BAG_STATE_LIST = 2
    const val BAG_STATE_USE = 3
    const val BAG_STATE_MESSAGE_PRINTING = 9
    const val BAG_STATE_MESSAGE_WAITING = 0xA
    const val BAG_SLOTS_PER_PAGE = 6
    const val BAG_MAX_ITEMS = 36             // items per pocket in BAG_ITEMS
    const val BAG_BUTTON_CANCEL_LIST = 6

    // --- Battle party (BattleParty T, BattlePartyContext P; ov08_0221BE98, verified live) ---
    const val PARTY_PARAM = 0x00L
    const val PARTY_ENTRIES = 0x04L          // display entry i at T+4+0x50*i
    const val PARTY_ENTRY_SIZE = 0x50L
    const val PE_MON = 0x00L                 // Pokemon * (into P's party)
    const val PE_SPECIES = 0x04L             // u16, 0 = empty slot
    const val PE_HP = 0x10L
    const val PE_MAX_HP = 0x12L
    const val PE_LEVEL = 0x16L               // bits 0-6
    const val PE_FLAGS = 0x17L               // bit7 egg
    const val PE_MOVES = 0x30L               // 8 bytes each: u16 id, +2 cur PP, +3 max PP
    const val PE_MOVE_SIZE = 8L
    const val PARTY_STRING = 0x1FB0L         // String * of the message box ("X is already in battle!"), found live
    const val PARTY_STATE = 0x2078L
    const val PARTY_SCREEN = 0x207AL
    const val PARTY_CURSOR = 0x2088L
    const val PP_BATTLE_SYSTEM = 0x08L
    const val PP_SELECTED = 0x11L            // display slot the screen is about (mode 3: real party slot)
    const val PP_PARTNER_SELECTED = 0x12L    // real slot the doubles partner already chose, 6 = none
    const val PP_ACTIVE = 0x14L              // real slot of the active mon
    const val PP_PARTNER_ACTIVE = 0x15L      // doubles: the other active mon
    const val PP_TRAPPED_OR_MOVE = 0x24L     // u16: modes 0/1 non-zero = can't switch out; mode 3 = move to learn
    const val PP_ORDER = 0x2CL               // u8[6] display slot -> real party slot
    const val PP_MODE = 0x35L
    const val PARTY_STATE_MESSAGE_PRINTING = 0x11
    const val PARTY_STATE_MESSAGE_WAITING = 0x12
    val PARTY_INPUT_STATES = setOf(1, 2, 3, 4, 5, 0x13, 0x14, 0x15)
}

/** Use cases of the overlay 8 party screen (`BattlePartyContext.mode`, P+0x35). */
internal enum class BattlePartyMode(val raw: Int) {
    SWITCH(0), REPLACE_FAINTED(1), USE_ITEM(2), LEARN_MOVE(3);

    companion object {
        fun of(raw: Int) = entries.firstOrNull { it.raw == raw }
    }
}

/** Screens of the overlay 8 party app (`BattlePartyScreen`, T+0x207A). */
internal enum class BattlePartyScreen(val raw: Int) {
    GRID(0), SELECT(1), SUMMARY(2), CHECK_MOVES(3), MOVE_SUMMARY(4), RESTORE_PP(5), LEARN_MOVE(6), CONFIRM_FORGET(7);

    companion object {
        fun of(raw: Int) = entries.firstOrNull { it.raw == raw }
    }
}

/** Kinds of the battle two-option prompt (the script `YesNoMenu` type, task +0x0F). */
internal enum class TwoOptionKind(val raw: Int, val top: Pair<String, String>, val bottom: Pair<String, String>) {
    YES_NO(0, "option:yes" to "YES", "option:no" to "NO"),
    FORGET_MOVE(1, "option:forget" to "FORGET A MOVE", "option:keep" to "KEEP OLD MOVES"),
    GIVE_UP_MOVE(2, "option:give_up" to "GIVE UP ON THE MOVE", "option:keep" to "DON'T GIVE UP"),
    NEXT_MON(3, "option:next" to "USE NEXT POKéMON", "option:flee" to "FLEE"),
    SWITCH_OR_KEEP(4, "option:switch" to "SWITCH", "option:keep" to "KEEP BATTLING"),
    NICKNAME(5, "option:yes" to "YES", "option:no" to "NO");

    companion object {
        fun of(raw: Int) = entries.firstOrNull { it.raw == raw }
    }
}

/** Where the running battle lives in RAM: BattleSystem, its BattleContext and (when valid) its BattleInput. */
internal class HgssBattleRoot(val bs: Long, val ctx: Long) {

    fun battleInput(mem: HgssMemory): Long? = mem.ptr(bs + A.BS_BATTLE_INPUT)

    fun battleType(mem: HgssMemory): Long = mem.u32(bs + A.BS_BATTLE_TYPE)

    /** The battle message being printed or last printed (`bs->msgBuffer`). */
    fun message(mem: HgssMemory): String? = mem.gameString(mem.ptr(bs + A.BS_MSG_BUFFER))?.trim()?.takeIf { it.isNotEmpty() }

    /** Identity of the Pokémon on the field at [battlerId] (BattleMon personality + original trainer id). */
    fun monId(mem: HgssMemory, battlerId: Int): MonId? {
        if (battlerId !in 0..3) return null
        val m = ctx + A.BC_BATTLE_MONS + battlerId * A.BM_SIZE
        if (mem.u16(m + A.BM_SPECIES) == 0) return null
        return MonId(mem.u32(m + A.BM_PERSONALITY), mem.u32(m + A.BM_OTID))
    }

    /** Name shown for the Pokémon at [battlerId] (nickname, else species). */
    fun battlerName(mem: HgssMemory, battlerId: Int): String? {
        val m = ctx + A.BC_BATTLE_MONS + battlerId * A.BM_SIZE
        val species = mem.u16(m + A.BM_SPECIES)
        if (species == 0) return null
        return HgssText.decode(mem.chars(m + A.BM_NICKNAME, 11)).takeIf { it.isNotEmpty() } ?: HgssData.speciesName(species)
    }

    companion object {
        /**
         * The battle app when its main loop runs (`OverlayManager.data` of the field's sub-application, init =
         * Battle_Init, exec state 2, proc state BSTATE_BATTLE_MAIN), like [HgssReader] finds it.
         */
        fun find(mem: HgssMemory): HgssBattleRoot? {
            val app = battleApp(mem) ?: return null
            if (mem.s32(app + A.OM_EXEC_STATE) != 2 || mem.s32(app + A.OM_PROC_STATE) != A.BATTLE_STATE_MAIN) return null
            val bs = mem.ptr(app + A.OM_DATA) ?: return null
            val ctx = mem.ptr(bs + A.BS_CTX) ?: return null
            return HgssBattleRoot(bs, ctx)
        }

        /** The battle OverlayManager (any proc state), or null when no battle app runs. */
        fun battleApp(mem: HgssMemory): Long? {
            val app = HgssScreenMemory.fieldSubApp(mem) ?: return null
            return app.takeIf { mem.fn(it + A.OM_INIT) == mem.version.fnBattleInit }
        }
    }
}

/** Small readers shared by the battle and post-battle decoders. */
internal object HgssScreenMemory {

    /** The application launched by the field (FieldSystem.unk0->unk4: battle, bag, party menu, summary...). */
    fun fieldSubApp(mem: HgssMemory): Long? {
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        val sub0 = mem.ptr(fs + A.FS_SUB0) ?: return null
        return mem.ptr(sub0 + A.FSS0_SUB_APP)
    }

    /** True while any text printer task runs (`sTextPrinterTasks`, src/text.c): a message is still being printed. */
    fun textPrinting(mem: HgssMemory): Boolean = (0 until 8).any { mem.u32(mem.version.textPrinterTasks + 4L * it) != 0L }

    /** Decodes the Pokémon structure (0xEC bytes) at [ptr]. */
    fun mon(mem: HgssMemory, ptr: Long?): HgssPokemon.Decoded? =
        ptr?.let { mem.bytes(it, A.POKEMON_SIZE.toInt()) }?.let { HgssPokemon.decode(it, HgssMonCheck::isPlausible) }

    fun HgssPokemon.Decoded.monId() = MonId(personality, otId)

    /** The name shown in game: "EGG", the nickname, or the species name. */
    fun HgssPokemon.Decoded.displayName(): String = when {
        isEgg -> "EGG"
        hasNickname -> HgssText.decode(nicknameChars).ifEmpty { HgssData.speciesName(species) }
        else -> HgssData.speciesName(species)
    }

    /** Display label of a move, e.g. `Waterfall (Water, 15/15 PP)`. */
    fun moveLabel(move: Int, pp: Int?, maxPp: Int?): String {
        val type = HgssData.moveData[move]?.type ?: "?"
        val pps = if (pp != null && maxPp != null) ", $pp/$maxPp PP" else ""
        return "${HgssData.moveName(move)} ($type$pps)"
    }
}

/**
 * The D-pad cursor of the overlay 8 sub-menus (battle bag and battle party, `ov08_02224B64..02224E20`).
 *
 * Its navigation table is read from RAM (`DpadMenuBox` of the current screen), so the topology is exactly what the
 * game does (`ov08_02224C94`): follow the link of the direction; a link with bit 0x80 goes back to the previous
 * index when there is one; a disabled target (enabled mask) is passed through in the same direction, and the move
 * is cancelled if that loops back. Everything is read at construction: the RAM buffer changes on the next frame.
 *
 * @param buttons number of buttons of the current screen (entries of its table).
 */
internal class Overlay8Cursor(mem: HgssMemory, c: Long, buttons: Int) {
    val visible = mem.u8(c + B.SC_VISIBLE) != 0
    val index = mem.u8(c + B.SC_INDEX)
    private val previous = mem.u8(c + B.SC_PREVIOUS)
    private val mask = mem.u32(c + B.SC_MASK)
    private val links: List<IntArray> = mem.u32(c + B.SC_TABLE).let { table ->
        (0 until buttons).map { b -> IntArray(4) { d -> mem.u8(table + b * B.DPAD_BOX_SIZE + B.DPAD_LINKS + d) } }
    }

    fun enabled(button: Int) = button in 0..31 && (mask shr button) and 1L == 1L

    private fun link(button: Int, direction: Int) = links.getOrNull(button)?.get(direction) ?: button

    /** The button reached from [from] with [button], or null when the cursor doesn't move. */
    fun next(from: Int, button: Button): Int? {
        val direction = when (button) {
            Button.UP -> 0
            Button.DOWN -> 1
            Button.LEFT -> 2
            Button.RIGHT -> 3
            else -> return null
        }
        var target = link(from, direction)
        if (target == 0xFF) return null
        if (target and B.LINK_PREVIOUS != 0) {
            val back = if (from == index) previous else 0xFF
            target = if (back != 0xFF) back else target and 0x7F
        }
        var guard = 0
        while (!enabled(target) && guard++ < 32) {
            val n = link(target, direction) and 0x7F
            if (n == target || n == from) return null
            target = n
        }
        return target.takeIf { it != from }
    }

    /** [Cursor] of a screen whose entries are [buttons] (button index of each entry, in order). */
    fun cursor(buttons: List<Int>): Cursor =
        if (!visible) Cursor.Hidden else buttons.indexOf(index).takeIf { it >= 0 }?.let { Cursor.At(it) } ?: Cursor.Hidden

    /** [Topology] over entries standing for [buttons]. */
    fun topology(buttons: List<Int>) = Topology { from, button ->
        buttons.getOrNull(from)?.let { next(it, button) }?.let { buttons.indexOf(it) }?.takeIf { it >= 0 }
    }
}

/**
 * A battle menu grid driven by `BattleCursor_CheckKeyInput` (src/battle/battle_input.c:4233): cells hold button ids,
 * 0xFF cells are skipped, the cursor clamps at the edges (no wrap) and a move landing on the same button id is undone
 * (merged buttons like FIGHT or CANCEL).
 *
 * @param cells button id per cell, row by row ([width] columns).
 * @param entryOf entry index of a button id (null = not an entry).
 * @param current raw (y, x) of the cursor: the starting cell for the entry under the cursor (its x matters, e.g. DOWN
 *   from FIGHT goes to BAG or POKéMON depending on where FIGHT was entered from). Other entries start at their first cell.
 * @param special overrides for a (cell, button) pair (FIGHT LEFT/RIGHT, RUN UP blocked...); return -1 to block.
 */
internal class BattleGrid(
    private val cells: IntArray,
    private val width: Int,
    private val entryOf: (Int) -> Int?,
    private val current: Pair<Int, Int>?,
    private val special: (y: Int, x: Int, button: Button) -> Pair<Int, Int>? = { _, _, _ -> null },
) {
    private val height = cells.size / width
    private fun at(y: Int, x: Int) = cells[y * width + x]

    private fun start(entry: Int): Pair<Int, Int>? {
        current?.let { (y, x) -> if (y in 0 until height && x in 0 until width && entryOf(at(y, x)) == entry) return y to x }
        for (y in 0 until height) for (x in 0 until width) if (entryOf(at(y, x)) == entry) return y to x
        return null
    }

    fun cursorEntry(): Int? = current?.let { (y, x) -> if (y in 0 until height && x in 0 until width) entryOf(at(y, x)) else null }

    val topology = Topology { from, button ->
        val (y0, x0) = start(from) ?: return@Topology null
        val (y, x) = special(y0, x0, button) ?: move(y0, x0, button) ?: return@Topology null
        if (y < 0) return@Topology null
        entryOf(at(y, x))?.takeIf { it != from }
    }

    private fun move(y0: Int, x0: Int, button: Button): Pair<Int, Int>? {
        var y = y0
        var x = x0
        when (button) {
            Button.UP -> {
                y = maxOf(y - 1, 0)
                while (at(y, x) == 0xFF) { y--; if (y < 0) { y = y0; break } }
            }
            Button.DOWN -> {
                y = minOf(y + 1, height - 1)
                while (at(y, x) == 0xFF) { y++; if (y >= height) { y = y0; break } }
            }
            Button.LEFT -> {
                x = maxOf(x - 1, 0)
                while (at(y, x) == 0xFF) { x--; if (x < 0) { x = x0; break } }
            }
            Button.RIGHT -> {
                x = minOf(x + 1, width - 1)
                while (at(y, x) == 0xFF) { x++; if (x >= width) { x = x0; break } }
            }
            else -> return null
        }
        return if (at(y, x) == at(y0, x0)) null else y to x
    }
}

/**
 * Battle input screens: command menu, move and target selection, the two-option prompts, the battle bag, the battle
 * party screen (switch, forced replacement, use item) and the in-battle "forget which move" screen
 * (design/screens/battle-menus.md).
 *
 * Detection walks the main task queue: the battle controller creates one task per pending request and the
 * overlay 8 apps are tasks too, so the running task says exactly which screen waits (no `curMenuId` heuristics).
 * While a battle runs and no input task waits, the battle message is returned (battle messages never wait for A:
 * they auto-advance, A only speeds them up, battle-menus.md §8).
 */
internal object HgssBattleScreens : HgssScreenDecoder {

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (state.mode != GameMode.BATTLE) return null
        val root = HgssBattleRoot.find(mem) ?: return null
        // A running game always has tasks; an empty queue means it couldn't be read: don't guess.
        val tasks = mem.mainTasks().takeIf { it.isNotEmpty() } ?: return null
        fun task(fn: Long) = tasks.firstOrNull { it.first == fn }?.second

        // Overlay 8 apps first: while they run, BattleInput is freed (bs+0x19C dangles, battle_022378C0.c:169-215).
        task(B.FN_PARTY)?.let { t -> return party(mem, root, t) }
        task(B.FN_BAG)?.let { t -> return bag(mem, root, t) }
        if (task(B.FN_BAG_CONTROLLER) != null || task(B.FN_PARTY_CONTROLLER) != null || task(B.FN_LEARN_MOVE_HOST) != null) {
            return Screen.Battle(Awaiting.ANIMATION) // overlay swap / fade between the battle menu and the sub-screen
        }
        task(B.FN_TWO_OPTION)?.let { t -> twoOption(mem, root, t)?.let { return it } }
        task(B.FN_TARGET)?.let { t -> target(mem, root, t)?.let { return it } }
        task(B.FN_MOVE)?.let { t -> moveSelect(mem, root, t)?.let { return it } }
        task(B.FN_COMMAND)?.let { t -> command(mem, root, t)?.let { return it } }
        // The naming screen after a capture is a nested app run by the catch task: the keyboard decoder handles it.
        if (HgssPostBattleScreens.catchNamingRunning(mem, root)) return null
        return message(mem, root)
    }

    /** No decision to make: the battle message (printing, or held a few frames before going on). */
    fun message(mem: HgssMemory, root: HgssBattleRoot): Screen {
        val printing = HgssScreenMemory.textPrinting(mem)
        return root.message(mem)?.let { Screen.Dialogue(TextSource.BATTLE, null, it, if (printing) Awaiting.TEXT_PRINTING else Awaiting.ANIMATION) }
            ?: Screen.Battle(if (printing) Awaiting.TEXT_PRINTING else Awaiting.ANIMATION)
    }

    // region Command menu

    /** Entry ids of the command menu, in entry order (FIGHT on top; BAG, RUN, POKéMON below). */
    private val COMMAND_IDS = listOf("option:fight", "option:bag", "option:run", "option:pokemon")

    /**
     * Command menu (battle-menus.md §1): task state 5 = polling input, menu up and not sliding in.
     * Grid `sCursorArrayMainMenu` = [FIGHT FIGHT FIGHT] [BAG RUN POKéMON]; extra moves in
     * `BattleInput_CursorMove_MainMenu` (battle_input.c:3704): from FIGHT, LEFT/RIGHT jump to BAG/POKéMON, and UP
     * from RUN is blocked. All verified live.
     */
    private fun command(mem: HgssMemory, root: HgssBattleRoot, t: Long): Screen? {
        val bi = root.battleInput(mem) ?: return null
        val menuId = mem.s8(bi + B.BI_CUR_MENU_ID)
        if (mem.u8(t + B.CMD_STATE) != B.CMD_STATE_INPUT || mem.u8(bi + B.BI_TOUCH_DISABLED) != 0) return message(mem, root)
        val actor = HgssStatuses.battlerRef(mem.u8(t + B.CMD_BATTLER))
        val cursorRaw = if (mem.u8(bi + B.BI_CURSOR) != 0) mem.s8(bi + B.BI_CURSOR + 1) to mem.s8(bi + B.BI_CURSOR + 2) else null
        val runIsCancel = menuId in B.MENUS_RUN_IS_CANCEL || mem.u8(bi + B.BI_CANCEL_RUN) == 1
        val entries: List<Entry> = when (menuId) {
            in B.MENUS_COMMAND -> listOf(
                Entry(COMMAND_IDS[0], "FIGHT"), Entry(COMMAND_IDS[1], "BAG"),
                if (runIsCancel) Entry("option:cancel", "CANCEL") else Entry(COMMAND_IDS[2], "RUN"),
                Entry(COMMAND_IDS[3], "POKéMON"),
            )
            in B.MENUS_SAFARI -> listOf(
                Entry("option:ball", "BALL"), Entry("option:bait", "BAIT"), Entry("option:run", "RUN"), Entry("option:mud", "MUD"),
            )
            in B.MENUS_BUG_CONTEST -> listOf(
                Entry(COMMAND_IDS[0], "FIGHT"), Entry("option:ball", "BALL"), Entry(COMMAND_IDS[2], "RUN"), Entry(COMMAND_IDS[3], "POKéMON"),
            )
            in B.MENUS_FIGHT_ONLY -> listOf(Entry(COMMAND_IDS[0], "FIGHT"))
            in B.MENUS_PAL_PARK -> listOf(Entry("option:ball", "BALL"), Entry("option:run", "RUN"))
            else -> return message(mem, root)
        }
        val cancel = if (runIsCancel) CancelBehavior.CLOSES else CancelBehavior.NONE
        if (entries.size == 1) {
            return Screen.BattleCommand(actor, entries, cursorRaw?.let { Cursor.At(0) } ?: Cursor.Hidden, Topology.vertical(1), cancel)
        }
        if (menuId in B.MENUS_PAL_PARK) {
            // sCursorArrayPalParkMenu[2][1]: BALL above RUN.
            val grid = BattleGrid(intArrayOf(0, 1), 1, { it }, cursorRaw)
            return Screen.BattleCommand(actor, entries, cursorRaw?.let { grid.cursorEntry() }?.let { Cursor.At(it) } ?: Cursor.Hidden, grid.topology, cancel)
        }
        // Button ids of sCursorArrayMainMenu: 0 FIGHT, 1 BAG, 2 POKéMON, 3 RUN -> entries FIGHT 0, BAG 1, RUN 2, POKéMON 3.
        val entryOfButton = mapOf(0 to 0, 1 to 1, 3 to 2, 2 to 3)
        val grid = BattleGrid(intArrayOf(0, 0, 0, 1, 3, 2), 3, { entryOfButton[it] }, cursorRaw) { y, x, button ->
            when {
                y == 0 && button == Button.LEFT -> 1 to 0
                y == 0 && button == Button.RIGHT -> 1 to 2
                y == 1 && x == 1 && button == Button.UP -> -1 to -1
                else -> null
            }
        }
        val topology = grid.topology
        val cursor = cursorRaw?.let { grid.cursorEntry() }?.let { Cursor.At(it) } ?: Cursor.Hidden
        return Screen.BattleCommand(actor, entries, cursor, topology, cancel)
    }

    // endregion

    // region Move and target selection

    /**
     * Move selection (§2): moves from the task data (filled before the menu is drawn), grid
     * `sCursorArrayFightMenu` = [M1 M2] [M3 M4] [CANCEL CANCEL]. Empty slots are reachable but ignored by A, and moves
     * the engine refuses (no PP, Disable, Taunt, Torment, Imprison, Choice item: StruggleCheck mask) are not
     * selectable. B = CANCEL (back to the command menu). Verified live (UP from CANCEL returns to the current column).
     */
    private fun moveSelect(mem: HgssMemory, root: HgssBattleRoot, t: Long): Screen? {
        val bi = root.battleInput(mem) ?: return null
        if (mem.u8(t + B.MOVE_STATE) != B.MOVE_STATE_INPUT || mem.s8(bi + B.BI_CUR_MENU_ID) != B.MENU_FIGHT) return message(mem, root)
        if (mem.u8(bi + B.BI_TOUCH_DISABLED) != 0) return message(mem, root)
        val invalid = mem.u16(t + B.MOVE_INVALID_MASK)
        val entries = (0 until 4).map { i ->
            val move = mem.u16(t + B.MOVE_MOVES + 2L * i)
            if (move == 0) {
                Entry("slot:$i", "-", selectable = false)
            } else {
                val pp = mem.u8(t + B.MOVE_PP + i)
                Entry("move:$move", HgssScreenMemory.moveLabel(move, pp, mem.u8(t + B.MOVE_PP_MAX + i)), selectable = pp > 0 && (invalid shr i) and 1 == 0)
            }
        } + Entry("option:cancel", "CANCEL")
        val cursorRaw = if (mem.u8(bi + B.BI_CURSOR) != 0) mem.s8(bi + B.BI_CURSOR + 1) to mem.s8(bi + B.BI_CURSOR + 2) else null
        // Button ids: 0 CANCEL, 1..4 moves -> entries 0..3 moves, 4 CANCEL.
        val grid = BattleGrid(intArrayOf(1, 2, 3, 4, 0, 0), 2, { if (it == 0) 4 else it - 1 }, cursorRaw)
        return Screen.MoveSelect(
            context = MoveContext.BATTLE,
            mon = root.monId(mem, mem.u8(t + B.MOVE_BATTLER)),
            newMove = null,
            entries = entries,
            cursor = cursorRaw?.let { grid.cursorEntry() }?.let { Cursor.At(it) } ?: Cursor.Hidden,
            topology = grid.topology,
            cancel = CancelBehavior.CLOSES,
        )
    }

    /** `sMoveRangeHitMons[type]` (battle_input.c:1174), slots in order PLAYER_LEFT, OPP_RIGHT, PLAYER_RIGHT, OPP_LEFT. */
    private val MOVE_RANGE_HIT = listOf(
        "1111", "0101", "0111", "1111", "1000", "1010", "0010", "1101", "0111", "1101", "1010", "0101",
    ).map { s -> s.map { it == '1' } }

    /** Target types that hit every target at once (`TARGET_ALL_*`): one big cursor + CANCEL. */
    private val SPREAD_TARGET_TYPES = setOf(1, 2, 3, 5, 7)

    /** Touch-screen slot (0 PL, 1 OR, 2 PR, 3 OL) -> entry id. */
    private val SLOT_REFS = listOf(BattlerRef.PLAYER_LEFT, BattlerRef.FOE_RIGHT, BattlerRef.PLAYER_RIGHT, BattlerRef.FOE_LEFT)

    /**
     * Target selection in doubles (§3, `BattleInput_CursorMove_TargetMenu` battle_input.c:3915). Entries: foe_left,
     * foe_right, ally_left, ally_right, cancel (grid [OL OR] [PL PR] [CANCEL]); cells out of the move's range are skipped,
     * fainted battlers in range are reachable but refused. Spread moves show one "all targets" entry above CANCEL.
     * Verified live (Route 37 twins): the entry names match the battle state's [BattlerRef]s and the screen layout.
     */
    private fun target(mem: HgssMemory, root: HgssBattleRoot, t: Long): Screen? {
        val bi = root.battleInput(mem) ?: return null
        if (mem.u8(t + B.TARGET_STATE) != B.TARGET_STATE_INPUT || mem.s8(bi + B.BI_CUR_MENU_ID) != B.MENU_TARGET) return message(mem, root)
        if (mem.u8(bi + B.BI_TOUCH_DISABLED) != 0) return message(mem, root)
        val type = mem.u8(bi + B.BI_TARGET_TYPE)
        val range = MOVE_RANGE_HIT.getOrNull(type) ?: MOVE_RANGE_HIT[0]
        // Slot i is the battler whose OpponentData battlerType == 2 + i (ov12_0223C1A0).
        val battlerOfSlot = (0 until 4).map { slot ->
            (0 until 4).firstOrNull { b -> mem.ptr(root.bs + B.BS_OPPONENT_DATA + 4L * b)?.let { mem.u8(it + B.OD_BATTLER_TYPE) } == 2 + slot }
        }
        fun alive(slot: Int) = battlerOfSlot[slot]?.let { b -> mem.u8(bi + B.BI_TARGET_MONS + b * B.TARGET_MON_SIZE + B.TARGET_MON_FLAGS) and 0x4 != 0 } ?: false
        fun label(slot: Int) = battlerOfSlot[slot]?.let { root.battlerName(mem, it) } ?: "-"
        val cursorRaw = if (mem.u8(bi + B.BI_CURSOR) != 0) mem.s8(bi + B.BI_CURSOR + 1) to mem.s8(bi + B.BI_CURSOR + 2) else null
        if (type in SPREAD_TARGET_TYPES) {
            val hit = (0 until 4).filter { range[it] && alive(it) }
            val entries = listOf(
                Entry("target:all", hit.joinToString(" + ") { label(it) }.ifEmpty { "-" }, selectable = hit.isNotEmpty()),
                Entry("option:cancel", "CANCEL"),
            )
            val cursor = cursorRaw?.let { (y, _) -> Cursor.At(if (y > 0) 1 else 0) } ?: Cursor.Hidden
            return Screen.TargetSelect(entries, cursor, Topology.vertical(2))
        }
        // Entry order = reading order of the grid: OL, OR, PL, PR, CANCEL. Cell values are touch slots (4 = CANCEL).
        val cellSlots = intArrayOf(3, 1, 0, 2)
        val entries = cellSlots.map { slot ->
            Entry(SLOT_REFS[slot].wire, label(slot), selectable = range[slot] && alive(slot))
        } + Entry("option:cancel", "CANCEL")
        val cells = IntArray(6) { i -> if (i >= 4) 4 else cellSlots[i].takeIf { range[it] } ?: 0xFF }
        val grid = BattleGrid(cells, 2, { slot -> if (slot == 4) 4 else cellSlots.indexOf(slot).takeIf { it >= 0 } }, cursorRaw)
        return Screen.TargetSelect(entries, cursorRaw?.let { grid.cursorEntry() }?.let { Cursor.At(it) } ?: Cursor.Hidden, grid.topology)
    }

    // endregion

    // region Two-option prompts

    /**
     * Two-option prompts (§4): task state 2 = polling. One column of two buttons, the cursor starts on the top one and
     * is hidden until the first key unless the previous choice was made with keys (then A at once confirms the top
     * option: the "AAAA nickname" trap, verified live). With the cursor shown, B picks the bottom option directly.
     */
    private fun twoOption(mem: HgssMemory, root: HgssBattleRoot, t: Long): Screen? {
        if (mem.u8(t + B.TWO_STATE) != B.TWO_STATE_INPUT) return message(mem, root)
        val bi = root.battleInput(mem) ?: return null
        if (mem.s8(bi + B.BI_CUR_MENU_ID) !in 13..17 || mem.u8(bi + B.BI_TOUCH_DISABLED) != 0) return message(mem, root)
        val kind = TwoOptionKind.of(mem.u8(t + B.TWO_KIND)) ?: return Screen.Unknown("battle two-option prompt ${mem.u8(t + B.TWO_KIND)}", Awaiting.INPUT)
        val cursor = if (mem.u8(bi + B.BI_CURSOR) != 0) Cursor.At(mem.s8(bi + B.BI_CURSOR + 1).coerceIn(0, 1)) else Cursor.Hidden
        val move = mem.u16(t + B.TWO_MOVE).takeIf { kind == TwoOptionKind.GIVE_UP_MOVE && it != 0 }?.let { HgssData.moveName(it) }
        val entries = if (move != null) {
            listOf(Entry(kind.top.first, "GIVE UP ON $move"), Entry(kind.bottom.first, "DON'T GIVE UP ON $move"))
        } else {
            listOf(Entry(kind.top.first, kind.top.second), Entry(kind.bottom.first, kind.bottom.second))
        }
        return when (kind) {
            TwoOptionKind.SWITCH_OR_KEEP ->
                Screen.ListMenu(MenuKind.BATTLE_SWITCH_OR_KEEP, entries, cursor, Topology.vertical(2), CancelBehavior.CONFIRMS_LAST)
            TwoOptionKind.FORGET_MOVE, TwoOptionKind.GIVE_UP_MOVE -> Screen.YesNo(
                root.message(mem), entries, cursor, Topology.vertical(2), CancelBehavior.CONFIRMS_LAST,
                HgssPostBattleScreens.moveToLearn(mem),
            )
            else -> Screen.YesNo(root.message(mem), entries, cursor, Topology.vertical(2), CancelBehavior.CONFIRMS_LAST)
        }
    }

    // endregion

    // region Battle bag

    /** Battle pockets (`T+0x114D`, msg bank 5), in index order; the 5th array slot is unused. */
    private val POCKETS = listOf(
        "hp_pp_restore" to "HP/PP RESTORE", "status_healers" to "STATUS HEALERS",
        "poke_balls" to "POKé BALLS", "battle_items" to "BATTLE ITEMS",
    )

    /**
     * The battle bag (§5): MENU (4 pockets, LAST USED ITEM, CANCEL), ITEM LIST (6 slots per page + CANCEL), USE
     * (USE / CANCEL), and its own message box. The item list exposes every page: entry `page * 6 + slot`, CANCEL last;
     * LEFT on the left column / RIGHT on the right column turn the page (wrapping) when the pocket has several pages.
     */
    private fun bag(mem: HgssMemory, root: HgssBattleRoot, t: Long): Screen {
        val p = mem.ptr(t + B.BAG_PARAM) ?: return Screen.Battle(Awaiting.ANIMATION)
        val c = mem.ptr(t + B.BAG_CURSOR) ?: return Screen.Battle(Awaiting.ANIMATION)
        val pocket = mem.u8(t + B.BAG_POCKET).coerceIn(0, POCKETS.size - 1)
        val pocketNames = POCKETS.map { it.second }
        // Buttons per screen: MENU 6, ITEM LIST 7 (slots + CANCEL; prev / next page are touch only), USE 2.
        val cursor = Overlay8Cursor(mem, c, buttons = 7)
        fun item(pocketIndex: Int, i: Int): Pair<Int, Int> {
            val a = t + B.BAG_ITEMS + pocketIndex * B.BAG_POCKET_STRIDE + i * B.BAG_ITEM_SIZE
            return mem.u16(a) to mem.u16(a + 2)
        }
        // Every pocket is filled when the bag opens (ov08): which pocket holds an item is known from the menu on.
        fun contents(): Map<String, List<ItemId>> = POCKETS.withIndex().associate { (index, pocketId) ->
            val count = mem.u8(t + B.BAG_COUNTS + index).coerceAtMost(B.BAG_MAX_ITEMS)
            "pocket:${pocketId.first}" to (0 until count).map { item(index, it) }.filter { (id, qty) -> id != 0 && qty != 0 }.map { ItemId(it.first) }
        }
        return when (mem.u8(t + B.BAG_STATE)) {
            B.BAG_STATE_MESSAGE_PRINTING, B.BAG_STATE_MESSAGE_WAITING -> Screen.Dialogue(
                TextSource.BATTLE, null, mem.gameString(mem.ptr(t + B.BAG_STRING)) ?: "",
                if (mem.u8(t + B.BAG_STATE) == B.BAG_STATE_MESSAGE_WAITING) Awaiting.INPUT else Awaiting.TEXT_PRINTING,
            )
            B.BAG_STATE_MENU -> {
                val lastUsed = mem.u16(p + B.BAG_P_LAST_USED)
                val entries = POCKETS.map { (id, label) -> Entry("pocket:$id", label) } + listOf(
                    Entry("option:last_used", if (lastUsed != 0) "LAST USED: ${HgssData.itemName(lastUsed)}" else "LAST USED ITEM", selectable = lastUsed != 0),
                    Entry("option:cancel", "CANCEL"),
                )
                // Buttons: 0 HP/PP, 1 STATUS, 2 BALLS, 3 BATTLE ITEMS, 4 LAST USED, 5 CANCEL (ov08_02225B4C / _02225D44).
                val buttons = listOf(0, 1, 2, 3, 4, 5)
                Screen.Bag(pocketNames[pocket], pocketNames, 0, 1, true, entries, cursor.cursor(buttons), cursor.topology(buttons), pocketContents = contents())
            }
            B.BAG_STATE_LIST -> {
                val count = mem.u8(t + B.BAG_COUNTS + pocket)
                val pages = mem.u8(t + B.BAG_LAST_PAGE + pocket) + 1
                val page = mem.u8(p + B.BAG_P_PAGE + pocket).coerceIn(0, pages - 1)
                val slots = pages * B.BAG_SLOTS_PER_PAGE
                val entries = (0 until slots).map { i ->
                    val (id, qty) = if (i < count) item(pocket, i) else 0 to 0
                    if (id == 0 || qty == 0) Entry("slot:$i", "-", selectable = false)
                    else Entry("item:$id", "${HgssData.itemName(id)} x$qty")
                } + Entry("option:cancel", "CANCEL")
                val cancelEntry = slots
                val raw = cursor.index
                val current = when {
                    !cursor.visible -> Cursor.Hidden
                    raw == B.BAG_BUTTON_CANCEL_LIST -> Cursor.At(cancelEntry)
                    raw in 0 until B.BAG_SLOTS_PER_PAGE -> Cursor.At(page * B.BAG_SLOTS_PER_PAGE + raw)
                    else -> Cursor.Hidden
                }
                val topology = Topology { from, button ->
                    val fromPage = if (from == cancelEntry) page else from / B.BAG_SLOTS_PER_PAGE
                    val fromButton = if (from == cancelEntry) B.BAG_BUTTON_CANCEL_LIST else from % B.BAG_SLOTS_PER_PAGE
                    val moved = cursor.next(fromButton, button)
                    when {
                        moved != null -> if (moved == B.BAG_BUTTON_CANCEL_LIST) cancelEntry else fromPage * B.BAG_SLOTS_PER_PAGE + moved
                        // ov08_02222918: LEFT on the left column / RIGHT on the right column turn the page (wrapping).
                        pages > 1 && fromButton < B.BAG_SLOTS_PER_PAGE && button == Button.LEFT && fromButton % 2 == 0 ->
                            ((fromPage - 1 + pages) % pages) * B.BAG_SLOTS_PER_PAGE + fromButton
                        pages > 1 && fromButton < B.BAG_SLOTS_PER_PAGE && button == Button.RIGHT && fromButton % 2 == 1 ->
                            ((fromPage + 1) % pages) * B.BAG_SLOTS_PER_PAGE + fromButton
                        else -> null
                    }
                }
                Screen.Bag(pocketNames[pocket], pocketNames, page, pages, true, entries, current, topology, pocketContents = contents())
            }
            B.BAG_STATE_USE -> {
                val pages = mem.u8(t + B.BAG_LAST_PAGE + pocket) + 1
                val page = mem.u8(p + B.BAG_P_PAGE + pocket).coerceIn(0, pages - 1)
                val (id, _) = item(pocket, page * B.BAG_SLOTS_PER_PAGE + mem.u8(p + B.BAG_P_POS_ON_PAGE + pocket))
                val entries = listOf(
                    Entry("option:use", if (id != 0) "USE ${HgssData.itemName(id)}" else "USE"),
                    Entry("option:cancel", "CANCEL"),
                )
                // Buttons: 0 USE, 1 CANCEL (ov08_02225ADC / _02225D04). B = CANCEL -> back to the item list.
                Screen.ContextMenu(null, entries, cursor.cursor(listOf(0, 1)), cursor.topology(listOf(0, 1)))
            }
            else -> Screen.Battle(Awaiting.ANIMATION) // opening, button animation, page change, closing
        }
    }

    // endregion

    // region Battle party (switch, forced replacement, use item, forget a move)

    /** The overlay 8 party screen (§6-7). */
    private fun party(mem: HgssMemory, root: HgssBattleRoot, t: Long): Screen {
        val p = mem.ptr(t + B.PARTY_PARAM) ?: return Screen.Battle(Awaiting.ANIMATION)
        val c = mem.ptr(t + B.PARTY_CURSOR) ?: return Screen.Battle(Awaiting.ANIMATION)
        val mode = BattlePartyMode.of(mem.u8(p + B.PP_MODE)) ?: return Screen.Unknown("battle party mode ${mem.u8(p + B.PP_MODE)}", Awaiting.INPUT)
        // At most 8 buttons per screen (CHECK MOVES: 4 moves, prev, next, SUMMARY, CANCEL).
        val cursor = Overlay8Cursor(mem, c, buttons = 8)
        val stateRaw = mem.u8(t + B.PARTY_STATE)
        if (stateRaw == B.PARTY_STATE_MESSAGE_PRINTING || stateRaw == B.PARTY_STATE_MESSAGE_WAITING) {
            val text = mem.gameString(mem.ptr(t + B.PARTY_STRING)) ?: ""
            return Screen.Dialogue(TextSource.BATTLE, null, text, if (stateRaw == B.PARTY_STATE_MESSAGE_WAITING) Awaiting.INPUT else Awaiting.TEXT_PRINTING)
        }
        if (stateRaw !in B.PARTY_INPUT_STATES) return Screen.Battle(Awaiting.ANIMATION)
        val view = BattlePartyView(mem, root, t, p, mode)
        return when (BattlePartyScreen.of(mem.u8(t + B.PARTY_SCREEN))) {
            BattlePartyScreen.GRID -> view.grid(cursor)
            BattlePartyScreen.SELECT -> view.select(cursor)
            BattlePartyScreen.SUMMARY -> view.pageMenu(cursor, SUMMARY_BUTTONS)
            BattlePartyScreen.CHECK_MOVES -> view.pageMenu(cursor, view.checkMovesButtons())
            BattlePartyScreen.RESTORE_PP -> view.restorePp(cursor)
            BattlePartyScreen.LEARN_MOVE -> view.learnMove(cursor)
            BattlePartyScreen.CONFIRM_FORGET -> view.confirmForget(cursor)
            BattlePartyScreen.MOVE_SUMMARY, null -> Screen.Unknown("battle party screen ${mem.u8(t + B.PARTY_SCREEN)}", Awaiting.INPUT)
        }
    }

    /** SUMMARY page buttons (ov08_02224E68): prev mon, next mon, CHECK MOVES, CANCEL. */
    private val SUMMARY_BUTTONS = listOf(0 to Entry("option:prev_mon", "PREVIOUS"), 1 to Entry("option:next_mon", "NEXT"), 2 to Entry("option:check_moves", "CHECK MOVES"), 3 to Entry("option:cancel", "CANCEL"))

    /** Reads one overlay 8 party screen: display entries `E(i)`, the context `P` and the mode. */
    private class BattlePartyView(val mem: HgssMemory, val root: HgssBattleRoot, val t: Long, val p: Long, val mode: BattlePartyMode) {
        fun entry(i: Int) = t + B.PARTY_ENTRIES + i * B.PARTY_ENTRY_SIZE
        fun species(i: Int) = mem.u16(entry(i) + B.PE_SPECIES)
        fun hp(i: Int) = mem.u16(entry(i) + B.PE_HP)
        fun isEgg(i: Int) = mem.u8(entry(i) + B.PE_FLAGS) and 0x80 != 0
        fun mon(i: Int) = HgssScreenMemory.mon(mem, mem.ptr(entry(i) + B.PE_MON))
        fun realSlot(i: Int) = mem.u8(p + B.PP_ORDER + i)
        val doubles get() = root.battleType(mem) and B.BATTLE_TYPE_DOUBLES != 0L

        fun monId(i: Int): MonId? = with(HgssScreenMemory) { mon(i)?.monId() }

        fun label(i: Int): String {
            val name = with(HgssScreenMemory) { mon(i)?.displayName() } ?: HgssData.speciesName(species(i))
            if (isEgg(i)) return "EGG"
            val level = mem.u8(entry(i) + B.PE_LEVEL) and 0x7F
            return "$name Lv$level ${hp(i)}/${mem.u16(entry(i) + B.PE_MAX_HP)}" + if (hp(i) == 0) " FAINTED" else ""
        }

        /**
         * Can display slot [i] be sent out? The game opens the submenu for any mon, then SHIFT refuses
         * (ov08_0221D91C, in this order): fainted, already in battle, egg, already chosen by the partner, trapped.
         */
        fun canSwitchIn(i: Int): Boolean {
            if (species(i) == 0 || hp(i) == 0 || isEgg(i)) return false
            val real = realSlot(i)
            if (real == mem.u8(p + B.PP_ACTIVE)) return false
            if (doubles && real == mem.u8(p + B.PP_PARTNER_ACTIVE)) return false
            val partnerChoice = mem.u8(p + B.PP_PARTNER_SELECTED)
            if (partnerChoice != 6 && real == partnerChoice) return false
            return mem.u16(p + B.PP_TRAPPED_OR_MOVE) == 0
        }

        fun grid(cursor: Overlay8Cursor): Screen {
            val entries = (0 until 6).map { i ->
                val id = monId(i)
                when {
                    species(i) == 0 || id == null -> Entry("slot:$i", "-", selectable = false)
                    mode == BattlePartyMode.USE_ITEM -> Entry(id.toString(), label(i), selectable = !isEgg(i))
                    else -> Entry(id.toString(), label(i), selectable = canSwitchIn(i))
                }
            } + Entry("option:cancel", "CANCEL", selectable = mode != BattlePartyMode.REPLACE_FAINTED)
            val buttons = (0..6).toList()
            return Screen.PartyGrid(
                purpose = when (mode) {
                    BattlePartyMode.SWITCH -> PartyPurpose.BATTLE_SWITCH
                    BattlePartyMode.REPLACE_FAINTED -> PartyPurpose.BATTLE_REPLACE_FAINTED
                    BattlePartyMode.USE_ITEM -> PartyPurpose.BATTLE_USE_ITEM
                    BattlePartyMode.LEARN_MOVE -> PartyPurpose.OTHER
                },
                entries = entries,
                cursor = cursor.cursor(buttons),
                topology = cursor.topology(buttons),
                // Forced replacement: B and CANCEL are silently ignored (ov08_0221C14C).
                cancel = if (mode == BattlePartyMode.REPLACE_FAINTED) CancelBehavior.NONE else CancelBehavior.CLOSES,
            )
        }

        /** SELECT submenu (ov08_02224E54): SHIFT / SUMMARY / CHECK MOVES / CANCEL for display slot P+0x11. */
        fun select(cursor: Overlay8Cursor): Screen {
            val i = mem.u8(p + B.PP_SELECTED).coerceIn(0, 5)
            val entries = listOf(
                Entry("option:shift", "SHIFT", selectable = canSwitchIn(i)),
                Entry("option:summary", "SUMMARY", selectable = !isEgg(i)),
                Entry("option:check_moves", "CHECK MOVES", selectable = !isEgg(i)),
                Entry("option:cancel", "CANCEL"),
            )
            val buttons = listOf(0, 1, 2, 3)
            return Screen.ContextMenu(monId(i), entries, cursor.cursor(buttons), cursor.topology(buttons))
        }

        /** CHECK MOVES page (ov08_02224F5C): 4 moves, prev, next, SUMMARY, CANCEL. */
        fun checkMovesButtons(): List<Pair<Int, Entry>> {
            val i = mem.u8(p + B.PP_SELECTED).coerceIn(0, 5)
            return (0 until 4).map { m -> m to moveEntry(i, m) } + listOf(
                4 to Entry("option:prev_mon", "PREVIOUS"), 5 to Entry("option:next_mon", "NEXT"),
                6 to Entry("option:summary", "SUMMARY"), 7 to Entry("option:cancel", "CANCEL"),
            )
        }

        fun pageMenu(cursor: Overlay8Cursor, buttons: List<Pair<Int, Entry>>): Screen {
            val ids = buttons.map { it.first }
            return Screen.ListMenu(MenuKind.OTHER, buttons.map { it.second }, cursor.cursor(ids), cursor.topology(ids), CancelBehavior.CLOSES)
        }

        fun moveEntry(i: Int, m: Int): Entry {
            val a = entry(i) + B.PE_MOVES + m * B.PE_MOVE_SIZE
            val move = mem.u16(a)
            return if (move == 0) Entry("slot:$m", "-", selectable = false)
            else Entry("move:$move", HgssScreenMemory.moveLabel(move, mem.u8(a + 2), mem.u8(a + 3)))
        }

        /** RESTORE PP (ov08_02224E94): "Restore which move?" for an Ether-type item; 4 moves + CANCEL. */
        fun restorePp(cursor: Overlay8Cursor): Screen {
            val i = mem.u8(p + B.PP_SELECTED).coerceIn(0, 5)
            return pageMenu(cursor, (0 until 4).map { m -> m to moveEntry(i, m) } + (4 to Entry("option:cancel", "CANCEL")))
        }

        /**
         * "Which move should be forgotten?" (mode 3, ov08_02224F3C): the 4 known moves, the new move, CANCEL; the
         * contest button (5) is masked out (mask 0x5F, verified live). A on a move (or the new one) opens the
         * confirmation; B / CANCEL = don't learn. The screen is about the real party slot P+0x11.
         */
        fun learnMove(cursor: Overlay8Cursor): Screen {
            val i = mem.u8(p + B.PP_SELECTED).coerceIn(0, 5)
            val newMove = mem.u16(p + B.PP_TRAPPED_OR_MOVE)
            val buttons = listOf(0, 1, 2, 3, 4, 6)
            // HM moves can't be forgotten here ("HM moves can't be forgotten now"): A on FORGET does nothing.
            val entries = (0 until 4).map { m -> moveEntry(i, m).let { e -> if (HgssMachines.isHm(e.id.removePrefix("move:").toIntOrNull() ?: 0)) e.copy(selectable = false) else e } } + listOf(
                Entry("move:$newMove", HgssScreenMemory.moveLabel(newMove, null, null) + " (new: don't learn it)"),
                Entry("option:cancel", "CANCEL"),
            )
            return Screen.MoveSelect(
                MoveContext.FORGET_IN_BATTLE, monId(i), Named(MoveId(newMove), HgssData.moveName(newMove)),
                entries, cursor.cursor(buttons), cursor.topology(buttons), CancelBehavior.CLOSES,
            )
        }

        /** CONFIRM FORGET (ov08_02224E44): FORGET / CANCEL (contest button masked, mask 0x05, verified live). */
        fun confirmForget(cursor: Overlay8Cursor): Screen {
            val i = mem.u8(p + B.PP_SELECTED).coerceIn(0, 5)
            val slot = mem.u8(p + SELECTED_MOVE_SLOT)
            val move = if (slot in 0..3) mem.u16(entry(i) + B.PE_MOVES + slot * B.PE_MOVE_SIZE) else mem.u16(p + B.PP_TRAPPED_OR_MOVE)
            val buttons = listOf(0, 2)
            val entries = listOf(
                Entry("option:forget", "FORGET ${HgssData.moveName(move)}", selectable = !HgssMachines.isHm(move)),
                Entry("option:cancel", "CANCEL"),
            )
            return Screen.ContextMenu(monId(i), entries, cursor.cursor(buttons), cursor.topology(buttons))
        }

        private companion object {
            /** u8 P+0x34 selectedMoveSlot: 0-3 = the move to forget, 4 = the new move. */
            const val SELECTED_MOVE_SLOT = 0x34L
        }
    }

    // endregion
}
