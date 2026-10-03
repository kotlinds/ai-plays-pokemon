package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.BlzCodec
import dev.kotlinds.NarcArchive
import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.data.BaseStats
import dev.kotlinds.pokemonclient.data.Evolution
import dev.kotlinds.pokemonclient.data.EvolutionMethod
import dev.kotlinds.pokemonclient.data.GameData
import dev.kotlinds.pokemonclient.data.GrowthRate
import dev.kotlinds.pokemonclient.data.ItemInfo
import dev.kotlinds.pokemonclient.data.ItemPocket
import dev.kotlinds.pokemonclient.data.LevelMove
import dev.kotlinds.pokemonclient.data.MachineId
import dev.kotlinds.pokemonclient.data.MoveCategory
import dev.kotlinds.pokemonclient.data.MoveInfo
import dev.kotlinds.pokemonclient.data.PokemonType
import dev.kotlinds.pokemonclient.data.SpeciesInfo
import dev.kotlinds.pokemonclient.data.TextBankId
import dev.kotlinds.pokemonclient.data.TypeChart
import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u16
import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u32
import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u8
import dev.kotlinds.pokemonclient.state.AbilityId
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.SpeciesId

/**
 * [GameData] of HeartGold / SoulSilver, read from the ROM.
 *
 * Sources (ROM filesystem paths = `a/0/X/Y` of NARC id XY, include/filesystem_files_def.h; formats from the decomp):
 * - [PERSONAL_NARC] `poketool/personal/personal.narc`: one `BaseStats` (44 bytes, include/pokemon_types_def.h) per species;
 * - [LEARNSET_NARC] `poketool/personal/wotbl.narc`: u16 `level << 9 | move` per species, `0xFFFF` ends;
 * - [EVOLUTION_NARC] `poketool/personal/evo.narc`: 7 `{u16 method, param, target}` per species;
 * - [MOVE_NARC] `poketool/waza/waza_tbl.narc`: one `MoveTbl` (16 bytes, include/move.h) per move;
 * - [ITEM_NARC] `itemtool/itemdata/item_data.narc`: one `ItemData` (36 bytes, include/item.h) per data member,
 *   item id → member through `sItemNarcIds` (ARM9);
 * - [MESSAGE_NARC] `msgdata/msg.narc`: the text banks ([HgssTextBanks]), decoded with [HgssMessageFile];
 * - ARM9 / battle overlay tables ([HgssCodeTables]): TM / HM moves, item → data member, type chart.
 *
 * Nothing depends on the ROM's language: the archives and banks have the same indices in every release; only the
 * names differ. [version] is kept for symmetry with [HgssWorldSource] (the data is the same in HG and SS).
 * Everything is decoded lazily, once.
 */
class HgssGameData(private val rom: NdsRom, val version: HgssVersion) : GameData {

    private val personal: List<ByteArray> by lazy { narc(PERSONAL_NARC) }
    private val learnsets: List<ByteArray> by lazy { narc(LEARNSET_NARC) }
    private val evolutionFiles: List<ByteArray> by lazy { narc(EVOLUTION_NARC) }
    private val moveFiles: List<ByteArray> by lazy { narc(MOVE_NARC) }
    private val itemFiles: List<ByteArray> by lazy { narc(ITEM_NARC) }
    private val messageFiles: List<ByteArray> by lazy { narc(MESSAGE_NARC) }

    private val arm9: ByteArray by lazy { BlzCodec.decompress(rom.arm9) }

    /** Every line of text bank [bank] (empty when the bank doesn't exist). Decodes the whole bank: cache the result. */
    fun bank(bank: TextBankId): List<String> = messageFile(bank)?.lines() ?: emptyList()

    /** A names bank: the PK / MN ligature glyphs (codes 0x1E0 / 0x1E1, charmap ₧ ₦) spelled "PK" / "MN". */
    private fun names(bank: TextBankId): List<String> = bank(bank).map { it.replace("₧", "PK").replace("₦", "MN") }

    private fun messageFile(bank: TextBankId): HgssMessageFile? = messageFiles.getOrNull(bank.value)?.let { HgssMessageFile(it) }

    /** Species names, index = species id. */
    val speciesNames: List<String> by lazy { names(HgssTextBanks.SPECIES_NAMES) }

    /** Move names, index = move id. */
    val moveNames: List<String> by lazy { names(HgssTextBanks.MOVE_NAMES) }

    /** Item names, index = item id. */
    val itemNames: List<String> by lazy { names(HgssTextBanks.ITEM_NAMES) }

    /** Ability names, index = ability id. */
    val abilityNames: List<String> by lazy { names(HgssTextBanks.ABILITY_NAMES) }

    /** Trainer class names, index = trainer class id. */
    val trainerClassNames: List<String> by lazy { names(HgssTextBanks.TRAINER_CLASS_NAMES) }

    private val typeNames: List<String> by lazy { names(HgssTextBanks.TYPE_NAMES) }

    override val speciesCount: Int get() = personal.size
    override val moveCount: Int get() = moveFiles.size
    override val itemCount: Int get() = itemMembers.size

    private val machineMoves: List<Int> by lazy {
        HgssCodeTables.machineMoves(arm9, moveCount) ?: error("TM / HM move table not found in the ARM9 binary")
    }

    private val itemMembers: List<Int> by lazy {
        HgssCodeTables.itemDataMembers(arm9, itemFiles.size, itemNames.size)
            ?: error("item data index table not found in the ARM9 binary")
    }

    override val typeChart: TypeChart by lazy {
        HgssCodeTables.typeChart(overlay(BATTLE_OVERLAY)) ?: error("type chart not found in overlay $BATTLE_OVERLAY")
    }

    /** Every species record, decoded once (index = species id). */
    private val allSpecies: List<SpeciesInfo?> by lazy { personal.indices.map { decodeSpecies(SpeciesId(it)) } }

    override fun species(id: SpeciesId): SpeciesInfo? = allSpecies.getOrNull(id.value)

    private fun decodeSpecies(id: SpeciesId): SpeciesInfo? {
        val p = personal.getOrNull(id.value)?.takeIf { it.size >= PERSONAL_SIZE } ?: return null
        val types = listOf(u8(p, 6), u8(p, 7)).distinct().mapNotNull { PokemonType.fromGameIndex(it) }
        val machines = MachineId.all.filter { m ->
            val bit = m.number - 1
            (u32(p, PERSONAL_TMHM + (bit / 32) * 4) shr (bit % 32)) and 1L == 1L
        }.toSet()
        return SpeciesInfo(
            id = id,
            name = speciesNames.getOrNull(id.value) ?: "",
            types = types,
            baseStats = BaseStats(u8(p, 0), u8(p, 1), u8(p, 2), u8(p, 3), u8(p, 4), u8(p, 5)),
            abilities = listOf(u8(p, 0x16), u8(p, 0x17)).filter { it != 0 }.distinct().map { AbilityId(it) },
            catchRate = u8(p, 8),
            baseExperience = u8(p, 9),
            growthRate = GrowthRate.fromGameIndex(u8(p, 0x13)),
            evolutions = evolutions(id.value),
            machines = machines,
        )
    }

    private fun evolutions(species: Int): List<Evolution> {
        val e = evolutionFiles.getOrNull(species) ?: return emptyList()
        return (0 until MAX_EVOLUTIONS).mapNotNull { i ->
            val o = i * 6
            if (o + 6 > e.size) return@mapNotNull null
            val method = EvolutionMethod.fromGameIndex(u16(e, o))?.takeIf { it != EvolutionMethod.NONE } ?: return@mapNotNull null
            Evolution(method, u16(e, o + 2), SpeciesId(u16(e, o + 4)))
        }
    }

    override fun learnset(id: SpeciesId): List<LevelMove> {
        val l = learnsets.getOrNull(id.value) ?: return emptyList()
        return (0 until l.size / 2).asSequence()
            .map { u16(l, it * 2) }
            .takeWhile { it != LEARNSET_END }
            .map { LevelMove(level = it shr 9, move = MoveId(it and 0x1FF)) }
            .toList()
    }

    override fun move(id: MoveId): MoveInfo? {
        val m = moveFiles.getOrNull(id.value)?.takeIf { it.size >= MOVE_SIZE } ?: return null
        return MoveInfo(
            id = id,
            name = moveNames.getOrNull(id.value) ?: "",
            type = PokemonType.fromGameIndex(u8(m, 4)) ?: return null,
            category = MoveCategory.fromGameIndex(u8(m, 2)) ?: return null,
            power = u8(m, 3),
            accuracy = u8(m, 5),
            pp = u8(m, 6),
            priority = m[10].toInt(),
            effectChance = u8(m, 7),
        )
    }

    override fun item(id: ItemId): ItemInfo? {
        val member = itemMembers.getOrNull(id.value) ?: return null
        val d = itemFiles.getOrNull(member)?.takeIf { it.size >= ITEM_SIZE } ?: return null
        return ItemInfo(
            id = id,
            name = itemNames.getOrNull(id.value) ?: "",
            pocket = ItemPocket.fromGameIndex((u16(d, 8) shr 7) and 0xF),
            price = u16(d, 0),
        )
    }

    override fun abilityName(id: AbilityId): String? = abilityNames.getOrNull(id.value)?.takeIf { it.isNotEmpty() }

    override fun typeName(type: PokemonType): String =
        typeNames.getOrNull(type.gameIndex)?.takeIf { it.isNotEmpty() } ?: type.label

    override fun trainerClassName(id: Int): String? = trainerClassNames.getOrNull(id)?.takeIf { it.isNotEmpty() }

    override fun machineMove(machine: MachineId): MoveId? = machineMoves.getOrNull(machine.number - 1)?.let { MoveId(it) }

    override fun machineOf(item: ItemId): MachineId? =
        if (item.value in ITEM_TM01..ITEM_HM08) MachineId(item.value - ITEM_TM01 + 1) else null

    override fun text(bank: TextBankId, line: Int): String? = messageFile(bank)?.line(line)

    /** Decompressed code of ARM9 overlay [index] (overlay table flag bit 0 = BLZ-compressed). */
    private fun overlay(index: Int): ByteArray {
        val raw = rom.arm9Overlays.getOrNull(index) ?: error("missing overlay $index")
        val compressed = (u8(rom.arm9OverlayTable, index * OVERLAY_ENTRY_SIZE + OVERLAY_FLAGS) and 1) != 0
        return if (compressed) BlzCodec.decompress(raw) else raw
    }

    private fun narc(path: String): List<ByteArray> =
        NarcArchive.unpack(rom.files[path] ?: error("missing $path in the ROM"))

    companion object {
        /** `NARC_poketool_personal_personal` (2). */
        const val PERSONAL_NARC = "a/0/0/2"

        /** `NARC_poketool_waza_waza_tbl` (11). */
        const val MOVE_NARC = "a/0/1/1"

        /** `NARC_itemtool_itemdata_item_data` (17). */
        const val ITEM_NARC = "a/0/1/7"

        /** `NARC_msgdata_msg` (27). */
        const val MESSAGE_NARC = "a/0/2/7"

        /** `NARC_poketool_personal_wotbl` (33). */
        const val LEARNSET_NARC = "a/0/3/3"

        /** `NARC_poketool_personal_evo` (34). */
        const val EVOLUTION_NARC = "a/0/3/4"

        /** The battle overlay, holding `sTypeEffectiveness`. */
        const val BATTLE_OVERLAY = 12

        /** `ITEM_TM01` .. `ITEM_HM08` (include/constants/items.h). */
        const val ITEM_TM01 = 328
        const val ITEM_HM08 = 427

        private const val PERSONAL_SIZE = 0x2C
        private const val PERSONAL_TMHM = 0x1C
        private const val MOVE_SIZE = 16
        private const val ITEM_SIZE = 10
        private const val MAX_EVOLUTIONS = 7
        private const val LEARNSET_END = 0xFFFF
        private const val OVERLAY_ENTRY_SIZE = 32
        private const val OVERLAY_FLAGS = 31
    }
}
