package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Screens that wait and must say so, on real HeartGold snapshots: the main menu (by option ids), the whiteout text,
 * the learn-move prompts naming the move, and the speaker of a trainer.
 */
class HgssMessageScreensTest {

    private fun screen(name: String): Screen = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).screen

    private val gyarados = MonId.parse("mon:49c199cc.76f3a6fb")

    @Test
    fun mainMenuListsTheOptionsByTheirIds() {
        val s = assertIs<Screen.ListMenu>(screen("msg_main_menu"))
        assertEquals(MenuKind.MAIN_MENU, s.kind)
        assertEquals(
            listOf("option:continue", "option:new_game", "option:pokewalker", "option:mystery_gift", "option:wfc", "option:wii_settings"),
            s.entries.map { it.id },
        )
        assertTrue(s.entries.single { it.id == "option:new_game" }.dangerous)
        assertEquals(Cursor.At(0), s.cursor)
        assertEquals(CancelBehavior.CLOSES, s.cancel)
        assertEquals(1, s.topology.next(0, dev.kotlinds.pokemonclient.console.Button.DOWN))
        assertEquals(null, s.topology.next(0, dev.kotlinds.pokemonclient.console.Button.UP))
    }

    @Test
    fun whiteoutTextWaitsForA() {
        val s = assertIs<Screen.Dialogue>(screen("msg_whiteout"))
        assertEquals(TextSource.FIELD, s.source)
        assertEquals(Awaiting.INPUT, s.awaiting)
        assertTrue(s.text.startsWith("ACE "), s.text)
    }

    @Test
    fun forgetPromptNamesTheMoveAndThePokemon() {
        val s = assertIs<Screen.YesNo>(screen("msg_forget_prompt"))
        assertEquals(listOf("option:forget", "option:keep"), s.entries.map { it.id })
        val offer = assertNotNull(s.learning)
        assertEquals(MoveId(401), offer.move.id)
        assertEquals(gyarados, offer.mon)
    }

    @Test
    fun giveUpPromptNamesTheMoveAndThePokemon() {
        val s = assertIs<Screen.YesNo>(screen("msg_give_up"))
        assertEquals(listOf("option:give_up", "option:keep"), s.entries.map { it.id })
        val offer = assertNotNull(s.learning)
        assertEquals(MoveId(401), offer.move.id)
        assertEquals(gyarados, offer.mon)
    }

    @Test
    fun theLevelUpPanelCellsAreNotABattleMessage() {
        for (name in listOf("pb_levelup", "pb_levelup_totals")) {
            val state = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name))
            assertNotNull(state.battle)
            assertEquals(null, state.battle!!.message, name)
        }
    }

    @Test
    fun trainerScriptsGiveTheTrainerId() {
        assertEquals(1, HgssScriptScreens.trainerOfScript(3000))
        assertEquals(143, HgssScriptScreens.trainerOfScript(3142))
        assertEquals(2, HgssScriptScreens.trainerOfScript(5001))
        assertEquals(null, HgssScriptScreens.trainerOfScript(12))
    }

    @Test
    fun trainerSpeakerIsTheTrainerClassAndName() {
        val data = HgssWorldRom.requireData()
        val previous = HgssData.gameData
        HgssData.useGameData(data)
        try {
            val s = assertIs<Screen.Dialogue>(screen("msg_trainer_speaker"))
            assertEquals("Lass Carrie", s.speaker)
        } finally {
            HgssData.useGameData(previous)
        }
    }
}
