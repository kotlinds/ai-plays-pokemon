package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.hgss.HgssRomBytes.s32
import dev.kotlinds.pokemonclient.games.hgss.HgssRomBytes.u16
import dev.kotlinds.pokemonclient.games.hgss.HgssRomBytes.u32

/**
 * Just enough of the map script files (NARC [HgssWorldAddresses.SCRIPT_NARC], member `MapHeader.scriptsBank`) to
 * find the warps that the zone events don't list: coordinate triggers whose script runs `Warp` to the same map
 * (Blackthorn Gym exit pads, scr_seq_T30GYM0101_002; Team Rocket HQ B1F trap tile, scr_seq_D35R0102_029).
 *
 * File layout (asm/macros/script.inc): `ScrDef` entries (u32, offset of the script relative to the end of the
 * entry) up to the u16 `ScrDefEnd` marker 0xFD13, then the bytecode. Event script ids are 1-based ("`_EV_..._002 + 1`"
 * is the third `ScrDef`); ids above the file's count are common scripts (`std_*`), ignored here.
 *
 * The bytecode isn't decoded (each command has its own argument sizes): the warp is found by its exact shape,
 * `Warp map, 0, x, z, dir` = u16 176, the zone itself, 0, x, z, a direction 0..3, between the start of the script
 * and the start of the next one.
 */
internal object HgssScripts {

    /** A `Warp` command found in a script: destination tile and facing (DIR_NORTH 0, SOUTH 1, WEST 2, EAST 3). */
    data class WarpCommand(val x: Int, val z: Int, val direction: Int)

    /** Start offsets of the scripts of [file], by 0-based index. */
    fun scriptStarts(file: ByteArray): List<Int> {
        val starts = mutableListOf<Int>()
        var o = 0
        while (o + 4 <= file.size && u16(file, o) != SCRDEF_END && starts.size < MAX_SCRIPTS) {
            val start = o + 4 + u32(file, o).toInt()
            if (start !in 0 until file.size) break
            starts += start
            o += 4
        }
        return starts
    }

    /** The `Warp` to zone [zone] itself in event script [scriptId] (1-based) of [file], or null. */
    fun sameZoneWarp(file: ByteArray, scriptId: Int, zone: Int): WarpCommand? {
        val starts = scriptStarts(file)
        val start = starts.getOrNull(scriptId - 1) ?: return null
        val end = minOf(starts.filter { it > start }.minOrNull() ?: file.size, start + MAX_SCAN, file.size)
        for (o in start..end - WARP_SIZE) {
            if (u16(file, o) != WARP_OPCODE || u16(file, o + 2) != zone || u16(file, o + 4) != 0) continue
            val direction = u16(file, o + 10)
            if (direction > 3) continue
            return WarpCommand(u16(file, o + 6), u16(file, o + 8), direction)
        }
        return null
    }

    /** A `Warp` to another zone found in a trigger script: the zone and the landing tile. */
    data class ZoneWarpCommand(val zone: Int, val x: Int, val z: Int)

    /**
     * The `Warp` to another zone than [zone] run by event script [scriptId] (1-based) of [file], or null: the holes of
     * Victory Road 3F (scr_seq_D43R0103_002..006) and Ice Path B1F (scr_seq_D39R0102_000..003). Those scripts set the
     * landing tile in temporary variables then jump to a shared fall routine:
     * `SetVar VAR_TEMP_x4000, x; SetVar VAR_TEMP_x4001, z; GoTo fall` … `Warp MAP_X, 0, VAR_TEMP_x4000, VAR_TEMP_x4001, dir`.
     * The leading `SetVar`s and `GoTo`s are followed, then the routine is scanned for the `Warp` (literal
     * coordinates, or the variables set before). [zoneCount] bounds the zone ids accepted.
     */
    fun zoneWarp(file: ByteArray, scriptId: Int, zone: Int, zoneCount: Int): ZoneWarpCommand? {
        val starts = scriptStarts(file)
        var pos = starts.getOrNull(scriptId - 1) ?: return null
        val vars = HashMap<Int, Int>()
        // Follow the prefix: SetVar (41: var, value) and GoTo (22: s32 offset from the end of the offset).
        repeat(MAX_PREFIX_COMMANDS) {
            if (pos + 6 > file.size) return null
            when (u16(file, pos)) {
                SETVAR_OPCODE -> {
                    vars[u16(file, pos + 2)] = u16(file, pos + 4)
                    pos += 6
                }
                GOTO_OPCODE -> {
                    pos = pos + 6 + s32(file, pos + 2)
                    if (pos !in file.indices) return null
                }
                else -> return@repeat
            }
        }
        fun value(raw: Int): Int? = if (raw >= VAR_BASE) vars[raw] else raw
        val end = minOf(pos + MAX_ZONE_WARP_SCAN, file.size)
        for (o in pos..end - WARP_SIZE) {
            if (u16(file, o) != WARP_OPCODE || u16(file, o + 4) != 0) continue
            val target = u16(file, o + 2)
            if (target == zone || target !in 0 until zoneCount) continue
            val x = value(u16(file, o + 6)) ?: continue
            val z = value(u16(file, o + 8)) ?: continue
            if (x >= MAX_COORDINATE || z >= MAX_COORDINATE) continue
            return ZoneWarpCommand(target, x, z)
        }
        return null
    }

    /**
     * True when event script [scriptId] (1-based) of [file] does nothing: its first command is `End` (a placeholder
     * scene trigger like Violet City's on the Sprout Tower bridge, scr_seq_T22_002). Stepping on its trigger runs
     * nothing the player would notice.
     */
    fun isEmpty(file: ByteArray, scriptId: Int): Boolean {
        val start = scriptStarts(file).getOrNull(scriptId - 1) ?: return false
        return start + 2 <= file.size && u16(file, start) == END_OPCODE
    }

    /**
     * The item event script [scriptId] (1-based) of [file] gives, or null: the `ItemVars item, quantity` of an item
     * gift (`SetVar VAR_SPECIAL_x8004, item`) followed by `CallStd std_obtain_item_verbose` / `std_give_item_verbose`
     * before the next script starts (the Cerulean Gym's Machine Part, scr_seq_T04GYM0101_005).
     */
    fun givenItem(file: ByteArray, scriptId: Int): Int? {
        val starts = scriptStarts(file)
        val start = starts.getOrNull(scriptId - 1) ?: return null
        val end = minOf(starts.filter { it > start }.minOrNull() ?: file.size, start + MAX_ITEM_SCAN, file.size)
        var item: Int? = null
        for (o in start..end - 4) {
            if (o + 6 <= end && u16(file, o) == SETVAR_OPCODE && u16(file, o + 2) == VAR_ITEM) item = u16(file, o + 4)
            if (item != null && u16(file, o) == CALLSTD_OPCODE && u16(file, o + 2) in ITEM_GIFT_STDS) return item
        }
        return null
    }

    /** `CallStd` (script command 20, asm/macros/script.inc). */
    private const val CALLSTD_OPCODE = 20

    /** `VAR_SPECIAL_x8004`: the item of `ItemVars`. */
    private const val VAR_ITEM = 0x8004

    /** `std_obtain_item_verbose` (2008), `std_give_item_verbose` (2033): include/constants/std_script.h. */
    private val ITEM_GIFT_STDS = setOf(2008, 2033)

    /** An item gift script (message, bag check, gift, hide the objects) is short. */
    private const val MAX_ITEM_SCAN = 0x80

    /** `End` (script command 2). */
    private const val END_OPCODE = 2
    private const val SETVAR_OPCODE = 41
    private const val GOTO_OPCODE = 22
    private const val VAR_BASE = 0x4000
    private const val MAX_PREFIX_COMMANDS = 8

    /** The shared fall routine moves the player and the follower before warping: a bit longer than a pad's script. */
    private const val MAX_ZONE_WARP_SCAN = 0x180
    private const val MAX_COORDINATE = 4096

    /** `Warp` (script command 176, asm/macros/script.inc: map, 0, x, z, direction as u16). */
    private const val WARP_OPCODE = 176
    private const val WARP_SIZE = 12
    private const val SCRDEF_END = 0xFD13
    private const val MAX_SCRIPTS = 512

    /** Warp scripts are short (lock, sound, fade, warp): don't look further than this into a script. */
    private const val MAX_SCAN = 0x100
}
