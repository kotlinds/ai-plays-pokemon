package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The Fly map ([HgssFlyMapScreens]) on real HeartGold (US) snapshots, captured with the dev bench: 8-badge save,
 * standing outside the Ecruteak Pokémon Center, Kenya (FEAROW) → FLY. Map unlock level 1 (Johto + Indigo
 * Plateau column); every Johto town visited, not Mt. Silver, Safari Zone, Battle Frontier, National Park, Indigo
 * Plateau nor Victory Road.
 *
 * Checked live: touching each touch point of `fly_map_ecruteak` / `fly_map_scrolled` put the cursor on that
 * destination (Goldenrod opened "Fly to Goldenrod City?"), the D-pad moved the cursor one tile per tap exactly as the
 * topology says, and FLY landed in Goldenrod (overworld input about 710 frames after A).
 */
class HgssFlyMapScreensTest {

    private fun screen(name: String): Screen = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).screen

    private fun Screen.Selectable.ids() = entries.map { it.id }

    private fun Screen.Selectable.moves(from: Int) =
        listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT).associateWith { topology.next(from, it) }

    private val ids = listOf(
        "fly:58", "fly:60", "fly:67", "fly:73", "fly:74", "fly:75", "fly:76", "fly:77", "fly:78", "fly:87", "fly:89",
        "fly:88", "fly:90", "fly:174", "fly:411", "fly:280", "fly:30", "option:cancel",
    )

    @Test
    fun destinationsOfTheMapUnlockLevelThenClose() {
        val map = assertIs<Screen.FlyMap>(screen("fly_map_ecruteak"))
        assertEquals(ids, map.ids())
        assertEquals("Ecruteak City", map.entries[8].label)
        // Visited Johto towns only: New Bark (1) … Lake of Rage (11).
        assertEquals((1..11).toList() + 17, map.entries.indices.filter { map.entries[it].selectable })
        assertEquals(CancelBehavior.CLOSES, map.cancel)
        assertEquals(TouchPoint(224, 168), map.entries.last().touch)
    }

    @Test
    fun cursorOnTheTownTheMapOpenedOn() {
        val map = assertIs<Screen.FlyMap>(screen("fly_map_ecruteak"))
        assertEquals(Cursor.At(8), map.cursor)
    }

    @Test
    fun touchPointsOfTheVisibleDestinations() {
        val map = assertIs<Screen.FlyMap>(screen("fly_map_ecruteak"))
        assertEquals(TouchPoint(108, 100), map.entries[6].touch) // Goldenrod: touched live, opened its confirmation
        assertEquals(TouchPoint(116, 52), map.entries[8].touch)
        assertEquals(TouchPoint(156, 28), map.entries[11].touch)
        // Indigo Plateau, Mt. Silver and Victory Road are off screen (columns 28 / 25 with the map scrolled to 0).
        assertNull(map.entries[0].touch)
        assertNull(map.entries[12].touch)
        assertNull(map.entries[16].touch)
    }

    @Test
    fun scrollingMovesTheTouchPoints() {
        val map = assertIs<Screen.FlyMap>(screen("fly_map_scrolled"))
        assertEquals(Cursor.Hidden, map.cursor)
        assertEquals(TouchPoint(68, 100), map.entries[6].touch) // touched live: "Fly to Goldenrod City?"
        assertEquals(TouchPoint(188, 84), map.entries[12].touch) // touched live: cursor on Mt. Silver
        assertEquals(TouchPoint(212, 68), map.entries[0].touch)
        assertNull(map.entries[5].touch) // Cianwood scrolled off on the left
    }

    @Test
    fun topologyIsOnePressBetweenAdjacentDestinationsOnly() {
        val map = assertIs<Screen.FlyMap>(screen("fly_map_scrolled"))
        // Indigo Plateau (28,6) sits right above Victory Road (28,7-8).
        assertEquals(mapOf(Button.UP to null, Button.DOWN to 16, Button.LEFT to null, Button.RIGHT to null), map.moves(0))
        assertEquals(mapOf(Button.UP to 0, Button.DOWN to null, Button.LEFT to null, Button.RIGHT to null), map.moves(16))
        // Olivine's touch tile (8,7) is next to the Battle Frontier (6-7,6-7).
        assertEquals(14, map.topology.next(7, Button.LEFT))
        assertNull(map.topology.next(8, Button.A))
    }

    @Test
    fun cursorBetweenTownsIsHidden() {
        // Two taps LEFT from Ecruteak's (12,5): tile (10,5) is no destination (checked live; DOWN then enters the
        // National Park point (10,6), as the topology of the next decode says).
        val map = assertIs<Screen.FlyMap>(screen("fly_map_between"))
        assertEquals(Cursor.Hidden, map.cursor)
        assertEquals(ids, map.ids())
    }

    @Test
    fun confirmationIsAYesNo() {
        val yesNo = assertIs<Screen.YesNo>(screen("fly_confirm"))
        assertEquals("Fly to Goldenrod City?", yesNo.question)
        assertEquals(listOf("option:yes", "option:no"), yesNo.ids())
        assertEquals(listOf("Fly", "Quit"), yesNo.entries.map { it.label })
        assertEquals(Cursor.At(0), yesNo.cursor)
        assertEquals(CancelBehavior.CONFIRMS_LAST, yesNo.cancel)
        assertEquals(TouchPoint(48, 48), yesNo.entries[0].touch)
        assertEquals(TouchPoint(48, 72), yesNo.entries[1].touch)
        assertEquals(mapOf(Button.UP to 1, Button.DOWN to 1, Button.LEFT to null, Button.RIGHT to null), yesNo.moves(0))
    }

    @Test
    fun confirmationCursorOnQuit() {
        val yesNo = assertIs<Screen.YesNo>(screen("fly_confirm_cancel"))
        assertEquals(Cursor.At(1), yesNo.cursor)
    }
}
