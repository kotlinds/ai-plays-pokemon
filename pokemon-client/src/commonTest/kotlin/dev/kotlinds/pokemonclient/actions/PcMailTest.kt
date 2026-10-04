package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.data.ItemInfo
import dev.kotlinds.pokemonclient.data.ItemPocket
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A party Pokémon holding Mail can't be stored: the PC actions refuse it before touching anything. */
class PcMailTest {

    private val grassMail = Named(ItemId(137), "Grass Mail")
    private val potion = Named(ItemId(17), "Potion")

    private fun mon(pid: Long, held: Named<ItemId>?) = PartyMon(
        MonId(pid, 1), 0, Named(SpeciesId(18), "PIDGEOT"), null, 40, 100, 100, null, emptyList(), held, null,
        emptyList(), emptyMap(), 0, null, false,
    )

    private fun game(party: List<PartyMon>) = FakeGame(Screen.Overworld(awaiting = Awaiting.INPUT)) {
        GameState(0, it, null, party, emptyList(), null, null)
    }.apply {
        data = StubGameData(
            items = mapOf(
                grassMail.id to ItemInfo(grassMail.id, grassMail.name, ItemPocket.MAIL, 50),
                potion.id to ItemInfo(potion.id, potion.name, ItemPocket.MEDICINE, 300),
            ),
        )
    }

    @Test
    fun depositOfAPokemonHoldingMailIsRefusedWithATypedError() {
        val holder = mon(1, grassMail)
        val game = game(listOf(holder, mon(2, potion)))
        val outcome = PcPlans.deposit.run(GameAction.Deposit(holder.id), game.context())
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(outcome).error)
        assertEquals(UnavailableReason.MAIL_BLOCKS_DEPOSIT, error.reason)
        assertTrue(game.presses.isEmpty() && game.touches.isEmpty(), "refused before touching the PC")
    }

    @Test
    fun aSwapStoringAPokemonHoldingMailIsRefusedToo() {
        val holder = mon(1, grassMail)
        val game = game(listOf(holder, mon(2, null)))
        val outcome = PcPlans.pc.run(GameAction.Pc(listOf(PcOperation.Swap(holder.id, MonId(9, 9)))), game.context())
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(outcome).error)
        assertEquals(UnavailableReason.MAIL_BLOCKS_DEPOSIT, error.reason)
    }
}
