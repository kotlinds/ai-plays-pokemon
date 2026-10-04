package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.WorldSource
import dev.kotlinds.pokemonclient.state.GameState
import kotlinx.serialization.json.JsonObject
import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Location
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.GameMode as AgentMode

/**
 * [PokemonGame] for HeartGold / SoulSilver: adapts the detailed [HgssReader] snapshot to what the agent needs.
 *
 * One class serves every HG/SS build; only the [version]'s address table differs.
 *
 * [Observation.facts] keys (values are short strings; a key is absent when it doesn't apply):
 * - `mode`: overworld, dialogue, script, start_menu, app, battle, field_busy, loading, title_screen, ...
 * - `screen`: what fills the screen: "map", "message box", "start menu", the app name ("bag", "pokegear",
 *   "mailbox"...), "battle", "title screen"...
 * - `location`: map name; `position`: "x,y"; `facing`: north/south/west/east (in the field only)
 * - `dialogue`: the lines of the message box shown now (current page, " / " between lines)
 * - `waiting_for`: what a running script waits for: printing, waiting_button, yes_no, multichoice, waiting_movement...
 * - `menu.kind`, `menu.cursor`: the open menu (yes/no, multichoice, start menu, battle: main / fight...) and the
 *   highlighted option ("1: NO"; "hidden" while the battle cursor isn't shown yet)
 * - `battle.menu`, `battle.cursor`: the battle's bottom-screen menu id and raw cursor "y,x"
 * - `battle.hp`: "Cyndaquil 20/20 vs Sentret 12/12"
 * - `party.hp`: "Cyndaquil 20/20, Pidgey 3/15"; `party.count`
 * - `money`, `badges`, `bag` ("<kinds> kinds, <total> items")
 */
class HgssGame(private val version: HgssVersion, rom: NdsRom? = null) : PokemonGame {

    /** Built from the ROM when one is given (maps are decoded lazily, area by area). */
    override val world: HgssWorldSource? = rom?.let { HgssWorldSource(it, version) }?.also { HgssData.useWorld(it) }

    /**
     * The game data read from the ROM, when one is given. Also installed in [HgssData] so the decoders' name and
     * data lookups read the ROM instead of the bundled tables.
     */
    override val data: HgssGameData? = rom?.let { HgssGameData(it, version) }?.also { HgssData.useGameData(it) }

    override val name = version.displayName

    override fun scriptVariable(memory: Memory, id: Int): Int? = HgssReader(memory, version).variable(id)

    override fun zoneName(id: Int): String? = HgssData.mapName(id)

    override val inputProbe = HgssInputProbe(version)

    override fun fieldMoveRule(move: dev.kotlinds.pokemonclient.world.FieldMoveKind) = HgssFieldMoves.rule(move)

    /**
     * The registered item buttons of the field's bottom-screen menu (overlay 27, hitbox table `ov27_0225CF68`, entries
     * 8 and 9: x 203-255, y 8-39 and 46-77), which set `FieldSystem.lastTouchMenuInput` to 9 / 10: the first / second
     * registered item (src/field/field_control.c).
     */
    override fun registeredItemTouch(slot: Int): dev.kotlinds.pokemonclient.console.TouchPoint? = when (slot) {
        0 -> dev.kotlinds.pokemonclient.console.TouchPoint(229, 23)
        1 -> dev.kotlinds.pokemonclient.console.TouchPoint(229, 61)
        else -> null
    }

    private val mapper = HgssStateMapper()

    /** PC boxes, options, trainers, shop catalogs and Fly permission ([HgssServices]). */
    private val services = HgssServices()

    override fun state(memory: Memory): GameState {
        val reader = HgssReader(memory, version)
        val state = reader.read() ?: HgssState(frame = 0, mode = GameMode.UNKNOWN, modeDetail = "unreadable RAM")
        val hgssMemory = HgssMemory(memory, version)
        val mapped = services.enrich(mapper.map(state, hgssMemory), state, hgssMemory)
        val field = mapped.field ?: return mapped
        val area = world?.areaOf(field.mapId)
        val puzzle = HgssPuzzles.read(field.mapId, HgssPuzzles.reads(reader), area)
        val pickedUp = area?.signs.orEmpty()
            .filter { it.zone == field.mapId && it.kind == SignKind.HIDDEN_ITEM && it.flag?.let(reader::flag) == true }
            .map { "hidden_item:${it.id}" }.toSet()
        if (puzzle == null && pickedUp.isEmpty()) return mapped
        return mapped.copy(field = field.copy(puzzle = puzzle, pickedUp = pickedUp))
    }

    override fun observe(memory: Memory): Observation {
        val state = HgssReader(memory, version).read()
            ?: return Observation(AgentMode.UNKNOWN, null, "Unreadable RAM", JsonObject(emptyMap()), awaitingInput = false)
        val location = state.location?.let {
            Location(
                it.mapId, it.locationName?.let { town -> "$town (${it.mapName})" } ?: it.mapName, it.x, it.z,
                Direction.parse(it.facing),
            )
        }
        val menu = HgssMenus.current(state)
        return Observation(
            mode = agentMode(state.mode),
            location = location,
            summary = summary(state),
            state = HgssScreenDescriber.describe(state),
            facts = facts(state, menu),
            awaitingInput = state.awaitingInput,
            dialogue = state.dialogue?.takeIf { it.messageBoxOpen }?.text,
            map = if (state.mode in FIELD_MODES) state.surroundings?.let { HgssMapView.localMap(it) } else null,
            menu = menu?.toMenuState(),
            progress = HgssProgress.milestones(state.story, state.player, state.party.size),
            storyGoal = HgssProgress.nextGoal(state.story, state.player, state.location?.mapId),
        )
    }

    /** The reader distinguishes many modes; our code only needs the broad situation. */
    private fun agentMode(mode: GameMode): AgentMode = when (mode) {
        GameMode.LOADING, GameMode.INTRO_MOVIE, GameMode.TITLE_SCREEN,
        GameMode.MAIN_MENU, GameMode.NEW_GAME_INTRO -> AgentMode.INTRO

        GameMode.OVERWORLD -> AgentMode.OVERWORLD
        GameMode.FIELD_BUSY, GameMode.SCRIPT, GameMode.DIALOGUE -> AgentMode.DIALOGUE
        GameMode.START_MENU, GameMode.APP -> AgentMode.MENU
        GameMode.BATTLE -> AgentMode.BATTLE
        GameMode.UNKNOWN -> AgentMode.UNKNOWN
    }

    private fun facts(state: HgssState, menu: HgssMenus.View?): Map<String, String> = buildMap {
        put("mode", state.mode.name.lowercase())
        put("screen", when (state.mode) {
            GameMode.OVERWORLD, GameMode.FIELD_BUSY, GameMode.SCRIPT -> "map"
            GameMode.DIALOGUE -> "message box"
            GameMode.START_MENU -> "start menu"
            GameMode.APP -> state.modeDetail ?: "app"
            GameMode.BATTLE -> "battle"
            else -> state.mode.name.lowercase().replace('_', ' ')
        })
        if (state.mode in FIELD_MODES) state.location?.let { l ->
            put("location", l.mapName)
            put("position", "${l.x},${l.z}")
            put("facing", l.facing)
        }
        state.dialogue?.let { d ->
            if (d.messageBoxOpen) (d.visibleText ?: d.text)?.let { put("dialogue", it.replace("\n", " / ")) }
            put("waiting_for", d.waitingFor)
        }
        menu?.let { m ->
            put("menu.kind", m.kind)
            put("menu.cursor", m.cursor?.let { "$it: ${m.options.getOrNull(it)}" } ?: "hidden")
        }
        state.battle?.let { b ->
            b.menu?.let { put("battle.menu", it.lowercase()) }
            put("battle.cursor", b.menuCursor?.joinToString(",") ?: "hidden")
            val ours = b.player.firstOrNull()
            val theirs = b.opponents.firstOrNull()
            if (ours != null && theirs != null) put("battle.hp", "${ours.speciesName} ${ours.hp}/${ours.maxHp} vs ${theirs.speciesName} ${theirs.hp}/${theirs.maxHp}")
        }
        if (state.party.isNotEmpty()) {
            put("party.count", state.party.size.toString())
            put("party.hp", state.party.joinToString(", ") { "${it.speciesName} ${it.hp}/${it.maxHp}" })
        }
        state.player?.let {
            put("money", it.money.toString())
            put("badges", it.badgeCount.toString())
        }
        state.bag?.let { pockets ->
            val items = pockets.flatMap { it.items }
            put("bag", "${items.size} kinds, ${items.sumOf { it.quantity }} items")
        }
    }

    private fun summary(state: HgssState): String = buildString {
        append(state.mode.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
        state.location?.let { append(" · ${it.locationName ?: it.mapName} (${it.x}, ${it.z}) facing ${it.facing}") }
        state.battle?.let { battle ->
            val ours = battle.player.firstOrNull()
            val theirs = battle.opponents.firstOrNull()
            if (ours != null && theirs != null) {
                append(" · ${ours.speciesName} ${ours.hp}/${ours.maxHp} vs ${theirs.speciesName} Lv${theirs.level} ${theirs.hp}/${theirs.maxHp}")
            }
        }
        state.dialogue?.let { d -> (d.visibleText ?: d.text)?.let { append(" · \"${it.replace('\n', ' ').take(60)}\"") } }
    }

    private companion object {
        val FIELD_MODES = setOf(GameMode.OVERWORLD, GameMode.FIELD_BUSY, GameMode.SCRIPT, GameMode.DIALOGUE, GameMode.START_MENU)
    }
}
