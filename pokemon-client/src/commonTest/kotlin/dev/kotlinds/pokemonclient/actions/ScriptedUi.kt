package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.PcStorage
import dev.kotlinds.pokemonclient.state.PlayerInfo
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldSource

/**
 * A scripted game for menu recipes: the cursor of any [Screen.Selectable] follows the D-pad along its topology, X
 * opens [START] from the field, and the test writes what A, B and Y do on (screen, highlighted entry id). The state
 * gives the mutable [party], [bag], [battle], [field], [registered] items, [storage] and [money].
 */
internal class ScriptedUi(
    start: Screen,
    var party: List<PartyMon> = emptyList(),
    var bag: List<BagPocket> = emptyList(),
    world: WorldSource? = null,
) {
    var battle: BattleState? = null
    var field: FieldState? = null
    var registered: List<ItemId?> = emptyList()
    var storage: PcStorage? = null
    var money: Long = 3000

    var onA: (Screen, String?) -> Screen = { s, _ -> s }
    var onB: (Screen) -> Screen = { s -> if (s is Screen.Overworld) s else OVERWORLD }
    var onY: (Screen) -> Screen = { s -> s }

    /** D-pad presses on screens that aren't plain lists (fly map, quantity); null = move the cursor along the topology. */
    var onDpad: (Button, Screen) -> Screen? = { _, _ -> null }

    val game = FakeGame(start, state = { screen ->
        GameState(
            0, screen, PlayerInfo("ACE", money, emptyList(), 1), party, bag, battle, field,
            registeredItems = registered, storage = storage,
        )
    })

    init {
        game.world = world
        game.onPress = { button, screen -> press(button, screen) }
    }

    fun context() = game.context()

    /** The frame the console is at (for scripted timings). */
    val frame get() = game.console.frame

    private fun press(button: Button, screen: Screen): Screen {
        val selectable = screen as? Screen.Selectable
        val at = (selectable?.cursor as? Cursor.At)?.index
        return when (button) {
            Button.A -> onA(screen, at?.let { selectable.entries[it].id })
            Button.B -> onB(screen)
            Button.Y -> onY(screen)
            Button.X -> if (screen is Screen.Overworld && screen.awaiting == Awaiting.INPUT) START else screen
            else -> {
                onDpad(button, screen)?.let { return it }
                if (selectable == null || at == null) return screen
                val next = selectable.topology.next(at, button) ?: return screen
                withCursor(selectable, next)
            }
        }
    }

    companion object {
        val OVERWORLD = Screen.Overworld(null, Awaiting.INPUT)

        val START = Screen.ListMenu(
            MenuKind.START_MENU,
            listOf("pokedex", "pokemon", "bag", "save").map { Entry("option:$it", it.uppercase()) },
            Cursor.At(0), Topology.vertical(4),
        )

        /** True on the start menu, wherever its cursor is. */
        fun isStart(screen: Screen) = (screen as? Screen.ListMenu)?.kind == MenuKind.START_MENU

        fun withCursor(screen: Screen.Selectable, index: Int): Screen = when (screen) {
            is Screen.ListMenu -> screen.copy(cursor = Cursor.At(index))
            is Screen.PartyGrid -> screen.copy(cursor = Cursor.At(index))
            is Screen.ContextMenu -> screen.copy(cursor = Cursor.At(index))
            is Screen.YesNo -> screen.copy(cursor = Cursor.At(index))
            is Screen.Bag -> screen.copy(cursor = Cursor.At(index))
            is Screen.MoveSelect -> screen.copy(cursor = Cursor.At(index))
            is Screen.BattleCommand -> screen.copy(cursor = Cursor.At(index))
            is Screen.Keyboard -> screen.copy(cursor = Cursor.At(index))
            is Screen.PcBox -> screen.copy(cursor = Cursor.At(index))
            is Screen.Shop -> screen.copy(cursor = Cursor.At(index))
            is Screen.FlyMap -> screen.copy(cursor = Cursor.At(index))
            is Screen.TargetSelect -> screen.copy(cursor = Cursor.At(index))
        }

        fun mon(n: Int, moves: List<KnownMove> = emptyList(), item: Named<ItemId>? = null, hp: Int = 20) = PartyMon(
            id = MonId(n.toLong(), 1), slot = n - 1, species = Named(SpeciesId(n), "MON$n"), nickname = null, level = 30,
            hp = hp, maxHp = 20, status = null, types = emptyList(), heldItem = item, ability = null, moves = moves,
            stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
        )

        fun move(id: Int, name: String) = KnownMove(Named(MoveId(id), name), 10, 10, "Normal")

        fun item(id: Int, name: String, quantity: Int = 1) = BagItem(Named(ItemId(id), name), quantity)

        fun grid(party: List<PartyMon>, purpose: PartyPurpose = PartyPurpose.FIELD) = Screen.PartyGrid(
            purpose, party.map { Entry(it.id.toString(), it.displayName) } + Entry("option:cancel", "CANCEL"),
            Cursor.At(0), Topology.vertical(party.size + 1), CancelBehavior.CLOSES,
        )

        fun menu(vararg ids: String, owner: MonId? = null, item: ItemId? = null) =
            Screen.ContextMenu(owner, ids.map { Entry(it, it) }, Cursor.At(0), Topology.vertical(ids.size), item = item)

        fun yesNo(question: String) = Screen.YesNo(question, listOf(Entry("option:yes", "YES"), Entry("option:no", "NO")), Cursor.At(0), Topology.vertical(2))

        fun dialogue(text: String, source: TextSource = TextSource.MENU) = Screen.Dialogue(source, null, text, Awaiting.INPUT)

        /** The bag open on [pocket], with tabs for [pockets] first, then [items] and CANCEL. */
        fun bag(pocket: String, items: List<Entry>, pockets: List<String> = listOf(pocket), page: Int = 0, pages: Int = 1): Screen.Bag {
            val entries = pockets.map { Entry("pocket:$it", it.uppercase()) } + items + Entry("option:cancel", "CANCEL")
            return Screen.Bag(pocket, pockets, page, pages, false, entries, Cursor.At(pockets.size), Topology.vertical(entries.size))
        }

        /** A field of floor tiles [width] × [height] with [special] tiles, as the only map of the world. */
        fun world(width: Int, height: Int, special: Map<Pair<Int, Int>, TileInfo> = emptyMap()): WorldSource {
            val area = Area(0, "test", 0, 0, width, height, Array(width * height) { i ->
                special[(i % width) to (i / width)] ?: TileInfo(false, TileKind.Floor)
            })
            return object : WorldSource {
                override fun areaOf(zoneId: Int) = area
            }
        }

        fun field(x: Int, y: Int, facing: Direction, objects: List<FieldObject> = emptyList(), mapId: Int = 1, movement: MovementMode = MovementMode.WALK) =
            FieldState(mapId, "map $mapId", x, y, 0, facing, movement, moving = false, objects = objects)
    }
}
