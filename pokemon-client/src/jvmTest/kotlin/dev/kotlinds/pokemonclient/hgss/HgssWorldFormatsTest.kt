package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HG/SS world file formats on hand-built bytes (no ROM needed), plus [HgssLoadedMap] on the live fixtures.
 * The same decoders are checked on the real ROM by [HgssWorldSourceTest].
 */
class HgssWorldFormatsTest {

    /** Little-endian byte builder. */
    private class Bytes {
        val out = mutableListOf<Byte>()
        fun u8(v: Int) = apply { out += v.toByte() }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun s32(v: Int) = apply { u16(v); u16(v shr 16) }
        fun ascii(s: String) = apply { s.forEach { u8(it.code) } }
        fun bytes() = out.toByteArray()
    }

    @Test
    fun `map headers read the matrix, banks and map type`() {
        val arm9 = ByteArray(0x100 + HgssWorldAddresses.MAP_HEADER_COUNT * HgssWorldAddresses.MAP_HEADER_SIZE)
        val header = Bytes().u8(0xFF).u8(31).u16(0).u16(90).u16(922).u16(695).u16(614).u16(1).u16(1).u16(77).u16(133)
            .s32((4 shl 8) or (1 shl 1)).bytes()
        header.copyInto(arm9, 0x100 + 80 * HgssWorldAddresses.MAP_HEADER_SIZE)
        val headers = HgssMapHeaders.decode(arm9, HgssWorldAddresses.ARM9_LOAD_ADDRESS + 0x100)
        assertEquals(HgssMapHeader(80, 90, 77, 922, 695, 614, 133, 4, 0xFF, flyAllowed = false), headers[80])
        assertEquals(0, headers[79].matrixId)
        assertFailsWith<IllegalArgumentException> { HgssMapHeaders.decode(arm9, HgssWorldAddresses.ARM9_LOAD_ADDRESS + 0x200) }
        assertEquals(0x020F6BE0L, HgssWorldAddresses.mapHeadersAddress("IPKE"))
        assertNull(HgssWorldAddresses.mapHeadersAddress("IPGE"))
    }

    @Test
    fun `matrix with and without header and altitude sections`() {
        val full = Bytes().u8(2).u8(1).u8(1).u8(1).u8(3).ascii("map")
            .u16(33).u16(60).u8(0).u8(6).u16(10).u16(HgssMapMatrix.NO_LAND).bytes()
        val m = HgssMapMatrix.parse(0, full)
        assertEquals("map", m.name)
        assertEquals(2 to 1, m.width to m.height)
        assertEquals(60, m.zoneAt(1, 0))
        assertEquals(6, m.altitudeAt(1, 0))
        assertEquals(listOf(10, HgssMapMatrix.NO_LAND), m.landData.toList())

        val single = HgssMapMatrix.parse(90, Bytes().u8(1).u8(2).u8(0).u8(0).u8(2).ascii("m_").u16(251).u16(252).bytes())
        assertNull(single.zones)
        assertNull(single.zoneAt(0, 1))
        assertEquals(0, single.altitudeAt(0, 1))
        assertEquals(252, single.landAt(0, 1))
    }

    /** A land data member: header (+ [extra] bytes), grid, buildings, empty model, BDHC with one or two plates. */
    private fun landData(extra: Int, attr: (Int) -> Int, plates: List<Pair<Int, Int>>): ByteArray {
        // BDHC: 2 points (the whole block), 2 normals (flat; and a slope rising 1 unit per unit of x), constants.
        val bdhc = Bytes().ascii("BDHC").u16(2).u16(2).u16(plates.size).u16(plates.size).u16(0).u16(0)
            .s32(-256 * 4096).s32(-256 * 4096).s32(256 * 4096).s32(256 * 4096)
            .s32(0).s32(4096).s32(0)
            .s32(-2896).s32(2896).s32(0)
        plates.forEach { (_, d) -> bdhc.s32(d * 4096) }
        plates.forEachIndexed { i, (normal, _) -> bdhc.u16(0).u16(1).u16(normal).u16(i) }
        val bdhcBytes = bdhc.bytes()
        val b = Bytes().s32(0x800).s32(4).s32(0).s32(bdhcBytes.size).u16(0x1234).u16(extra)
        repeat(extra) { b.u8(0xEE) }
        repeat(1024) { b.u16(attr(it)) }
        b.s32(0x7777) // buildings
        bdhcBytes.forEach { b.u8(it.toInt()) }
        return b.bytes()
    }

    @Test
    fun `land data grid starts after the variable header`() {
        for (extra in listOf(0, 0x18, 0x58)) {
            val land = HgssLandData.parse(landData(extra, { if (it == 3 * 32 + 5) 0x803B else it % 2 }, listOf(0 to -48)))
            assertEquals(0x803B, land.attribute(5, 3), "extra 0x${extra.toString(16)}")
            assertEquals(1, land.attribute(1, 0))
            assertNotNull(land.bdhc)
        }
    }

    @Test
    fun `BDHC heights per tile, several surfaces on a bridge`() {
        // Flat plane y = 48 (n = (0,1,0), d = -48) and a 45° slope y = x (n = (-0.707, 0.707, 0), d = 0).
        val heights = HgssLandData.parse(landData(0, { 0 }, listOf(0 to -48, 1 to 0))).bdhc!!.tileHeights()
        assertEquals(1024, heights.size)
        // Tile 0 has its center at x = -248 (slope height -248), tile 16 at x = 8.
        assertEquals(listOf(-248, 48), heights[0])
        assertEquals(listOf(8, 48), heights[16])
        assertEquals(listOf(-8, 48), heights[15 + 31 * 32]) // any row: the planes do not depend on z
    }

    @Test
    fun `zone events sections and trainer sight`() {
        val b = Bytes()
            .s32(1).u16(6).u16(0).s32(13).s32(51).s32(0).u16(0).u16(0)
            .s32(2)
        // Edith: trainer (type 1), facing east (3), sight 5; then a plain person hidden by a flag.
        b.u16(4).u16(219).u16(17).u16(1).u16(0).u16(3492).u16(3).u16(5).u16(0).u16(0).u16(0).u16(0).u16(9).u16(29).s32(0)
        b.u16(6).u16(330).u16(15).u16(0).u16(583).u16(0).u16(1).u16(0).u16(0).u16(0).u16(0).u16(0).u16(16).u16(49).s32(0)
        b.s32(1).u16(16).u16(53).u16(78).u16(7).s32(0)
        b.s32(1).u16(3).u16(11).u16(12).u16(2).u16(7).u16(0).u16(0).u16(0x4109)
        val ev = HgssZoneEvents.parse(b.bytes())
        assertEquals(HgssZoneEvents.BgEvent(6, 0, 13, 51, 0, 0), ev.bgs.single())
        val (edith, oldMan) = ev.objects
        assertTrue(edith.isTrainer)
        assertEquals(listOf(9, 29, 3, 5, 3492), listOf(edith.x, edith.z, edith.facing, edith.params[0], edith.script))
        assertTrue(!oldMan.isTrainer)
        assertEquals(583, oldMan.eventFlag)
        assertEquals(HgssZoneEvents.WarpEvent(16, 53, 78, 7, 0), ev.warps.single())
        assertEquals(HgssZoneEvents.CoordEvent(3, 11, 12, 2, 7, 0, 0, 0x4109), ev.coords.single())
        assertEquals(HgssZoneEvents(emptyList(), emptyList(), emptyList(), emptyList()), HgssZoneEvents.parse(ByteArray(0)))
        assertFailsWith<IllegalArgumentException> { HgssZoneEvents.parse(Bytes().s32(50).s32(0).s32(0).s32(0).bytes()) }
    }

    @Test
    fun `tile behaviors map to the common kinds`() {
        fun kind(b: Int, blocked: Boolean = false) = HgssTileBehaviors.kind(b, blocked)
        assertEquals(TileKind.Floor, kind(0x00))
        assertEquals(TileKind.Wall, kind(0x00, true))
        assertEquals(TileKind.TallGrass, kind(0x02))
        assertEquals(TileKind.TallGrass, kind(0x03))
        assertEquals(TileKind.Cave, kind(0x08))
        assertEquals(TileKind.Wall, kind(0x06, true)) // headbutt tree
        assertEquals(TileKind.Water(surfable = true, fishable = true), kind(0x10))
        assertEquals(TileKind.Water(surfable = true, fishable = true), kind(0x15))
        assertEquals(TileKind.Whirlpool, kind(0x11))
        assertEquals(TileKind.Waterfall, kind(0x13))
        assertEquals(TileKind.Floor, kind(0x17)) // shallow water: walkable
        assertEquals(TileKind.Ice, kind(0x20))
        assertEquals(TileKind.Sand, kind(0x21))
        assertEquals(TileKind.Lava, kind(0x2C))
        assertEquals(TileKind.Floor, kind(0x2E)) // crossing walkway (Goldenrod Gym)
        assertEquals(TileKind.Ledge(Direction.EAST), kind(0x38, true))
        assertEquals(TileKind.Ledge(Direction.WEST), kind(0x39, true))
        assertEquals(TileKind.Ledge(Direction.NORTH), kind(0x3A, true))
        assertEquals(TileKind.Ledge(Direction.SOUTH), kind(0x3B, true))
        assertEquals(TileKind.Ladder, kind(0x3C))
        assertEquals(TileKind.Ladder, kind(0x3E))
        assertEquals(TileKind.Spinner(Direction.EAST), kind(0x40))
        assertEquals(TileKind.Spinner(Direction.SOUTH), kind(0x43))
        assertEquals(TileKind.SpinnerStop, kind(0x4D))
        assertEquals(TileKind.Door, kind(0x69, true))
        assertEquals(TileKind.Door, kind(0x65))
        assertEquals(TileKind.Door, kind(0x6F))
        assertEquals(TileKind.Counter, kind(0x80, true))
        assertEquals(TileKind.Pc, kind(0x83, true))
        assertEquals(TileKind.RockClimb, kind(0x4B, true))
        assertEquals(TileKind.RockClimb, kind(0x4C, true))
        // Railings (sub_0205B8F4..B960), bridges (sub_0205BA24/BA30/BA54), the waterfall top (sub_0205B78C).
        assertEquals(TileKind.Railing(setOf(Direction.EAST)), kind(0x30))
        assertEquals(TileKind.Railing(setOf(Direction.WEST, Direction.EAST)), kind(0x4A))
        assertEquals(TileKind.Bridge(start = true), kind(0x70))
        assertEquals(TileKind.Bridge(), kind(0x71))
        assertEquals(TileKind.Bridge(), kind(0x72))
        assertEquals(TileKind.Bridge(overWater = true), kind(0x73))
        assertEquals(TileKind.Water(surfable = true, fishable = false), kind(0x22))
        assertEquals(TileKind.Floor, kind(0x24))
        assertEquals(TileKind.Unknown(0xF0), kind(0xF0))
        assertTrue(HgssTileBehaviors.isSurfable(0x15) && !HgssTileBehaviors.isSurfable(0x17))

        assertEquals(Direction.SOUTH, HgssTileBehaviors.warpDirection(0x65))
        assertEquals(Direction.EAST, HgssTileBehaviors.warpDirection(0x5E))
        assertEquals(Direction.WEST, HgssTileBehaviors.warpDirection(0x6D))
        assertEquals(Direction.NORTH, HgssTileBehaviors.warpDirection(0x3C))
        assertNull(HgssTileBehaviors.warpDirection(0x69))
    }

    @Test
    fun `loaded map reads the player, the blocks and the events from RAM`() {
        val gym = HgssLoadedMap(HgssFixtures.load("world_ecruteak_gym"), HgssVersion.HEARTGOLD_US)
        assertEquals(80, gym.zoneId)
        assertEquals(Triple(16, 41, 0), Triple(gym.playerX, gym.playerZ, gym.playerHeight))
        assertEquals(setOf(0, 1), gym.loadedBlocks)
        assertEquals(0x0600, gym.attribute(16, 49))
        assertNull(gym.attribute(-1, 0))
        assertNull(gym.attribute(40, 0))
        val events = assertNotNull(gym.events())
        assertEquals(15, events.coords.size)
        assertEquals(HgssZoneEvents.WarpEvent(16, 53, 78, 7, 0), events.warps.single())

        val city = HgssLoadedMap(HgssFixtures.load("world_ecruteak"), HgssVersion.HEARTGOLD_US)
        assertEquals(78, city.zoneId)
        assertEquals(Triple(397, 184, 6), Triple(city.playerX, city.playerZ, city.playerHeight))
        assertEquals(setOf(246, 247, 293, 294), city.loadedBlocks)
        assertEquals(47, city.matrixWidth)
        assertNull(city.attribute(0, 0), "block not loaded")
    }
}
