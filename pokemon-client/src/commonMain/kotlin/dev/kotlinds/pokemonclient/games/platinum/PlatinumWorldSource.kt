package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.BlzCodec
import dev.kotlinds.NarcArchive
import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.games.gen4.Gen4LandData
import dev.kotlinds.pokemonclient.games.gen4.Gen4MapMatrix
import dev.kotlinds.pokemonclient.games.gen4.Gen4MessageFile
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes.u16
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes.u8
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs
import dev.kotlinds.pokemonclient.games.gen4.Gen4ZoneEvents
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.PersonTemplate
import dev.kotlinds.pokemonclient.world.Sign
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Trigger
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WorldSource

/**
 * The text banks of Platinum (`msgdata/pl_msg.narc`, member = `TEXT_BANK_*` of the decomp's generated
 * text_banks.h), decoded with the Gen 4 message format ([Gen4MessageFile]). The bank numbers are the same in every
 * language release.
 */
class PlatinumText(private val rom: NdsRom) {
    private val files: List<ByteArray> by lazy { NarcArchive.unpack(rom.files[MESSAGE_NARC] ?: error("missing $MESSAGE_NARC")) }

    /** Line [line] of bank [bank], control codes removed; null when out of range. */
    fun line(bank: Int, line: Int): String? = files.getOrNull(bank)?.let { Gen4MessageFile(it).line(line) }

    companion object {
        const val MESSAGE_NARC = "msgdata/pl_msg.narc"

        /** TEXT_BANK_MAIN_MENU_ALERTS: line 5 is the new game warning. */
        const val MAIN_MENU_ALERTS = 14
        const val MAIN_MENU_ALERT_NEW_GAME = 5

        /** TEXT_BANK_ROWAN_INTRO (res/text/rowan_intro.json). */
        const val ROWAN_INTRO = 389

        /** TEXT_BANK_LOCATION_NAMES: index = `MapHeader.mapLabelTextID`. */
        const val LOCATION_NAMES = 433

        /** TEXT_BANK_MAIN_MENU_OPTIONS (MainMenuOptions_Text_*). */
        const val MAIN_MENU_OPTIONS = 550

        /** TEXT_BANK_ROWAN_INTRO_TV_APP: line 0 is the TV programme. */
        const val ROWAN_INTRO_TV = 607
    }
}

/** One entry of Platinum's `sMapHeaders` (include/map_header.h): only what the world decoder needs. */
data class PlatinumMapHeader(
    val zoneId: Int,
    val matrixId: Int,
    val scriptsBank: Int,
    val eventsBank: Int,
    /** `mapLabelTextID`: the location name's line in [PlatinumText.LOCATION_NAMES]. */
    val locationName: Int,
    /** `mapType` (7 bits). */
    val mapType: Int,
    val bikeAllowed: Boolean,
    val flyAllowed: Boolean,
)

/** Decodes Platinum's map header table from the (decompressed) ARM9 binary. */
object PlatinumMapHeaders {
    /** NELEMS(sMapHeaders): 0x3798 bytes / 24 (xMAP). */
    const val COUNT = 593
    const val SIZE = 24
    const val ARM9_LOAD_ADDRESS = 0x02000000L

    /**
     * Layout (include/map_header.h, 24 bytes): u8 areaDataArchiveID, u8 unk_01, u16 mapMatrixID, u16 scriptsArchiveID,
     * u16 initScriptsArchiveID, u16 msgArchiveID, u16 dayMusicID, u16 nightMusicID, u16 wildEncountersArchiveID,
     * u16 eventsArchiveID, u16 (mapLabelTextID:8, mapLabelWindowID:8), u8 weather, u8 cameraType, u16 (mapType:7,
     * battleBG:5, isBikeAllowed:1, isRunningAllowed:1, isEscapeRopeAllowed:1, isFlyAllowed:1). Bitfields from bit 0.
     * (Not the HGSS layout: [dev.kotlinds.pokemonclient.games.hgss.HgssMapHeaders].)
     */
    fun decode(arm9: ByteArray, tableAddress: Long): List<PlatinumMapHeader> {
        val base = (tableAddress - ARM9_LOAD_ADDRESS).toInt()
        require(base >= 0 && base + COUNT * SIZE <= arm9.size) { "map header table outside the ARM9 binary" }
        return List(COUNT) { id ->
            val o = base + id * SIZE
            val flags = u16(arm9, o + 22)
            PlatinumMapHeader(
                zoneId = id,
                matrixId = u16(arm9, o + 2),
                scriptsBank = u16(arm9, o + 4),
                eventsBank = u16(arm9, o + 16),
                locationName = u8(arm9, o + 18),
                mapType = flags and 0x7F,
                bikeAllowed = (flags shr 12) and 1 == 1,
                flyAllowed = (flags shr 15) and 1 == 1,
            )
        }
    }
}

/**
 * Platinum's tile behaviours (`enum TileBehavior`, include/constants/field/map_tile_behaviors.h) as [TileKind]s.
 * Most codes are the Gen 4 ones HeartGold / SoulSilver also use (water 0x10-0x15, ledges 0x38-0x3B, warps 0x5E-0x6F,
 * door 0x69, bridges 0x70-0x75, counter 0x80, PC 0x83); a few differ (0x2C is REFLECTIVE here, MAGMA in HGSS; no
 * ladders at 0x3C-0x3E; no whirlpool 0x11), so the table is per game.
 */
object PlatinumTileBehaviors {
    private val WATER = setOf(0x10, 0x15, 0x19, 0x7C)
    private val GRASS = setOf(0x02, 0x03, 0xA6, 0xA7)
    private val WARPS = (0x5E..0x6F).toSet() - setOf(0x60, 0x61, 0x66, 0x68)
    private val RAILINGS = mapOf(
        0x30 to setOf(Direction.EAST), 0x31 to setOf(Direction.WEST), 0x32 to setOf(Direction.NORTH), 0x33 to setOf(Direction.SOUTH),
        0x34 to setOf(Direction.NORTH, Direction.EAST), 0x35 to setOf(Direction.NORTH, Direction.WEST),
        0x36 to setOf(Direction.SOUTH, Direction.EAST), 0x37 to setOf(Direction.SOUTH, Direction.WEST),
        0x49 to setOf(Direction.NORTH, Direction.SOUTH), 0x4A to setOf(Direction.WEST, Direction.EAST),
    )

    fun kind(behavior: Int, blocked: Boolean): TileKind = when (behavior) {
        0x00 -> if (blocked) TileKind.Wall else TileKind.Floor
        in GRASS -> TileKind.TallGrass
        0x08 -> TileKind.Cave
        0x13 -> TileKind.Waterfall
        in WATER -> TileKind.Water(surfable = true, fishable = true)
        0x20 -> TileKind.Ice
        0x21 -> TileKind.Sand
        0x38 -> TileKind.Ledge(Direction.EAST)
        0x39 -> TileKind.Ledge(Direction.WEST)
        0x3A -> TileKind.Ledge(Direction.NORTH)
        0x3B -> TileKind.Ledge(Direction.SOUTH)
        in RAILINGS -> if (blocked) TileKind.Wall else TileKind.Railing(RAILINGS.getValue(behavior))
        0x4B, 0x4C -> TileKind.RockClimb
        0x70 -> if (blocked) TileKind.Wall else TileKind.Bridge(start = true)
        0x71, 0x72, 0x74, 0x75 -> if (blocked) TileKind.Wall else TileKind.Bridge()
        0x73 -> TileKind.Bridge(overWater = true)
        0x80 -> TileKind.Counter
        0x83 -> TileKind.Pc
        in WARPS -> TileKind.Door
        else -> if (blocked) TileKind.Wall else TileKind.Floor
    }

    /** The direction to press on a warp tile (exit mats, side stairs), null when stepping on it is enough (doors). */
    fun warpDirection(behavior: Int): Direction? = when (behavior) {
        0x62, 0x6C, 0x5E -> Direction.EAST
        0x63, 0x6D, 0x5F -> Direction.WEST
        0x64, 0x6E -> Direction.NORTH
        0x65, 0x6F -> Direction.SOUTH
        else -> null
    }
}

/**
 * The static world of Platinum from the ROM: zone → map header ([PlatinumMapHeaders]) → map matrix
 * ([Gen4MapMatrix], `fielddata/mapmatrix/map_matrix.narc`) → land data of each block ([Gen4LandData],
 * `fielddata/land_data/land_data.narc`: 0x10-byte header, no 0x1234 marker) + zone events ([Gen4ZoneEvents],
 * `fielddata/eventdata/zone_event.narc`). The file formats are the Gen 4 ones HGSS uses; only the header table, the
 * NARC paths and the tile behaviours differ.
 *
 * Basic support: tiles (collision, behaviour, BDHC heights), warps, signs, people and coordinate triggers. Not done
 * yet: hidden items, script warps, trigger warps, region / dynamic matrices (the Distortion World...).
 */
class PlatinumWorldSource(private val rom: NdsRom, private val version: PlatinumVersion) : WorldSource {

    val headers: List<PlatinumMapHeader> by lazy {
        val arm9 = runCatching { BlzCodec.decompress(rom.arm9) }.getOrNull()?.takeIf { it.size > rom.arm9.size } ?: rom.arm9
        PlatinumMapHeaders.decode(arm9, version.mapHeaders)
    }

    val text = PlatinumText(rom)

    private val matrixFiles by lazy { narc(MAP_MATRIX_NARC) }
    private val landFiles by lazy { narc(LAND_DATA_NARC) }
    private val eventFiles by lazy { narc(ZONE_EVENT_NARC) }
    private val areas = HashMap<Pair<Int, Int?>, Area>()
    private val lands = HashMap<Int, Gen4LandData>()

    fun header(zoneId: Int): PlatinumMapHeader? = headers.getOrNull(zoneId)

    override val zoneCount: Int get() = headers.size

    override fun flyAllowed(zoneId: Int): Boolean? = header(zoneId)?.flyAllowed

    override fun bikeAllowed(zoneId: Int): Boolean? = header(zoneId)?.bikeAllowed

    fun matrix(matrixId: Int): Gen4MapMatrix? = matrixFiles.getOrNull(matrixId)?.let { Gen4MapMatrix.parse(matrixId, it) }

    fun landData(landId: Int): Gen4LandData? =
        lands[landId] ?: landFiles.getOrNull(landId)?.let { Gen4LandData.parse(it) }?.also { lands[landId] = it }

    fun events(zoneId: Int): Gen4ZoneEvents? = header(zoneId)?.eventsBank?.let { eventFiles.getOrNull(it) }?.let { Gen4ZoneEvents.parse(it) }

    /** The location name of zone [zoneId] (e.g. "Twinleaf Town"), null when unknown. */
    fun locationName(zoneId: Int): String? = header(zoneId)?.let { text.line(PlatinumText.LOCATION_NAMES, it.locationName) }

    override fun areaOf(zoneId: Int): Area? {
        val header = header(zoneId) ?: return null
        val matrix = matrix(header.matrixId) ?: return null
        val key = header.matrixId to (if (matrix.zones != null) null else zoneId)
        return areas[key] ?: buildArea(matrix, zoneId).also { areas[key] = it }
    }

    private fun narc(path: String): List<ByteArray> = NarcArchive.unpack(rom.files[path] ?: error("missing $path in the ROM"))

    private fun buildArea(matrix: Gen4MapMatrix, zoneId: Int): Area {
        val b = Gen4MapMatrix.BLOCK_TILES
        val width = matrix.width * b
        val height = matrix.height * b
        val tiles = arrayOfNulls<TileInfo>(width * height)
        val zones = IntArray(width * height) { -1 }
        val interned = HashMap<TileInfo, TileInfo>()
        for (bz in 0 until matrix.height) for (bx in 0 until matrix.width) {
            val landId = matrix.landAt(bx, bz)
            if (landId == Gen4MapMatrix.NO_LAND) continue
            val land = landData(landId) ?: continue
            val heights = land.bdhc?.tileHeights()
            val blockZone = matrix.zoneAt(bx, bz)?.takeIf { it != EVERYWHERE } ?: if (matrix.zones == null) zoneId else -1
            for (lz in 0 until b) for (lx in 0 until b) {
                val attr = land.attribute(lx, lz)
                val blocked = attr and COLLISION_BIT != 0
                val info = TileInfo(blocked, PlatinumTileBehaviors.kind(attr and 0xFF, blocked), heights?.get(lz * b + lx) ?: emptyList())
                val i = (bz * b + lz) * width + bx * b + lx
                tiles[i] = interned.getOrPut(info) { info }
                zones[i] = blockZone
            }
        }
        fun behaviorAt(x: Int, z: Int): Int? =
            if (x in 0 until width && z in 0 until height) {
                matrix.landAt(x / b, z / b).takeIf { it != Gen4MapMatrix.NO_LAND }?.let { landData(it)?.attribute(x % b, z % b)?.and(0xFF) }
            } else null
        val zoneIds = matrix.zones?.let { all -> (all.filter { it != EVERYWHERE }.toSortedSet() + zoneId).toList() } ?: listOf(zoneId)
        val warps = mutableListOf<Warp>()
        val signs = mutableListOf<Sign>()
        val people = mutableListOf<PersonTemplate>()
        val triggers = mutableListOf<Trigger>()
        for (zone in zoneIds) {
            val ev = events(zone) ?: continue
            ev.warps.forEachIndexed { i, w ->
                warps += Warp(zone, i, w.x, w.z, w.header, w.anchor, behaviorAt(w.x, w.z)?.let(PlatinumTileBehaviors::warpDirection))
            }
            ev.bgs.forEachIndexed { i, bg -> signs += Sign(zone, i, bg.x, bg.z, bg.script) }
            ev.objects.forEach { o ->
                people += PersonTemplate(
                    zone = zone, id = o.id, sprite = o.sprite, x = o.x, y = o.z, facing = Gen4Structs.DIRECTIONS.getOrNull(o.facing),
                    sightRange = if (o.isTrainer) o.params[0] else 0, script = o.script, hiddenByFlag = o.eventFlag,
                )
            }
            ev.coords.forEachIndexed { i, c -> triggers += Trigger(zone, i, c.x, c.z, c.width, c.height, c.script, c.variable, c.value) }
        }
        val name = if (matrix.zones != null) "Sinnoh" else locationName(zoneId) ?: "zone $zoneId"
        return Area(matrix.id, name, 0, 0, width, height, tiles, warps, signs, people, triggers, zones)
    }

    companion object {
        const val MAP_MATRIX_NARC = "fielddata/mapmatrix/map_matrix.narc"
        const val LAND_DATA_NARC = "fielddata/land_data/land_data.narc"
        const val ZONE_EVENT_NARC = "fielddata/eventdata/zone_event.narc"

        /** Bit 15 of a terrain attribute: the tile cannot be entered (same in every Gen 4 game). */
        const val COLLISION_BIT = 0x8000

        /** MAP_EVERYWHERE: the zone of overworld blocks that belong to no route or town. */
        const val EVERYWHERE = 0
    }
}
