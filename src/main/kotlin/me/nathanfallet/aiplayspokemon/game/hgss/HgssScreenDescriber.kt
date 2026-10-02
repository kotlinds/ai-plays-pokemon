package me.nathanfallet.aiplayspokemon.game.hgss

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Describes an [HgssState] the way a player perceives the screen, for the AI (the "pure" mode description).
 *
 * Coordinates are the game's tile coordinates everywhere: x grows east, y grows south (the reader calls it z).
 * Overworld: the map is cropped to the real room/area (nothing beyond the walls of a room), with column (x) and row
 * (y) labels, one space-separated symbol per tile, the player drawn as an arrow showing where they face, a legend
 * of only the symbols present, what is right next to the player, and the exits / people / objects with how to use
 * them. Then the message box, the menu (options + highlighted one), the full-screen app or the battle, and the team.
 *
 * This is perception only: it never says where to go (the story goal is added by the agent when enabled).
 */
object HgssScreenDescriber {

    /** Size of the map shown to the AI (around the player, inside the real area). */
    private const val VIEW_HALF_WIDTH = 8
    private const val VIEW_HALF_HEIGHT = 6
    private const val MAX_WHOLE_WIDTH = 24
    private const val MAX_WHOLE_HEIGHT = 18

    private val terrainNames = mapOf(
        '.' to "floor/ground (walkable)", '"' to "tall grass (walkable, wild Pokémon)", '~' to "water",
        '#' to "wall/obstacle", '_' to "ledge (jump down it going south only)", '=' to "ledge (jump it going north only)",
        '{' to "ledge (jump it going west only)", '}' to "ledge (jump it going east only)", '-' to "nothing (outside the map)",
    )
    private val arrows = mapOf("north" to '^', "south" to 'v', "west" to '<', "east" to '>')

    fun describe(state: HgssState): JsonObject = buildJsonObject {
        put("screen", screen(state))
        val inField = state.mode in setOf(GameMode.OVERWORLD, GameMode.FIELD_BUSY, GameMode.SCRIPT, GameMode.DIALOGUE, GameMode.START_MENU)
        state.location?.takeIf { inField }?.let { location ->
            put("location", location.locationName?.takeIf { it != location.mapName }?.let { "$it · ${location.mapName}" } ?: location.mapName)
        }
        if (inField && state.location != null && state.surroundings != null) {
            overworld(state.location, state.surroundings)
        }
        state.dialogue?.takeIf { it.messageBoxOpen }?.let { d ->
            put("message", d.visibleText ?: d.text ?: "")
            put("message_status", messageStatus(d))
        }
        HgssMenus.current(state)?.let { menu ->
            put("menu", buildJsonObject {
                put("kind", menu.kind)
                put("options", JsonArray(menu.options.map(::JsonPrimitive)))
                put("highlighted", menu.highlighted ?: "none")
                put("controls", menu.controls)
                menu.note?.let { put("note", it) }
            })
        }
        state.battle?.let { put("battle", battle(it)) }

        if (state.party.isNotEmpty()) put("team", JsonArray(state.party.map { JsonPrimitive(teamMember(it)) }))
        state.bag?.filter { it.items.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { pockets ->
            put("bag", buildJsonObject {
                pockets.forEach { pocket ->
                    put(pocket.pocket.replace('_', ' '), pocket.items.joinToString(", ") { "${it.name} x${it.quantity}" })
                }
            })
        }
        state.player?.let { put("trainer", "₽${it.money} · ${it.badgeCount} badge${if (it.badgeCount == 1) "" else "s"}") }
    }

    private fun screen(state: HgssState): String = when (state.mode) {
        GameMode.LOADING -> "Loading (wait)."
        GameMode.INTRO_MOVIE -> "Intro movie (press START or A)."
        GameMode.TITLE_SCREEN -> "Title screen (press START or A)."
        GameMode.MAIN_MENU -> "Main menu (continue / new game)."
        GameMode.NEW_GAME_INTRO -> "New game introduction with the professor."
        GameMode.OVERWORLD -> "Overworld: you can walk around (no message box open)."
        GameMode.FIELD_BUSY -> "Overworld: a transition or animation is playing (wait)."
        GameMode.SCRIPT -> "Overworld: a scene is playing (wait)."
        GameMode.DIALOGUE -> "Overworld with a message box open."
        GameMode.START_MENU -> "The start menu is open over the map."
        GameMode.APP -> state.app?.takeIf { it.entries.isNotEmpty() }?.let { "Full-screen screen: ${it.title}." }
            ?: "Full-screen menu open: ${appName(state.modeDetail)}. Press B to go back."
        GameMode.BATTLE -> if (state.battle == null) "A battle is starting or ending (wait)." else "Battle."
        GameMode.UNKNOWN -> "Unknown screen."
    }

    private fun appName(detail: String?): String = when (detail) {
        null -> "unknown"
        "party_menu" -> "Pokémon party"
        "pokemon_summary" -> "Pokémon summary"
        "bag" -> "Bag"
        "pokegear" -> "Pokégear"
        "pokedex" -> "Pokédex"
        "trainer_card" -> "Trainer Card"
        "options" -> "Options"
        "mailbox" -> "PC Mailbox (list of stored mail)"
        "pc_box" -> "PC Pokémon storage"
        "naming_screen" -> "naming screen (type a name, then OK)"
        "choose_starter" -> "starter selection"
        "town_map" -> "town map"
        else -> if (detail.startsWith("app(")) "unknown" else detail.replace('_', ' ')
    }

    private fun messageStatus(d: DialogueInfo): String = when (d.waitingFor) {
        "printing" -> "still printing (wait)"
        "waiting_button" -> "fully shown, press A to continue"
        "yes_no" -> "question: answer with the YES/NO menu"
        "multichoice" -> "choose an option in the menu"
        "waiting_movement" -> "someone is moving (wait)"
        "waiting_app" -> "a screen is opening (wait)"
        else -> "a scene is playing (wait)"
    }

    private fun JsonObjectBuilder.overworld(location: LocationInfo, s: Surroundings) {
        val grid = s.grid ?: return
        val px = location.x
        val py = location.z
        val arrow = arrows[location.facing] ?: '@'
        val objects = HgssMapView.visibleObjects(s)
        val examinables = HgssMapView.examinables(s)

        fun symbol(x: Int, y: Int): Char {
            if (x == px && y == py) return arrow
            objects.firstOrNull { it.x == x && it.z == y }?.let {
                return when (it.kind) {
                    "follower" -> 'f'
                    "item_ball" -> 'o'
                    "obstacle" -> '*'
                    else -> 'P'
                }
            }
            if (s.warps.any { it.x == x && it.z == y }) return 'E'
            if (examinables.any { it.x == x && it.z == y }) return '*'
            return grid.at(x, y)
        }

        // The whole room when it is small, else a window around the player; then trimmed of empty borders.
        val whole = grid.width <= MAX_WHOLE_WIDTH && grid.height <= MAX_WHOLE_HEIGHT
        var x0 = if (whole) grid.originX else maxOf(grid.originX, px - VIEW_HALF_WIDTH)
        var x1 = if (whole) grid.originX + grid.width - 1 else minOf(grid.originX + grid.width - 1, px + VIEW_HALF_WIDTH)
        var y0 = if (whole) grid.originZ else maxOf(grid.originZ, py - VIEW_HALF_HEIGHT)
        var y1 = if (whole) grid.originZ + grid.height - 1 else minOf(grid.originZ + grid.height - 1, py + VIEW_HALF_HEIGHT)
        while (y0 < py && (x0..x1).all { symbol(it, y0) == '-' }) y0++
        while (y1 > py && (x0..x1).all { symbol(it, y1) == '-' }) y1--
        while (x0 < px && (y0..y1).all { symbol(x0, it) == '-' }) x0++
        while (x1 > px && (y0..y1).all { symbol(x1, it) == '-' }) x1--

        put("you", "x $px, y $py, facing ${location.facing} (the $arrow on the map)")
        put("adjacent", listOf("north" to (0 to -1), "south" to (0 to 1), "west" to (-1 to 0), "east" to (1 to 0)).joinToString(" · ") { (dir, d) ->
            "$dir: " + describeTile(px + d.first, py + d.second, s, grid, objects, examinables)
        })

        val cell = maxOf(x1.toString().length, x0.toString().length, 1)
        val label = maxOf(y1.toString().length, y0.toString().length, 3)
        val rows = mutableListOf<String>()
        rows += "y\\x".padStart(label) + " " + (x0..x1).joinToString(" ") { it.toString().padStart(cell) }
        val used = linkedSetOf(arrow)
        for (y in y0..y1) {
            rows += y.toString().padStart(label) + " " + (x0..x1).joinToString(" ") { x ->
                symbol(x, y).also { used += it }.toString().padStart(cell)
            }
        }
        put("map", JsonArray(rows.map(::JsonPrimitive)))
        put("legend", used.joinToString(" · ") { c ->
            "$c " + when (c) {
                arrow -> "you (facing ${location.facing})"
                'P' -> "person"
                'o' -> "item ball"
                'f' -> "your Pokémon following you"
                'E' -> "exit (see exits)"
                '*' -> "something to examine (see objects)"
                else -> terrainNames[c] ?: "?"
            }
        })

        if (s.warps.isNotEmpty()) put("exits", JsonArray(s.warps.sortedBy { distance(it.dx, it.dz) }.map { w ->
            JsonPrimitive("${w.kind} at (${w.x},${w.z}) ${relative(w.dx, w.dz)} → ${w.destMapName}: ${exitHowTo(w)}")
        }))
        // People and things: only what is inside the map shown (like the screen); exits are all listed.
        fun shown(x: Int, y: Int) = x in x0..x1 && y in y0..y1
        val people = objects.filter { it.kind == "npc" && shown(it.x, it.z) }
        if (people.isNotEmpty()) put("people", JsonArray(people.map { o ->
            JsonPrimitive("${o.label} at (${o.x},${o.z}) ${relative(o.dx, o.dz)}")
        }))
        val items = objects.filter { it.kind == "item_ball" && shown(it.x, it.z) }
        if (items.isNotEmpty()) put("items", JsonArray(items.map { o ->
            JsonPrimitive("item ball at (${o.x},${o.z}) ${relative(o.dx, o.dz)}")
        }))
        val things = groupExaminables(examinables.filter { shown(it.x, it.z) }) + objects.filter { it.kind == "obstacle" && shown(it.x, it.z) }.map { "${it.label} at (${it.x},${it.z}) ${relative(it.dx, it.dz)}" }
        if (things.isNotEmpty()) put("objects", JsonArray(things.map(::JsonPrimitive)))
        if (s.neighbors.isNotEmpty()) put("nearby_areas", JsonArray(s.neighbors.map { n ->
            val bound = when (n.direction) {
                "west" -> "x ≤ ${n.boundary}"
                "east" -> "x ≥ ${n.boundary}"
                "north" -> "y ≤ ${n.boundary}"
                else -> "y ≥ ${n.boundary}"
            }
            JsonPrimitive("${n.name} to the ${n.direction} ($bound)")
        }))
    }

    private fun exitHowTo(w: WarpInfo): String = when {
        w.kind == "door" -> "walk into it"
        w.pressDirection != null && w.kind == "stairs" -> "walk onto it going ${w.pressDirection} (press ${w.pressDirection} again if you stop on it)"
        w.pressDirection != null -> "stand on it and press ${w.pressDirection}"
        else -> "walk onto it"
    }

    private fun describeTile(
        x: Int, y: Int, s: Surroundings, grid: LocalGrid, objects: List<MapObjectInfo>, examinables: List<BgEventInfo>,
    ): String {
        objects.firstOrNull { it.x == x && it.z == y }?.let {
            return when (it.kind) {
                "follower" -> "your Pokémon"
                "item_ball" -> "item ball (A to pick up)"
                "obstacle" -> "${it.label} (blocks the way, A to examine)"
                else -> "${it.label} (A to talk)"
            }
        }
        s.warps.firstOrNull { it.x == x && it.z == y }?.let { return "${it.kind} to ${it.destMapName}" }
        examinables.firstOrNull { it.x == x && it.z == y }?.let { return "${it.label} (A to examine)" }
        return when (val c = grid.at(x, y)) {
            '.' -> "floor"
            '"' -> "tall grass"
            '~' -> "water"
            '#' -> "wall/obstacle"
            '-' -> "nothing (edge)"
            else -> terrainNames[c] ?: "?"
        }
    }

    /** "bookshelf at x 1-2, y 10": merges identical labels on consecutive tiles of a row. */
    private fun groupExaminables(list: List<BgEventInfo>): List<String> {
        val out = mutableListOf<String>()
        list.groupBy { it.label to it.z }.forEach { (key, events) ->
            val xs = events.map { it.x }.distinct().sorted()
            var start = xs.first()
            var prev = start
            fun flush() {
                out += if (start == prev) "${key.first} at ($start,${key.second})" else "${key.first} at x $start-$prev, y ${key.second}"
            }
            xs.drop(1).forEach { x ->
                if (x != prev + 1) {
                    flush(); start = x
                }
                prev = x
            }
            flush()
        }
        return out
    }

    private fun distance(dx: Int, dz: Int) = abs(dx) + abs(dz)

    /** "(2 west, 1 north)" relative to the player (x grows east, y grows south). */
    fun relative(dx: Int, dz: Int): String {
        if (dx == 0 && dz == 0) return "(where you stand)"
        val parts = buildList {
            if (dx != 0) add("${abs(dx)} ${if (dx > 0) "east" else "west"}")
            if (dz != 0) add("${abs(dz)} ${if (dz > 0) "south" else "north"}")
        }
        return parts.joinToString(", ", "(", ")")
    }

    private fun teamMember(mon: PartyMon): String {
        val name = mon.nickname?.takeIf { it != mon.speciesName }?.let { "$it (${mon.speciesName})" } ?: mon.speciesName
        if (mon.isEgg) return "Egg"
        val status = if (mon.status != "OK") ", ${mon.status.lowercase()}" else ""
        val moves = mon.moves.joinToString(", ") { "${it.name} ${it.pp}/${it.maxPp}" }
        return "$name Lv${mon.level}, HP ${mon.hp}/${mon.maxHp}$status, ${mon.types.joinToString("/")}; moves: $moves"
    }

    private fun battle(battle: BattleInfo) = buildJsonObject {
        put("kind", if (battle.isWild) "wild Pokémon" else "trainer battle")
        battle.trainers.firstOrNull()?.let { put("opponent_trainer", "${it.trainerClass} ${it.name}") }
        put("opponent", battle.opponents.joinToString(" | ") { battler(it) })
        put("you", battle.player.joinToString(" | ") { battler(it) })
        battle.message?.let { put("message", it.replace('\n', ' ')) }
        when {
            battle.menu == "BAG_SCREEN" -> put("status", "the Bag is open on the touch screen (D-pad moves, A chooses, B goes back to the battle menu)")
            battle.menu == "PARTY_SCREEN" -> put("status", "the party screen is open (D-pad moves, A chooses, B goes back to the battle menu)")
            !battle.awaitingInput -> put("status", "animations/messages are playing (wait)")
        }
    }

    private fun battler(mon: Battler): String {
        val status = if (mon.status != "OK") ", ${mon.status.lowercase()}" else ""
        return "${mon.speciesName} Lv${mon.level}, HP ${mon.hp}/${mon.maxHp}$status, ${mon.types.joinToString("/")}"
    }
}
