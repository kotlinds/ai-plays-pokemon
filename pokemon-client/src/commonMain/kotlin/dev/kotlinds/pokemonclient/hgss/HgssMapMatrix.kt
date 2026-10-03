package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u16
import dev.kotlinds.pokemonclient.hgss.HgssRomBytes.u8

/**
 * A map matrix (`fielddata/mapmatrix`, NARC [HgssWorldAddresses.MAP_MATRIX_NARC]): a grid of blocks of 32x32 tiles
 * that share one coordinate space. The player's global tile position (MapObject x/z) is `block * 32 + local`, so
 * tile (x, z) of the matrix is tile (x % 32, z % 32) of the land data of block (x / 32, z / 32).
 *
 * The Johto/Kanto overworld is matrix 0 (47x17 blocks) and carries a zone id per block; buildings and caves are
 * small matrices without one (every block belongs to the zone being loaded).
 */
class HgssMapMatrix(
    val id: Int,
    /** Internal name (e.g. `map`, `m_gym0401_`). */
    val name: String,
    /** Size in blocks. */
    val width: Int,
    val height: Int,
    /** Zone id of each block (row-major), or null when the matrix has no header section. */
    val zones: IntArray?,
    /** Altitude of each block (row-major), or null. The loader places a block's model at `altitude << 15` fx32. */
    val altitudes: IntArray?,
    /** Land data member of each block (row-major), [NO_LAND] where the block is empty. */
    val landData: IntArray,
) {
    fun landAt(bx: Int, bz: Int): Int = landData[bz * width + bx]
    fun zoneAt(bx: Int, bz: Int): Int? = zones?.get(bz * width + bx)
    fun altitudeAt(bx: Int, bz: Int): Int = altitudes?.get(bz * width + bx) ?: 0

    companion object {
        /** Land data id of an empty block. */
        const val NO_LAND = 0xFFFF

        /** Tiles per block side. */
        const val BLOCK_TILES = 32

        /**
         * Parses a matrix member (src/map_matrix.c MapMatrix_MapMatrixData_Load): u8 width, u8 height,
         * u8 hasHeaders, u8 hasAltitudes, u8 nameLength, name, [u16 headers[w*h]], [u8 altitudes[w*h]],
         * u16 landData[w*h].
         */
        fun parse(id: Int, b: ByteArray): HgssMapMatrix {
            val width = u8(b, 0)
            val height = u8(b, 1)
            val hasHeaders = u8(b, 2) != 0
            val hasAltitudes = u8(b, 3) != 0
            val nameLength = u8(b, 4)
            val name = b.copyOfRange(5, 5 + nameLength).decodeToString().trimEnd('\u0000')
            val n = width * height
            var o = 5 + nameLength
            val zones = if (hasHeaders) IntArray(n) { u16(b, o + 2 * it) }.also { o += 2 * n } else null
            val altitudes = if (hasAltitudes) IntArray(n) { u8(b, o + it) }.also { o += n } else null
            val land = IntArray(n) { u16(b, o + 2 * it) }
            return HgssMapMatrix(id, name, width, height, zones, altitudes, land)
        }
    }
}
