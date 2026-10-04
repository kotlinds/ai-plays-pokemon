package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.actions.FakeGame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.AnimationKind
import kotlin.test.Test
import kotlin.test.assertEquals

class RecorderTest {
    /** A battle whose message buffer reads [messages] on successive polls (one per frame with `every = 1`). */
    private fun battleMessages(messages: List<String?>): List<String> {
        var poll = 0
        val game = FakeGame(Screen.Battle(Awaiting.ANIMATION)) { screen ->
            val message = messages[minOf(poll, messages.size - 1)]
            GameState(0, screen, null, emptyList(), null, BattleState(BattleKind.TRAINER, false, null, emptyList(), emptyList(), emptyList(), message), null)
        }
        val recorder = Recorder(game, every = 1)
        val memory = RamMemory(ByteArray(16))
        messages.indices.forEach { frame ->
            poll = frame
            recorder.onFrame(frame.toLong()) { memory }
        }
        return recorder.log.since(0).filterIsInstance<GameEvent.TextShown>().map { it.text }
    }

    @Test
    fun `a half written message is not recorded`() {
        val full = "The foe's MAGNETON fainted!"
        assertEquals(listOf(full), battleMessages(listOf("The foe's MAGNETON fai", full, full, full)))
    }

    @Test
    fun `every message is recorded once, in order`() {
        val messages = listOf("A used Tackle!", "A used Tackle!", "B fainted!", "B fainted!", "B fainted!", "A gained 50 EXP!", "A gained 50 EXP!")
        assertEquals(listOf("A used Tackle!", "B fainted!", "A gained 50 EXP!"), battleMessages(messages))
    }

    @Test
    fun `the same message in two turns is recorded twice when something came between`() {
        val messages = listOf("Ho-Oh flinched!", "Ho-Oh flinched!", "What will HO-OH do?", "What will HO-OH do?", "Ho-Oh flinched!", "Ho-Oh flinched!")
        assertEquals(listOf("Ho-Oh flinched!", "What will HO-OH do?", "Ho-Oh flinched!"), battleMessages(messages))
    }

    /** The texts recorded while the screen goes through [screens] (one per poll). */
    private fun fieldTexts(screens: List<Screen>): List<Pair<TextSource, String>> {
        var poll = 0
        val game = FakeGame(screens[0]) { GameState(0, screens[minOf(poll, screens.size - 1)], null, emptyList(), null, null, null) }
        val recorder = Recorder(game, every = 1)
        val memory = RamMemory(ByteArray(16))
        screens.indices.forEach { frame ->
            poll = frame
            recorder.onFrame(frame.toLong()) { memory }
        }
        return recorder.log.since(0).filterIsInstance<GameEvent.TextShown>().map { it.source to it.text }
    }

    @Test
    fun `a text waiting for a fanfare is recorded once printed`() {
        val found = "ACE found one PP Up!"
        val put = "ACE put the PP Up in the MEDICINE Pocket."
        val screens = listOf(
            Screen.Dialogue(TextSource.FIELD, null, "ACE found", Awaiting.TEXT_PRINTING),
            Screen.Dialogue(TextSource.FIELD, null, found, Awaiting.ANIMATION),
            Screen.Dialogue(TextSource.FIELD, null, found, Awaiting.ANIMATION),
            Screen.Dialogue(TextSource.FIELD, null, put, Awaiting.TEXT_PRINTING),
            Screen.Dialogue(TextSource.FIELD, null, put, Awaiting.INPUT),
        )
        assertEquals(listOf(TextSource.FIELD to found, TextSource.FIELD to put), fieldTexts(screens))
    }

    /** Live (Cherrygrove Center, heal): the question, its yes/no menu, then the box shows the question again once answered. */
    @Test
    fun `the nurse's question is recorded once around its yes-no menu`() {
        val question = "Would you like to rest your\nPokémon?"
        val yesNo = Screen.YesNo(question, listOf(dev.kotlinds.pokemonclient.state.Entry("option:yes", "YES"), dev.kotlinds.pokemonclient.state.Entry("option:no", "NO")),
            dev.kotlinds.pokemonclient.state.Cursor.At(0), dev.kotlinds.pokemonclient.state.Topology.vertical(2))
        val ok = "OK, I'll take your Pokémon for a few\nseconds."
        val screens = listOf(
            Screen.Dialogue(TextSource.FIELD, "nurse", question, Awaiting.INPUT),
            Screen.Animation(AnimationKind.TRANSITION),
            Screen.Dialogue(TextSource.FIELD, "nurse", question, Awaiting.INPUT),
            yesNo,
            yesNo,
            Screen.Dialogue(TextSource.FIELD, "nurse", question, Awaiting.INPUT),
            Screen.Dialogue(TextSource.FIELD, "nurse", ok, Awaiting.TEXT_PRINTING),
            Screen.Dialogue(TextSource.FIELD, "nurse", ok, Awaiting.INPUT),
        )
        assertEquals(listOf(TextSource.FIELD to question, TextSource.FIELD to ok), fieldTexts(screens))
    }

    @Test
    fun `a sign banner is recorded once`() {
        val sign = "Goldenrod City Game Corner"
        val screens = listOf(
            Screen.Overworld(null, Awaiting.INPUT),
            Screen.Overworld(sign, Awaiting.INPUT),
            Screen.Overworld(sign, Awaiting.INPUT),
            Screen.Overworld(null, Awaiting.INPUT),
        )
        assertEquals(listOf(TextSource.SIGN to sign), fieldTexts(screens))
    }

    @Test
    fun `a fade under a message box doesn't record it twice`() {
        val text = "Which Ranking do you want to see?"
        val screens = listOf(
            Screen.Dialogue(TextSource.FIELD, null, text, Awaiting.INPUT),
            Screen.Animation(AnimationKind.TRANSITION),
            Screen.Dialogue(TextSource.FIELD, null, text, Awaiting.INPUT),
        )
        assertEquals(listOf(TextSource.FIELD to text), fieldTexts(screens))
    }
}
