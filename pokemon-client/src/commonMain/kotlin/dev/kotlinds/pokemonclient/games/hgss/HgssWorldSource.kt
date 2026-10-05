package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.BlzCodec
import dev.kotlinds.NarcArchive
import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Region
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.PersonTemplate
import dev.kotlinds.pokemonclient.world.ScriptWarp
import dev.kotlinds.pokemonclient.world.Sign
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.Trigger
import dev.kotlinds.pokemonclient.world.TriggerWarp
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WorldSource

/**
 * The static world of HeartGold / SoulSilver, decoded from the ROM:
 * zone → map header (ARM9 `sMapHeaders`) → map matrix (blocks of 32x32 tiles) → land data of each block
 * (collision + behavior per tile, BDHC heights) + zone events (warps, signs, people, coordinate triggers).
 *
 * Coordinates are the game's global tile coordinates, the ones of the player's MapObject x/z (and of the events):
 * tile (x, z) is tile (x % 32, z % 32) of block (x / 32, z / 32) of the zone's matrix. Areas start at (0, 0).
 * Heights ([TileInfo.heights]) are BDHC heights in world units (16 per tile side); the player's MapObject y is
 * that height / 8 (verified live: Ecruteak 48 ↔ y 6, New Bark 16 ↔ y 2). The matrix block altitudes are not added:
 * the loader only uses them to place the 3D model (`altitude << 15`, ov01_021F5FB8), and in the ROM only blocks
 * without a zone (scenery around the routes) have a non-zero altitude.
 *
 * An [Area] is a matrix: the overworld matrix (id 0, with a zone per block) holds every outdoor zone and their
 * events; a matrix without a zone section (buildings, caves, gyms) is loaded for one zone at a time, so its area
 * holds that zone only (two zones sharing such a matrix get two areas with the same [Area.id]).
 *
 * Static data only: the game patches some matrices at load time (Lake of Rage water level, Mahogany antenna tree,
 * Safari Zone areas, src/map_matrix.c) and moves people around; read the RAM for the live state.
 * Everything is decoded lazily and cached.
 */
class HgssWorldSource(private val rom: NdsRom, private val version: HgssVersion) : WorldSource {

    /** Every map header (index = zone id). */
    val headers: List<HgssMapHeader> by lazy {
        val table = HgssWorldAddresses.mapHeadersAddress(version.gameCode)
            ?: error("no map header table address for ${version.gameCode}")
        HgssMapHeaders.decode(BlzCodec.decompress(rom.arm9), table)
    }

    private val matrixFiles: List<ByteArray> by lazy { narc(HgssWorldAddresses.MAP_MATRIX_NARC) }
    private val landFiles: List<ByteArray> by lazy { narc(HgssWorldAddresses.LAND_DATA_NARC) }
    private val eventFiles: List<ByteArray> by lazy { narc(HgssWorldAddresses.ZONE_EVENT_NARC) }
    private val scriptFiles: List<ByteArray> by lazy { narc(HgssWorldAddresses.SCRIPT_NARC) }

    private val matrices = HashMap<Int, HgssMapMatrix>()
    private val lands = HashMap<Int, DecodedLand>()
    private val events = HashMap<Int, HgssZoneEvents>()
    private val areas = HashMap<AreaKey, Area>()

    /** Cache key of an area: a shared matrix, or one zone of a per-zone matrix. */
    private data class AreaKey(val matrixId: Int, val zoneId: Int?)

    /** A land data member with its per-tile heights. */
    private class DecodedLand(val data: HgssLandData) {
        val heights: Array<List<Int>> by lazy { data.bdhc?.tileHeights() ?: Array(TILES_PER_BLOCK) { emptyList() } }
    }

    fun header(zoneId: Int): HgssMapHeader? = headers.getOrNull(zoneId)

    override val zoneCount: Int get() = headers.size

    override fun flyAllowed(zoneId: Int): Boolean? = header(zoneId)?.flyAllowed

    override fun bikeAllowed(zoneId: Int): Boolean? = header(zoneId)?.bikeAllowed

    override fun regionOf(zoneId: Int): Region? = header(zoneId)?.region?.let { Region(it, if (it == HgssMapHeaders.REGION_KANTO) "Kanto" else "Johto") }

    /** The script file (bytecode, [HgssScripts]) of zone [zoneId], or null. */
    fun scriptFile(zoneId: Int): ByteArray? = header(zoneId)?.scriptsBank?.let { scriptFiles.getOrNull(it) }

    fun matrix(matrixId: Int): HgssMapMatrix? =
        matrices[matrixId] ?: matrixFiles.getOrNull(matrixId)?.let { HgssMapMatrix.parse(matrixId, it) }?.also { matrices[matrixId] = it }

    fun landData(landId: Int): HgssLandData? = land(landId)?.data

    /** The raw events of zone [zoneId] (member `eventsBank` of its header). */
    fun events(zoneId: Int): HgssZoneEvents? {
        val bank = header(zoneId)?.eventsBank ?: return null
        return events[bank] ?: eventFiles.getOrNull(bank)?.let { HgssZoneEvents.parse(it) }?.also { events[bank] = it }
    }

    override fun areaOf(zoneId: Int): Area? {
        val header = header(zoneId) ?: return null
        val matrix = matrix(header.matrixId) ?: return null
        val key = AreaKey(matrix.id, if (matrix.zones != null) null else zoneId)
        return areas[key] ?: buildArea(matrix, zoneId).also { areas[key] = it }
    }

    private fun land(landId: Int): DecodedLand? =
        lands[landId] ?: landFiles.getOrNull(landId)?.let { DecodedLand(HgssLandData.parse(it)) }?.also { lands[landId] = it }

    private fun narc(path: String): List<ByteArray> =
        NarcArchive.unpack(rom.files[path] ?: error("missing $path in the ROM"))

    private fun buildArea(matrix: HgssMapMatrix, zoneId: Int): Area {
        val b = HgssMapMatrix.BLOCK_TILES
        val width = matrix.width * b
        val height = matrix.height * b
        val tiles = arrayOfNulls<TileInfo>(width * height)
        val zones = IntArray(width * height) { -1 }
        // Most tiles are identical (same attribute, same flat height): share the instances.
        val interned = HashMap<TileInfo, TileInfo>()
        for (bz in 0 until matrix.height) for (bx in 0 until matrix.width) {
            val landId = matrix.landAt(bx, bz)
            if (landId == HgssMapMatrix.NO_LAND) continue
            val land = land(landId) ?: continue
            // Overworld blocks of zone 0 (MAP_EVERYWHERE) are scenery around the routes: no zone.
            val blockZone = matrix.zoneAt(bx, bz)?.takeIf { it != EVERYWHERE } ?: if (matrix.zones == null) zoneId else -1
            for (lz in 0 until b) for (lx in 0 until b) {
                val attr = land.data.attribute(lx, lz)
                val behavior = attr and 0xFF
                val blocked = attr and COLLISION_BIT != 0
                val info = TileInfo(blocked, HgssTileBehaviors.kind(behavior, blocked), land.heights[lz * b + lx])
                val i = (bz * b + lz) * width + bx * b + lx
                tiles[i] = interned.getOrPut(info) { info }
                zones[i] = blockZone
            }
        }
        val zoneIds = matrix.zones?.let { all -> (all.filter { it != EVERYWHERE }.toSortedSet() + zoneId).toList() } ?: listOf(zoneId)
        val warps = mutableListOf<Warp>()
        val signs = mutableListOf<Sign>()
        val people = mutableListOf<PersonTemplate>()
        val triggers = mutableListOf<Trigger>()
        val scriptWarps = mutableListOf<ScriptWarp>()
        val triggerWarps = mutableListOf<TriggerWarp>()
        fun behaviorAt(x: Int, z: Int): Int? =
            if (x in 0 until width && z in 0 until height) {
                matrix.landAt(x / b, z / b).takeIf { it != HgssMapMatrix.NO_LAND }
                    ?.let { land(it)?.data?.attribute(x % b, z % b)?.and(0xFF) }
            } else null
        for (zone in zoneIds) {
            val ev = events(zone) ?: continue
            ev.warps.forEachIndexed { i, w ->
                val direction = behaviorAt(w.x, w.z)?.let { HgssTileBehaviors.warpDirection(it) }
                warps += Warp(zone, i, w.x, w.z, w.header, w.anchor, direction)
            }
            ev.bgs.forEachIndexed { i, bg -> signs += sign(zone, i, bg) }
            ev.objects.forEach { o ->
                people += PersonTemplate(
                    zone = zone,
                    id = o.id,
                    sprite = o.sprite,
                    x = o.x,
                    y = o.z,
                    facing = DIRECTIONS.getOrNull(o.facing),
                    sightRange = if (o.isTrainer) o.params[0] else 0,
                    script = o.script,
                    hiddenByFlag = o.eventFlag,
                    obstacle = when (HgssData.spriteName(o.sprite)) {
                        "TREE" -> FieldMoveKind.CUT
                        "BREAKROCK" -> FieldMoveKind.ROCK_SMASH
                        "ROCK" -> FieldMoveKind.STRENGTH
                        else -> null
                    },
                )
            }
            val scripts = scriptFile(zone)
            ev.coords.forEachIndexed { i, c ->
                val inert = scripts != null && HgssScripts.isEmpty(scripts, c.script)
                triggers += Trigger(zone, i, c.x, c.z, c.width, c.height, c.script, c.variable, c.value, inert)
            }
            scriptWarps += scriptWarps(zone, ev)
            triggerWarps += triggerWarps(zone, ev)
        }
        val name = if (matrix.zones != null && matrix.id == OVERWORLD_MATRIX) "Johto and Kanto" else HgssData.mapName(zoneId)
        return Area(matrix.id, name, 0, 0, width, height, tiles, warps, signs, people, triggers, zones, scriptWarps, triggerWarps)
    }

    /**
     * A background event: a hidden item when its script is one of the `std_hiddenitem_*` common scripts (the
     * zone_event files give them type 2), whose flag is `script - 8000 + HIDDEN_ITEMS_FLAG_BASE`
     * (HiddenItemScriptNoToFlagId, src/fieldmap.c); a sign otherwise.
     */
    private fun sign(zone: Int, index: Int, bg: dev.kotlinds.pokemonclient.games.gen4.Gen4ZoneEvents.BgEvent): Sign =
        if (bg.script in HIDDEN_ITEM_SCRIPTS) {
            Sign(zone, index, bg.x, bg.z, bg.script, SignKind.HIDDEN_ITEM, bg.script - HIDDEN_ITEM_SCRIPTS.first + HIDDEN_ITEMS_FLAG_BASE)
        } else Sign(zone, index, bg.x, bg.z, bg.script)

    /**
     * Coordinate triggers of [zone] whose script warps the player to another zone: holes ([HgssScripts.zoneWarp]). A
     * trigger on a warp's own tile leading to the warp's map is that warp (New Bark's trigger on the ladder up to Elm's
     * lab 2F runs the warp with a scene around it): the warp already lists it, it is no hole.
     */
    private fun triggerWarps(zone: Int, events: HgssZoneEvents): List<TriggerWarp> {
        if (events.coords.isEmpty()) return emptyList()
        val file = header(zone)?.scriptsBank?.let { scriptFiles.getOrNull(it) } ?: return emptyList()
        return events.coords.mapIndexedNotNull { i, c ->
            HgssScripts.zoneWarp(file, c.script, zone, headers.size)
                ?.takeIf { w -> events.warps.none { it.x == c.x && it.z == c.z && it.header == w.zone } }
                ?.let { w -> TriggerWarp(zone, i, c.x, c.z, w.zone, w.x, w.z) }
        }
    }

    /** Coordinate triggers of [zone] whose script warps the player within [zone] itself ([HgssScripts]). */
    private fun scriptWarps(zone: Int, events: HgssZoneEvents): List<ScriptWarp> {
        if (events.coords.isEmpty()) return emptyList()
        val file = header(zone)?.scriptsBank?.let { scriptFiles.getOrNull(it) } ?: return emptyList()
        return events.coords.mapIndexedNotNull { i, c ->
            HgssScripts.sameZoneWarp(file, c.script, zone)?.let { w -> ScriptWarp(zone, i, w.x, w.z, DIRECTIONS.getOrNull(w.direction)) }
        }
    }

    companion object {
        /** Bit 15 of a terrain attribute: the tile cannot be entered (sub_020548C0, asm/unk_02054648.s). */
        const val COLLISION_BIT = 0x8000

        /** `MAP_EVERYWHERE`: the zone of the overworld blocks that belong to no route or town. */
        const val EVERYWHERE = 0

        /** The Johto/Kanto overworld matrix (`map_matrix_0000_EVERYWHERE`). */
        const val OVERWORLD_MATRIX = 0

        /** `_std_hidden_item` .. `_std_safari - 1` (include/constants/std_script.h): the hidden item scripts. */
        val HIDDEN_ITEM_SCRIPTS = 8000 until 8800

        /** `HIDDEN_ITEMS_FLAG_BASE` (include/constants/flags.h). */
        const val HIDDEN_ITEMS_FLAG_BASE = 800

        private const val TILES_PER_BLOCK = HgssMapMatrix.BLOCK_TILES * HgssMapMatrix.BLOCK_TILES

        /** `facingDirection` values (DIR_NORTH 0, DIR_SOUTH 1, DIR_WEST 2, DIR_EAST 3). */
        private val DIRECTIONS = listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
    }
}
