package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NavigatorTest {

    private fun menu(labels: List<String>, cursor: Cursor, topology: Topology = Topology.vertical(labels.size)) =
        Screen.ListMenu(MenuKind.OTHER, labels.mapIndexed { i, l -> Entry("option:$i", l) }, cursor, topology)

    /** Moves the cursor of a ListMenu along its topology, [step] cells per press (2 = the "double move" bug). */
    private fun FakeGame.moving(step: Int = 1, confirmed: (Screen.ListMenu) -> Screen = { it }) {
        onPress = { button, screen ->
            val m = screen as Screen.ListMenu
            when (button) {
                Button.A -> confirmed(m)
                else -> {
                    var index = (m.cursor as? Cursor.At)?.index
                    if (index == null) m.copy(cursor = Cursor.At(0))
                    else {
                        repeat(step) { index = m.topology.next(index!!, button) ?: index }
                        m.copy(cursor = Cursor.At(index!!))
                    }
                }
            }
        }
    }

    @Test
    fun selectsWithOneTapPerCellAlongTheShortestPath() {
        val game = FakeGame(menu(listOf("SUMMARY", "SWITCH", "ITEM", "QUIT"), Cursor.At(0)))
        game.moving()
        val step = Navigator(game.scope(), game).select(Screen.ListMenu::class, "QUIT") { it.label == "QUIT" }
        assertIs<Step.Done<*>>(step)
        assertEquals(listOf(Button.DOWN, Button.DOWN, Button.DOWN), game.presses)
    }

    @Test
    fun wrapsAroundWhenItIsShorter() {
        val game = FakeGame(menu(listOf("A", "B", "C", "D", "E"), Cursor.At(0), Topology.vertical(5, wrap = true)))
        game.moving()
        Navigator(game.scope(), game).select(Screen.ListMenu::class, "E") { it.label == "E" }
        assertEquals(listOf(Button.UP), game.presses)
    }

    @Test
    fun aHiddenCursorIsRevealedFirst() {
        val game = FakeGame(menu(listOf("FIGHT", "BAG"), Cursor.Hidden))
        game.moving()
        val step = Navigator(game.scope(), game).select(Screen.ListMenu::class, "BAG") { it.label == "BAG" }
        assertIs<Step.Done<*>>(step)
        assertEquals(Button.UP, game.presses.first())
    }

    @Test
    fun aCursorThatJumpsTwoCellsStillEndsOnTheTargetOrFailsWithoutConfirming() {
        // The old "double move" bug: every press moves two cells. SWITCH (1) can't be reached by parity.
        val game = FakeGame(menu(listOf("SUMMARY", "SWITCH", "ITEM", "QUIT"), Cursor.At(0), Topology.vertical(4, wrap = true)))
        var confirmed = false
        game.moving(step = 2) { confirmed = true; it }
        val result = Navigator(game.scope(), game).choose(Screen.ListMenu::class, "SWITCH") { it.label == "SWITCH" }
        val failed = assertIs<Step.Failed>(result)
        assertIs<ActionError.VerificationFailed>(failed.error)
        assertFalse(confirmed, "must never confirm another entry")
        assertFalse(Button.A in game.presses)
    }

    @Test
    fun confirmRefusesWhenTheCursorIsNotOnTheTarget() {
        val game = FakeGame(menu(listOf("DEPOSIT", "RELEASE"), Cursor.At(1)))
        game.moving()
        val result = Navigator(game.scope(), game).confirm("DEPOSIT", { it.label == "DEPOSIT" })
        assertIs<Step.Failed>(result)
        assertTrue(game.presses.isEmpty())
    }

    @Test
    fun unselectableEntriesAreRefused() {
        val screen = Screen.ListMenu(MenuKind.OTHER, listOf(Entry("mon:1", "PIDGEY"), Entry("mon:2", "RATTATA", selectable = false)), Cursor.At(0), Topology.vertical(2))
        val game = FakeGame(screen)
        val result = Navigator(game.scope(), game).select(Screen.ListMenu::class, "RATTATA") { it.id == "mon:2" }
        assertIs<ActionError.NotSelectable>(assertIs<Step.Failed>(result).error)
    }

    @Test
    fun aMissingEntryIsReportedWithWhatIsOnScreen() {
        val game = FakeGame(menu(listOf("YES", "NO"), Cursor.At(0)))
        val error = assertIs<Step.Failed>(Navigator(game.scope(), game).select(Screen.ListMenu::class, "MAYBE") { it.label == "MAYBE" }).error
        assertIs<ActionError.NotOnScreen>(error)
        assertEquals(listOf("YES", "NO"), error.entries)
    }

    @Test
    fun theWrongScreenIsReported() {
        val game = FakeGame(Screen.Overworld(awaiting = Awaiting.INPUT))
        val error = assertIs<Step.Failed>(Navigator(game.scope(), game).select(Screen.ListMenu::class, "X") { true }).error
        assertIs<ActionError.UnexpectedScreen>(error)
    }

    @Test
    fun advancingMessagesStopsAtAChoiceInsteadOfPressingThrough() {
        val pages = ArrayDeque(listOf("Hello!", "Want a nickname?"))
        val game = FakeGame(Screen.Dialogue(TextSource.FIELD, null, pages.removeFirst(), Awaiting.INPUT))
        game.onPress = { button, screen ->
            if (button == Button.A && screen is Screen.Dialogue) {
                pages.removeFirstOrNull()?.let { Screen.Dialogue(TextSource.FIELD, null, it, Awaiting.INPUT) }
                    ?: Screen.YesNo("Want a nickname?", listOf(Entry("option:yes", "YES"), Entry("option:no", "NO")), Cursor.At(0), Topology.vertical(2))
            } else screen
        }
        val result = Navigator(game.scope(), game).advanceUntil { it.screen !is Screen.Dialogue }
        assertIs<Step.Done<*>>(result)
        assertIs<Screen.YesNo>(game.screen)
        assertEquals(2, game.presses.size, "one A per page, none on the yes/no")
    }

    @Test
    fun anUnexpectedMenuStopsAdvancing() {
        val game = FakeGame(Screen.Dialogue(TextSource.FIELD, null, "...", Awaiting.INPUT))
        game.onPress = { _, _ -> menu(listOf("A", "B"), Cursor.At(0)) }
        val error = assertIs<Step.Failed>(Navigator(game.scope(), game).advanceUntil { it.screen is Screen.Overworld }).error
        assertIs<ActionError.UnexpectedScreen>(error)
    }

    /** The Pokégear's contact list: two contacts the D-pad moves between, and Close, only reachable by touch. */
    private fun contacts() = Screen.ListMenu(
        MenuKind.PHONE_CONTACTS,
        listOf(Entry("contact:mom", "MOM"), Entry("contact:elm", "ELM"), Entry("option:cancel", "Close", touch = TouchPoint(230, 176))),
        Cursor.At(0),
        Topology { from, button -> when (button) { Button.DOWN -> if (from == 0) 1 else null; Button.UP -> if (from == 1) 0 else null; else -> null } },
    )

    @Test
    fun anEntryOnlyReachableByTouchIsTouchedAndTheScreenChecked() {
        val game = FakeGame(contacts())
        game.moving()
        game.onTouch = { point, screen -> if (point == TouchPoint(230, 176)) Screen.Overworld(awaiting = Awaiting.INPUT) else screen }
        val result = Navigator(game.scope(), game).choose(Screen.ListMenu::class, "Close") { it.id == "option:cancel" }
        assertIs<Step.Done<*>>(result)
        assertEquals(listOf(TouchPoint(230, 176)), game.touches)
        assertFalse(Button.A in game.presses, "touching is the confirmation")
    }

    @Test
    fun aTouchTheGameIgnoresFailsAfterTheRetries() {
        val game = FakeGame(contacts())
        game.moving()
        val error = assertIs<Step.Failed>(Navigator(game.scope(), game).choose(Screen.ListMenu::class, "Close") { it.id == "option:cancel" }).error
        assertIs<ActionError.VerificationFailed>(error)
        assertEquals(RetryPolicy().maxCorrections + 1, game.touches.size)
    }

    @Test
    fun anUnreachableEntryWithoutTouchPointStaysUnreachable() {
        val screen = contacts().let { it.copy(entries = it.entries.map { e -> e.copy(touch = null) }) }
        val game = FakeGame(screen)
        game.moving()
        val error = assertIs<Step.Failed>(Navigator(game.scope(), game).choose(Screen.ListMenu::class, "Close") { it.id == "option:cancel" }).error
        assertIs<ActionError.Unreachable>(error)
        assertTrue(game.touches.isEmpty())
    }
}
