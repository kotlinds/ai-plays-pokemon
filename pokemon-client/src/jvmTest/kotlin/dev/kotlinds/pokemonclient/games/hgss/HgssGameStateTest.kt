package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The common [GameState] model produced from real HeartGold snapshots (see [HgssGameTest] for the fixtures). */
class HgssGameStateTest {

    private fun state(name: String): GameState = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name))

    @Test
    fun battleCommandMenuWithCursorOnFight() {
        val screen = assertIs<Screen.BattleCommand>(state("battle1").screen)
        assertEquals(listOf("FIGHT", "BAG", "RUN", "POKéMON"), screen.entries.map { it.label })
        assertEquals(Cursor.At(0), screen.cursor)
        // From FIGHT: LEFT goes to BAG, DOWN to RUN, RIGHT to POKéMON; UP comes back.
        assertEquals(1, screen.topology.next(0, Button.LEFT))
        assertEquals(2, screen.topology.next(0, Button.DOWN))
        assertEquals(0, screen.topology.next(3, Button.UP))
    }

    @Test
    fun battleStateHasTheOpponentAndStableIdsForOurSide() {
        val battle = assertNotNull(state("battle1").battle)
        assertTrue(battle.battlers.any { !it.ref.isPlayerSide && it.hp > 0 })
        val ours = battle.battlers.first { it.ref.isPlayerSide }
        assertNotNull(ours.mon)
    }

    @Test
    fun startMenuIsATwoColumnGridWithEmptySlotsNotSelectable() {
        val screen = assertIs<Screen.ListMenu>(state("startmenu1").screen)
        assertEquals(MenuKind.START_MENU, screen.kind)
        assertIs<Cursor.At>(screen.cursor)
        assertTrue(screen.entries.filter { it.label == "-" }.none { it.selectable })
    }

    @Test
    fun touchYesNoIsAYesNoScreen() {
        val screen = assertIs<Screen.YesNo>(state("yesno1").screen)
        assertEquals(2, screen.entries.size)
    }

    @Test
    fun overworldWithFieldPosition() {
        val state = state("nb1")
        assertIs<Screen.Overworld>(state.screen)
        val field = assertNotNull(state.field)
        assertTrue(field.mapName.contains("New Bark"))
    }

    @Test
    fun messageWaitingForAIsADialogue() {
        val screen = assertIs<Screen.Dialogue>(state("mom1").screen)
        assertEquals(TextSource.FIELD, screen.source)
        assertEquals(Awaiting.INPUT, screen.awaiting)
        assertTrue(screen.text.isNotBlank())
    }

    @Test
    fun partyMonsHaveStableIds() {
        val party = state("starter1").party
        assertEquals(1, party.size)
        val mon = party.single()
        assertNotEquals(0L, mon.id.personality)
        assertEquals("CYNDAQUIL", mon.species.name.uppercase())
    }
}
