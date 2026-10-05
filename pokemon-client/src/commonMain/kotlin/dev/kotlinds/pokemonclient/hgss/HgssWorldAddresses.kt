package dev.kotlinds.pokemonclient.hgss

/**
 * Where the static world data of HeartGold / SoulSilver lives in the ROM: the map header table in the ARM9 binary
 * and the NARC archives of the filesystem. Used by [HgssWorldSource].
 *
 * The NARC paths are the ROM filesystem paths of the decomp's `files/fielddata/...` archives (see the `NARC_*`
 * includes of src/data/map_headers.h); they are the same for every HG/SS build.
 */
object HgssWorldAddresses {

    /** Load address of the ARM9 static binary (ROM header 0x28); the map header table is addressed from it. */
    const val ARM9_LOAD_ADDRESS = 0x02000000L

    /**
     * `static const MapHeader sMapHeaders[]` (src/data/map_headers.h, src/map_header.c) per game code: address in
     * the decompressed ARM9 binary. HeartGold USA: `build/heartgold.us/main.elf.xMAP` line
     * `020F6BE0 000032A0 .rodata sMapHeaders (map_header.o)`.
     */
    private val mapHeaders: Map<String, Long> = mapOf("IPKE" to 0x020F6BE0L)

    /** `sizeof(MapHeader)` (include/map_header.h): 24 bytes. */
    const val MAP_HEADER_SIZE = 24

    /** Number of map headers (0x32A0 / 24 = 540, `NUM_MAPS` in include/constants/maps.h). */
    const val MAP_HEADER_COUNT = 540

    /** `fielddata/mapmatrix/map_matrix.narc`: one matrix (blocks of 32x32 tiles) per `MapHeader.matrixId`. */
    const val MAP_MATRIX_NARC = "a/0/4/1"

    /** `fielddata/land_data/land_data.narc`: one member per matrix block (permissions grid, models, BDHC). */
    const val LAND_DATA_NARC = "a/0/6/5"

    /** `fielddata/eventdata/zone_event.narc`: one member per `MapHeader.eventsBank` (bgs, objects, warps, coords). */
    const val ZONE_EVENT_NARC = "a/0/3/2"

    /** `fielddata/script/scr_seq.narc`: one member per `MapHeader.scriptsBank` (the map's event scripts). */
    const val SCRIPT_NARC = "a/0/1/2"

    /** Address of `sMapHeaders` for the ROM with [gameCode], or null when that ROM is not supported yet. */
    fun mapHeadersAddress(gameCode: String): Long? = mapHeaders[gameCode]
}

/** Little-endian reads of ROM data (the DS is little-endian), the Gen 4 ones ([dev.kotlinds.pokemonclient.gen4.Gen4RomBytes]). */
internal object HgssRomBytes {
    fun u8(b: ByteArray, o: Int): Int = dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.u8(b, o)
    fun u16(b: ByteArray, o: Int): Int = dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.u16(b, o)
    fun s16(b: ByteArray, o: Int): Int = dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.s16(b, o)
    fun s32(b: ByteArray, o: Int): Int = dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.s32(b, o)
    fun u32(b: ByteArray, o: Int): Long = dev.kotlinds.pokemonclient.gen4.Gen4RomBytes.u32(b, o)
}
