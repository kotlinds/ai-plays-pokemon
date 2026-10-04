package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u16

/**
 * What a shop clerk sells, known before talking to them: the clerk's map script sets `VAR_SPECIAL_x8004` then
 * calls the common Poké Mart script (`SetVar 0x8004, N` + `CallStd std_pokemart` / `std_special_mart`,
 * e.g. scr_seq_T10R0101_004). The normal counter sells by badge count, a special counter sells list N
 * (src/scrcmd_mart.c `ScrCmd_MartBuy` / `ScrCmd_SpecialMartBuy`, tables copied from there: item ids).
 */
internal object HgssMarts {

    /** The item ids clerk script [scriptId] of zone [zoneId] sells with [badges] badges, or null when unknown. */
    fun catalog(zoneId: Int, scriptId: Int, badges: Int): List<Int>? {
        if (scriptId == STD_POKEMART) return normal(badges)
        val file = HgssData.world?.scriptFile(zoneId) ?: return null
        return when (val mart = martOf(file, scriptId)) {
            null -> null
            is Mart.Normal -> normal(badges)
            is Mart.Special -> SPECIAL.getOrNull(mart.index)
        }
    }

    /** Which counter a clerk script runs. */
    sealed interface Mart {
        data object Normal : Mart
        data class Special(val index: Int) : Mart
    }

    /** Finds `SetVar 0x8004, N` immediately followed by `CallStd std_pokemart|std_special_mart` in script [scriptId]. */
    fun martOf(file: ByteArray, scriptId: Int): Mart? {
        val starts = HgssScripts.scriptStarts(file)
        val start = starts.getOrNull(scriptId - 1) ?: return null
        val end = minOf(starts.filter { it > start }.minOrNull() ?: file.size, start + MAX_SCAN, file.size)
        for (o in start..end - PATTERN_SIZE) {
            if (u16(file, o) != SET_VAR || u16(file, o + 2) != VAR_SPECIAL_8004 || u16(file, o + 6) != CALL_STD) continue
            return when (u16(file, o + 8)) {
                STD_POKEMART -> Mart.Normal
                STD_SPECIAL_MART -> Mart.Special(u16(file, o + 4))
                else -> continue
            }
        }
        return null
    }

    /**
     * The normal counter (`ScrCmd_MartBuy`): every item of [BADGE_ITEMS] whose tier is at most the tier of the badge
     * count (0 → 1, 1-2 → 2, 3-4 → 3, 5-6 → 4, 7 → 5, 8+ → 6; Johto and Kanto badges both count).
     */
    fun normal(badges: Int): List<Int> {
        val tier = when (badges) {
            0 -> 1
            1, 2 -> 2
            3, 4 -> 3
            5, 6 -> 4
            7 -> 5
            else -> 6
        }
        return BADGE_ITEMS.filter { (_, t) -> t <= tier }.map { it.first }
    }

    /** `_020FBF22`: (item, tier). */
    private val BADGE_ITEMS = listOf(
        4 to 1, 3 to 3, 2 to 4, 17 to 1, 26 to 2, 25 to 4, 24 to 5, 23 to 6, 28 to 3, 18 to 1, 22 to 1, 21 to 2,
        19 to 2, 20 to 2, 27 to 4, 78 to 2, 79 to 2, 76 to 3, 77 to 4,
    )

    /** `_0210FA3C`: the special counters, by index (`SetVar VAR_SPECIAL_x8004, index`). */
    private val SPECIAL = listOf(
        listOf(146, 14),
        listOf(141, 14, 6),
        listOf(140, 14, 6),
        listOf(17, 26, 25, 24, 28, 18, 22, 19, 20, 21, 27),
        listOf(4, 3, 2, 78, 63, 79, 76, 77, 137, 138, 139, 145),
        listOf(59, 57, 58, 55, 56, 60, 61, 62),
        listOf(46, 47, 49, 52, 48, 45),
        listOf(397, 344, 381, 410, 343, 360, 349, 379, 365, 352, 341, 342),
        listOf(36, 34, 35, 37),
        listOf(146, 14, 6),
        listOf(143, 14, 6),
        listOf(17, 26, 25, 27, 28),
        listOf(146, 6, 13),
        listOf(2, 77, 25, 24, 23, 28, 27),
        listOf(146, 8, 13, 15),
        listOf(146, 13, 15),
        listOf(144, 13, 15),
        listOf(146, 15),
        listOf(17, 26, 25, 24, 28, 18, 22, 19, 20, 21, 27),
        listOf(4, 3, 2, 78, 63, 79, 76, 77, 137, 138, 139, 145),
        listOf(348, 354, 414, 405, 339, 368, 347, 355, 403, 382, 399, 406),
        listOf(146, 141, 140),
        listOf(59, 57, 58, 55, 56, 60, 61, 62),
        listOf(46, 47, 49, 52, 48, 45),
        listOf(142, 13, 15),
        listOf(142, 8, 15),
        listOf(142, 6, 14),
        listOf(63, 30, 31, 32, 79, 143),
        listOf(86, 4, 17),
        listOf(3, 26, 25, 18, 22, 76, 28, 146),
    )

    private const val SET_VAR = 41
    private const val CALL_STD = 20
    private const val VAR_SPECIAL_8004 = 0x8004
    private const val STD_POKEMART = 2048
    private const val STD_SPECIAL_MART = 2052
    private const val PATTERN_SIZE = 10
    private const val MAX_SCAN = 0x100
}
