package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.StarterStage
import dev.kotlinds.pokemonclient.state.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HgssTextScreens] and [HgssFishing] on real HeartGold (USA) RAM fixtures captured with the dev bench from the
 * 8-badge Ecruteak save: message boxes, sign banners, touch menus, Pokégear phone, Oak's intro and fishing.
 */
class HgssTextScreensTest {

    private val version = HgssVersion.HEARTGOLD_US

    private fun screen(name: String): Screen = HgssGame(version).state(HgssFixtures.load(name)).screen

    private fun fishing(name: String): FishingState? = HgssFishing.state(HgssMemory(HgssFixtures.load(name), version))

    private fun Screen.Selectable.ids() = entries.map { it.id }

    // region Message boxes

    @Test
    fun nurseMessageStillPrintingShowsWhatIsPrintedSoFar() {
        val screen = assertIs<Screen.Dialogue>(screen("text_nurse_printing"))
        assertEquals(TextSource.FIELD, screen.source)
        assertEquals(Awaiting.TEXT_PRINTING, screen.awaiting)
        assertEquals("nurse", screen.speaker)
        assertTrue(screen.text.isNotBlank())
        assertTrue("Pokémon Center" !in screen.text, "only the printed part: ${screen.text}")
    }

    @Test
    fun nurseMessageAtAPageBreakWaitsForA() {
        val screen = assertIs<Screen.Dialogue>(screen("text_nurse_page"))
        assertEquals(Awaiting.INPUT, screen.awaiting)
        assertEquals("Hello, and welcome to\nthe Pokémon Center.", screen.text)
        assertEquals("nurse", screen.speaker)
    }

    @Test
    fun lastPageWaitingForAIsInput() {
        // A CallStd child context waits for A (WaitABPress) while its caller sits in ScrNative_WaitStd.
        val screen = assertIs<Screen.Dialogue>(screen("text_nurse_last"))
        assertEquals(Awaiting.INPUT, screen.awaiting)
        assertEquals("Please, come back again any time!", screen.text)
    }

    @Test
    fun keyItemRefusalIsAFieldMessage() {
        val printing = assertIs<Screen.Dialogue>(screen("text_keyitem_printing"))
        assertEquals(Awaiting.TEXT_PRINTING, printing.awaiting)
        val done = assertIs<Screen.Dialogue>(screen("text_keyitem_done"))
        assertEquals(Awaiting.INPUT, done.awaiting)
        assertTrue(done.text.isNotBlank())
        assertIs<FishingState.Refused>(fishing("text_keyitem_done"))
    }

    // endregion

    // region Touch yes / no and multichoice

    @Test
    fun touchYesNoHasSemanticIdsTouchPointsAndNoWrap() {
        val screen = assertIs<Screen.YesNo>(screen("text_nurse_yesno"))
        assertEquals(listOf("option:yes", "option:no"), screen.ids())
        assertEquals(Cursor.At(0), screen.cursor)
        assertEquals(TouchPoint(127, 71), screen.entries[0].touch)
        assertEquals(TouchPoint(127, 119), screen.entries[1].touch)
        assertEquals(1, screen.topology.next(0, Button.DOWN))
        assertEquals(0, screen.topology.next(1, Button.UP))
        assertNull(screen.topology.next(1, Button.DOWN), "no wrap (verified live)")
        assertNull(screen.topology.next(0, Button.UP))
        assertEquals("Would you like to rest your\nPokémon?", screen.question)
        assertEquals(CancelBehavior.CONFIRMS_LAST, screen.cancel)
    }

    @Test
    fun touchYesNoCursorOnNo() {
        assertEquals(Cursor.At(1), assertIs<Screen.YesNo>(screen("text_nurse_yesno_no")).cursor)
    }

    @Test
    fun touchMultichoiceUsesTheOverlayNavigationTable() {
        val screen = assertIs<Screen.ListMenu>(screen("text_pc_multichoice"))
        assertEquals(MenuKind.MULTICHOICE, screen.kind)
        assertEquals(listOf("option:0", "option:1", "option:2"), screen.ids())
        assertEquals(Cursor.At(0), screen.cursor)
        assertEquals(CancelBehavior.CONFIRMS_LAST, screen.cancel)
        assertEquals(listOf(TouchPoint(127, 47), TouchPoint(127, 94), TouchPoint(127, 143)), screen.entries.map { it.touch })
        assertEquals(1, screen.topology.next(0, Button.DOWN))
        assertEquals(2, screen.topology.next(1, Button.DOWN))
        assertNull(screen.topology.next(2, Button.DOWN), "no wrap (verified live)")
        assertNull(screen.topology.next(0, Button.UP))
        assertNull(screen.topology.next(0, Button.LEFT))
        assertEquals(Cursor.At(1), assertIs<Screen.ListMenu>(screen("text_pc_multichoice_1")).cursor)
        assertEquals(Cursor.At(2), assertIs<Screen.ListMenu>(screen("text_pc_multichoice_2")).cursor)
    }

    @Test
    fun multichoiceGridTablesFollowTheGame() {
        // 6 options: UP from the top-right goes to the top-left (ov27_0225D480 quirk); 7 options: 6 alone bottom-right.
        val six = HgssTextAddresses.topology(HgssTextAddresses.TOUCH_MENU_NAVIGATION[4])
        assertEquals(0, six.next(1, Button.UP))
        assertEquals(3, six.next(1, Button.DOWN))
        assertEquals(5, six.next(4, Button.RIGHT))
        assertNull(six.next(0, Button.LEFT))
        val seven = HgssTextAddresses.topology(HgssTextAddresses.TOUCH_MENU_NAVIGATION[5])
        assertEquals(6, seven.next(5, Button.DOWN))
        assertEquals(6, seven.next(4, Button.DOWN))
        assertNull(seven.next(6, Button.LEFT))
        assertEquals(TouchPoint(191, 167), HgssTextAddresses.TOUCH_MENU_RECTS[5][6].center)
    }

    // endregion

    // region Sign banners

    @Test
    fun trainerTipsBannerWaitingAtAPageIsOverworldWithTheWholeText() {
        val screen = assertIs<Screen.Overworld>(screen("text_banner_tips"))
        assertEquals(Awaiting.INPUT, screen.awaiting)
        assertEquals("Ecruteak City Pokémon Gym\nLeader: Morty\n\nThe Mystic Seer of the Future", screen.banner)
    }

    @Test
    fun trainerTipsBannerWhilePrintingIsNotBlocking() {
        val screen = assertIs<Screen.Overworld>(screen("text_banner_tips_printing"))
        assertEquals(Awaiting.INPUT, screen.awaiting)
        assertNotNull(screen.banner)
    }

    @Test
    fun bannerAfterCallStdIsReadOnTheInnermostContext() {
        // ctx0 = ScrNative_WaitStd (0x02040BCC), ctx1 = ScrCmd_060 wait (0x020415E0): the old "native(...)" scene.
        val screen = assertIs<Screen.Overworld>(screen("text_banner_tips_end"))
        assertEquals(Awaiting.INPUT, screen.awaiting)
        assertTrue(screen.banner!!.startsWith("Ecruteak City Pokémon Gym"))
    }

    @Test
    fun directionSignpostBannerIsOverworld() {
        val screen = assertIs<Screen.Overworld>(screen("text_banner_direction"))
        assertEquals(Awaiting.INPUT, screen.awaiting)
        assertEquals("Ecruteak City\nA Historical City", screen.banner)
    }

    @Test
    fun bannerSlideInIsAShortAnimationWithoutStaleText() {
        val screen = assertIs<Screen.Overworld>(screen("text_banner_slide"))
        assertEquals(Awaiting.ANIMATION, screen.awaiting)
        assertNull(screen.banner)
    }

    @Test
    fun closedBannerLeavesNoBannerEvenWithTheStaleWindowFlag() {
        val screen = assertIs<Screen.Overworld>(screen("text_banner_closed"))
        assertEquals(Awaiting.INPUT, screen.awaiting)
        assertNull(screen.banner)
    }

    // endregion

    // region Negatives

    @Test
    fun startMenuIsNotTakenByTheTextDecoder() {
        assertEquals(MenuKind.START_MENU, assertIs<Screen.ListMenu>(screen("startmenu1")).kind)
        // The bottom-screen icons of the Pokémon Center save are always shown: that is the overworld.
        assertEquals(Screen.Overworld(null, Awaiting.INPUT), screen("text_startmenu"))
    }

    @Test
    fun overworldIsLeftToTheGenericMapping() {
        assertEquals(Screen.Overworld(null, Awaiting.INPUT), screen("text_overworld_pc"))
        assertNull(fishing("text_overworld_pc"))
        assertNull(fishing("text_fish_pond_overworld"))
        assertNull(fishing("text_phone_list"))
    }

    // endregion

    // region Pokégear phone

    @Test
    fun contactListWithTheCursorOnTheAppBar() {
        val screen = assertIs<Screen.ListMenu>(screen("text_phone_bar"))
        assertEquals(MenuKind.PHONE_CONTACTS, screen.kind)
        assertEquals(
            listOf(
                "contact:0", "contact:1", "contact:2", "contact:5", "contact:4", "contact:6", "contact:7", "contact:24",
                "contact:53", "contact:54", "contact:21",
                "app:configure", "app:radio", "app:map", "option:cancel", "app:phone",
            ),
            screen.ids(),
        )
        assertEquals(Cursor.At(15), screen.cursor) // app:phone
        assertEquals(CancelBehavior.CLOSES, screen.cancel)
        // The bar wraps; LEFT from the phone goes to the map, RIGHT to the close button.
        assertEquals(13, screen.topology.next(15, Button.LEFT))
        assertEquals(14, screen.topology.next(15, Button.RIGHT))
        assertEquals(11, screen.topology.next(14, Button.RIGHT))
        assertEquals(14, screen.topology.next(11, Button.LEFT))
        assertNull(screen.topology.next(15, Button.UP))
        assertEquals(TouchPoint(230, 176), screen.entries[14].touch)
        assertEquals("Mother", screen.entries[0].label)
    }

    @Test
    fun contactListWithTheCursorInTheList() {
        val screen = assertIs<Screen.ListMenu>(screen("text_phone_list"))
        assertEquals(Cursor.At(0), screen.cursor)
        assertEquals(CancelBehavior.SELECTS_LAST, screen.cancel)
        assertEquals(1, screen.topology.next(0, Button.DOWN))
        assertNull(screen.topology.next(0, Button.UP), "no wrap")
        assertNull(screen.topology.next(10, Button.DOWN))
        // Only the rows on the page can be touched.
        assertEquals(TouchPoint(116, 20), screen.entries[0].touch)
        assertNull(screen.entries[6].touch)
        // RIGHT turns a page keeping the row: first contact 0 -> 5 (11 contacts, 6 rows).
        assertEquals(5, screen.topology.next(0, Button.RIGHT))
        assertNull(screen.topology.next(0, Button.LEFT))
        assertEquals(Cursor.At(1), assertIs<Screen.ListMenu>(screen("text_phone_list_row1")).cursor)
    }

    @Test
    fun contactListAfterAPageTurn() {
        val screen = assertIs<Screen.ListMenu>(screen("text_phone_list_page2"))
        assertEquals(Cursor.At(6), screen.cursor) // row 1 of the page starting at contact 5
        assertEquals(TouchPoint(116, 20), screen.entries[5].touch)
        assertNull(screen.entries[4].touch)
        assertEquals(1, screen.topology.next(6, Button.LEFT))
        assertNull(screen.topology.next(6, Button.RIGHT))
    }

    @Test
    fun callSortQuitMenu() {
        val screen = assertIs<Screen.ListMenu>(screen("text_phone_ctxmenu"))
        assertEquals(MenuKind.OTHER, screen.kind)
        assertEquals(listOf("option:0", "option:1", "option:2"), screen.ids())
        assertEquals(listOf("Call", "Sort", "Quit"), screen.entries.map { it.label })
        assertEquals(CancelBehavior.CLOSES, screen.cancel)
        assertEquals(TouchPoint(176, 88), screen.entries[0].touch)
        assertEquals(0, screen.topology.next(2, Button.DOWN), "TouchscreenListMenu wraps")
    }

    @Test
    fun callRingingThenPrintingThenPage() {
        val ring = assertIs<Screen.Dialogue>(screen("text_phone_ring"))
        assertEquals(TextSource.PHONE, ring.source)
        assertEquals("Prof. Oak", ring.speaker)
        assertEquals(Awaiting.ANIMATION, ring.awaiting)
        val printing = assertIs<Screen.Dialogue>(screen("text_phone_printing"))
        assertEquals(Awaiting.TEXT_PRINTING, printing.awaiting)
        assertEquals("Prof. Oak", printing.speaker)
        val page = assertIs<Screen.Dialogue>(screen("text_phone_page"))
        assertEquals(Awaiting.INPUT, page.awaiting)
        assertEquals("Hello, this is Professor Oak...\nOh, hello, ACE!", page.text)
    }

    @Test
    fun questionDuringACall() {
        val screen = assertIs<Screen.ListMenu>(screen("text_phone_question"))
        assertEquals(MenuKind.MULTICHOICE, screen.kind)
        assertEquals(listOf("option:0", "option:1"), screen.ids())
        assertEquals(Cursor.At(0), screen.cursor)
        assertEquals(1, screen.topology.next(0, Button.UP), "wraps (verified live)")
        assertEquals(TouchPoint(152, 104), screen.entries[0].touch)
        assertEquals(Cursor.At(1), assertIs<Screen.ListMenu>(screen("text_phone_question_1")).cursor)
    }

    @Test
    fun hangUpWaitAndClick() {
        val end = assertIs<Screen.PressToContinue>(screen("text_phone_hangup"))
        assertEquals(ContinueReason.PHONE_CALL_ENDED, end.reason)
        assertEquals("Show me your Pokédex again anytime!", end.text)
        assertEquals(Screen.Animation(AnimationKind.TRANSITION), screen("text_phone_click"))
    }

    // endregion

    // region Oak's intro

    @Test
    fun infoMenuStartsWithAHiddenCursor() {
        val hidden = assertIs<Screen.ListMenu>(screen("text_oak_info_hidden"))
        assertEquals(Cursor.Hidden, hidden.cursor)
        assertEquals(listOf("option:0", "option:1", "option:2"), hidden.ids())
        assertEquals(CancelBehavior.CONFIRMS_LAST, hidden.cancel)
        assertEquals(TouchPoint(131, 35), hidden.entries[0].touch)
        assertEquals(Cursor.At(0), assertIs<Screen.ListMenu>(screen("text_oak_info_shown")).cursor)
        val last = assertIs<Screen.ListMenu>(screen("text_oak_info_2"))
        assertEquals(Cursor.At(2), last.cursor)
        assertNull(last.topology.next(2, Button.DOWN), "no wrap (verified live)")
    }

    @Test
    fun understoodYesNoShowsItsCursorAtOnce() {
        val screen = assertIs<Screen.YesNo>(screen("text_oak_understood"))
        assertEquals(Cursor.At(0), screen.cursor)
        assertEquals(listOf("option:yes", "option:no"), screen.ids())
        assertEquals(Cursor.At(1), assertIs<Screen.YesNo>(screen("text_oak_understood_no")).cursor)
    }

    @Test
    fun genderPickAndConfirmation() {
        val gender = assertIs<Screen.ListMenu>(screen("text_oak_gender"))
        assertEquals(Cursor.At(0), gender.cursor) // pad mode kept from the info menu: no hidden step this time
        assertEquals(1, gender.topology.next(0, Button.RIGHT))
        assertNull(gender.topology.next(1, Button.RIGHT))
        assertEquals(CancelBehavior.NONE, gender.cancel)
        assertEquals(Cursor.At(1), assertIs<Screen.ListMenu>(screen("text_oak_gender_girl")).cursor)
        val confirm = assertIs<Screen.YesNo>(screen("text_oak_confirm_hidden"))
        assertEquals(Cursor.Hidden, confirm.cursor)
        assertEquals(TouchPoint(195, 54), confirm.entries[0].touch) // boy: menu on the right
        assertEquals(Cursor.At(0), assertIs<Screen.YesNo>(screen("text_oak_confirm_shown")).cursor)
        assertEquals(Cursor.At(1), assertIs<Screen.YesNo>(screen("text_oak_confirm_no")).cursor)
    }

    // endregion

    // region Fishing

    @Test
    fun fishingCastWaitAndBite() {
        assertEquals(FishingState.Casting(Rod.GOOD), fishing("text_fish_casting"))
        assertEquals(Screen.Animation(AnimationKind.CUTSCENE), screen("text_fish_casting"))
        assertEquals(FishingState.Waiting(Rod.GOOD), fishing("text_fish_waiting"))
        val bite = assertIs<FishingState.Bite>(fishing("text_fish_bite"))
        assertEquals(Rod.GOOD, bite.rod)
        assertTrue(bite.framesLeft in 1..60)
        assertEquals(ContinueReason.FISHING_BITE, assertIs<Screen.PressToContinue>(screen("text_fish_bite")).reason)
    }

    @Test
    fun fishingOutcomes() {
        val landed = assertIs<FishingState.Hooked>(fishing("text_fish_landed"))
        assertEquals(Awaiting.INPUT, landed.awaiting)
        assertEquals("Landed a Pokémon!", landed.text)
        val gotAway = assertIs<FishingState.GotAway>(fishing("text_fish_gotaway"))
        assertEquals(Awaiting.INPUT, gotAway.awaiting)
        val tooEarly = assertIs<FishingState.TooEarly>(fishing("text_fish_tooearly"))
        assertEquals(Awaiting.INPUT, tooEarly.awaiting)
        val dialogue = assertIs<Screen.Dialogue>(screen("text_fish_gotaway"))
        assertEquals(Awaiting.INPUT, dialogue.awaiting)
        assertFalse(dialogue.text.isBlank())
    }

    // endregion

    @Test
    fun theProfessorsSpeechIsADialogueWaitingForA() {
        // A new game, "NO INFO NEEDED", then the professor's first message page waiting for A (src/oaks_speech.c:961).
        val s = assertIs<Screen.Dialogue>(screen("oak_speech_wait"))
        assertEquals(TextSource.INTRO, s.source)
        assertEquals("Sorry to keep you waiting!", s.text)
        assertEquals(Awaiting.INPUT, s.awaiting)
    }

    @Test
    fun elmsMachineListsTheStartersBySpecies() {
        val s = assertIs<Screen.StarterChoice>(screen("starter_machine"))
        assertEquals(listOf(152, 155, 158), s.starters.map { it.id.value })
        assertEquals(0, s.front)
        assertEquals(StarterStage.LOOKING, s.stage)
        assertEquals(Awaiting.INPUT, s.awaiting)
    }

    @Test
    fun lyrasCatchingDemoIsACutSceneWithThePlayersOwnParty() {
        // Route 29: Lyra's Marill battles a Rattata by itself (BATTLE_TYPE_TUTORIAL). Its menus aren't the player's, and
        // the battle's party is Lyra's: the state keeps the player's (no "obtained MARILL").
        val state = HgssGame(version).state(HgssFixtures.load("lyra_catching_demo"))
        assertEquals(Screen.Animation(AnimationKind.CUTSCENE), state.screen)
        assertEquals(dev.kotlinds.pokemonclient.state.BattleKind.DEMO, assertNotNull(state.battle).kind)
        assertEquals(listOf(155), state.party.map { it.species.id.value })
    }
}
