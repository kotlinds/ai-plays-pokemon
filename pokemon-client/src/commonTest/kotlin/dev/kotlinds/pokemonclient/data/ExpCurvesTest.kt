package dev.kotlinds.pokemonclient.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ExpCurvesTest {

    @Test
    fun levelsFromExperience() {
        assertEquals(9, ExpCurves.levelForExp(GrowthRate.MEDIUM_FAST, 999))
        assertEquals(10, ExpCurves.levelForExp(GrowthRate.MEDIUM_FAST, 1000))
        assertEquals(1, ExpCurves.levelForExp(GrowthRate.MEDIUM_SLOW, 0))
        assertEquals(100, ExpCurves.levelForExp(GrowthRate.SLOW, 5_000_000))
        // Kenya (Fearow, medium fast) at 56039 experience: level 38.
        assertEquals(38, ExpCurves.levelForExp(GrowthRate.MEDIUM_FAST, 56039))
    }

    @Test
    fun experienceToTheNextLevel() {
        assertEquals(59319L - 56039L, ExpCurves.expToNextLevel(GrowthRate.MEDIUM_FAST, 38, 56039))
        assertEquals(0L, ExpCurves.expToNextLevel(GrowthRate.FAST, 100, 800000))
        assertEquals(1_640_000L, ExpCurves.expForLevel(GrowthRate.FLUCTUATING, 100))
    }
}
