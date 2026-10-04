package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.PlayTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** What the state reads in battle: trainer names, the battle's own bag, the play time. */
class HgssBattleReadTest {

    private fun state(name: String): GameState = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name))

    @Test
    fun trainerNamesAreUncompressed() {
        // Trainer.name holds the ROM's compressed string (F100 1B41 0A85 7FEA): it used to read "??쥠?".
        assertEquals(listOf("Elite Four Will"), state("bt_trainer_will").battle?.trainers)
    }

    @Test
    fun compressedStringsUnpackNineBitsFromFifteen() {
        assertEquals("Will", HgssText.decode(intArrayOf(0xF100, 0x1B41, 0x0A85, 0x7FEA, 0xFFFF, 0, 0, 0)))
    }

    @Test
    fun theBagInBattleIsTheBattlesCopy() {
        // One Great Ball thrown out of 7: the save's bag still says 7 until the battle ends.
        val balls = assertNotNull(state("bt_bag_after_throw").bag).single { it.name == "balls" }.items
        assertEquals(6, balls.single { it.item.id.value == 3 }.quantity)
    }

    @Test
    fun playTimeIsRead() {
        val time = assertNotNull(state("bt_bag_after_throw").player?.playTime)
        assertEquals(true, time.minutes in 0..59 && time.hours > 0, "$time")
        assertEquals("3:07", PlayTime(3, 7, 0).toString())
    }
}
