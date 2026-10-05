package dev.kotlinds.pokemonclient.games.hgss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Team Rocket HQ doors ([HgssBlockers]) on the completed hideout (bench, DeSmuME, 8-badge save):
 * `pz_hq_b2f_open` (B2F, map 248, at the stairs from B1F) and `pz_hq_b3f_open` (B3F, map 249). Both doors are open:
 * their objects stand where the map scripts put open doors, so nothing blocks.
 */
class HgssRocketHqFixtureTest {

    private fun state(name: String) = assertNotNull(HgssReader(HgssFixtures.load(name), HgssVersion.HEARTGOLD_US).read())

    @Test
    fun `the B2F voice door is open once the hideout is done`() {
        val state = state("pz_hq_b2f_open")
        assertEquals(248, state.location?.mapId)
        val door = assertNotNull(state.surroundings).objects.filter { it.id in setOf(5, 6) }
        // scr_seq_D35R0103_002: both door objects at (29,22) when the door is open.
        assertEquals(listOf(29 to 22, 29 to 22), door.map { it.x to it.z })
        assertTrue(HgssBlockers.of(state).isEmpty())
    }

    @Test
    fun `the B3F password door is open and the passwords are known`() {
        val state = state("pz_hq_b3f_open")
        assertEquals(249, state.location?.mapId)
        val door = assertNotNull(state.surroundings).objects.filter { it.id in setOf(10, 11) }
        // scr_seq_D35R0104_008: both door objects at (22,15) when open.
        assertEquals(listOf(22 to 15, 22 to 15), door.map { it.x to it.z })
        assertTrue(HgssBlockers.of(state).isEmpty())
        // The two grunts who tell the passwords were beaten during the run.
        val flags = assertNotNull(state.story).flags
        assertTrue(0x550 + 222 in flags && 0x550 + 404 in flags, "$flags")
    }
}
