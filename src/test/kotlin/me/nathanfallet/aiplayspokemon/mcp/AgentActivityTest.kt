package me.nathanfallet.aiplayspokemon.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the panel (and later the overlay) shows of the agent: the action in progress first, else the last one. */
class AgentActivityTest {

    @Test
    fun theActionInProgressIsShownWhileItRunsThenBecomesTheLastOne() {
        val idle = AgentActivity()
        assertNull(idle.shown)

        val running = idle.started("heal", "Healing before the Elite Four")
        assertEquals(AgentActivity.Shown(AgentActivity.Phase.IN_PROGRESS, "heal", "Healing before the Elite Four", null), running.shown)

        val done = running.finished(ok = true, result = null)
        assertEquals(AgentActivity.Shown(AgentActivity.Phase.LAST, "heal", "Healing before the Elite Four", null), done.shown)
        assertEquals(1, done.calls)
    }

    @Test
    fun aFailureIsShownWithItsErrorAndTheNextActionReplacesItAtOnce() {
        val failed = AgentActivity().started("fly(Goldenrod)", "Back to the city").finished(ok = false, result = "NOT_FLYABLE_HERE")
        assertEquals("NOT_FLYABLE_HERE", failed.shown?.result)
        val next = failed.started("go_to(warp:0)", "Going outside")
        assertEquals(AgentActivity.Phase.IN_PROGRESS, next.shown?.phase)
        assertEquals("go_to(warp:0)", next.shown?.action)
    }
}
