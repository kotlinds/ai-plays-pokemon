package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Selling at a Poké Mart, captured in game (Ecruteak Mart): the bag SELL opens, the number, the price question, the end. */
class HgssSellScreensTest {

    private fun screen(name: String): Screen = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).screen

    @Test
    fun theSellListIsTheBag() {
        val bag = assertIs<Screen.Bag>(screen("sc_sell_list"))
        assertEquals("balls", bag.pocket)
        assertTrue(bag.entries.any { it.id == "item:3" }, "Great Balls to sell")
    }

    @Test
    fun howManyToSell() {
        assertEquals(Screen.Quantity(2, 1, 7), screen("sc_sell_qty"))
    }

    @Test
    fun thePriceQuestionAndTheEnd() {
        val yesNo = assertIs<Screen.YesNo>(screen("sc_sell_yesno"))
        assertEquals(listOf("option:yes", "option:no"), yesNo.entries.map { it.id })
        assertEquals(Cursor.At(0), yesNo.cursor)
        val done = assertIs<Screen.Dialogue>(screen("sc_sell_done"))
        assertEquals(TextSource.MENU, done.source)
        assertTrue(done.text.isNotBlank())
    }
}
