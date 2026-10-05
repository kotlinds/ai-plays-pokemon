package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Captures (captured on the bench, wild SPINARAK with a full party). */
class HgssCaptureTest {
    private fun state(name: String) = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name))

    @Test
    fun theShakesAreKnownAsSoonAsTheBallLands() {
        // A Great Ball that breaks free after one shake ("Aww! It appeared to be caught!").
        assertEquals(1, state("bt_throw_shakes1").battle?.ballShakes)
    }

    @Test
    fun theTransferToThePcAfterANicknameIsAMessage() {
        val message = assertIs<Screen.Dialogue>(state("bt_catch_transfer_named").screen)
        assertEquals(TextSource.MENU, message.source)
        assertEquals("SPINARAK was transferred to\nBOX 1 in Bill’s PC!", message.text)
        assertEquals(Awaiting.ANIMATION, message.awaiting)
    }
}
