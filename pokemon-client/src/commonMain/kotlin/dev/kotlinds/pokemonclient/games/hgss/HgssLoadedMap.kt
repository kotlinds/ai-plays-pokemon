package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A

/**
 * The map the game holds in RAM right now: the terrain attributes of the (up to 4) matrix blocks loaded around the
 * player, the events of the current zone, and the player's zone and position. It is what the ROM decoder
 * ([HgssWorldSource]) must agree with (checked by the bench `world` command and HgssWorldSourceTest), and it
 * reflects the run-time patches the ROM doesn't show (Lake of Rage water level, Safari Zone areas).
 *
 * Terrain is resolved like the game's `GetMetatileBehavior` → `FieldSystem.unk60` accessor (asm/unk_02054648.s), as
 * the private tile reader of [HgssReader] does; only normal maps (the field map loader accessor) are supported.
 */
class HgssLoadedMap(memory: Memory, version: HgssVersion) {
    private val m = HgssMemory(memory, version)
    private val fieldSystem: Long? = m.ptr(version.fieldSystemPtr)

    /** Matrix block index (`bz * width + bx`) → address of its 32x32 attribute buffer. */
    private val buffers = LinkedHashMap<Int, Long>()

    /** Matrix width in blocks, 0 when no normal map is loaded. */
    val matrixWidth: Int

    /** Zone (map) id of the player, or null outside the field. */
    val zoneId: Int?

    /** Player position (global tiles), when in the field. */
    val playerX: Int?
    val playerZ: Int?

    /**
     * Player height as the MapObject stores it (`y`): BDHC height / 8, i.e. a [dev.kotlinds.pokemonclient.world.TileInfo]
     * height of 48 is a MapObject y of 6 (checked live in Ecruteak and New Bark).
     */
    val playerHeight: Int?

    init {
        val fs = fieldSystem
        val loader = fs?.let { m.ptr(it + A.FS_MAP_LOADER) }
        var width = 0
        if (fs != null && loader != null && m.u32(fs + A.FS_TERRAIN_ACCESSOR) == version.terrainAccessorLoader) {
            width = m.s32(loader + A.ML_MATRIX_WIDTH).takeIf { it in 1..255 } ?: 0
            if (width > 0) for (i in 0 until A.ML_SLOT_COUNT) {
                val buf = m.ptr(loader + A.ML_BLOCK_BUFFERS + 4L * i) ?: continue
                if (!m.inRam(buf, A.ML_BUFFER_BLOCK_INDEX + 4)) continue
                val block = m.s32(buf + A.ML_BUFFER_BLOCK_INDEX)
                if (block >= 0) buffers[block] = buf
            }
        }
        matrixWidth = width
        zoneId = fs?.let { m.ptr(it + A.FS_LOCATION) }?.let { m.s32(it + A.LOC_MAP_ID) }?.takeIf { it in 0 until 1000 }
        val mo = fs?.let { m.ptr(it + A.FS_PLAYER_AVATAR) }?.let { m.ptr(it + A.PA_MAP_OBJECT) }
        playerX = mo?.let { m.s32(it + A.MO_X) }
        playerZ = mo?.let { m.s32(it + A.MO_Z) }
        playerHeight = mo?.let { m.s32(it + A.MO_Y) }
    }

    /** Matrix block indices currently loaded. */
    val loadedBlocks: Set<Int> get() = buffers.keys

    /** Raw attribute (bit 15 collision, low byte behavior) of global tile (x, z), or null when not loaded. */
    fun attribute(x: Int, z: Int): Int? {
        if (x < 0 || z < 0 || matrixWidth == 0) return null
        val b = HgssMapMatrix.BLOCK_TILES
        if (x / b >= matrixWidth) return null
        val buf = buffers[(z / b) * matrixWidth + x / b] ?: return null
        return m.u16(buf + 2L * ((z % b) * b + x % b))
    }

    /**
     * The events of the current zone (`FieldSystem.mapEvents`, include/map_events_internal.h): the four counts and
     * arrays, re-serialized in the ROM file layout and parsed by [HgssZoneEvents.parse]. Null outside the field.
     */
    fun events(): HgssZoneEvents? {
        val me = fieldSystem?.let { m.ptr(it + A.FS_MAP_EVENTS) } ?: return null
        val sections = listOf(
            Triple(A.ME_NUM_BG, A.ME_BG, A.BG_SIZE),
            Triple(A.ME_NUM_OBJ, A.ME_OBJ, OBJECT_EVENT_SIZE),
            Triple(A.ME_NUM_WARP, A.ME_WARP, A.WARP_SIZE),
            Triple(A.ME_NUM_COORD, A.ME_COORD, A.COORD_SIZE),
        )
        val file = mutableListOf<Byte>()
        for ((countOffset, arrayOffset, size) in sections) {
            val count = m.s32(me + countOffset).takeIf { it in 0..256 } ?: return null
            for (i in 0 until 4) file += (count shr (8 * i)).toByte()
            if (count == 0) continue
            val array = m.ptr(me + arrayOffset, 2) ?: return null
            file += m.bytes(array, (count * size).toInt())?.toList() ?: return null
        }
        return HgssZoneEvents.parse(file.toByteArray())
    }

    private companion object {
        /** `sizeof(ObjectEvent)` (include/map_events_internal.h). */
        const val OBJECT_EVENT_SIZE = 0x20L
    }
}
