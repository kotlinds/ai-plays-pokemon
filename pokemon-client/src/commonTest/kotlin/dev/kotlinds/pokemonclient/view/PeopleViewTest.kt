package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the agent sees of the people of the map (PER-08 facing and roles, PER-09 people off screen) and its height. */
class PeopleViewTest {

    private val area = Area(1, "test", 0, 0, 40, 40, Array(1600) { TileInfo(false, TileKind.Floor, listOf(0)) })

    private fun field(objects: List<FieldObject>) = FieldState(7, "Route", 5, 5, 6, Direction.NORTH, MovementMode.WALK, false, objects)

    @Test
    fun peopleOnScreenShowWhereTheyLook() {
        val trainer = FieldObject("person:2", "Youngster Joey (trainer, not beaten, sees 4 tiles ahead)", FieldObjectKind.PERSON, 7, 5, Direction.WEST)
        val view = MapView.render(area, field(listOf(trainer)))
        assertEquals("person:2 Youngster Joey (trainer, not beaten, sees 4 tiles ahead) at 7,5 (2 east) facing west", view["people"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(view["position"]!!.jsonPrimitive.content.endsWith("height 6"), view["position"].toString())
    }

    @Test
    fun peopleOffScreenAreListedCompactlyNearestFirst() {
        val near = FieldObject("person:1", "boy", FieldObjectKind.PERSON, 6, 6, Direction.SOUTH)
        val far = FieldObject("person:5", "Farfetch'd (Pokémon)", FieldObjectKind.PERSON, 30, 30, Direction.SOUTH)
        val farther = FieldObject("person:6", "shutter", FieldObjectKind.PERSON, 35, 35, null, PersonRole.SHUTTER)
        val item = FieldObject("person:9", "item ball", FieldObjectKind.ITEM_BALL, 33, 33, null)
        val view = MapView.render(area, field(listOf(farther, item, near, far)))
        assertEquals(listOf("person:1 boy at 6,6 (1 east, 1 south) facing south"), view["people"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("person:5 Farfetch'd (Pokémon) at 30,30", "person:6 shutter at 35,35 [shutter]"),
            view["people_off_screen"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun nobodyOffScreenMeansNoList() {
        val view = MapView.render(area, field(emptyList()))
        assertNull(view["people_off_screen"])
    }
}
