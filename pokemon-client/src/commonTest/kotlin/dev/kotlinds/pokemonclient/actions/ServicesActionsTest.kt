package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BoxMon
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PcBoxContents
import dev.kotlinds.pokemonclient.state.PcStorage
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.PlayerInfo
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ShopItem
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.Topology
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Availability and parsing of the service actions (shop, PC, options, fly) on hand-made states. */
class ServicesActionsTest {

    private val registry = ActionRegistry.of()

    private val fly = KnownMove(Named(MoveId(19), "Fly"), 15, 15, "Flying")
    private val tackle = KnownMove(Named(MoveId(33), "Tackle"), 35, 35, "Normal")

    private fun mon(pid: Long, moves: List<KnownMove> = listOf(tackle)) = PartyMon(
        MonId(pid, 1), 0, Named(SpeciesId(18), "PIDGEOT"), null, 40, 100, 100, null, listOf("Normal", "Flying"), null, null,
        moves, emptyMap(), 0, null, false,
    )

    private fun field(
        flyAllowed: Boolean? = true,
        objects: List<FieldObject> = emptyList(),
        facing: Direction = Direction.NORTH,
    ) = FieldState(1, "Town", 10, 10, 0, facing, MovementMode.WALK, false, objects, flyAllowed = flyAllowed)

    private fun state(
        screen: Screen = Screen.Overworld(awaiting = Awaiting.INPUT),
        party: List<PartyMon> = listOf(mon(1, listOf(tackle, fly)), mon(2)),
        field: FieldState? = field(),
        badges: List<String> = listOf("Storm"),
        storage: PcStorage? = null,
        badgeIds: Set<Int> = if ("Storm" in badges) setOf(STORM) else emptySet(),
    ) = GameState(0, screen, PlayerInfo("ACE", 5000, badges, 1, badgeIds = badgeIds), party, emptyList(), null, field, storage = storage)

    private fun available(state: GameState) = registry.available(state, ActionMode.ASSISTED).associateBy { it.name }
    private fun unavailable(state: GameState) = registry.unavailable(state, ActionMode.ASSISTED).associateBy { it.name }

    @Test
    fun flyIsRefusedIndoorsWithoutBadgeOrWithoutAFlyer() {
        assertTrue("fly" in available(state()))
        assertEquals(UnavailableReason.NOT_FLYABLE_HERE, unavailable(state(field = field(flyAllowed = false)))["fly"]?.reason)
        assertEquals(UnavailableReason.NEEDS_BADGE, unavailable(state(badges = emptyList()))["fly"]?.reason)
        assertEquals(UnavailableReason.NO_POKEMON_KNOWS_MOVE, unavailable(state(party = listOf(mon(2))))["fly"]?.reason)
    }

    @Test
    fun theFlyBadgeIsCheckedByIdNotByItsName() {
        // A French game names it "Tempête": the badge id is what counts.
        assertTrue("fly" in available(state(badges = listOf("Tempête"), badgeIds = setOf(STORM))))
        // An English name without the id isn't enough.
        assertEquals(UnavailableReason.NEEDS_BADGE, unavailable(state(badges = listOf("Storm"), badgeIds = setOf(0, 1, 2, 3)))["fly"]?.reason)
    }

    @Test
    fun pcMenusDontOfferPartyAndBagActions() {
        val pcMenu = Screen.ContextMenu(
            MonId(9, 9),
            listOf("deposit", "summary", "marking", "release").map { Entry("option:$it", it.uppercase()) } + Entry("option:cancel", "EXIT"),
            Cursor.At(0), Topology.vertical(5),
        )
        val names = available(state(screen = pcMenu)).keys
        for (name in listOf("use_item", "teach", "save_game", "reorder_party", "give_item", "set_options")) assertTrue(name !in names, name)
        val partyMenu = Screen.ContextMenu(MonId(1, 1), listOf(Entry("option:summary", "SUMMARY"), Entry("option:switch", "SWITCH"), Entry("option:item", "ITEM"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(4))
        assertTrue("save_game" in available(state(screen = partyMenu)))
    }

    @Test
    fun buyListsTheCatalogInTheMartAndWorksOnTheShopScreen() {
        val clerk = FieldObject(
            "person:4", "shop clerk", FieldObjectKind.PERSON, 10, 8, Direction.SOUTH, PersonRole.CLERK,
            catalog = listOf(ShopItem(Named(ItemId(23), "Full Restore"), 3000), ShopItem(Named(ItemId(28), "Revive"), 1500)),
        )
        val inMart = available(state(field = field(objects = listOf(clerk))))
        assertEquals(listOf("item:23", "item:28"), inMart.getValue("buy").choices.getValue("item").map { it.value })
        val shop = Screen.Shop(5000, listOf(Entry("item:2", "Ultra Ball ₽1200"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
        assertEquals(listOf("item:2"), available(state(screen = shop)).getValue("buy").choices.getValue("item").map { it.value })
        // The clerk's BUY / SELL / SEE YA! menu, facing the clerk across the counter.
        val clerkMenu = Screen.ListMenu(dev.kotlinds.pokemonclient.state.MenuKind.MULTICHOICE, (0..2).map { Entry("option:$it", "") }, Cursor.At(0), Topology.vertical(3))
        assertTrue("buy" in available(state(screen = clerkMenu, field = field(objects = listOf(clerk)))))
        assertTrue("buy" !in available(state(screen = clerkMenu, field = field(objects = listOf(clerk), facing = Direction.SOUTH))))
    }

    @Test
    fun buyParsesOneLineOrAList() {
        val one = parse("""{"type":"buy","item":"Revive","quantity":3}""")
        assertEquals(GameAction.Buy(ItemRef("Revive"), 3), one)
        val many = parse("""{"type":"buy","items":[{"item":"item:23","quantity":15},{"item":"Revive"}]}""")
        assertEquals(GameAction.Buy(listOf(Purchase(ItemRef("item:23"), 15), Purchase(ItemRef("Revive"), 1))), many)
        assertEquals(GameAction.Buy(emptyList()), parse("""{"type":"buy"}"""))
    }

    @Test
    fun pcParsesOperationsWithBoxesFromOne() {
        val a = "mon:00000001.00000001"
        val b = "mon:00000005.00000001"
        val action = parse("""{"type":"pc","operations":[{"op":"deposit","pokemon":"$a","box":2},{"op":"withdraw","pokemon":"$b"},{"op":"move","pokemon":"$b","box":18},{"op":"swap","pokemon":"$a","with":"$b"}]}""")
        val ids = listOf(MonId.parse(a)!!, MonId.parse(b)!!)
        assertEquals(
            GameAction.Pc(listOf(PcOperation.Deposit(ids[0], 1), PcOperation.Withdraw(ids[1]), PcOperation.Move(ids[1], 17), PcOperation.Swap(ids[0], ids[1]))),
            action,
        )
    }

    @Test
    fun withdrawListsTheStoredPokemon() {
        val stored = BoxMon(MonId(5, 1), 0, 2, Named(SpeciesId(250), "HO-OH"), null, 45, null, false)
        val storage = PcStorage(0, listOf(PcBoxContents(0, "BOX 1", listOf(stored), 30)))
        val choices = available(state(storage = storage)).getValue("withdraw").choices.getValue("pokemon")
        assertEquals(listOf("mon:00000005.00000001"), choices.map { it.value })
        assertTrue("BOX 1" in choices.single().label)
    }

    @Test
    fun setOptionsRefusesUnknownValues() {
        assertEquals(GameAction.SetOptions(battleScene = false), parse("""{"type":"set_options","battle_scene":"off"}"""))
        val error = registry.parse(Json.parseToJsonElement("""{"type":"set_options","text_speed":"turbo"}""").jsonObject, ActionMode.ASSISTED).exceptionOrNull()
        assertIs<ActionException>(error)
        assertEquals("INVALID_PARAM", error.error.code)
    }

    private fun parse(json: String) = registry.parse(Json.parseToJsonElement(json).jsonObject, ActionMode.ASSISTED).getOrThrow()

    private companion object {
        /** BADGE_STORM (include/constants/badge.h). */
        const val STORM = 4
    }
}
