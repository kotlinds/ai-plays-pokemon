package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.WorldRouter
import dev.kotlinds.pokemonclient.world.ZoneLink
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Text map and routing on real saves (bench, DeSmuME, 8-badge save; skipped without `POKEMON_ROM`):
 * - `pz_vr3f_entry`: Victory Road 3F (map 179) at the Indigo Plateau entrance (38,14). Live, `go_to 22,17 on Victory
 *   Road 2F` from there walked into hole:4 and landed on 2F (22,17) ("via hole:4 (fell, one way)").
 */
class HgssMapViewFixtureTest {

    private fun field(name: String) = assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).field)

    @Test
    fun `go_to plans through a Victory Road hole to the floor below`() {
        val world = HgssWorldRom.require()
        val field = field("pz_vr3f_entry")
        assertEquals(179 to (38 to 14), field.mapId to (field.x to field.y))
        val second = assertNotNull(world.areaOf(178))
        val route = assertNotNull(
            WorldRouter(world) { _, _ -> Overlay() }.route(179, Node(field.x, field.y), RouteOptions()) {
                it.area === second && it.node.x == 22 && it.node.y == 17
            },
        )
        val hole = route.links.single()
        assertEquals(ZoneLink.Kind.HOLE, hole.kind)
        assertEquals("hole:4", hole.id)
        assertTrue(route.oneWay)
    }

    @Test
    fun `the Victory Road map shows the raised walkway as another level`() {
        val world = HgssWorldRom.require()
        val field = field("pz_vr3f_entry")
        val view = MapView.render(assertNotNull(world.areaOf(179)), field, world = world)
        val levels = assertNotNull(view["levels"]).jsonArray.map { it.jsonPrimitive.content }
        // The walkway north of the entrance room (y 9-10) is higher than the room the player stands in.
        assertEquals("   9 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1", levels[0])
        assertTrue(levels[5].contains("0 0 0 0"), levels[5])
        assertTrue("you are on level 0" in view["levels_legend"]!!.jsonPrimitive.content)
        // The holes are exits with their floor below.
        assertTrue(view["exits"]!!.jsonArray.any { "hole:4 at 20,18" in it.jsonPrimitive.content && "(22,17)" in it.jsonPrimitive.content })
    }

    @Test
    fun `Ilex Forest has no herding puzzle once both Farfetch'd are found`() {
        // `pz_ilex_done`: Ilex Forest (map 117) at its Azalea entrance (15,80), both birds caught during the run.
        val field = field("pz_ilex_done")
        assertEquals(117 to (15 to 80), field.mapId to (field.x to field.y))
        assertTrue(field.objects.none { it.id == "person:0" || it.id == "person:2" })
        val reader = HgssReader(HgssFixtures.load("pz_ilex_done"), HgssVersion.HEARTGOLD_US)
        val objects = field.objects.mapNotNull { o -> o.id.removePrefix("person:").toIntOrNull()?.let { HgssIlexFarfetchd.ObjectAt(it, o.x, o.y) } }
        assertEquals(null, HgssPuzzles.read(117, HgssPuzzles.reads(reader), null, objects))
    }
}
