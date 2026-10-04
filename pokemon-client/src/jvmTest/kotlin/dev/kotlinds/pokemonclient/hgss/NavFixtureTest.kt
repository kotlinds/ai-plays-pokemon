package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.actions.MovePlans
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Navigation facts on real HeartGold (USA) RAM captured in a new game (bench, nav workstream):
 * - `nav_violet_early`: Violet City at 511,269 right after arriving from Route 31 (no badge yet);
 * - `nav_sprout_nico_beaten`: Sprout Tower 2F at 20,14, just after beating Sage Nico (person:0, 20,13); Sage
 *   Edmond (person:1, 13,27, facing west, sees 6 tiles) not beaten yet;
 * - `nav_route30`: Route 30 at 550,383 on the way to Mr. Pokémon (the Rattata / Pidgey scene still on the path).
 */
class NavFixtureTest {

    private val version = HgssVersion.HEARTGOLD_US

    @Test
    fun theSproutTowerBridgeTriggerIsArmedInTheEarlyGame() {
        val raw = assertNotNull(HgssReader(HgssFixtures.load("nav_violet_early"), version).read())
        // scr_seq_T22_002 (an empty script) waits on VAR_SCENE_VIOLET_CITY_OW = 0 across the bridge at y 234: armed now,
        // which is why it must be treated as inert (HgssBridgesRomTest), or go_to finds no dry way to the tower.
        val bridge = assertNotNull(raw.surroundings?.triggers?.firstOrNull { it.x == 486 && it.z == 234 })
        assertEquals(true, bridge.active)
        assertEquals(3, bridge.width)
    }

    /** The inert bridge trigger isn't a story blocker either (needs the ROM's scripts: skipped without `POKEMON_ROM`). */
    @Test
    fun theInertBridgeTriggerIsNotABlocker() {
        HgssData.useWorld(HgssWorldRom.require())
        val story = assertNotNull(HgssGame(version).state(HgssFixtures.load("nav_violet_early")).story)
        assertTrue(story.blockers.none { it.target == "trigger:0" }, story.blockers.toString())
    }

    /** Trainers are told from the ROM's scripts and trainer data (skipped without `POKEMON_ROM`). */
    @Test
    fun aBeatenSageNoLongerWatchesAndTheOtherStillDoes() {
        HgssData.useWorld(HgssWorldRom.require())
        HgssData.useGameData(HgssWorldRom.requireData())
        val field = assertNotNull(HgssGame(version).state(HgssFixtures.load("nav_sprout_nico_beaten")).field)
        assertEquals(20 to 14, field.x to field.y)
        val nico = field.objects.single { it.id == "person:0" }
        assertEquals(true, nico.trainer?.defeated)
        assertEquals(0, MovePlans.sightRange(nico, emptyList()))
        val other = field.objects.single { it.id == "person:1" }
        assertEquals(false, other.trainer?.defeated)
        assertEquals(6, MovePlans.sightRange(other, emptyList()))
    }

    @Test
    fun peopleOffScreenAndApricornTreesOnRoute30() {
        val field = assertNotNull(HgssGame(version).state(HgssFixtures.load("nav_route30")).field)
        // The Apricorn trees (BONGURI) are named, not "person".
        assertEquals("Apricorn tree", field.objects.single { it.id == "person:11" }.label)
        val area = Area(1, "flat", 500, 250, 100, 150, Array(100 * 150) { TileInfo(false, TileKind.Floor, listOf(0)) })
        val view = MapView.render(area, field)
        val far = view["people_off_screen"]!!.jsonArray.map { it.jsonPrimitive.content }
        // Youngster Mikey (person:5), 58 tiles up the route, and the Rattata / Pidgey scene are listed; the window shows
        // nobody but the follower.
        assertTrue(far.any { it.startsWith("person:5 ") && it.endsWith(" at 552,325") }, far.toString())
        assertTrue("person:6 Rattata (Pokémon) at 552,327" in far, far.toString())
        assertEquals(null, view["people"])
        assertEquals(field.objects.count { it.kind == FieldObjectKind.PERSON }, far.size)
    }
}
