package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.PokegearCard
import dev.kotlinds.pokemonclient.state.RadioBand
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.sameAs
import dev.kotlinds.pokemonclient.view.StateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Captured in game (the player's Kanto save): the Pokégear radio's dial and programme in Johto and in Kanto with the
 * Expansion Card, the S.S. Aqua crossing, the Pokégear's cards and Mom's parcels at a Poké Mart.
 */
class HgssGearFixtureTest {

    private fun state(name: String): GameState = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name))

    private fun radio(name: String) = assertIs<Screen.Viewer>(state(name).screen, name).also { assertEquals(ViewerApp.POKEGEAR_RADIO, it.app) }

    @Test
    fun theJohtoDialTunedToPokemonMusic() {
        val viewer = radio("gr_radio_johto_music")
        val radio = viewer.radio!!
        assertEquals(RadioBand.JOHTO, radio.band)
        assertEquals(listOf(0, 1, 2, 3), radio.channels.map { it.index })
        assertEquals(TouchPoint(32, 62), radio.channels[0].preset)
        assertEquals(0, radio.tuned)
        assertTrue(radio.clear)
        assertEquals(RadioStation.POKEMON_MUSIC, radio.station)
        assertEquals(RadioStation.POKEMON_MUSIC, radio.playing)
        assertEquals(TouchPoint(112, 76), radio.cursor)
        assertTrue(radio.title!!.isNotBlank())
        // The app bar, to switch to another Pokégear app by touch.
        assertEquals(TouchPoint(80, 176), viewer.apps.single { it.id == "app:radio" }.touch)
    }

    @Test
    fun aCursorBetweenChannelsTunesNothing() {
        val radio = radio("gr_radio_johto_off").radio!!
        assertEquals(RadioBand.JOHTO, radio.band)
        assertNull(radio.tuned)
        assertNull(radio.station)
        assertFalse(radio.clear)
        assertTrue(radio.inAppBar, "opened from the start menu, the cursor is on the app bar")
    }

    @Test
    fun theKantoExpansionDialPlaysThePokeFlute() {
        val radio = radio("gr_radio_kanto_flute").radio!!
        assertEquals(RadioBand.KANTO_EXPANSION, radio.band)
        val flute = radio.channels.single { RadioStation.POKE_FLUTE in it.stations }
        assertEquals(4, flute.index)
        assertEquals(TouchPoint(128, 48), flute.touch)
        assertNull(flute.preset, "no preset button: the cursor is dragged there")
        assertEquals(4, radio.tuned)
        assertEquals(RadioStation.POKE_FLUTE, radio.station)
        assertEquals(RadioStation.POKE_FLUTE, radio.playing)
        val json = StateView.screen(radio("gr_radio_kanto_flute")).toString()
        assertTrue("poke_flute" in json && "128,48" in json, json)
    }

    @Test
    fun twoDifferentDialsAreNotTheSameScreen() {
        assertFalse(radio("gr_radio_johto_music").sameAs(radio("gr_radio_johto_off")))
    }

    @Test
    fun theShipCrossingIsACutScene() {
        val screen = assertIs<Screen.Animation>(state("gr_ship_crossing").screen)
        assertEquals(AnimationKind.CUTSCENE, screen.kind)
        assertEquals(HgssCutsceneScreens.SHIP_HINT, screen.hint)
    }

    @Test
    fun theCardsAndMomsParcelsAreReadFromTheSave() {
        val state = state("gr_mom_parcels")
        val player = state.player!!
        assertEquals(setOf(PokegearCard.MAP, PokegearCard.RADIO, PokegearCard.EXPANSION), player.pokegearCards)
        assertEquals(listOf(79 to 1, 184 to 5, 195 to 5, 26 to 1, 251 to 1), player.momParcels.map { it.item.id.value to it.quantity })
        val json = StateView.state(state).toString()
        assertTrue("mom_parcels" in json && "delivery man" in json, json)
    }
}
