package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.IncomingCall
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.ViewerExit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Screens captured in game that used to be `unknown`: a call ringing while walking (only the caller's name next to
 * the POKéGEAR button), and the applications with nothing to choose (Pokédex, trainer card, summary, Pokégear map
 * and radio) with their way out.
 */
class HgssViewerAndCallScreensTest {

    private fun screen(name: String): Screen = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).screen

    @Test
    fun aCallRingingWhileWalkingIsShownOnTheOverworld() {
        val elm = assertIs<Screen.Overworld>(screen("sc_call_ringing"))
        assertEquals(IncomingCall("contact:1", "Prof. Elm", TouchPoint(38, 150)), elm.incomingCall)
        assertEquals(Awaiting.INPUT, elm.awaiting)
        assertEquals("contact:0", assertIs<Screen.Overworld>(screen("sc_call_mom")).incomingCall?.callerId)
    }

    @Test
    fun noCallOnAPlainOverworld() {
        assertNull(assertIs<Screen.Overworld>(screen("ow_grass")).incomingCall)
    }

    @Test
    fun applicationsToLookAtSayHowToLeave() {
        val back = ViewerExit(button = Button.B)
        val close = ViewerExit(touch = TouchPoint(230, 176))
        val expected = mapOf(
            "sc_pokedex" to (ViewerApp.POKEDEX to back),
            "sc_trainer_card" to (ViewerApp.TRAINER_CARD to back),
            "sc_summary" to (ViewerApp.SUMMARY to back),
            "sc_gear_map" to (ViewerApp.POKEGEAR_MAP to close),
            "sc_gear_radio" to (ViewerApp.POKEGEAR_RADIO to close),
        )
        for ((fixture, app) in expected) {
            val viewer = assertIs<Screen.Viewer>(screen(fixture), fixture)
            assertEquals(app, viewer.app to viewer.exit, fixture)
            assertEquals(Awaiting.INPUT, viewer.awaiting, fixture)
        }
        assertEquals("viewer:pokegear_map", screen("sc_gear_map").kind)
    }

    @Test
    fun thePhoneIsStillTheContactList() {
        // The Pokégear's phone stays a menu (contacts + app bar), not a viewer.
        assertIs<Screen.ListMenu>(screen("text_phone_list"))
    }
}
