package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u16
import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u32

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

    /** `Warp` (script command 176, asm/macros/script.inc: map, 0, x, z, direction as u16). */
    private const val WARP_OPCODE = 176
    private const val WARP_SIZE = 12
    private const val SCRDEF_END = 0xFD13
    private const val MAX_SCRIPTS = 512

    /** Warp scripts are short (lock, sound, fade, warp): don't look further than this into a script. */
    private const val MAX_SCAN = 0x100
}
