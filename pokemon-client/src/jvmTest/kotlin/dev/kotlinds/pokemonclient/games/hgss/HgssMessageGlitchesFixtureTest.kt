package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Messages of the bag and the mart on real HeartGold US frames (Indigo Plateau save, small fixes workstream): leftovers
 * of a buffer the game hasn't rewritten yet, the clerk's bonus.
 */
class HgssMessageGlitchesFixtureTest {

    private fun screen(name: String): Screen = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).screen

    /**
     * TM07 → USE: for a few frames the bag's TM sequence is at step 0, its message buffer still holding the TM's
     * power printed by the info panel ("---" for Hail, "100" for a 100-power move): not a message.
     */
    @Test
    fun theTmSequenceBeforeItsFirstMessageIsATransition() {
        assertIs<Screen.Animation>(screen("sf_tm_f245"))
        val booted = assertIs<Screen.Dialogue>(screen("sf_tm_f248"))
        assertEquals(TextSource.MENU, booted.source)
        assertEquals("Booted up a TM.", booted.text)
    }

    /** Ten Poké Balls bought (mart.state): the clerk's bonus message is its own reason, the Premier Ball already in the bag. */
    @Test
    fun theClerksBonusMessageIsTyped() {
        val state = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("sf_mart_f646"))
        assertEquals(ContinueReason.SHOP_BONUS, assertIs<Screen.PressToContinue>(state.screen).reason)
        assertEquals(1, state.bag.orEmpty().flatMap { it.items }.single { it.item.id.value == 12 }.quantity)
    }

    /**
     * Elite Four Will, PILOSWINE (holding the Exp. Share, not in battle) grows to Lv40: before the stats panel the game
     * writes its nameplate ("PILOSWINE ♀ Lv. 40") into the battle message buffer. Part of the panel: no battle message.
     */
    @Test
    fun theLevelUpNameplateIsNotABattleMessage() {
        val memory = HgssFixtures.load("sf_nameplate_f1185")
        assertEquals(true, HgssPostBattleScreens.levelUpPanelUp(HgssMemory(memory, HgssVersion.HEARTGOLD_US)))
        val state = HgssGame(HgssVersion.HEARTGOLD_US).state(memory)
        assertEquals(null, state.battle?.message)
    }
}
