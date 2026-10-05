package dev.kotlinds.pokemonclient.gen4

import dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.s16
import dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.s32
import dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.u16
import dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.u32

/**
 * The events of a zone as stored in the ROM (NARC `fielddata/eventdata/zone_event.narc`, member
 * `MapHeader.eventsBank`), with the raw fields of include/map_events_internal.h. The readable source of these files
 * is the decomp's `files/fielddata/eventdata/zone_event/<bank>_<name>.json`.
 *
 * File layout: u32 count + BG_EVENT[count] (0x14 bytes), u32 count + ObjectEvent[count] (0x20), u32 count +
 * WARP_EVENT[count] (0x0C), u32 count + COORD_EVENT[count] (0x10). Coordinates are global matrix tiles (the game
 * compares them directly with the player's position, src/map_events.c).
 */
data class Gen4ZoneEvents(
    val bgs: List<BgEvent>,
    val objects: List<ObjectEvent>,
    val warps: List<WarpEvent>,
    val coords: List<CoordEvent>,
) {
    /** `BG_EVENT`: something examined with A (sign, hidden item...). [type] 0 = script, 2 = hidden item (`std_hiddenitem` scripts in the JSON files). */
    data class BgEvent(val script: Int, val type: Int, val x: Int, val z: Int, val y: Int, val dir: Int)

    /**
     * `ObjectEvent`: a person or object. [type] 1, 2, 4..8 are trainers (src/overlay_26_022599D0.c), whose sight
     * range is `param[0]`. [y] is an fx32 height, not always reliable (NOTES 17s): trust the BDHC.
     */
    data class ObjectEvent(
        val id: Int, val sprite: Int, val movement: Int, val type: Int, val eventFlag: Int, val script: Int,
        val facing: Int, val params: List<Int>, val xRange: Int, val zRange: Int, val x: Int, val z: Int, val y: Int,
    ) {
        val isTrainer: Boolean get() = type in TRAINER_TYPES
    }

    /** `WARP_EVENT`: stepping on (x, z) leads to warp [anchor] of zone [header]. */
    data class WarpEvent(val x: Int, val z: Int, val header: Int, val anchor: Int, val y: Long)

    /** `COORD_EVENT`: runs [script] when the player enters the rectangle while variable [variable] == [value]. */
    data class CoordEvent(val script: Int, val x: Int, val z: Int, val width: Int, val height: Int, val y: Int, val value: Int, val variable: Int)

    companion object {
        /** Object types handled as trainers (src/overlay_26_022599D0.c: case 1, 2, 4-8). */
        val TRAINER_TYPES = setOf(1, 2, 4, 5, 6, 7, 8)

        fun parse(b: ByteArray): Gen4ZoneEvents {
            if (b.size < 16) return Gen4ZoneEvents(emptyList(), emptyList(), emptyList(), emptyList())
            var o = 0
            fun <T> section(size: Int, read: (Int) -> T): List<T> {
                val count = u32(b, o).toInt()
                val base = o + 4
                require(count in 0..1024 && base + count * size <= b.size) { "bad zone event section at 0x${o.toString(16)}" }
                o = base + count * size
                return List(count) { read(base + it * size) }
            }
            val bgs = section(0x14) { p -> BgEvent(u16(b, p), u16(b, p + 2), s32(b, p + 4), s32(b, p + 8), s32(b, p + 12), u16(b, p + 16)) }
            val objects = section(0x20) { p ->
                ObjectEvent(
                    id = u16(b, p), sprite = u16(b, p + 2), movement = u16(b, p + 4), type = u16(b, p + 6),
                    eventFlag = u16(b, p + 8), script = u16(b, p + 10), facing = s16(b, p + 12),
                    params = listOf(u16(b, p + 14), u16(b, p + 16), u16(b, p + 18)),
                    xRange = s16(b, p + 20), zRange = s16(b, p + 22), x = u16(b, p + 24), z = u16(b, p + 26), y = s32(b, p + 28),
                )
            }
            val warps = section(0x0C) { p -> WarpEvent(u16(b, p), u16(b, p + 2), u16(b, p + 4), u16(b, p + 6), u32(b, p + 8)) }
            val coords = section(0x10) { p ->
                CoordEvent(u16(b, p), s16(b, p + 2), s16(b, p + 4), u16(b, p + 6), u16(b, p + 8), u16(b, p + 10), u16(b, p + 12), u16(b, p + 14))
            }
            return Gen4ZoneEvents(bgs, objects, warps, coords)
        }
    }
}
