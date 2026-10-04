package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.state.MajorStatus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Gen 4 capture formula, checked by hand against `BattleSystem_CalculateBallShakes`. */
class CatchChanceTest {

    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 0.001, "expected ~$expected, got $actual")

    @Test
    fun easyCatchWithAPokeBallAtFullHp() {
        // a = (255 × 10 / 10) × (3h − 2h) / 3h = 85; b = 0xFFFF0 / √√(0xFF0000 / 85) = 1048560 / √443 = 1048560 / 21 = 49931.
        near(Math.pow(49931 / 65536.0, 4.0), CatchChance.chance(255, 10, 100, 100, null))
    }

    @Test
    fun legendaryAsleepAtOneHpWithAnUltraBall() {
        // a = (3 × 20 / 10) × 298 / 300 = 5, asleep ×2 = 10; b = 1048560 / √√1671168 = 1048560 / 35 = 29958.
        near(Math.pow(29958 / 65536.0, 4.0), CatchChance.chance(3, 20, 1, 100, MajorStatus.Asleep(2)))
    }

    @Test
    fun statusAndHpRaiseTheChanceAndBigValuesAreCertain() {
        val full = CatchChance.chance(45, 15, 100, 100, null)
        val low = CatchChance.chance(45, 15, 10, 100, null)
        val paralysed = CatchChance.chance(45, 15, 10, 100, MajorStatus.Paralyzed)
        assertTrue(full < low && low < paralysed)
        assertEquals(1.0, CatchChance.chance(255, 20, 1, 100, MajorStatus.Frozen))
    }
}
