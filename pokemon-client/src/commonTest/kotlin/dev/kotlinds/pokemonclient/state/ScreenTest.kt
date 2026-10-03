package dev.kotlinds.pokemonclient.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenTest {

    private fun yesNo(question: String, cursor: Int) =
        Screen.YesNo(question, listOf(Entry("option:yes", "YES"), Entry("option:no", "NO")), Cursor.At(cursor), Topology.vertical(2))

    @Test
    fun twoDecodesOfTheSameMenuAreTheSameScreen() {
        // Each decode builds a new topology function: plain equality fails, sameAs doesn't.
        assertFalse(yesNo("Save?", 0) == yesNo("Save?", 0))
        assertTrue(yesNo("Save?", 0).sameAs(yesNo("Save?", 0)))
    }

    @Test
    fun anyOtherFieldMakesADifferentScreen() {
        assertFalse(yesNo("Save?", 0).sameAs(yesNo("Save?", 1)))
        assertFalse(yesNo("Save?", 0).sameAs(yesNo("Overwrite?", 0)))
        assertFalse(yesNo("Save?", 0).sameAs(Screen.Animation(AnimationKind.TRANSITION)))
        assertTrue(Screen.Animation(AnimationKind.TRANSITION).sameAs(Screen.Animation(AnimationKind.TRANSITION)))
    }
}
