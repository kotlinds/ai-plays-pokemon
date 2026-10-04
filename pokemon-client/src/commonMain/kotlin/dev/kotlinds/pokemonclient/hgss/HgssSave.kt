package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.data.GrowthRate
import dev.kotlinds.pokemonclient.state.BattleStyle
import dev.kotlinds.pokemonclient.state.BoxMon
import dev.kotlinds.pokemonclient.state.GameOptions
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PcBoxContents
import dev.kotlinds.pokemonclient.state.PcStorage
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.StartMenuFeature
import dev.kotlinds.pokemonclient.state.TextSpeed
import dev.kotlinds.pokemonclient.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.hgss.HgssKeyboardPcShopAddresses as K

/**
 * Reads of the save data in RAM that the main reader doesn't do: event / trainer flags, the OPTIONS and the PC boxes
 * (save arrays, include/constants/save_arrays.h; `SaveArray_Get`, src/save.c:128). The save data lives in RAM for the
 * whole game, so these are known anywhere (not only while the PC or the options screen is open).
 */
internal class HgssSave(private val mem: HgssMemory) {

    /** `SaveData *`, or null before the save is loaded. */
    private val saveData: Long? = mem.ptr(mem.version.saveDataPtr)

    /** Address of save array [id], or null. */
    fun array(id: Int): Long? {
        val sd = saveData ?: return null
        val hdr = sd + A.SAVE_ARRAY_HEADERS + id * A.SAH_SIZE
        if (mem.s32(hdr + A.SAH_ID) != id) return null
        val addr = sd + A.SAVE_DYNAMIC_REGION + mem.u32(hdr + A.SAH_OFFSET)
        return addr.takeIf { mem.inRam(it, mem.u32(hdr + A.SAH_LENGTH).coerceAtLeast(1)) }
    }

    private val flags: Long? by lazy { array(A.SAVE_FLAGS) }

    /** Event flag [id] (`Save_VarsFlags_CheckFlagInArray`), null when unreadable. */
    fun flag(id: Int): Boolean? {
        if (id !in 0 until A.NUM_SAVE_FLAGS) return null
        val vf = flags ?: return null
        return mem.u8(vf + A.FLAGS_OFFSET + id / 8) shr (id % 8) and 1 == 1
    }

    /** Save script variable [id] (`0x4000` until `0x4170`, `Save_VarsFlags_GetVarAddr`), null when unreadable. */
    fun variable(id: Int): Int? {
        if (id !in A.VAR_BASE until A.VAR_BASE + NUM_SAVE_VARS) return null
        val vf = flags ?: return null
        return mem.u16(vf + 2L * (id - A.VAR_BASE))
    }

    /** Whether trainer [trainerId] was beaten (`TrainerFlagCheck`, src/fieldmap.c: flag `TRAINER_FLAG_BASE + id`). */
    fun trainerDefeated(trainerId: Int): Boolean? = flag(TRAINER_FLAG_BASE + trainerId)

    /**
     * The start menu entries unlocked so far: `FLAG_GOT_BAG + i` for BAG, TRAINER CARD, SAVE, OPTIONS
     * (CheckGotMenuIconI, src/sys_flags.c:285). X opens nothing before the bag (src/field/field_control.c:149).
     */
    fun startMenu(): Set<StartMenuFeature>? {
        val features = StartMenuFeature.entries.map { it to (flag(FLAG_GOT_BAG + it.ordinal) ?: return null) }
        return features.filter { it.second }.map { it.first }.toSet()
    }

    /**
     * The OPTIONS (`Options`, include/options.h, first u16 of the player data; bitfields from bit 0: textSpeed:4,
     * soundMethod:2, battleStyle:1, battleScene:1 — 0 means ON / SHIFT, src/options.c Options_Init).
     */
    fun options(): GameOptions? {
        val pd = array(A.SAVE_PLAYERDATA) ?: return null
        val raw = mem.u16(pd + PD_OPTIONS)
        val speed = TextSpeed.entries.getOrNull(raw and 0xF) ?: return null
        return GameOptions(
            textSpeed = speed,
            battleScene = (raw shr 7) and 1 == 0,
            battleStyle = if ((raw shr 6) and 1 == 0) BattleStyle.SHIFT else BattleStyle.SET,
        )
    }

    /** `PCStorage *` (SAVE_PCSTORAGE), or null. */
    fun pcStorage(): Long? = array(SAVE_PCSTORAGE)

    companion object {
        /** `FLAG_GOT_BAG` (include/constants/flags.h); the next three unlock TRAINER CARD, SAVE and OPTIONS. */
        const val FLAG_GOT_BAG = 0x11B

        /** `NUM_VARS` (include/constants/vars.h). */
        const val NUM_SAVE_VARS = 0x170

        /** `TRAINER_FLAG_BASE` (include/constants/flags.h). */
        const val TRAINER_FLAG_BASE = 0x550

        /** `SAVE_PCSTORAGE` (include/constants/save_arrays.h). */
        const val SAVE_PCSTORAGE = 41

        /** `PlayerData.options` (include/player_data.h). */
        const val PD_OPTIONS = 0x00L
    }
}

/**
 * Reads the 18 PC boxes (`struct PokemonStorageSystem`, include/pokemon_storage_system.h: `PC_BOX boxes[18]` of
 * 30 `BoxPokemon` + 16 bytes, then `int curBox` at 0x12000 and the box names at 0x12008).
 *
 * Decrypting 540 Pokémon on every state read would be wasteful: a slot is decoded again only when its personality
 * or checksum word changes (any edit of a stored Pokémon re-encrypts it with a new checksum), so this reader keeps a
 * cache and must be reused between reads (one per game).
 */
internal class HgssBoxReader {

    private data class SlotKey(val personality: Long, val flagsAndChecksum: Long)

    private val cache = HashMap<Int, Pair<SlotKey, BoxMon?>>()

    fun read(mem: HgssMemory, save: HgssSave = HgssSave(mem)): PcStorage? {
        val storage = save.pcStorage() ?: return null
        // One copy of the whole storage (boxes, current box, names): far cheaper than thousands of small reads.
        val raw = mem.bytes(storage, STORAGE_READ_SIZE) ?: return null
        val current = HgssRomBytes.s32(raw, PCS_CURRENT_BOX.toInt())
        if (current !in 0 until K.BOX_COUNT) return null
        val boxes = (0 until K.BOX_COUNT).map { box ->
            val mons = (0 until K.BOX_SLOTS).mapNotNull { slot ->
                val offset = (box * K.PCS_BOX_STRIDE + slot * K.BOX_MON_SIZE).toInt()
                val key = SlotKey(HgssRomBytes.u32(raw, offset), HgssRomBytes.u32(raw, offset + 4))
                val index = box * K.BOX_SLOTS + slot
                val cached = cache[index]
                if (cached != null && cached.first == key) return@mapNotNull cached.second
                val mon = if (key.personality == 0L && key.flagsAndChecksum == 0L) null else decode(raw.copyOfRange(offset, offset + K.BOX_MON_SIZE), box, slot)
                // A slot caught while the game rewrites it decodes as garbage: don't remember it.
                if (mon != null || key.personality == 0L) cache[index] = key to mon
                mon
            }
            val names = (K.PCS_BOX_NAMES + box * K.PCS_BOX_NAME_CHARS * 2L).toInt()
            val name = HgssText.decode(IntArray(K.PCS_BOX_NAME_CHARS) { HgssRomBytes.u16(raw, names + 2 * it) })
                .takeIf { it.isNotBlank() } ?: "BOX ${box + 1}"
            PcBoxContents(box, name, mons, K.BOX_SLOTS)
        }
        return PcStorage(current, boxes)
    }

    private fun decode(raw: ByteArray, box: Int, slot: Int): BoxMon? {
        val mon = HgssPokemon.decode(raw) ?: return null
        if (!mon.checksumOk || mon.species == 0 || mon.species > PartyMon.MAX_SPECIES) return null
        val speciesName = HgssData.speciesName(mon.species)
        val nickname = if (mon.isEgg) null else HgssText.decode(mon.nicknameChars).takeIf { it.isNotEmpty() && it != speciesName }
        return BoxMon(
            id = MonId(mon.personality, mon.otId),
            box = box,
            slot = slot,
            species = Named(SpeciesId(mon.species), speciesName),
            nickname = nickname,
            level = if (mon.isEgg) null else HgssData.gameData?.species(SpeciesId(mon.species))?.growthRate?.let { levelFor(it, mon.exp) },
            heldItem = mon.heldItem.takeIf { it != 0 }?.let { Named(ItemId(it), HgssData.itemName(it)) },
            isEgg = mon.isEgg,
        )
    }

    companion object {
        /** `PokemonStorageSystem.curBox`. */
        const val PCS_CURRENT_BOX = 0x12000L

        /** Boxes, current box, modified flags and the 18 box names (up to the wallpapers at 0x122D8). */
        private const val STORAGE_READ_SIZE = 0x122D8

        /** The level a Pokémon of growth rate [rate] has with [exp] experience points (Generation 4 curves). */
        fun levelFor(rate: GrowthRate, exp: Long): Int = (2..100).lastOrNull { expFor(rate, it) <= exp } ?: 1

        /** Total experience needed to reach [level] (the game's experience tables follow these formulas). */
        fun expFor(rate: GrowthRate, level: Int): Long {
            val n = level.toLong()
            val cube = n * n * n
            return when (rate) {
                GrowthRate.MEDIUM_FAST -> cube
                GrowthRate.FAST -> cube * 4 / 5
                GrowthRate.SLOW -> cube * 5 / 4
                GrowthRate.MEDIUM_SLOW -> cube * 6 / 5 - 15 * n * n + 100 * n - 140
                GrowthRate.ERRATIC -> when {
                    n <= 50 -> cube * (100 - n) / 50
                    n <= 68 -> cube * (150 - n) / 100
                    n <= 98 -> cube * ((1911 - 10 * n) / 3) / 500
                    else -> cube * (160 - n) / 100
                }
                GrowthRate.FLUCTUATING -> when {
                    n <= 15 -> cube * ((n + 1) / 3 + 24) / 50
                    n <= 36 -> cube * (n + 14) / 50
                    else -> cube * (n / 2 + 32) / 50
                }
            }
        }
    }
}
