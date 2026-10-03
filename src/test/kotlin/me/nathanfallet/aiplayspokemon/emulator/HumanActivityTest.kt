package me.nathanfallet.aiplayspokemon.emulator

import me.nathanfallet.aiplayspokemon.agent.ActGate
import dev.kotlinds.pokemonclient.actions.ActionError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who may act: an agent's action is refused only while a human really plays (keys held, or released moments ago),
 * never just because the game runs freely in real time (that is what "pause while thinking" lifts before each act).
 */
class HumanActivityTest {

    private var now = 0L
    private fun activity() = HumanActivity(clock = { now }, graceMillis = 2_000)

    @Test
    fun theGameRunningFreelyIsNotAHumanPlaying() {
        val activity = activity()
        now = 10_000
        assertFalse(activity.isPlaying())
        assertNull(ActGate.refusal(humanPlaying = activity.isPlaying(), userPaused = false))
    }

    @Test
    fun aHumanHoldingKeysPlays() {
        val activity = activity()
        activity.input(active = true)
        now = 60_000
        assertTrue(activity.isPlaying(), "still holding")
        assertEquals(ActionError.HumanDriving, ActGate.refusal(humanPlaying = activity.isPlaying(), userPaused = false))
    }

    @Test
    fun theAgentGetsTheGameBackShortlyAfterTheHumanLetsGo() {
        val activity = activity()
        activity.input(active = true)
        now = 1_000
        activity.input(active = false)
        now = 2_500
        assertTrue(activity.isPlaying(), "released 1.5 s ago: the human may press again")
        now = 3_100
        assertFalse(activity.isPlaying(), "released 2.1 s ago: the agent may act")
    }

    @Test
    fun theHumanPauseRefusesActionsWithItsOwnError() {
        assertEquals(ActionError.PausedByHuman, ActGate.refusal(humanPlaying = false, userPaused = true))
    }
}

/**
 * The whole sequence, as an agent sees it through [ActGate]: it plays, the human takes over (keys, then the touch
 * screen), lets go, the agent gets the game back after the grace period; then the Pause button, and resume.
 */
class HumanTakeoverScenarioTest {

    private var now = 0L
    private val activity = HumanActivity(clock = { now }, graceMillis = 2_000)
    private var paused = false

    /** What an agent's act would get now: null = it may act. */
    private fun agentGets(): ActionError? = ActGate.refusal(humanPlaying = activity.isPlaying(), userPaused = paused)

    private fun at(millis: Long, expected: ActionError?, step: String) {
        now = millis
        assertEquals(expected, agentGets(), "at $millis ms: $step")
    }

    @Test
    fun agentThenHumanThenAgentAgainThenPause() {
        at(0, null, "nobody touches anything: the agent plays")
        at(5_000, null, "the game runs freely between two agent calls: still the agent's")

        now = 6_000
        activity.keys(held = true)
        at(6_000, ActionError.HumanDriving, "the human presses a key")
        at(9_000, ActionError.HumanDriving, "keeps holding it")

        now = 9_500
        activity.keys(held = false)
        at(10_000, ActionError.HumanDriving, "just let go: the human may press again")

        now = 10_500
        activity.touch(touching = true)
        at(10_600, ActionError.HumanDriving, "now touches the screen")
        now = 11_000
        activity.keys(held = true)
        now = 11_200
        activity.touch(touching = false)
        at(13_500, ActionError.HumanDriving, "the touch is over but a key is still held")
        now = 14_000
        activity.keys(held = false)

        at(15_900, ActionError.HumanDriving, "1.9 s after the last input")
        at(16_100, null, "2.1 s after the last input: back to the agent")
        at(60_000, null, "and it stays the agent's")

        paused = true
        at(61_000, ActionError.PausedByHuman, "the Pause button")
        now = 62_000
        activity.keys(held = true)
        at(62_000, ActionError.PausedByHuman, "paused AND a key held: the pause is what the agent is told")
        now = 62_500
        activity.keys(held = false)
        paused = false
        at(63_000, ActionError.HumanDriving, "resumed, but the key was released only 0.5 s ago")
        at(64_600, null, "resumed and quiet: the agent plays again")
    }
}
