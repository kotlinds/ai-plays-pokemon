package dev.kotlinds.pokemonclient.gen4

import dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.s32
import dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.u16
import dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.u32
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One land data member (NARC `fielddata/land_data/land_data.narc`, not extracted by the decomp): the 32x32 tile
 * permissions of a matrix block and its BDHC height surfaces. (Building placements and the NSBMD model are skipped.)
 *
 * Layout (found by the agent, NOTES-partie-claude.md 17s / 17ad, checked on every member of the HG US ROM):
 * - 0x00 u32 permissions size (0x800), 0x04 u32 buildings size, 0x08 u32 model size, 0x0C u32 BDHC size;
 * - 0x10 u16 marker 0x1234, 0x12 u16 length of an extra header (0 for most members, 0x18 for the lighthouse
 *   exterior, 0x58 for some outdoor blocks): the permissions start at `0x14 + extra`;
 * - permissions (32x32 u16, row-major), buildings, model, BDHC, back to back.
 */
class Gen4LandData(
    /** Raw attribute of each tile (`z * 32 + x`): bit 15 collision, bits 0-7 behavior (asm/unk_02054648.s). */
    val attributes: IntArray,
    /** Height surfaces, or null when the member has none. */
    val bdhc: Gen4Bdhc?,
) {
    fun attribute(x: Int, z: Int): Int = attributes[z * BLOCK + x]

    companion object {
        private const val BLOCK = Gen4MapMatrix.BLOCK_TILES
        private const val MARKER = 0x1234

        fun parse(b: ByteArray): Gen4LandData {
            val permissionsSize = u32(b, 0x00).toInt()
            val buildingsSize = u32(b, 0x04).toInt()
            val modelSize = u32(b, 0x08).toInt()
            val bdhcSize = u32(b, 0x0C).toInt()
            val start = 0x14 + if (u16(b, 0x10) == MARKER) u16(b, 0x12) else -4
            require(permissionsSize >= BLOCK * BLOCK * 2) { "land data without a permission grid" }
            val attributes = IntArray(BLOCK * BLOCK) { u16(b, start + 2 * it) }
            val bdhcStart = start + permissionsSize + buildingsSize + modelSize
            val bdhc = if (bdhcSize > 0 && bdhcStart + bdhcSize <= b.size) Gen4Bdhc.parse(b, bdhcStart) else null
            return Gen4LandData(attributes, bdhc)
        }
    }
}

/**
 * BDHC: the walkable surfaces of a block, as axis-aligned rectangles ("plates") on planes. The game takes the
 * height of a position from the plates containing it; several plates can cover one tile (bridges, two-level gyms),
 * and the player stays on the one closest to their current height (NOTES 17s).
 *
 * Layout: "BDHC", u16 counts (points, normals, constants, plates, strips, accesses), then points (fx32 x, z),
 * normals (fx32 x, y, z), constants (fx32 d), plates (u16 point1, point2, normal, constant). Strips / accesses
 * (an index by z for fast lookup) are not needed here.
 *
 * Coordinates are block-local, centered on the block: x, z in `[-256, 256]` units (16 units per tile).
 */
class Gen4Bdhc(private val plates: List<Plate>) {

    /** A rectangle [x1, x2] x [z1, z2] on the plane `nx*x + ny*y + nz*z + d = 0` (units, not fx32). */
    data class Plate(val x1: Double, val z1: Double, val x2: Double, val z2: Double, val nx: Double, val ny: Double, val nz: Double, val d: Double)

    /**
     * Heights (units, rounded) of the surfaces at the center of each tile of the block (`z * 32 + x`), sorted and
     * without duplicates; an empty list where no plate covers the tile.
     */
    fun tileHeights(): Array<List<Int>> {
        val result = Array<MutableList<Int>?>(BLOCK * BLOCK) { null }
        for (p in plates) {
            if (p.ny == 0.0) continue // vertical plane: no height
            // Tile x has its center at x*16 - 248: take the tiles whose center lies inside the plate (inclusive).
            val tx0 = max(0, ceil((min(p.x1, p.x2) + 248) / 16).toInt())
            val tx1 = min(BLOCK - 1, floor((max(p.x1, p.x2) + 248) / 16).toInt())
            val tz0 = max(0, ceil((min(p.z1, p.z2) + 248) / 16).toInt())
            val tz1 = min(BLOCK - 1, floor((max(p.z1, p.z2) + 248) / 16).toInt())
            for (tz in tz0..tz1) for (tx in tx0..tx1) {
                val wx = tx * 16.0 - 248
                val wz = tz * 16.0 - 248
                val h = (-(p.nx * wx + p.nz * wz + p.d) / p.ny).roundToInt()
                val list = result[tz * BLOCK + tx] ?: mutableListOf<Int>().also { result[tz * BLOCK + tx] = it }
                if (h !in list) list += h
            }
        }
        return Array(BLOCK * BLOCK) { i -> result[i]?.sorted() ?: emptyList() }
    }

    companion object {
        private const val BLOCK = Gen4MapMatrix.BLOCK_TILES
        private const val FX = 4096.0

        fun parse(b: ByteArray, start: Int): Gen4Bdhc? {
            if (b.decodeToString(start, start + 4) != "BDHC") return null
            val points = u16(b, start + 4)
            val normals = u16(b, start + 6)
            val constants = u16(b, start + 8)
            val plates = u16(b, start + 10)
            var o = start + 16
            val px = DoubleArray(points) { s32(b, o + 8 * it) / FX }
            val pz = DoubleArray(points) { s32(b, o + 8 * it + 4) / FX }
            o += 8 * points
            val nx = DoubleArray(normals) { s32(b, o + 12 * it) / FX }
            val ny = DoubleArray(normals) { s32(b, o + 12 * it + 4) / FX }
            val nz = DoubleArray(normals) { s32(b, o + 12 * it + 8) / FX }
            o += 12 * normals
            val d = DoubleArray(constants) { s32(b, o + 4 * it) / FX }
            o += 4 * constants
            val list = List(plates) {
                val p = o + 8 * it
                val a = u16(b, p)
                val c = u16(b, p + 2)
                val n = u16(b, p + 4)
                val k = u16(b, p + 6)
                Plate(px[a], pz[a], px[c], pz[c], nx[n], ny[n], nz[n], d[k])
            }
            return Gen4Bdhc(list)
        }
    }
}
