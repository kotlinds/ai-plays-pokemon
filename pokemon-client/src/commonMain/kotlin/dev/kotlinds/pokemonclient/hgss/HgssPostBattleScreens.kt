package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.data.MachineId
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.hgss.HgssPostBattleAddresses as P
import dev.kotlinds.pokemonclient.hgss.HgssScreenMemory.displayName
import dev.kotlinds.pokemonclient.hgss.HgssScreenMemory.monId

/**
 * RAM layout of the screens around battles and level ups (design/screens/post-battle.md), HeartGold US.
 * Sources: src/battle/battle_command.c (Task_GetExp / Task_GetPokemon), asm/unk_020755E8.s (evolution),
 * asm/unk_02088288.s (summary screen), src/party_menu.c (TM party grid).
 */
internal object HgssPostBattleAddresses {

    // --- Battle tasks driven by the battle script (getterWork, include/battle/battle.h:637) ---
    /** `Task_GetExp` (overlay 12): experience gauge, level-up panels, "wants to learn" flow. Verified live. */
    const val FN_GET_EXP = 0x02245898L

    /** `Task_GetPokemon` (overlay 12): ball throw, shakes, Pokédex page, nickname prompt. Verified live. */
    const val FN_GET_POKEMON = 0x022465A8L

    /** `ctx->getterWork` (GetterWork *, NULL when no task). */
    const val BC_GETTER_WORK = 0x178L

    /**
     * `ctx->prevLevelStats` (u32[6]), valid while the level-up panel shows. Verified live: already in panel order
     * (max HP, attack, defense, sp. atk, sp. def, speed), not in the PartyPokemon order.
     */
    const val BC_PREV_LEVEL_STATS = 0x17CL

    const val GW_STATE = 0x28L
    const val GW_SHAKES = 0x38L              // tempData[2]: 0..3 breaks free after n shakes, 4 = caught
    const val GW_PARTY_SLOT = 0x48L          // tempData[6]: party slot of the Pokémon gaining the level
    const val GW_POINTER_0 = 0x50L           // tempPointers[0]: Pokédex page work / naming screen OverlayManager

    /** `STATE_GET_EXP_LEVEL_UP_SUMMARY_PRINT_DIFF_WAIT` / `..._PRINT_TRUE_WAIT`: the stats panel waits (A/B/X/Y/touch). */
    const val EXP_STATE_PANEL_DIFF = 11
    const val EXP_STATE_PANEL_TOTALS = 13

    /** Catch task states: 12 dex page loading, 13 dex page shown (waits for A or touch only, B does nothing). */
    const val CATCH_STATE_DEX_LOADING = 12
    const val CATCH_STATE_DEX = 13
    val CATCH_STATES_NAMING = 20..21

    /** Pokédex page work (ov18_021F8974): u8 1 once the page accepts input (verified live: 0 for a few frames). */
    const val DEX_READY = 0x254L
    const val DEX_MON = 0x0CL                // Pokemon * (the caught mon)

    // --- Evolution scene (sub_02075A7C, EvolutionTaskData 0xBC bytes) ---
    /** `sub_02075D08`: the evolution SysTask (after a battle, Rare Candy, stone, trade). */
    const val FN_EVOLUTION = 0x02075D08L
    const val EVO_SUMMARY_APP = 0x38L        // OverlayManager * of the nested summary (forget a move), state 23
    const val EVO_FROM = 0x60L
    const val EVO_TO = 0x62L
    const val EVO_STATE = 0x64L
    const val EVO_MOVE = 0x6CL               // u16 move to learn
    const val EVO_PROMPT_SUBSTATE = 0x8AL    // 0 input, 1 button animation
    const val EVO_PROMPT_CURSOR = 0x8BL      // 1 top, 2 bottom
    const val EVO_STATE_FORGET_PROMPT = 21   // "Forget a move!" / "Keep old moves!" (set up in 20)
    const val EVO_STATE_GIVE_UP_PROMPT = 35  // "Give up on X!" / "Don't give up on X!" (set up in 34)
    const val EVO_STATE_SUMMARY = 23

    // --- Pokémon summary in learn/forget mode (PokemonSummaryArgs = OM args, work = OM data) ---
    const val SUM_ARGS_PARTY = 0x00L         // Party * (or a Pokemon * during an evolution)
    const val SUM_ARGS_MODE = 0x12L          // 2 = learn / forget a move
    const val SUM_ARGS_SLOT = 0x14L
    const val SUM_ARGS_MOVE = 0x18L          // u16 move to learn
    const val SUM_MODE_FORGET = 2
    const val SUM_MOVES = 0x264L             // u16[4] (mon cache of the work)
    const val SUM_PP = 0x26CL                // u8[4]
    const val SUM_PP_MAX = 0x270L            // u8[4]
    const val SUM_CURSOR = 0x7BDL            // low nibble: 0-3 known moves, 4 = the new move (verified live, wraps)
    const val SUM_PROC_CHOOSE = 8
    const val SUM_PROC_CONFIRM = 9

    /** Summary back button (`_021038AC`): cancels the confirmation / leaves without learning. */
    val SUM_BACK_TOUCH = TouchPoint(219, 176)

    // --- Field party menu teaching a TM / HM (party_menu.c; same offsets as the party menu decoder) ---
    const val PM_ARGS = 0x654L
    const val PM_DRAW_STATE = 0x828L         // monsDrawState[6], 0x30 bytes each
    const val PM_DRAW_STATE_SIZE = 0x30L
    const val PM_DRAW_ACTIVE = 0x2DL
    const val PM_CURSOR = 0xC65L             // 0..5 slots, 7 = CANCEL
    const val PM_OPENED_ON = 0xC66L          // slot the menu opened on: its column picks the CANCEL UP/DOWN order
    const val PM_BUSY = 0xC9CL
    const val ARGS_CONTEXT = 0x24L
    const val ARGS_ITEM = 0x28L
    const val CONTEXT_TM_HM = 6
    const val PM_STATE_USE_TMHM = 21
    const val PM_CURSOR_CANCEL = 7
    const val ITEM_TM01 = 328
    const val ITEM_HM08 = 427
}

/** Can a Pokémon learn a TM / HM, as the party menu draws it (party_context_menu.c:838, msg_0300 158-160). */
enum class TmCompatibility(val label: String) { ABLE("ABLE!"), UNABLE("UNABLE!"), LEARNED("LEARNED") }

/**
 * TM / HM data (`sTMHMMoves` src/item.c:31, personal data `tmhm`): from the ROM when one is loaded
 * ([HgssData.gameData]), else from the bundled tables.
 */
object HgssMachines {
    /** Move taught by machine n (1..92 TMs, 93..100 HMs), index n - 1. */
    private val moves: List<Int>
        get() = HgssData.gameData?.let { data -> MachineId.all.map { data.machineMove(it)?.value ?: 0 } } ?: bundledMoves

    private val bundledMoves: List<Int> by lazy { HgssData.lines("tm_moves.txt").map { it.trim().toIntOrNull() ?: 0 } }

    /** Machines each species is compatible with, index = species (bundled table). */
    private val bundledCompat: List<Set<Int>> by lazy {
        HgssData.lines("tm_compat.txt").map { line -> line.split(' ').mapNotNull { it.toIntOrNull() }.toSet() }
    }

    /** Machine numbers species [species] is compatible with. */
    private fun compatibleMachines(species: Int): Set<Int> =
        HgssData.gameData?.let { data -> data.species(SpeciesId(species))?.machines?.map { it.number }?.toSet() ?: emptySet() }
            ?: bundledCompat.getOrNull(species) ?: emptySet()

    /** Machine number (1..100) of an item, or null when it isn't a TM / HM. */
    fun machineOf(itemId: Int): Int? = (itemId - P.ITEM_TM01 + 1).takeIf { itemId in P.ITEM_TM01..P.ITEM_HM08 }

    fun moveOf(itemId: Int): Int = machineOf(itemId)?.let { moves.getOrNull(it - 1) } ?: 0

    /** True for the 8 HM moves (MoveIsHM, src/item.c:946): they can't be forgotten. */
    fun isHm(moveId: Int): Boolean = moveId != 0 && moves.drop(92).contains(moveId)

    fun compatibility(species: Int, isEgg: Boolean, knownMoves: List<Int>, itemId: Int): TmCompatibility {
        val machine = machineOf(itemId) ?: return TmCompatibility.UNABLE
        return when {
            isEgg -> TmCompatibility.UNABLE
            moveOf(itemId) in knownMoves -> TmCompatibility.LEARNED
            machine in compatibleMachines(species) -> TmCompatibility.ABLE
            else -> TmCompatibility.UNABLE
        }
    }
}

/**
 * Screens around battles and level ups (design/screens/post-battle.md): the level-up stats panel, the Pokédex page
 * after a capture, the evolution scene and its prompts, forgetting a move on the summary screen (TM / HM, evolution,
 * tutor), and the party grid of a TM / HM ("Teach which Pokémon?" with ABLE / UNABLE / LEARNED).
 *
 * The in-battle prompts (keep / forget, give up, nickname yes / no) and the in-battle "forget which move" screen are
 * battle input screens: [HgssBattleScreens] decodes them.
 */
internal object HgssPostBattleScreens : HgssScreenDecoder {

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        mem.mainTaskData(P.FN_EVOLUTION)?.let { e -> return evolution(mem, e) }
        HgssScreenMemory.fieldSubApp(mem)?.let { app ->
            when (mem.fn(app + A.OM_INIT)) {
                mem.version.fnSummaryInit -> return summaryForget(mem, app)
                mem.version.fnPartyMenuInit -> return tmGrid(mem, state, app)
            }
        }
        if (state.mode != GameMode.BATTLE) return null
        val root = HgssBattleRoot.find(mem) ?: return null
        val gw = mem.ptr(root.ctx + P.BC_GETTER_WORK) ?: return null
        val owner = mem.mainTasks().firstOrNull { it.second == gw }?.first
        val taskState = mem.s32(gw + P.GW_STATE)
        return when (owner) {
            P.FN_GET_EXP -> if (taskState == P.EXP_STATE_PANEL_DIFF || taskState == P.EXP_STATE_PANEL_TOTALS) levelUpPanel(mem, root, gw) else null
            P.FN_GET_POKEMON -> when (taskState) {
                P.CATCH_STATE_DEX -> {
                    val dex = mem.ptr(gw + P.GW_POINTER_0)
                    if (dex != null && mem.u8(dex + P.DEX_READY) == 1) {
                        val caught = HgssScreenMemory.mon(mem, mem.ptr(dex + P.DEX_MON))
                        Screen.PressToContinue(ContinueReason.POKEDEX_ENTRY, caught?.let { HgssData.speciesName(it.species) })
                    } else Screen.Battle(Awaiting.ANIMATION)
                }
                P.CATCH_STATE_DEX_LOADING -> Screen.Battle(Awaiting.ANIMATION)
                else -> null
            }
            else -> null
        }
    }

    /** True while the catch task runs the naming screen (a nested app the keyboard decoder handles). */
    fun catchNamingRunning(mem: HgssMemory, root: HgssBattleRoot): Boolean {
        val gw = mem.ptr(root.ctx + P.BC_GETTER_WORK) ?: return false
        return mem.mainTasks().any { it.second == gw && it.first == P.FN_GET_POKEMON } && mem.s32(gw + P.GW_STATE) in P.CATCH_STATES_NAMING
    }

    /**
     * Shakes of the ball being thrown (0..3 = breaks free after that many, 4 = caught), while the catch task runs and
     * once it computed them (state >= 3); null otherwise. The model has no place for it yet (see the report).
     */
    fun catchShakes(mem: HgssMemory): Int? {
        val root = HgssBattleRoot.find(mem) ?: return null
        val gw = mem.ptr(root.ctx + P.BC_GETTER_WORK) ?: return null
        if (mem.mainTasks().none { it.second == gw && it.first == P.FN_GET_POKEMON } || mem.s32(gw + P.GW_STATE) < 3) return null
        return mem.s32(gw + P.GW_SHAKES).takeIf { it in 0..4 }
    }

    // region Level up

    /** Panel order (rodata ov12_0226C354): MaxHP, Attack, Defense, Sp. Atk, Sp. Def, Speed. */
    private val PANEL = listOf("Max HP", "Attack", "Defense", "Sp. Atk", "Sp. Def", "Speed")

    /**
     * The level-up stats panel (Task_GetExp state 11 "+N" or 13 totals): any of A / B / X / Y / touch goes on. The
     * text lists the new values and gains: old stats from `ctx->prevLevelStats` (hp, atk, def, speed, spAtk, spDef),
     * new ones from the battle's party copy.
     */
    private fun levelUpPanel(mem: HgssMemory, root: HgssBattleRoot, gw: Long): Screen {
        val slot = mem.s32(gw + P.GW_PARTY_SLOT)
        val party = mem.ptr(root.bs + A.BS_TRAINER_PARTY)
        val mon = party?.takeIf { slot in 0..5 }?.let { HgssScreenMemory.mon(mem, it + A.PARTY_MONS + slot * A.POKEMON_SIZE) }
        val old = mem.ptr(root.ctx + P.BC_PREV_LEVEL_STATS)?.let { p -> (0 until 6).map { mem.s32(p + 4L * it) } }
        val text = mon?.let { m ->
            val now = listOf(m.maxHp, m.atk, m.def, m.spAtk, m.spDef, m.speed)
            val before = old
            "${m.displayName()} Lv${m.level}: " + PANEL.indices.joinToString(", ") { i ->
                val gain = before?.let { b -> " (+${now[i] - b[i]})" } ?: ""
                "${PANEL[i]} ${now[i]}$gain"
            }
        }
        return Screen.PressToContinue(ContinueReason.LEVEL_UP_STATS, text)
    }

    // endregion

    // region Evolution

    /**
     * The evolution scene (sub_02075D08 jump table at 0x02075F40). Nothing waits for A except the two prompts:
     * state 21 "Forget a move!" / "Keep old moves!" and 35 "Give up on X!" / "Don't give up on X!" (cursor E+0x8B,
     * 1 = top, starts on top, no hidden-cursor step; B picks the bottom one at once). In state 23 the nested summary
     * screen asks which move to forget. B cancels the evolution only during the morphing (state 8) when allowed.
     */
    private fun evolution(mem: HgssMemory, e: Long): Screen {
        val evoState = mem.u8(e + P.EVO_STATE)
        val from = mem.u16(e + P.EVO_FROM)
        val to = mem.u16(e + P.EVO_TO)
        if (evoState == P.EVO_STATE_SUMMARY) {
            mem.ptr(e + P.EVO_SUMMARY_APP)?.let { app -> if (mem.fn(app + A.OM_INIT) == mem.version.fnSummaryInit) summaryForget(mem, app)?.let { return it } }
        }
        if ((evoState == P.EVO_STATE_FORGET_PROMPT || evoState == P.EVO_STATE_GIVE_UP_PROMPT) && mem.u8(e + P.EVO_PROMPT_SUBSTATE) == 0) {
            val move = HgssData.moveName(mem.u16(e + P.EVO_MOVE))
            val entries = if (evoState == P.EVO_STATE_FORGET_PROMPT) {
                listOf(Entry("option:forget", "FORGET A MOVE"), Entry("option:keep", "KEEP OLD MOVES"))
            } else {
                listOf(Entry("option:give_up", "GIVE UP ON $move"), Entry("option:keep", "DON'T GIVE UP ON $move"))
            }
            val cursor = when (mem.u8(e + P.EVO_PROMPT_CURSOR)) {
                1 -> Cursor.At(0)
                2 -> Cursor.At(1)
                else -> Cursor.Hidden
            }
            return Screen.YesNo(null, entries, cursor, Topology.vertical(2), CancelBehavior.CONFIRMS_LAST)
        }
        return Screen.Evolution(
            Named(SpeciesId(from), HgssData.speciesName(from)),
            to.takeIf { it != 0 }?.let { Named(SpeciesId(it), HgssData.speciesName(it)) },
            Awaiting.ANIMATION,
        )
    }

    // endregion

    // region Forget a move on the summary screen

    /**
     * The summary screen in learn / forget mode (args mode 2; field TM / HM, Rare Candy, tutor, or nested in an
     * evolution). Proc state 8: a vertical list of the known moves then the new one, UP / DOWN wrap and skip empty
     * slots (sub_0208A71C, verified live); A on a known move asks to confirm (HM moves are refused), A on the new move
     * or B = don't learn. Proc state 9: "Forget!" confirmation, A forgets, B goes back.
     */
    private fun summaryForget(mem: HgssMemory, app: Long): Screen? {
        val args = mem.ptr(app + A.OM_ARGS) ?: return null
        val work = mem.ptr(app + A.OM_DATA) ?: return null
        if (mem.s32(app + A.OM_EXEC_STATE) != 2 || mem.u8(args + P.SUM_ARGS_MODE) != P.SUM_MODE_FORGET) return null
        val proc = mem.s32(app + A.OM_PROC_STATE)
        if (proc != P.SUM_PROC_CHOOSE && proc != P.SUM_PROC_CONFIRM) return Screen.Animation(dev.kotlinds.pokemonclient.state.AnimationKind.TRANSITION)
        val newMove = mem.u16(args + P.SUM_ARGS_MOVE)
        val moves = (0 until 4).map { mem.u16(work + P.SUM_MOVES + 2L * it) }
        val mon = summaryMon(mem, args)
        val cursorIndex = mem.u8(work + P.SUM_CURSOR) and 0x0F
        if (proc == P.SUM_PROC_CONFIRM) {
            val move = moves.getOrNull(cursorIndex) ?: 0
            return Screen.ContextMenu(
                mon,
                listOf(
                    Entry("option:forget", "FORGET ${HgssData.moveName(move)}"),
                    Entry("option:cancel", "CANCEL", touch = P.SUM_BACK_TOUCH),
                ),
                Cursor.At(0),
                Topology { _, _ -> null },
                CancelBehavior.CLOSES,
            )
        }
        val entries = moves.mapIndexed { i, move ->
            if (move == 0) Entry("slot:$i", "-", selectable = false)
            else Entry(
                "move:$move",
                HgssScreenMemory.moveLabel(move, mem.u8(work + P.SUM_PP + i), mem.u8(work + P.SUM_PP_MAX + i)),
                selectable = !(newMove != 0 && HgssMachines.isHm(move)),
            )
        } + Entry("move:$newMove", HgssScreenMemory.moveLabel(newMove, null, null) + " (new: don't learn it)")
        val order = moves.indices.filter { moves[it] != 0 } + if (newMove != 0) listOf(4) else emptyList()
        val topology = Topology { from, button ->
            val at = order.indexOf(from)
            if (at < 0 || order.size < 2) return@Topology null
            when (button) {
                Button.UP -> order[(at - 1 + order.size) % order.size]
                Button.DOWN -> order[(at + 1) % order.size]
                else -> null
            }
        }
        return Screen.MoveSelect(
            MoveContext.FORGET_SUMMARY, mon, Named(MoveId(newMove), HgssData.moveName(newMove)),
            entries, Cursor.At(cursorIndex.coerceIn(0, 4)), topology, CancelBehavior.CLOSES,
        )
    }

    /** The Pokémon of a summary: `args->party` is a Party (slot `args+0x14`) or, during an evolution, a Pokemon. */
    private fun summaryMon(mem: HgssMemory, args: Long): MonId? {
        val target = mem.ptr(args + P.SUM_ARGS_PARTY) ?: return null
        val isParty = mem.u32(target + A.PARTY_MAX_COUNT) == 6L && mem.u32(target + A.PARTY_CUR_COUNT) in 1L..6L
        val ptr = if (isParty) target + A.PARTY_MONS + mem.u8(args + P.SUM_ARGS_SLOT).coerceIn(0, 5) * A.POKEMON_SIZE else target
        return HgssScreenMemory.mon(mem, ptr)?.monId()
    }

    // endregion

    // region TM / HM party grid

    /** Field party grid neighbours (`_0210140C`, party_menu.c:160): UP, DOWN, LEFT, RIGHT; 7 = CANCEL. */
    private val GRID = mapOf(
        0 to listOf(7, 2, 7, 1), 1 to listOf(7, 3, 0, 2), 2 to listOf(0, 4, 1, 3),
        3 to listOf(1, 5, 2, 4), 4 to listOf(2, 7, 3, 5), 5 to listOf(3, 7, 4, 7), 7 to listOf(-1, -1, 5, 0),
    )
    private val CANCEL_UP = listOf(listOf(4, 2, 0, 5, 3, 1), listOf(5, 3, 1, 4, 2, 0))
    private val CANCEL_DOWN = listOf(listOf(0, 2, 4, 1, 3, 5), listOf(1, 3, 5, 0, 2, 4))

    /**
     * "Teach which Pokémon?" (party menu proc state 21, context 6): each slot is ABLE, UNABLE or LEARNED, computed
     * like the panels the game draws (personal TM data, party_context_menu.c:838). UNABLE / LEARNED mons are refused
     * (the game prints "not compatible" / "already knows" and leaves). Grid moves as in the field party menu.
     * Verified live on TM01 (Ampharos / Typhlosion / Machoke ABLE, Fearow / Gyarados / Swinub UNABLE).
     */
    private fun tmGrid(mem: HgssMemory, state: HgssState, app: Long): Screen? {
        if (mem.s32(app + A.OM_PROC_STATE) != P.PM_STATE_USE_TMHM) return null
        val pm = mem.ptr(app + A.OM_DATA) ?: return null
        val args = mem.ptr(pm + P.PM_ARGS) ?: return null
        if (mem.u8(args + P.ARGS_CONTEXT) != P.CONTEXT_TM_HM) return null
        if (mem.u32(pm + P.PM_BUSY) != 0L) return Screen.Animation(dev.kotlinds.pokemonclient.state.AnimationKind.TRANSITION)
        val item = mem.u16(args + P.ARGS_ITEM)
        val active = BooleanArray(6) { mem.u8(pm + P.PM_DRAW_STATE + P.PM_DRAW_STATE_SIZE * it + P.PM_DRAW_ACTIVE) != 0 }
        val entries = (0 until 6).map { slot ->
            val mon = state.party.firstOrNull { it.slot == slot }?.takeIf { active[slot] }
            if (mon == null) {
                Entry("slot:$slot", "-", selectable = false)
            } else {
                val compat = HgssMachines.compatibility(mon.species, mon.isEgg, mon.moves.map { it.id }, item)
                val name = if (mon.isEgg) "EGG" else mon.nickname ?: mon.speciesName
                Entry(MonId(mon.personality, mon.otId).toString(), "$name Lv${mon.level} ${compat.label}", selectable = compat == TmCompatibility.ABLE)
            }
        } + Entry("option:cancel", "CANCEL")
        val raw = mem.u8(pm + P.PM_CURSOR)
        val cursor = when (raw) {
            in 0..5 -> Cursor.At(raw)
            P.PM_CURSOR_CANCEL -> Cursor.At(6)
            else -> Cursor.Hidden
        }
        val column = mem.u8(pm + P.PM_OPENED_ON) and 1
        fun isActive(slot: Int) = slot in 0..5 && active[slot]
        val topology = Topology { from, button ->
            val direction = when (button) {
                Button.UP -> 0
                Button.DOWN -> 1
                Button.LEFT -> 2
                Button.RIGHT -> 3
                else -> return@Topology null
            }
            val start = if (from == 6) P.PM_CURSOR_CANCEL else from
            val target = when {
                start == P.PM_CURSOR_CANCEL && direction == 0 -> CANCEL_UP[column].firstOrNull(::isActive)
                start == P.PM_CURSOR_CANCEL && direction == 1 -> CANCEL_DOWN[column].firstOrNull(::isActive)
                else -> {
                    // PartyMenu_GetSelectionInDirection (party_menu.c:1380): empty slots are passed through.
                    var current = start
                    var guard = 0
                    do {
                        current = GRID.getValue(current)[direction]
                    } while (current != P.PM_CURSOR_CANCEL && current >= 0 && !isActive(current) && guard++ < 8)
                    current.takeIf { it >= 0 }
                }
            } ?: return@Topology null
            val index = if (target == P.PM_CURSOR_CANCEL) 6 else target
            index.takeIf { it != from }
        }
        return Screen.PartyGrid(PartyPurpose.TEACH, entries, cursor, topology, CancelBehavior.CLOSES)
    }

    // endregion
}
