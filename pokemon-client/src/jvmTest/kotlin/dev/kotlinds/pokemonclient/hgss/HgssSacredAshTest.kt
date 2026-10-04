package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Sacred Ash used from the field bag (`PARTY_MENU_STATE_SACRED_ASH`): the party menu revives every fainted Pokémon
 * in turn, an HP bar then "X regained health." waiting for A. Captured on the bench (HOOTHOOT fainted after a wild
 * battle on Route 44).
 */
class HgssSacredAshTest {
    private fun screen(name: String): Screen = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).screen

    @Test
    fun theRevivalMessageWaitsForA() {
        val message = assertIs<Screen.Dialogue>(screen("bt_sacred_ash_msg"))
        assertEquals(TextSource.MENU, message.source)
        assertEquals("HOOTHOOT\nregained health.", message.text)
        assertEquals(Awaiting.INPUT, message.awaiting)
    }

    @Test
    fun theHpBarFillingIsAnAnimation() {
        assertIs<Screen.Animation>(screen("bt_sacred_ash_heal"))
    }
}
