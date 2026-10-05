package dev.kotlinds.pokemonclient.platinum

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.GameMode
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Platinum's intro and first field screens decoded from RAM fixtures captured live on the bench (Pokémon Platinum
 * USA), without the ROM: ids, cursors, topology, touch points. Texts that come from the ROM's banks are null here
 * (see [PlatinumRomTest]).
 */
class PlatinumIntroFixtureTest {

    private val game = PlatinumGame(PlatinumVersion.PLATINUM_US)

    private fun state(name: String): GameState = game.state(PlatinumFixtures.load(name))

    private fun ids(screen: Screen.Selectable) = screen.entries.map { it.id }

    @Test
    fun `opening movie can be skipped once its first scene is shown`() {
        assertEquals(Screen.Intro("intro_movie", Awaiting.INPUT), state("pt_opening").screen)
    }

    @Test
    fun `title screen waits for A`() {
        assertEquals(Screen.Intro("title_screen", Awaiting.INPUT), state("pt_title").screen)
    }

    @Test
    fun `main menu with a save lists the options shown by their ids`() {
        val menu = assertIs<Screen.ListMenu>(state("pt_main_menu").screen)
        assertEquals(MenuKind.MAIN_MENU, menu.kind)
        assertEquals(listOf("option:continue", "option:new_game", "option:wfc", "option:wii_settings"), ids(menu))
        assertEquals(Cursor.At(0), menu.cursor)
        assertTrue(menu.entries[1].dangerous)
        assertEquals(1, menu.topology.next(0, Button.DOWN))
        assertNull(menu.topology.next(0, Button.UP)) // FocusNextOption doesn't wrap
        assertNull(menu.topology.next(3, Button.DOWN))
    }

    @Test
    fun `new game warning waits for A`() {
        val screen = assertIs<Screen.PressToContinue>(state("pt_new_game_warning").screen)
        assertEquals(ContinueReason.MESSAGE, screen.reason)
    }

    @Test
    fun `professor's message is an intro dialogue waiting at its page break`() {
        val s = state("pt_rowan_dialogue")
        assertEquals(Screen.Dialogue(TextSource.INTRO, null, "Hello there! It’s so very nice to meet you!", Awaiting.INPUT), s.screen)
        assertNull(s.field)
        assertEquals(GameMode.INTRO, game.observe(PlatinumFixtures.load("pt_rowan_dialogue")).mode)
    }

    @Test
    fun `info menu is a list whose ids are the choice indices`() {
        val menu = assertIs<Screen.ListMenu>(state("pt_info_menu").screen)
        assertEquals(listOf("option:0", "option:1", "option:2"), ids(menu))
        assertEquals(listOf("CONTROL INFO", "ADVENTURE INFO", "NO INFO NEEDED"), menu.entries.map { it.label })
        assertEquals(Cursor.At(0), menu.cursor)
        assertEquals(CancelBehavior.NONE, menu.cancel)
        assertEquals(2, menu.topology.next(1, Button.DOWN))
        assertNull(menu.topology.next(2, Button.DOWN))
    }

    @Test
    fun `control info text block waits for A`() {
        assertIs<Screen.PressToContinue>(state("pt_control_text").screen)
    }

    @Test
    fun `control info yes no is touch only`() {
        val yesNo = assertIs<Screen.YesNo>(state("pt_control_yesno").screen)
        assertEquals(listOf("option:yes", "option:no"), ids(yesNo))
        assertEquals(listOf(TouchPoint(120, 80), TouchPoint(120, 120)), yesNo.entries.map { it.touch })
        assertEquals(Cursor.Hidden, yesNo.cursor)
        for (i in 0..1) for (b in Button.entries) assertNull(yesNo.topology.next(i, b))
    }

    @Test
    fun `poke ball is touched to open it`() {
        val menu = assertIs<Screen.ListMenu>(state("pt_poke_ball").screen)
        assertEquals(listOf("option:poke_ball"), ids(menu))
        assertEquals(TouchPoint(128, 100), menu.entries.single().touch)
        assertEquals(Cursor.Hidden, menu.cursor)
    }

    @Test
    fun `gender choice switches with left and right`() {
        val menu = assertIs<Screen.ListMenu>(state("pt_gender").screen)
        assertEquals(listOf("option:0", "option:1"), ids(menu))
        assertEquals(Cursor.At(0), menu.cursor)
        assertEquals(1, menu.topology.next(0, Button.LEFT))
        assertEquals(0, menu.topology.next(1, Button.RIGHT))
        assertNull(menu.topology.next(0, Button.DOWN))
    }

    @Test
    fun `gender confirmation is a yes no where B answers no`() {
        val yesNo = assertIs<Screen.YesNo>(state("pt_gender_yesno").screen)
        assertEquals(listOf("option:yes", "option:no"), ids(yesNo))
        assertEquals(listOf("YES", "NO"), yesNo.entries.map { it.label })
        assertEquals(Cursor.At(0), yesNo.cursor)
        assertEquals(CancelBehavior.CONFIRMS_LAST, yesNo.cancel)
    }

    @Test
    fun `naming keyboard hides its cursor until the first d-pad press`() {
        val keyboard = assertIs<Screen.Keyboard>(state("pt_keyboard_hidden").screen)
        assertEquals("player", keyboard.purpose)
        assertEquals("upper", keyboard.page)
        assertEquals("", keyboard.buffer)
        assertEquals(7, keyboard.maxLength)
        assertEquals(Cursor.Hidden, keyboard.cursor)
    }

    @Test
    fun `naming keyboard is the common keyboard model`() {
        val keyboard = assertIs<Screen.Keyboard>(state("pt_keyboard").screen)
        assertEquals(Cursor.At(14), keyboard.cursor) // first RIGHT revealed the cursor on A, the second moved it
        assertEquals("key:A", keyboard.entries[13].id)
        assertEquals("key:B", keyboard.entries[14].id)
        assertEquals("page:lower", keyboard.entries[2].id)
        assertEquals("option:ok", keyboard.entries[11].id)
        assertEquals(1, keyboard.topology.next(14, Button.UP))
        assertEquals(25, keyboard.topology.next(13, Button.LEFT)) // wraps on the row
    }

    @Test
    fun `rival names are a list read from RAM`() {
        val menu = assertIs<Screen.ListMenu>(state("pt_rival_choice").screen)
        assertEquals(listOf("option:0", "option:1", "option:2", "option:3", "option:4"), ids(menu))
        assertEquals(listOf("New name!", "Barry", "Nolan", "Roy", "Gavin"), menu.entries.map { it.label })
    }

    @Test
    fun `tv programme waits for A`() {
        assertIs<Screen.PressToContinue>(state("pt_tv").screen)
    }

    @Test
    fun `field message of a script`() {
        val s = state("pt_field_dialogue")
        val dialogue = assertIs<Screen.Dialogue>(s.screen)
        assertEquals(TextSource.FIELD, dialogue.source)
        assertEquals("That concludes our special program, “Let’s Ask Prof. Rowan!”", dialogue.text)
        assertEquals(Awaiting.INPUT, dialogue.awaiting)
        assertEquals(415, s.field?.mapId)
    }

    @Test
    fun `bedroom overworld with the player's position`() {
        val s = state("pt_bedroom")
        assertEquals(Screen.Overworld(awaiting = Awaiting.INPUT), s.screen)
        val field = s.field!!
        assertEquals(415, field.mapId)
        assertEquals(4 to 6, field.x to field.y)
        assertEquals(Direction.NORTH, field.facing)
        assertEquals(false, field.moving)
        assertEquals(GameMode.OVERWORLD, game.observe(PlatinumFixtures.load("pt_bedroom")).mode)
    }

    @Test
    fun `sign message of a coordinate trigger`() {
        assertEquals(Screen.Dialogue(TextSource.SIGN, null, "The X Button opens the menu!", Awaiting.INPUT), state("pt_sign_tip").screen)
    }
}
