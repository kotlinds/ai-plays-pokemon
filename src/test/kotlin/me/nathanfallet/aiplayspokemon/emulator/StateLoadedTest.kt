package me.nathanfallet.aiplayspokemon.emulator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every [ConsoleHost.loadState] tells its [ConsoleHost.stateLoadedListener] when the state was loaded (the app's
 * event recorder forgets the game it learned there, `Recorder.saveLoaded`: a state loaded with the F-keys from before
 * a move was learned showed a false "learned X, forgot Y"), and never when nothing was loaded (no state in the slot:
 * the game is unchanged, what the recorder learned still holds).
 */
class StateLoadedTest {

    @Test
    fun aLoadedStateTellsTheListener() {
        var told = 0
        assertTrue(ConsoleHost.afterLoad(loaded = true) { told++ })
        assertEquals(1, told)
    }

    @Test
    fun aLoadThatFailedTellsNothing() {
        var told = 0
        assertFalse(ConsoleHost.afterLoad(loaded = false) { told++ })
        assertEquals(0, told)
        // No listener (no recorder: an unsupported game): the load's outcome is unchanged.
        assertTrue(ConsoleHost.afterLoad(loaded = true, listener = null))
    }

    @Test
    fun aListenerThatFailsDoesNotFailTheLoad() {
        assertTrue(ConsoleHost.afterLoad(loaded = true) { error("recorder broke") })
    }
}
