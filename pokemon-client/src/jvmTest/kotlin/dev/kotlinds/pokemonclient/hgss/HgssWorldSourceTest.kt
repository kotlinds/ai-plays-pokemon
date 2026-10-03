package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.TileKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [HgssWorldSource] on the real HeartGold (USA) ROM (skipped without `POKEMON_ROM`, see [HgssWorldRom]).
 *
 * Expected values come from the decomp (src/data/map_headers.h, `files/fielddata/eventdata/zone_event/<bank>_<name>.json`, copied
 * in the test resources under `hgss/zone_event/`) and from the agent's notes (NOTES-partie-claude.md 17r-17ad).
 * Zone ids: Route 29 = 33, New Bark = 60, Ecruteak = 78, Ecruteak Gym = 80, Ecruteak Pokémon Center 1F = 81,
 * Goldenrod Gym = 137, Olivine Lighthouse 4F = 223 (include/constants/maps.h order; the NOTES quote the event bank
 * numbers, e.g. "133_T25GYM0101" is the Goldenrod Gym's event bank, not its zone).
 *
 * The live checks use sparse RAM fixtures captured with the bench `world:<name>` command (DeSmuME, 8-badge save):
 * every attribute of the blocks loaded in RAM equals the ROM land data, and the zone events in RAM equal the ROM's.
 */
class HgssWorldSourceTest {

    private val world get() = HgssWorldRom.require()

    // ---------------------------------------------------------------------------------------------------------
    // Map headers and matrices
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `map headers match the decomp`() {
        val w = world
        assertEquals(HgssWorldAddresses.MAP_HEADER_COUNT, w.headers.size)
        // [MAP_NEW_BARK]: matrix 0 (EVERYWHERE), events 057_T20, scripts 0842, msg 0542, MAP_TYPE_CITY_TOWN.
        val newBark = assertNotNull(w.header(60))
        assertEquals(listOf(0, 57, 842, 615, 542, 1), listOf(newBark.matrixId, newBark.eventsBank, newBark.scriptsBank, newBark.scriptHeaderBank, newBark.msgBank, newBark.mapType))
        // [MAP_ECRUTEAK_GYM]: matrix 0090_T27GYM0101, events 077, scripts 0922, msg 0614, MAP_TYPE_INTERIOR, ENCDATA_NA.
        val gym = assertNotNull(w.header(80))
        assertEquals(listOf(90, 77, 922, 614, 4, 0xFF), listOf(gym.matrixId, gym.eventsBank, gym.scriptsBank, gym.msgBank, gym.mapType, gym.wildEncounterBank))
        // Route 29 (MAP_TYPE_ROUTE) and the Goldenrod Gym (matrix 0089_T25GYM0101, events 133).
        assertEquals(Triple(0, 30, 2), w.header(33)!!.let { Triple(it.matrixId, it.eventsBank, it.mapType) })
        assertEquals(89 to 133, w.header(137)!!.let { it.matrixId to it.eventsBank })
        assertEquals(null, w.header(HgssWorldAddresses.MAP_HEADER_COUNT))
    }

    @Test
    fun `matrices give the size, the land data and the zone of each block`() {
        val w = world
        val overworld = assertNotNull(w.matrix(0))
        assertEquals(47 to 17, overworld.width to overworld.height)
        assertNotNull(overworld.zones)
        val gym = assertNotNull(w.matrix(90))
        assertEquals(1 to 2, gym.width to gym.height)
        assertEquals(listOf(251, 252), gym.landData.toList()) // NOTES 17y: land_data members 251 and 252
        assertEquals(null, gym.zones)
        assertEquals(listOf(250), w.matrix(89)!!.landData.toList()) // NOTES 17s: Goldenrod Gym = member 250

        val area = assertNotNull(w.areaOf(33))
        assertSame(area, w.areaOf(60), "the outdoor zones share the overworld area (cached)")
        assertEquals(0, area.id)
        assertEquals(47 * 32 to 17 * 32, area.width to area.height)
        assertEquals(33, area.zoneAt(608, 407))
        assertEquals(60, area.zoneAt(695, 397))
        assertEquals(78, area.zoneAt(397, 184))
        assertEquals(null, area.zoneAt(-1, 0))
        assertEquals(null, area.zoneAt(0, 0), "scenery block of MAP_EVERYWHERE")
        assertTrue(area.warps.any { it.zone == 60 } && area.warps.any { it.zone == 78 }, "events of every outdoor zone")

        val gymArea = assertNotNull(w.areaOf(80))
        assertEquals(90, gymArea.id)
        assertEquals("Ecruteak Gym", gymArea.name)
        assertEquals(32 to 64, gymArea.width to gymArea.height)
        assertEquals(80, gymArea.zoneAt(16, 49))
        assertEquals(null, w.areaOf(-1))
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tiles
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `route 29 ledge at 608,407 jumps south`() {
        val area = world.areaOf(33)!!
        val ledge = assertNotNull(area.tile(608, 407))
        assertEquals(TileKind.Ledge(Direction.SOUTH), ledge.kind)
        assertTrue(ledge.blocked, "ledges carry the collision bit: the jump comes from the behavior")
        assertEquals(TileInfoOf(false, TileKind.Floor, 16), area.tile(608, 406).summary())
        assertEquals(TileInfoOf(false, TileKind.Floor, 16), area.tile(608, 408).summary())
    }

    @Test
    fun `walkable spots of New Bark, Route 29 and Ecruteak`() {
        val area = world.areaOf(78)!!
        // Positions where the player stood (fixtures nb1, fol1, world_ecruteak) and a Route 29 spot next to the ledge.
        for ((x, z) in listOf(695 to 397, 676 to 397, 660 to 397, 608 to 406, 397 to 184)) {
            val tile = assertNotNull(area.tile(x, z), "($x,$z)")
            assertTrue(!tile.blocked && tile.kind == TileKind.Floor, "($x,$z) is walkable: $tile")
        }
        assertEquals(listOf(16), area.tile(695, 397)!!.heights)
        assertEquals(listOf(48), area.tile(397, 184)!!.heights)
        // The Ecruteak Gym door, one tile north of the street.
        assertEquals(TileKind.Door, area.tile(376, 183)!!.kind)
        val door = area.warps.single { it.zone == 78 && it.x == 376 && it.y == 183 }
        assertEquals(80 to 0, door.targetZone to door.targetWarp)
        assertEquals(null, door.exitDirection)
    }

    @Test
    fun `Goldenrod Gym has two walkable levels`() {
        val gym = world.areaOf(137)!!
        // (13,10): the walkway crosses over the corridor leading to Whitney (NOTES 17r): ground and upper level.
        assertEquals(listOf(0, 52), gym.tile(13, 10)!!.heights)
        assertTrue(!gym.tile(13, 10)!!.blocked)
        assertEquals(listOf(0), gym.tile(13, 4)!!.heights) // Whitney, on the ground
        assertEquals(listOf(52), gym.tile(6, 11)!!.heights) // the Beauty, on the walkway
        // Stairs: intermediate heights create the transitions (NOTES 17s: 8.7 / 26 / 43.3).
        assertEquals(listOf(listOf(9), listOf(26), listOf(43)), (3..5).map { gym.tile(it, 17)!!.heights })
        // The scene trigger at (13,11) and the trainers' sight ranges.
        assertTrue(gym.triggers.any { it.x == 13 && it.y == 11 })
        val beauty = gym.people.single { it.x == 6 && it.y == 11 }
        assertEquals(Direction.EAST to 2, beauty.facing to beauty.sightRange)
        assertEquals(0, gym.people.single { it.x == 13 && it.y == 4 }.sightRange, "Whitney is not a sight trainer")
    }

    @Test
    fun `Ecruteak Gym pits are 15 coordinate triggers`() {
        val w = world
        val gym = w.areaOf(80)!!
        assertEquals(15, gym.triggers.size)
        // All run scr_seq_T27GYM0101_002 (script 2 + 1) while VAR_UNK_4109 == 0 (NOTES 17y).
        assertTrue(gym.triggers.all { it.script == 3 && it.variable == 0x4109 && it.value == 0 })
        assertEquals(Triple(11, 12, 2 to 7), gym.triggers[0].let { Triple(it.x, it.y, it.width to it.height) })
        // The pits are not a behavior: walkable trigger tiles are plain 0x0000, safe tiles 0x0600.
        val matrix = w.matrix(90)!!
        fun attr(x: Int, z: Int) = w.landData(matrix.landAt(0, z / 32))!!.attribute(x, z % 32)
        val pitAttributes = gym.triggers.flatMap { t -> (t.x until t.x + t.width).flatMap { x -> (t.y until t.y + t.height).map { z -> attr(x, z) } } }
        assertEquals(setOf(0x0000, 0x8000), pitAttributes.toSet())
        assertEquals(0x0600, attr(16, 49))
        // Trainers: position, facing, sight range (NOTES 17y).
        val sight = gym.people.filter { it.sightRange > 0 }.map { listOf(it.x, it.y, it.facing!!.ordinal, it.sightRange) }
        assertEquals(
            listOf(listOf(17, 39, Direction.WEST.ordinal, 1), listOf(19, 30, Direction.SOUTH.ordinal, 1), listOf(9, 29, Direction.EAST.ordinal, 5), listOf(11, 19, Direction.SOUTH.ordinal, 3)),
            sight,
        )
        val exit = gym.warps.single()
        assertEquals(listOf(16, 53, 78, 7), listOf(exit.x, exit.y, exit.targetZone, exit.targetWarp))
        assertEquals(Direction.SOUTH, exit.exitDirection)
    }

    @Test
    fun `Olivine Lighthouse 4F window fall is a coordinate trigger`() {
        val floor = world.areaOf(223)!!
        // NOTES 17aa: stepping east onto (16,9) runs scr_seq_D27R0105 (a Warp to the exterior), not a warp event.
        val fall = floor.triggers.single()
        assertEquals(listOf(16, 9, 1, 1), listOf(fall.x, fall.y, fall.width, fall.height))
        assertTrue(floor.warps.none { it.x == 16 && it.y == 9 })
        // Ladders: the warp direction to press comes from the ladder tile.
        assertEquals(Direction.NORTH, floor.warps.single { it.x == 3 && it.y == 5 }.exitDirection)
    }

    @Test
    fun `Pokemon Center counters are talked across`() {
        val pc = world.areaOf(81)!!
        assertTrue(pc.tile(8, 5)!!.let { it.kind == TileKind.Counter && it.blocked })
        assertEquals(TileKind.Floor, pc.tile(8, 13)!!.kind)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Events against the decomp JSON
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `zone events match the decomp JSON files`() {
        val w = world
        // zone id -> event file (bank_name)
        val zones = mapOf(33 to "030_R29", 60 to "057_T20", 78 to "075_T27", 80 to "077_T27GYM0101", 137 to "133_T25GYM0101", 223 to "209_D27R0105")
        for ((zone, file) in zones) {
            assertEquals(file.substringBefore('_').toInt(), w.header(zone)!!.eventsBank, file)
            val json = Json.parseToJsonElement(javaClass.getResource("/hgss/zone_event/$file.json")!!.readText()).jsonObject
            val ev = assertNotNull(w.events(zone))
            fun list(key: String) = (json[key] as? JsonArray)?.map { it.jsonObject } ?: emptyList()

            val bgs = list("bgs")
            assertEquals(bgs.size, ev.bgs.size, "$file bgs")
            bgs.zip(ev.bgs).forEach { (j, b) ->
                assertEquals(listOf(j.int("type"), j.int("x"), j.int("z"), j.int("y"), j.int("dir")), listOf(b.type, b.x, b.z, b.y, b.dir), "$file bg")
                script(j)?.let { assertEquals(it, b.script, "$file bg script") }
            }
            val objects = list("objects")
            assertEquals(objects.size, ev.objects.size, "$file objects")
            objects.zip(ev.objects).forEachIndexed { i, (j, o) ->
                assertEquals(i, o.id)
                assertEquals(j.str("spriteId"), "SPRITE_" + HgssData.spriteName(o.sprite), "$file object $i sprite")
                assertEquals(
                    listOf("movement", "type", "facingDirection", "param0", "param1", "param2", "xRange", "yRange", "x", "z", "y").map { j.int(it) },
                    listOf(o.movement, o.type, o.facing, o.params[0], o.params[1], o.params[2], o.xRange, o.zRange, o.x, o.z, o.y),
                    "$file object $i",
                )
                flag(j.str("eventFlag"))?.let { assertEquals(it, o.eventFlag, "$file object $i flag") }
                script(j)?.let { assertEquals(it, o.script, "$file object $i script") }
            }
            val warps = list("warps")
            assertEquals(warps.size, ev.warps.size, "$file warps")
            warps.zip(ev.warps).forEach { (j, wp) ->
                assertEquals(listOf(j.int("x"), j.int("z"), j.int("anchor"), j.int("y")), listOf(wp.x, wp.z, wp.anchor, wp.y.toInt()), "$file warp")
                assertEquals(j.str("header"), "MAP_" + HgssData.mapName(wp.header).uppercase().replace(' ', '_'), "$file warp target")
            }
            val coords = list("coords")
            assertEquals(coords.size, ev.coords.size, "$file coords")
            coords.zip(ev.coords).forEach { (j, c) ->
                assertEquals(listOf("x", "z", "w", "h", "y", "val").map { j.int(it) }, listOf(c.x, c.z, c.width, c.height, c.y, c.value), "$file coord")
                variable(j.str("var"))?.let { assertEquals(it, c.variable, "$file coord var") }
                script(j)?.let { assertEquals(it, c.script, "$file coord script") }
            }
            // The Area exposes the same events (zone-tagged).
            val area = w.areaOf(zone)!!
            assertEquals(ev.coords.size, area.triggers.count { it.zone == zone })
            assertEquals(ev.warps.size, area.warps.count { it.zone == zone })
            assertEquals(ev.objects.size, area.people.count { it.zone == zone })
            assertEquals(ev.bgs.size, area.signs.count { it.zone == zone })
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Live: the map loaded in RAM
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `terrain loaded in RAM equals the decoded tiles`() {
        val w = world
        // world_* fixtures hold the 4 (or 2, 1) loaded blocks in full; nb1 / fol1 (HgssReader fixtures, New Bark and
        // the Route 29 block west of it) only the window the reader looked at, hence the comparison around the player.
        for (name in listOf("world_ecruteak", "world_ecruteak_gym", "world_ecruteak_pc", "nb1", "fol1")) {
            val live = HgssLoadedMap(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US)
            val zone = assertNotNull(live.zoneId, name)
            val area = w.areaOf(zone)!!
            val matrix = w.matrix(w.header(zone)!!.matrixId)!!
            val px = live.playerX!!
            val pz = live.playerZ!!
            val radius = if (name.startsWith("world_")) 64 else 24
            var compared = 0
            for (z in pz - radius..pz + radius) for (x in px - radius..px + radius) {
                val attr = live.attribute(x, z) ?: continue
                val land = w.landData(matrix.landAt(x / 32, z / 32))!!
                assertEquals(land.attribute(x % 32, z % 32), attr, "$name ($x,$z)")
                val tile = area.tile(x, z)!!
                assertEquals(attr and HgssWorldSource.COLLISION_BIT != 0, tile.blocked, "$name ($x,$z)")
                assertEquals(HgssTileBehaviors.kind(attr and 0xFF, tile.blocked), tile.kind, "$name ($x,$z)")
                compared++
            }
            assertTrue(compared >= 1024, "$name: $compared tiles compared")
            val standing = area.tile(px, pz)!!
            assertTrue(!standing.blocked, "$name: the player stands on a walkable tile")
            assertTrue(live.playerHeight!! * 8 in standing.heights, "$name: player y ${live.playerHeight} vs heights ${standing.heights}")
            assertEquals(zone, area.zoneAt(px, pz))
        }
    }

    @Test
    fun `events loaded in RAM equal the ROM events`() {
        val w = world
        for ((name, zone) in listOf("world_ecruteak" to 78, "world_ecruteak_gym" to 80, "world_ecruteak_pc" to 81)) {
            val live = HgssLoadedMap(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US)
            assertEquals(zone, live.zoneId)
            assertEquals(w.events(zone), live.events(), name)
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private data class TileInfoOf(val blocked: Boolean, val kind: TileKind, val height: Int)

    private fun dev.kotlinds.pokemonclient.world.TileInfo?.summary() = this?.let { TileInfoOf(it.blocked, it.kind, it.heights.single()) }

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int
    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    /** "_EV_scr_seq_<name>_NNN + 1" → NNN + 1; a plain number; null for macros (std_trainer, std_hiddenitem...). */
    private fun script(j: JsonObject): Int? {
        val p = j["scriptId"]!!.jsonPrimitive
        p.intOrNull?.let { return it }
        return Regex("""_EV_scr_seq_\w+_(\d+) \+ 1""").matchEntire(p.content)?.groupValues?.get(1)?.toInt()?.plus(1)
    }

    /** FLAG_NOTHING = 0, FLAG_UNK_xxx (hex); null for named flags. */
    private fun flag(name: String): Int? = when {
        name == "FLAG_NOTHING" -> 0
        name.startsWith("FLAG_UNK_") -> name.removePrefix("FLAG_UNK_").toInt(16)
        else -> null
    }

    /** VAR_UNK_xxxx / VAR_TEMP_xXXXX (hex); null for named variables. */
    private fun variable(name: String): Int? =
        Regex("""VAR_(?:UNK_|TEMP_x)([0-9A-Fa-f]{4})""").matchEntire(name)?.groupValues?.get(1)?.toInt(16)
}
