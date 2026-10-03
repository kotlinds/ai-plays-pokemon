package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Sign
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Warp
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewsTest {

    private val area = Area(
        id = 1, name = "test", originX = 0, originY = 0, width = 5, height = 3,
        tiles = Array(15) { i -> if (i % 5 == 4) TileInfo(true, TileKind.Wall) else if (i == 2) TileInfo(false, TileKind.TallGrass) else TileInfo(false, TileKind.Floor) },
        warps = listOf(Warp(zone = 7, id = 0, x = 0, y = 2, targetZone = 9, targetWarp = 0, exitDirection = Direction.SOUTH)),
        signs = listOf(Sign(zone = 7, id = 3, x = 3, y = 0, script = 1)),
    )

    private val field = FieldState(
        mapId = 7, mapName = "Town", x = 2, y = 1, height = 0, facing = Direction.NORTH, movement = MovementMode.WALK, moving = false,
        objects = listOf(FieldObject("person:4", "nurse", FieldObjectKind.PERSON, 3, 1, Direction.SOUTH, PersonRole.NURSE)),
    )

    @Test
    fun theMapShowsTilesPeopleExitsAndSignsWithTheirIds() {
        val view = MapView.render(area, field, zoneName = { if (it == 9) "Route 1" else null }, width = 5, height = 3)
        val rows = view["map"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("   1 . . A P #", rows[2])
        assertEquals("   0 . . \" S #", rows[1])
        assertEquals("   2 E . . . #", rows[3])
        val exits = view["exits"]!!.jsonArray.single().jsonPrimitive.content
        assertTrue(exits.startsWith("warp:0 at 0,2 (2 west, 1 south) → Route 1"), exits)
        assertTrue("press south" in exits)
        assertEquals("person:4 nurse at 3,1 (1 east) [nurse]", view["people"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("sign:3 at 3,0 (1 east, 1 north)", view["signs"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue("tall grass" in view["legend"]!!.jsonPrimitive.content)
    }

    @Test
    fun relativePositions() {
        assertEquals("here", MapView.relative(field, 2, 1))
        assertEquals("4 west, 2 south", MapView.relative(field, -2, 3))
    }

    @Test
    fun screensAreDescribedWithIdsAndTheHighlightedEntry() {
        val menu = Screen.YesNo("Save?", listOf(Entry("option:yes", "YES"), Entry("option:no", "NO", touch = null)), Cursor.At(1), Topology.vertical(2))
        val json = StateView.screen(menu)
        assertEquals("option:no", json["highlighted"]!!.jsonPrimitive.content)
        assertEquals("Save?", json["question"]!!.jsonPrimitive.content)
        val dialogue = StateView.screen(Screen.Dialogue(TextSource.FIELD, "nurse", "Hello", Awaiting.INPUT))
        assertEquals("nurse", dialogue["speaker"]!!.jsonPrimitive.content)
        val state = StateView.state(GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field))
        assertTrue(state.isNotEmpty())
    }
}
