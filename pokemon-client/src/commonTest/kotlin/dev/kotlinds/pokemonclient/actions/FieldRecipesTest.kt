package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.OVERWORLD
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.isStart
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.bag
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.dialogue
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.field
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.grid
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.item
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.menu
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.mon
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.move
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.yesNo
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.LearnQuestion
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.MoveOffer
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Field recipes that had no test of their logic, on scripted screens shaped like the real ones met live on the bench
 * (see HgssLiveChecksFixtureTest for the real screens): teaching a TM / HM, key items with Y or from the bag,
 * registering, Fly (touch, steering an off-screen town, not visited), fishing's frame watch, give / take (Mail).
 */
class FieldRecipesTest {

    private val focusPunch = Named(MoveId(264), "Focus Punch")
    private val tm01 = item(328, "TM01")
    private val hm04 = item(423, "HM04")

    // region teach

    /** A TM session: bag → TM → USE → "Teach?" YES → the Pokémon → "forget a move?" YES → the move list. */
    private fun teachUi(moves: List<dev.kotlinds.pokemonclient.state.KnownMove>, cutIsHm: Boolean = true, items: List<Entry>? = null): ScriptedUi {
        val user = mon(1, moves = moves)
        val tms = listOf(tm01, hm04)
        val ui = ScriptedUi(OVERWORLD, party = listOf(user, mon(2)), bag = listOf(BagPocket("tms_hms", tms)))
        val bagScreen = bag("tms_hms", items ?: tms.map { Entry("item:${it.item.id.value}", it.item.name) })
        ui.onA = { screen, id ->
            when {
                isStart(screen) && id == "option:bag" -> bagScreen
                screen is Screen.Bag && id == "item:328" -> menu("option:use", "option:give", "option:cancel", item = ItemId(328))
                screen is Screen.ContextMenu && id == "option:use" -> dialogue("Booted up a TM.")
                screen is Screen.Dialogue && screen.text.startsWith("Booted") -> yesNo("Teach Focus Punch to a Pokémon?")
                screen is Screen.YesNo && screen.learning == null && id == "option:yes" -> grid(ui.party, PartyPurpose.TEACH)
                screen is Screen.PartyGrid && id == user.id.toString() -> Screen.YesNo(
                    "Should a move be deleted?", listOf(Entry("option:yes", "YES"), Entry("option:no", "NO")), Cursor.At(0), Topology.vertical(2),
                    learning = MoveOffer(user.id, user.displayName, focusPunch, LearnQuestion.FORGET_A_MOVE),
                )
                screen is Screen.YesNo && screen.learning != null && id == "option:yes" -> Screen.MoveSelect(
                    MoveContext.FORGET_SUMMARY, user.id, focusPunch,
                    moves.map { Entry("move:${it.move.id.value}", it.move.name, selectable = !(cutIsHm && it.move.id.value == 15)) } +
                        Entry("move:264", "Focus Punch (new)"),
                    Cursor.At(0), Topology.vertical(moves.size + 1), CancelBehavior.CLOSES,
                )
                screen is Screen.MoveSelect && id != null -> {
                    val forgotten = id.removePrefix("move:").toInt()
                    ui.party = ui.party.map { if (it.id == user.id) it.copy(moves = it.moves.map { m -> if (m.move.id.value == forgotten) move(264, "Focus Punch") else m }) else it }
                    dialogue("1, 2, and... Poof!")
                }
                screen is Screen.Dialogue && screen.text.startsWith("1, 2") -> dialogue("learned Focus Punch!")
                screen is Screen.Dialogue -> bagScreen
                else -> screen
            }
        }
        return ui
    }

    private val fourMoves = listOf(move(436, "Lava Plume"), move(15, "Cut"), move(53, "Flamethrower"), move(129, "Swift"))

    @Test
    fun teachForgetsTheGivenMoveAndChecksTheNewMoveSet() {
        val ui = teachUi(fourMoves)
        val outcome = PartyBagPlans.teach.run(GameAction.Teach(ItemRef("TM01"), MonId(1, 1), MoveRef("Swift")), ui.context())
        assertIs<ActionOutcome.Done>(outcome, outcome.toString())
        assertEquals(listOf(436, 15, 53, 264), ui.party.first().moves.map { it.move.id.value })
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    @Test
    fun anHmIsNeverForgottenForATm() {
        val ui = teachUi(fourMoves)
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.teach.run(GameAction.Teach(ItemRef("item:328"), MonId(1, 1), MoveRef("Cut")), ui.context()))
        assertEquals(ActionError.HmCannotForget("Cut"), failed.error)
        // Nothing forgotten, and the move list stays open for the agent's next choice (learn_move).
        assertEquals(fourMoves, ui.party.first().moves)
        assertIs<Screen.MoveSelect>(ui.game.screen)
    }

    @Test
    fun withFourMovesTeachWantsToKnowWhatToForget() {
        val ui = teachUi(fourMoves)
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.teach.run(GameAction.Teach(ItemRef("TM01"), MonId(1, 1)), ui.context()))
        val error = assertIs<ActionError.InvalidParameter>(failed.error)
        assertEquals("forget", error.parameter)
        assertEquals(fourMoves.map { it.move.name }, error.allowed)
    }

    @Test
    fun teachRefusesAPokemonOutsideTheParty() {
        val ui = teachUi(fourMoves)
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.teach.run(GameAction.Teach(ItemRef("TM01"), MonId(9, 9), MoveRef("Swift")), ui.context()))
        assertEquals(UnavailableReason.UNKNOWN_POKEMON, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun aMachineOnTheSecondPageIsReachedWithThePageArrow() {
        // Page 1 shows TM01 only; the ▶ arrow (touch only) shows page 2 with HM04.
        val next = TouchPoint(60, 180)
        val page1 = bag("tms_hms", listOf(Entry("item:328", "TM01"), Entry("page:next", "▶", touch = next)), pages = 2)
        val page2 = bag("tms_hms", listOf(Entry("item:423", "HM04"), Entry("page:next", "▶", touch = next)), page = 1, pages = 2)
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1)), bag = listOf(BagPocket("tms_hms", listOf(tm01, hm04))))
        ui.onA = { screen, id -> if (isStart(screen) && id == "option:bag") page1 else screen }
        ui.game.onTouch = { point, screen -> if (point == next && (screen as? Screen.Bag)?.page == 0) page2 else screen }
        val found = assertIs<Step.Done<Entry>>(PartyBagPlans.bagItem(ui.context(), ItemRef("HM04")))
        assertEquals("item:423", found.value.id)
        assertEquals(listOf(next), ui.game.touches)
    }

    // endregion

    // region key items

    private val bicycle = item(450, "Bicycle")
    private val goodRod = item(446, "Good Rod")

    private fun keyItemsUi(): ScriptedUi {
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1)), bag = listOf(BagPocket("items", emptyList()), BagPocket("key_items", listOf(goodRod, bicycle))))
        ui.field = field(5, 5, Direction.NORTH)
        return ui
    }

    @Test
    fun aKeyItemRegisteredOnYIsUsedWithYWithoutTheBag() {
        val ui = keyItemsUi()
        ui.registered = listOf(ItemId(450), null)
        ui.onY = { screen ->
            ui.field = ui.field!!.copy(movement = MovementMode.BIKE)
            Screen.Overworld(null, Awaiting.ANIMATION)
        }
        ui.game.onFrame = { _, screen -> if (screen is Screen.Overworld && screen.awaiting == Awaiting.ANIMATION) OVERWORLD else screen }
        val done = assertIs<ActionOutcome.Done>(PartyBagPlans.useKeyItem.run(GameAction.UseKeyItem(ItemRef("Bicycle")), ui.context()))
        assertTrue("with Y" in done.detail.orEmpty() && "bike" in done.detail.orEmpty(), done.detail)
        assertEquals(listOf(Button.Y), ui.game.presses)
    }

    @Test
    fun anUnregisteredKeyItemIsUsedFromTheBagPocketThatHoldsIt() {
        val ui = keyItemsUi()
        ui.registered = listOf(ItemId(446), null)
        val itemsPocket = bag("items", emptyList(), pockets = listOf("items", "key_items"))
        val keyPocket = bag("key_items", listOf(Entry("item:446", "Good Rod"), Entry("item:450", "Bicycle")), pockets = listOf("items", "key_items"))
        ui.onA = { screen, id ->
            when {
                isStart(screen) && id == "option:bag" -> itemsPocket
                screen is Screen.Bag && id == "pocket:key_items" -> keyPocket
                screen is Screen.Bag && id == "item:450" -> menu("option:use", "option:register", "option:cancel", item = ItemId(450))
                screen is Screen.ContextMenu && id == "option:use" -> {
                    ui.field = ui.field!!.copy(movement = MovementMode.BIKE)
                    OVERWORLD
                }
                else -> screen
            }
        }
        val done = assertIs<ActionOutcome.Done>(PartyBagPlans.useKeyItem.run(GameAction.UseKeyItem(ItemRef("item:450")), ui.context()))
        assertTrue("from the bag" in done.detail.orEmpty(), done.detail)
        assertTrue(Button.Y !in ui.game.presses)
    }

    @Test
    fun theBicycleWhereCyclingIsForbiddenIsRefusedWithoutPressingAnything() {
        val ui = keyItemsUi()
        ui.game.bicycleItem = 450
        ui.field = ui.field!!.copy(bikeAllowed = false)
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.useKeyItem.run(GameAction.UseKeyItem(ItemRef("Bicycle")), ui.context()))
        assertEquals(UnavailableReason.CANNOT_USE_HERE, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(ui.game.presses.isEmpty(), ui.game.presses.toString())
    }

    /** Live (League Center, sf bench run): USE → "Oak's words echoed... There's a time and place for everything!". */
    @Test
    fun theGamesRefusalOfTheBicycleIsATypedErrorAndTheMessageIsClosed() {
        val ui = keyItemsUi()
        ui.game.bicycleItem = 450
        val keyPocket = bag("key_items", listOf(Entry("item:446", "Good Rod"), Entry("item:450", "Bicycle")), pockets = listOf("items", "key_items"))
        ui.onA = { screen, id ->
            when {
                isStart(screen) && id == "option:bag" -> keyPocket
                screen is Screen.Bag && id == "item:450" -> menu("option:use", "option:register", "option:cancel", item = ItemId(450))
                screen is Screen.ContextMenu && id == "option:use" -> dialogue("Oak's words echoed... There's a time and place for everything! But not now.")
                screen is Screen.Dialogue -> keyPocket
                else -> screen
            }
        }
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.useKeyItem.run(GameAction.UseKeyItem(ItemRef("Bicycle")), ui.context()))
        assertEquals(UnavailableReason.CANNOT_USE_HERE, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertIs<Screen.Overworld>(ui.game.screen)
        assertEquals(MovementMode.WALK, ui.field!!.movement)
    }

    @Test
    fun registeringASecondKeyItemSaysYStillUsesTheFirst() {
        val ui = keyItemsUi()
        ui.registered = listOf(ItemId(446), null)
        val keyPocket = bag("key_items", listOf(Entry("item:446", "Good Rod"), Entry("item:450", "Bicycle")))
        ui.onA = { screen, id ->
            when {
                isStart(screen) && id == "option:bag" -> keyPocket
                screen is Screen.Bag && id == "item:450" -> menu("option:use", "option:register", "option:cancel", item = ItemId(450))
                screen is Screen.ContextMenu && id == "option:register" -> {
                    ui.registered = listOf(ItemId(446), ItemId(450))
                    keyPocket
                }
                else -> screen
            }
        }
        val done = assertIs<ActionOutcome.Done>(PartyBagPlans.registerItem.run(GameAction.RegisterItem(ItemRef("Bicycle")), ui.context()))
        assertEquals("registered on the second touch button (Y keeps Good Rod)", done.detail)
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    // endregion

    // region fly

    private val violet = Entry("fly:73", "Violet City", touch = TouchPoint(100, 80))

    /** Party (MON1 can't fly, MON2 can) → FLY → the map with [map] → YES → the flight, landing on map 73. */
    private fun flyUi(map: Screen.FlyMap): ScriptedUi {
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1), mon(2)))
        ui.field = field(5, 5, Direction.SOUTH, mapId = 89)
        var landing: Long? = null
        ui.onA = { screen, id ->
            when {
                isStart(screen) && id == "option:pokemon" -> grid(ui.party)
                screen is Screen.PartyGrid && id == "mon:00000001.00000001" -> menu("option:summary", "option:switch", "option:item", "option:quit")
                screen is Screen.PartyGrid && id == "mon:00000002.00000001" -> menu("option:summary", "fieldmove:fly", "option:switch", "option:quit")
                screen is Screen.ContextMenu && id == "fieldmove:fly" -> map
                screen is Screen.YesNo && id == "option:yes" -> {
                    landing = ui.frame + 60
                    Screen.Animation(dev.kotlinds.pokemonclient.state.AnimationKind.TRANSITION)
                }
                else -> screen
            }
        }
        ui.onB = { screen -> if (screen is Screen.ContextMenu) grid(ui.party) else OVERWORLD }
        ui.game.onTouch = { point, screen -> if (screen is Screen.FlyMap && point == violet.touch) yesNo("Fly to Violet City?") else screen }
        ui.game.onFrame = { frame, screen ->
            if (screen is Screen.Animation && landing != null && frame >= landing!!) {
                ui.field = field(10, 10, Direction.SOUTH, mapId = 73)
                OVERWORLD
            } else screen
        }
        return ui
    }

    private fun flyMap(vararg entries: Entry, cursorCell: Screen.MapCell? = null, cells: Map<String, Screen.MapCell> = emptyMap()) =
        Screen.FlyMap(entries.toList() + Entry("option:cancel", "CANCEL"), Cursor.At(0), Topology.vertical(entries.size + 1), cursorCell = cursorCell, cells = cells)

    @Test
    fun flyFindsTheFlyerTouchesTheTownAndWaitsForTheLanding() {
        val ui = flyUi(flyMap(violet))
        val done = assertIs<ActionOutcome.Done>(FieldPlans.fly.run(GameAction.Fly("Violet City"), ui.context()))
        assertEquals("landed in map 73", done.detail)
        assertEquals(listOf(violet.touch), ui.game.touches)
    }

    @Test
    fun aTownNotVisitedYetIsRefusedAndTheMenusClosed() {
        val ui = flyUi(flyMap(violet, Entry("fly:89", "Blackthorn City", selectable = false)))
        val failed = assertIs<ActionOutcome.Failed>(FieldPlans.fly.run(GameAction.Fly("fly:89"), ui.context()))
        assertEquals(UnavailableReason.NOT_VISITED, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(ui.game.touches.isEmpty())
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    @Test
    fun anUnknownDestinationListsTheVisitedTowns() {
        val ui = flyUi(flyMap(violet, Entry("fly:89", "Blackthorn City", selectable = false)))
        val failed = assertIs<ActionOutcome.Failed>(FieldPlans.fly.run(GameAction.Fly("Pallet Town"), ui.context()))
        assertEquals(listOf("fly:73 (Violet City)"), assertIs<ActionError.InvalidParameter>(failed.error).allowed)
    }

    @Test
    fun aTownOffScreenIsBroughtIntoViewOneDpadPressAtATime() {
        // Violet is 2 cells west and 1 north of the cursor: it can only be touched once the map scrolled there.
        val target = Screen.MapCell(3, 4)
        val hidden = violet.copy(touch = null)
        var cell = Screen.MapCell(5, 5)
        fun map() = flyMap(if (cell == target) violet else hidden, cursorCell = cell, cells = mapOf("fly:73" to target))
        val ui = flyUi(map())
        ui.onDpad = { button, screen ->
            if (screen !is Screen.FlyMap) null else {
                cell = when (button) {
                    Button.LEFT -> cell.copy(x = cell.x - 1)
                    Button.RIGHT -> cell.copy(x = cell.x + 1)
                    Button.UP -> cell.copy(y = cell.y - 1)
                    Button.DOWN -> cell.copy(y = cell.y + 1)
                    else -> cell
                }
                map()
            }
        }
        val start = ui.onA
        ui.onA = { screen, id -> if (screen is Screen.ContextMenu && id == "fieldmove:fly") map() else start(screen, id) }
        assertIs<ActionOutcome.Done>(FieldPlans.fly.run(GameAction.Fly("Violet City"), ui.context()))
        assertEquals(listOf(Button.LEFT, Button.LEFT, Button.UP), ui.game.presses.filter { it in setOf(Button.LEFT, Button.RIGHT, Button.UP, Button.DOWN) }.takeLast(3))
        assertEquals(listOf(violet.touch), ui.game.touches)
    }

    // endregion

    // region fish

    /**
     * A rod registered on Y; the line is cast, something bites [biteAt] frames after the cast for 20 frames (A then
     * hooks it), else [after] shows (no bite at all, or it got away).
     */
    private fun fishingUi(biteAt: Int?, after: String): Pair<ScriptedUi, () -> List<Long>> {
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1)), bag = listOf(BagPocket("key_items", listOf(goodRod))))
        ui.field = field(5, 5, Direction.NORTH)
        ui.registered = listOf(ItemId(446), null)
        val aFrames = mutableListOf<Long>()
        var cast = -1L
        val casting = Screen.Overworld(null, Awaiting.ANIMATION)
        val bite = Screen.PressToContinue(ContinueReason.FISHING_BITE)
        ui.onY = { cast = ui.frame; casting }
        ui.onA = { screen, _ ->
            aFrames += ui.frame
            when {
                screen == bite -> {
                    ui.battle = BattleState(BattleKind.WILD, false, null, emptyList(), emptyList(), emptyList(), null)
                    Screen.Battle(Awaiting.ANIMATION)
                }
                screen is Screen.Dialogue -> OVERWORLD
                else -> screen
            }
        }
        ui.game.onFrame = { frame, screen ->
            val since = frame - cast
            when {
                cast < 0 || screen !is Screen.Overworld && screen != bite -> screen
                biteAt != null && since in biteAt until biteAt + 20 -> bite
                since == (biteAt ?: 120).toLong() + (if (biteAt != null) 20 else 0) -> dialogue(after, dev.kotlinds.pokemonclient.state.TextSource.FIELD)
                else -> screen
            }
        }
        return ui to { aFrames.toList() }
    }

    @Test
    fun aIsPressedOnTheBiteAndTheWildBattleIsTheOutcome() {
        val (ui, aFrames) = fishingUi(biteAt = 150, after = "It got away...")
        val done = assertIs<ActionOutcome.Done>(FieldPlans.fish.run(GameAction.Fish(ItemRef("Good Rod")), ui.context()))
        assertEquals("hooked a wild Pokémon", done.detail)
        // One A only, during the bite (never before: it would reel in for nothing).
        assertEquals(1, aFrames().size)
        assertEquals(listOf(Button.Y, Button.A), ui.game.presses)
    }

    @Test
    fun noBiteReadsTheMessageAndSaysNothingBit() {
        val (ui, aFrames) = fishingUi(biteAt = null, after = "Not even a nibble...")
        val done = assertIs<ActionOutcome.Done>(FieldPlans.fish.run(GameAction.Fish(ItemRef("Good Rod")), ui.context()))
        assertEquals("nothing bit", done.detail)
        assertEquals(1, aFrames().size)
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    // endregion

    // region give / take (Mail)

    @Test
    fun giveItemAnswersYesToSwapTheHeldItemAndChecksIt() {
        val potion = Named(ItemId(17), "Potion")
        val seed = item(239, "Miracle Seed")
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1, item = potion)), bag = listOf(BagPocket("items", listOf(seed))))
        ui.onA = { screen, id ->
            when {
                isStart(screen) && id == "option:bag" -> bag("items", listOf(Entry("item:239", "Miracle Seed")))
                screen is Screen.Bag && id == "item:239" -> menu("option:use", "option:give", "option:cancel", item = ItemId(239))
                screen is Screen.ContextMenu && id == "option:give" -> grid(ui.party, PartyPurpose.GIVE_ITEM)
                screen is Screen.PartyGrid && id == "mon:00000001.00000001" -> yesNo("Switch the items?")
                screen is Screen.YesNo && id == "option:yes" -> {
                    ui.party = listOf(mon(1, item = seed.item))
                    dialogue("The Potion was taken and replaced with the Miracle Seed.")
                }
                screen is Screen.Dialogue -> bag("items", listOf(Entry("item:17", "Potion")))
                else -> screen
            }
        }
        assertIs<ActionOutcome.Done>(PartyBagPlans.giveItem.run(GameAction.GiveItem(MonId(1, 1), ItemRef("Miracle Seed")), ui.context()))
        assertEquals(239, ui.party.single().heldItem?.id?.value)
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    @Test
    fun mailIsNotGivenBecauseTheGameWantsAMessageWritten() {
        // Live: GIVE opens the mail editor, where an empty Mail is refused ("Please enter a phrase or word").
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1)), bag = listOf(BagPocket("mail", listOf(item(146, "Air Mail", 2)))))
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.giveItem.run(GameAction.GiveItem(MonId(1, 1), ItemRef("Air Mail")), ui.context()))
        assertEquals(UnavailableReason.MAIL_NEEDS_WRITING, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun takingMailSendsItToThePcSoTheMessageIsKept() {
        val airMail = Named(ItemId(146), "Air Mail")
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1, item = airMail)))
        var sentToPc = false
        ui.onA = { screen, id ->
            when {
                isStart(screen) && id == "option:pokemon" -> grid(ui.party)
                screen is Screen.PartyGrid -> menu("option:summary", "option:switch", "option:mail", "option:quit", owner = MonId(1, 1))
                screen is Screen.ContextMenu && id == "option:mail" -> menu("option:read", "option:take", "option:quit", owner = MonId(1, 1))
                screen is Screen.ContextMenu && id == "option:take" -> yesNo("Send the removed Mail to your PC?")
                screen is Screen.YesNo && id == "option:yes" -> {
                    sentToPc = true
                    ui.party = listOf(mon(1))
                    dialogue("The Mail was sent to your PC.")
                }
                screen is Screen.Dialogue -> grid(ui.party)
                else -> screen
            }
        }
        // B on the question would keep the Mail held (it is a NO).
        ui.onB = { screen -> if (screen is Screen.YesNo) grid(ui.party) else OVERWORLD }
        assertIs<ActionOutcome.Done>(PartyBagPlans.takeItem.run(GameAction.TakeItem(MonId(1, 1)), ui.context()))
        assertTrue(sentToPc)
        assertEquals(null, ui.party.single().heldItem)
    }

    // endregion
}
