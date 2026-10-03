package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.data.Effectiveness
import dev.kotlinds.pokemonclient.data.MachineId
import dev.kotlinds.pokemonclient.data.PokemonType
import dev.kotlinds.pokemonclient.data.TypeChart
import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u16
import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u8

/**
 * The game data tables compiled into the code (ARM9 binary and overlays) rather than stored as files.
 *
 * They are found by content, not by address: each table starts with entries that are the same in every release
 * (they are game data, not text), so the search works on any language build without a per-ROM address list.
 * Every match is validated over the whole table. Addresses in HeartGold USA (build/heartgold.us/main.elf.xMAP) are
 * given for reference.
 */
internal object HgssCodeTables {

    /**
     * `static const u16 sTMHMMoves[100]` (src/item.c:31): the move of each machine, TM01 first.
     * HeartGold USA: ARM9 0x021000CC. Starts with Focus Punch (264), Dragon Claw (337), Water Pulse (352), Calm Mind (347).
     */
    fun machineMoves(arm9: ByteArray, moveCount: Int): List<Int>? {
        val count = MachineId.TM_COUNT + MachineId.HM_COUNT
        val signature = u16Bytes(264, 337, 352, 347, 46)
        return find(arm9, signature) { at ->
            if (at + count * 2 > arm9.size) return@find null
            val moves = List(count) { u16(arm9, at + it * 2) }
            moves.takeIf { m -> m.all { it in 1 until moveCount } && m.toSet().size == count }
        }
    }

    /**
     * `static const u16 sItemNarcIds[ITEMS_COUNT][4]` (src/item.c:134): per item id, its member in the item data
     * archive, its icon and palette, and its Generation 3 id. HeartGold USA: ARM9 0x02100194, 537 items.
     * Starts with ITEM_NONE {0, 793, 794, 0} and ITEM_MASTER_BALL {1, 2, 3, 1}.
     *
     * Returns the item data member of each item id (index = item id), for at most [maxItems] items (the number of
     * item names: the table has one entry per item id).
     */
    fun itemDataMembers(arm9: ByteArray, dataMembers: Int, maxItems: Int): List<Int>? {
        val signature = u16Bytes(0, 793, 794, 0, 1, 2, 3, 1)
        return find(arm9, signature) { at ->
            // One entry per item id (unused ids point at member 0); stop early if a member is out of range.
            val members = ArrayList<Int>()
            var o = at
            while (o + 8 <= arm9.size && members.size < maxItems) {
                val member = u16(arm9, o)
                if (member >= dataMembers) break
                members += member
                o += 8
            }
            members.takeIf { it.size > ITEMS_MIN }
        }
    }

    /**
     * `static const u8 sTypeEffectiveness[][3]` (src/battle/overlay_12_0224E4FC.c:2083) in the battle overlay (12):
     * `{attacker, defender, multiplier x10}` triples; a `{0xFE, 0xFE, 0}` marker separates the pairs that Foresight
     * ignores, `{0xFF, 0xFF, 0}` ends the table. HeartGold USA: overlay 12, 0x0226CC7C.
     * Starts with Normal→Rock ×0.5, Normal→Steel ×0.5, Fire→Fire ×0.5.
     */
    fun typeChart(battleOverlay: ByteArray): TypeChart? {
        val signature = byteArrayOf(0, 5, 5, 0, 8, 5, 10, 10, 5)
        return find(battleOverlay, signature) { at ->
            val matrix = HashMap<Pair<PokemonType, PokemonType>, Effectiveness>()
            val foresight = HashSet<Pair<PokemonType, PokemonType>>()
            var afterMarker = false
            var o = at
            while (o + 3 <= battleOverlay.size) {
                val a = u8(battleOverlay, o)
                val d = u8(battleOverlay, o + 1)
                val m = u8(battleOverlay, o + 2)
                o += 3
                when (a) {
                    TYPE_ENDTABLE -> return@find TypeChart(matrix, foresight)
                    TYPE_FORESIGHT -> { afterMarker = true; continue }
                }
                val attacker = PokemonType.fromGameIndex(a) ?: return@find null
                val defender = PokemonType.fromGameIndex(d) ?: return@find null
                val effectiveness = when (m) {
                    0 -> Effectiveness.NO_EFFECT
                    5 -> Effectiveness.NOT_VERY_EFFECTIVE
                    10 -> Effectiveness.NORMAL
                    20 -> Effectiveness.SUPER_EFFECTIVE
                    else -> return@find null
                }
                matrix[attacker to defender] = effectiveness
                if (afterMarker) foresight += attacker to defender
            }
            null
        }
    }

    private const val TYPE_FORESIGHT = 0xFE
    private const val TYPE_ENDTABLE = 0xFF

    /** A sanity floor: Generation 4 has hundreds of items. */
    private const val ITEMS_MIN = 400

    private fun u16Bytes(vararg values: Int): ByteArray =
        ByteArray(values.size * 2) { i -> (values[i / 2] shr (8 * (i % 2))).toByte() }

    /** The first offset where [signature] occurs and [decode] accepts the table, or null. */
    private fun <T> find(data: ByteArray, signature: ByteArray, decode: (Int) -> T?): T? {
        var at = 0
        while (at + signature.size <= data.size) {
            var match = true
            for (i in signature.indices) if (data[at + i] != signature[i]) { match = false; break }
            if (match) decode(at)?.let { return it }
            at += 1
        }
        return null
    }
}
