package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.hgss.HgssRomBytes.u16
import dev.kotlinds.pokemonclient.games.hgss.HgssRomBytes.u32
import dev.kotlinds.pokemonclient.games.hgss.HgssRomBytes.u8

/**
 * One entry of `sMapHeaders` (`struct MapHeader`, include/map_header.h): what a zone (map id) is made of.
 * Only the fields the world decoder needs are kept, plus the ones handy to identify a zone.
 */
data class HgssMapHeader(
    /** Zone (map) id, the index in the table (`MAP_*` of include/constants/maps.h). */
    val zoneId: Int,
    /** Member of the map matrix NARC ([HgssWorldAddresses.MAP_MATRIX_NARC]). */
    val matrixId: Int,
    /** Member of the zone event NARC ([HgssWorldAddresses.ZONE_EVENT_NARC]). */
    val eventsBank: Int,
    /** `scr_seq` member holding the zone's scripts (script ids of events index into it). */
    val scriptsBank: Int,
    val scriptHeaderBank: Int,
    /** `msgdata` bank of the zone's texts. */
    val msgBank: Int,
    /** Map section (`MAPSEC_*`): the location name shown on entry. */
    val mapsec: Int,
    /** `MapType` (1 city/town, 2 route, 3 cave, 4 interior...). */
    val mapType: Int,
    /** Wild encounter bank (`ENCDATA_NA` = 0xFF when none). */
    val wildEncounterBank: Int,
    /** `flyAllowed`: Fly (and Teleport) can be used from this zone (outdoors). */
    val flyAllowed: Boolean = true,
    /** `bikeAllowed`: the Bicycle can be ridden in this zone. */
    val bikeAllowed: Boolean = true,
    /** `regionNo`: 0 Johto, 1 Kanto ([HgssMapHeaders.REGION_JOHTO], [HgssMapHeaders.REGION_KANTO]). */
    val region: Int = 0,
)

/** Decodes the map header table from the (decompressed) ARM9 binary. */
object HgssMapHeaders {

    /**
     * Reads [HgssWorldAddresses.MAP_HEADER_COUNT] headers at [tableAddress] of [arm9] (decompressed, loaded at
     * [HgssWorldAddresses.ARM9_LOAD_ADDRESS]).
     *
     * Layout (include/map_header.h, 24 bytes): u8 wildEncounterBank, u8 areaDataBank, u16 bitfield (moveModelBank:4,
     * worldMapX:6, worldMapY:6), u16 matrixId, u16 scriptsBank, u16 scriptHeaderBank, u16 msgBank, u16 dayMusicId,
     * u16 nightMusicId, u16 eventsBank, u16 (mapsec:8, areaIcon:4, momCallIntroParam:4), u32 bitfield (regionNo:1,
     * weather:7, mapType:4, cameraType:6, followMode:2, battleBg:5, bikeAllowed:1, runningAllowed:1,
     * escapeRopeAllowed:1, flyAllowed:1 (bit 28), ...). mwcc allocates bitfields from bit 0.
     */
    fun decode(arm9: ByteArray, tableAddress: Long): List<HgssMapHeader> {
        val base = (tableAddress - HgssWorldAddresses.ARM9_LOAD_ADDRESS).toInt()
        require(base >= 0 && base + HgssWorldAddresses.MAP_HEADER_COUNT * HgssWorldAddresses.MAP_HEADER_SIZE <= arm9.size) {
            "map header table 0x${tableAddress.toString(16)} is outside the ARM9 binary"
        }
        return List(HgssWorldAddresses.MAP_HEADER_COUNT) { id ->
            val o = base + id * HgssWorldAddresses.MAP_HEADER_SIZE
            val flags = u32(arm9, o + 20)
            HgssMapHeader(
                zoneId = id,
                matrixId = u16(arm9, o + 4),
                eventsBank = u16(arm9, o + 16),
                scriptsBank = u16(arm9, o + 6),
                scriptHeaderBank = u16(arm9, o + 8),
                msgBank = u16(arm9, o + 10),
                mapsec = u8(arm9, o + 18),
                mapType = ((flags shr 8) and 0xF).toInt(),
                wildEncounterBank = u8(arm9, o),
                flyAllowed = (flags shr FLY_ALLOWED_BIT) and 1L == 1L,
                bikeAllowed = (flags shr BIKE_ALLOWED_BIT) and 1L == 1L,
                region = (flags and 1L).toInt(),
            )
        }
    }

    /** Bit of `bikeAllowed` in the header's u32 bitfield (see [decode]). */
    private const val BIKE_ALLOWED_BIT = 25

    /** `regionNo` values. */
    const val REGION_JOHTO = 0
    const val REGION_KANTO = 1

    /** Bit of `flyAllowed` in the header's u32 bitfield (see [decode]). */
    private const val FLY_ALLOWED_BIT = 28
}
