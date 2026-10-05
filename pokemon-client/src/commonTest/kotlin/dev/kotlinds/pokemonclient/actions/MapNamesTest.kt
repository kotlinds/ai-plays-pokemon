package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.world.ZoneLink
import dev.kotlinds.pokemonclient.state.FieldState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Map names as agents write them, and same-map warps (NOTES, Kanto part). */
class MapNamesTest {

    @Test
    fun aMapIsNamedWithoutItsTownOrItsGroundFloor() {
        assertTrue(WorldTravel.looseMatch("New Bark Elms Lab 1F", "Elms Lab"))
        assertTrue(WorldTravel.looseMatch("New Bark Elms Lab 1F", "elms lab 1F"))
        assertTrue(WorldTravel.looseMatch("Vermilion Pokecenter 1F", "Vermilion Pokecenter"))
        assertFalse(WorldTravel.looseMatch("New Bark Elms Lab 2F", "Elms Lab"))
        // Whole words only, and not too short.
        assertFalse(WorldTravel.looseMatch("New Bark Elms Lab 1F", "ab"))
        assertFalse(WorldTravel.looseMatch("Goldenrod Dept Store 1F", "ore"))
        assertTrue(WorldTravel.looseMatch("Goldenrod Dept Store 1F", "Dept Store"))
    }

    @Test
    fun townsAreNamedWithOrWithoutTownOrCity() {
        assertTrue(WorldTravel.sameMapName("Cerulean City", "Cerulean"))
        assertTrue(WorldTravel.sameMapName("Cerulean", "cerulean city"))
        assertFalse(WorldTravel.sameMapName("Cerulean Gym", "Cerulean"))
    }

    private fun field(x: Int, y: Int) = FieldState(mapId = 410, mapName = "Saffron Gym", x = x, y = y, height = 0, facing = null, movement = dev.kotlinds.pokemonclient.state.MovementMode.WALK, moving = false)

    @Test
    fun aPadToTheSameMapWorkedWhenThePlayerLeftIt() {
        val pad = ZoneLink(ZoneLink.Kind.WARP, "warp:1", 410, 18, 22, null, 410, 28, 25)
        assertTrue(WorldTravel.teleported(pad, field(28, 25)))
        assertFalse(WorldTravel.teleported(pad, field(18, 22)))
        // A warp to another map never "teleports" on this one.
        assertFalse(WorldTravel.teleported(pad.copy(targetZone = 59), field(28, 25)))
    }
}
