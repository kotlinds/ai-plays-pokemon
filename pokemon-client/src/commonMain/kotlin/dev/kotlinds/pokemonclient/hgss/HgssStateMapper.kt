package dev.kotlinds.pokemonclient.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleStat
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PlayerInfo
import dev.kotlinds.pokemonclient.state.ReadWarning
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.Stat
import dev.kotlinds.pokemonclient.state.StoryState
import dev.kotlinds.pokemonclient.state.StoryStep
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.state.BagPocket as CommonBagPocket
import dev.kotlinds.pokemonclient.state.PartyMon as CommonPartyMon

/**
 * Turns the detailed HGSS reading ([HgssState]) into the common [GameState] model.
 *
 * Stateful on purpose: it remembers the last plausible reading of each Pokémon, so a party structure read
 * while the game rewrites it (after a capture, a switch, a map change...) is replaced by its last good value
 * instead of showing garbage like "Lv109, HP 10241/59961".
 */
class HgssStateMapper {

    private val lastGood = mutableMapOf<Int, CommonPartyMon>()

    /** Maps [state]; with [memory], the screen decoders ([HgssScreens]) read the screens [HgssState] doesn't cover. */
    fun map(state: HgssState, memory: HgssMemory? = null): GameState {
        val warnings = state.warnings.map { ReadWarning(ReadWarning.Kind.OTHER, it) }.toMutableList()
        val party = state.party.mapNotNull { mon ->
            if (mon.plausible) mon.toCommon().also { lastGood[mon.slot] = it }
            else lastGood[mon.slot]?.also {
                warnings += ReadWarning(ReadWarning.Kind.POKEMON_CHECKSUM, "slot ${mon.slot}: unreadable right now, showing the last good reading")
            }
        }
        if (state.party.isNotEmpty()) lastGood.keys.retainAll(state.party.map { it.slot }.toSet())
        return GameState(
            frame = state.frame,
            screen = if (state.fading) Screen.Animation(AnimationKind.TRANSITION) else memory?.let { decoded(it, state) } ?: screen(state, party),
            player = state.player?.let { PlayerInfo(it.name, it.money, it.badges, it.trainerId) },
            party = party,
            bag = state.bag?.map { pocket ->
                CommonBagPocket(pocket.pocket, pocket.items.map { BagItem(Named(ItemId(it.id), it.name), it.quantity) })
            },
            battle = state.battle?.let { battle(it, party) },
            field = state.location?.takeIf { state.mode in FIELD_MODES }?.let { l ->
                FieldState(
                    mapId = l.mapId,
                    mapName = l.locationName?.let { town -> "$town (${l.mapName})" } ?: l.mapName,
                    x = l.x,
                    y = l.z,
                    height = l.height,
                    facing = Direction.parse(l.facing),
                    movement = when {
                        "surf" in l.avatarState.lowercase() -> MovementMode.SURF
                        "bike" in l.avatarState.lowercase() || "cycl" in l.avatarState.lowercase() -> MovementMode.BIKE
                        else -> MovementMode.WALK
                    },
                    moving = l.moving,
                    objects = state.surroundings?.objects.orEmpty().filterNot { it.hidden }.map { o ->
                        FieldObject(
                            id = "person:${o.id}",
                            label = o.label,
                            kind = when (o.kind) {
                                "follower" -> FieldObjectKind.FOLLOWER
                                "item_ball" -> FieldObjectKind.ITEM_BALL
                                "obstacle" -> FieldObjectKind.OBSTACLE
                                else -> FieldObjectKind.PERSON
                            },
                            x = o.x,
                            y = o.z,
                            facing = Direction.parse(o.facing),
                            role = when (o.sprite) {
                                "PCWOMAN1" -> PersonRole.NURSE
                                "SHOPM1", "SHOPM1_2", "SHOPW1" -> PersonRole.CLERK
                                else -> null
                            },
                            obstacle = when (o.sprite) {
                                "TREE" -> ObstacleKind.CUT_TREE
                                "BREAKROCK" -> ObstacleKind.SMASH_ROCK
                                "ROCK" -> ObstacleKind.BOULDER
                                else -> null
                            },
                        )
                    },
                )
            },
            warnings = warnings,
            registeredItems = state.registeredItems.map { id -> id.takeIf { it != 0 }?.let(::ItemId) },
            story = story(state),
        )
    }

    // region Story

    /** The next step of [HgssStoryTable] and the blockers of the current map ([HgssBlockers]), from flags and vars. */
    private fun story(state: HgssState): StoryState? {
        val story = state.story ?: return null
        val mapId = state.location?.mapId
        val goal = HgssStoryTable.goal(story)?.let { StoryStep(it.id, it.describe(mapId)) }
        val blockers = if (state.mode in FIELD_MODES) HgssBlockers.of(state) else emptyList()
        return StoryState(goal, blockers)
    }

    // endregion

    // region Screen

    private fun decoded(memory: HgssMemory, state: HgssState) =
        HgssScreens.decoders.firstNotNullOfOrNull { decoder -> runCatching { decoder.decode(memory, state) }.getOrNull() }

    private fun screen(state: HgssState, party: List<CommonPartyMon>): Screen {
        // During a screen fade (opening an app, entering a building...) the previous screen is still in memory and
        // may look like it waits for input: it doesn't.
        if (state.fading) return Screen.Animation(AnimationKind.TRANSITION)
        val awaiting = if (state.awaitingInput) Awaiting.INPUT else Awaiting.ANIMATION
        state.menu?.takeIf { it.options.isNotEmpty() }?.let { return scriptMenu(it) }
        state.battle?.let { b -> battleScreen(b, party)?.let { return it } }
        return when (state.mode) {
            // The start menu stays drawn while an entry runs (the SAVE question prints under it): only its
            // HANDLE_INPUT state waits for a choice.
            GameMode.START_MENU -> state.startMenu?.let { if (it.waiting) startMenu(it) else Screen.Animation(AnimationKind.TRANSITION) }
                ?: Screen.Unknown("start menu", awaiting)
            GameMode.DIALOGUE -> state.dialogue?.let { d ->
                val text = d.visibleText ?: d.text
                if (text != null && d.messageBoxOpen) {
                    Screen.Dialogue(TextSource.FIELD, speaker = null, text = text, awaiting = if (d.printing) Awaiting.TEXT_PRINTING else awaiting)
                } else null
            } ?: Screen.Unknown("script", awaiting)
            GameMode.OVERWORLD -> Screen.Overworld(awaiting = awaiting)
            GameMode.FIELD_BUSY, GameMode.SCRIPT -> Screen.Overworld(awaiting = Awaiting.ANIMATION)
            GameMode.BATTLE -> state.battle?.message?.let {
                Screen.Dialogue(TextSource.BATTLE, null, it, Awaiting.ANIMATION)
            } ?: Screen.Battle(Awaiting.ANIMATION)
            GameMode.APP -> state.app?.takeIf { it.entries.isNotEmpty() }?.let(::appMenu)
                ?: Screen.Unknown(state.modeDetail, awaiting)
            GameMode.LOADING, GameMode.INTRO_MOVIE, GameMode.TITLE_SCREEN, GameMode.MAIN_MENU, GameMode.NEW_GAME_INTRO ->
                Screen.Intro(state.mode.name.lowercase(), awaiting)
            GameMode.UNKNOWN -> Screen.Unknown(state.modeDetail, awaiting)
        }
    }

    private fun scriptMenu(m: MenuInfo): Screen.Selectable {
        val cursor = m.cursor?.let { Cursor.At(it) } ?: Cursor.Hidden
        val topology = if (m.columns > 1) Topology.grid(m.options.size, m.columns) else Topology.vertical(m.options.size, wrap = true)
        return if (m.kind == "yes_no") {
            // Script yes/no menus always list YES then NO (whatever the language).
            Screen.YesNo(null, m.options.mapIndexed { i, o -> Entry(if (i == 0) "option:yes" else "option:no", o) }, cursor, topology)
        } else {
            Screen.ListMenu(MenuKind.MULTICHOICE, m.options.mapIndexed { i, o -> option(i, o) }, cursor, topology, CancelBehavior.CONFIRMS_LAST)
        }
    }

    private fun startMenu(sm: StartMenuInfo): Screen.Selectable = Screen.ListMenu(
        kind = MenuKind.START_MENU,
        entries = sm.items.mapIndexed { i, o ->
            sm.ids.getOrNull(i)?.let { Entry("option:$it", o) } ?: Entry("empty:$i", o, selectable = false)
        },
        cursor = sm.cursor?.let { Cursor.At(it) } ?: Cursor.Hidden,
        topology = Topology.grid(sm.items.size, columns = 2),
    )

    private fun appMenu(app: AppInfo): Screen.Selectable = Screen.ListMenu(
        kind = MenuKind.OTHER,
        entries = app.entries.mapIndexed { i, o -> option(i, o) },
        cursor = app.cursor?.let { Cursor.At(it) } ?: Cursor.Hidden,
        topology = when (app.layout) {
            "horizontal" -> Topology.horizontal(app.entries.size)
            "grid2" -> Topology.grid(app.entries.size, 2)
            else -> Topology.vertical(app.entries.size)
        },
    )

    private fun battleScreen(b: BattleInfo, party: List<CommonPartyMon>): Screen? {
        if (!b.awaitingInput) return null
        val cursor = b.menuCursor
        return when (b.menu) {
            "MAIN", "MAIN_FIGHT_ONLY", "PAL_PARK", "PAL_PARK_INITIAL" -> {
                // Ids are semantic (by position in the menu), labels are what the game shows: recipes only use ids,
                // so they work whatever the game's language.
                val labels = if (b.menu == "MAIN_FIGHT_ONLY") listOf("fight" to "FIGHT") else listOf("fight" to "FIGHT", "bag" to "BAG", "run" to "RUN", "pokemon" to "POKéMON")
                // battle_input.c: y=0 FIGHT (big button on top); y=1: x=0 BAG, x=1 RUN, x=2 POKéMON
                val index = cursor?.let { (y, x) -> if (y == 0) 0 else when (x) { 0 -> 1; 1 -> 2; else -> 3 } }
                Screen.BattleCommand(
                    actor = BattlerRef.PLAYER_LEFT,
                    entries = labels.map { (id, label) -> Entry("option:$id", label) },
                    cursor = index?.let { Cursor.At(it) } ?: Cursor.Hidden,
                    topology = if (labels.size == 1) Topology.vertical(1) else BATTLE_MAIN_TOPOLOGY,
                )
            }
            "FIGHT" -> {
                val moves = b.player.firstOrNull()?.moves.orEmpty()
                val entries = (0 until 4).map { i ->
                    moves.getOrNull(i)?.let { m -> Entry("move:${m.id}", "${m.name} (${m.type ?: "?"}, ${m.pp}/${m.maxPp} PP)", selectable = m.pp > 0) }
                        ?: Entry("empty:$i", "-", selectable = false)
                } + Entry("option:cancel", "CANCEL")
                Screen.MoveSelect(
                    context = MoveContext.BATTLE,
                    mon = b.player.firstOrNull()?.let { MonId(it.personality, it.otId) },
                    newMove = null,
                    entries = entries,
                    cursor = cursor?.let { (y, x) -> Cursor.At(if (y >= 2) 4 else y * 2 + x) } ?: Cursor.Hidden,
                    topology = FIGHT_TOPOLOGY,
                    cancel = CancelBehavior.CLOSES,
                )
            }
            "YES_NO", "KEEP_FORGET_MOVE", "GIVE_UP_ON_MOVE", "SWITCH_OR_FLEE", "SWITCH_OR_KEEP" -> {
                val options = when (b.menu) {
                    "KEEP_FORGET_MOVE" -> listOf("forget" to "FORGET A MOVE", "keep" to "KEEP OLD MOVES")
                    "SWITCH_OR_FLEE" -> listOf("next" to "USE NEXT POKéMON", "flee" to "FLEE")
                    "SWITCH_OR_KEEP" -> listOf("switch" to "SWITCH", "keep" to "KEEP BATTLING")
                    else -> listOf("yes" to "YES", "no" to "NO")
                }
                val entries = options.map { (id, label) -> Entry("option:$id", label) }
                val c = cursor?.let { (y, _) -> Cursor.At(y.coerceIn(0, 1)) } ?: Cursor.Hidden
                if (b.menu == "SWITCH_OR_KEEP") {
                    Screen.ListMenu(MenuKind.BATTLE_SWITCH_OR_KEEP, entries, c, Topology.vertical(2), CancelBehavior.CONFIRMS_LAST)
                } else {
                    Screen.YesNo(b.message, entries, c, Topology.vertical(2), CancelBehavior.CONFIRMS_LAST)
                }
            }
            // Decoded by their own screens (phase 1 decoders); until then they are explicit unknowns.
            "BAG_SCREEN" -> Screen.Unknown("battle bag", Awaiting.INPUT)
            "PARTY_SCREEN" -> Screen.Unknown("battle party screen", Awaiting.INPUT)
            else -> Screen.Unknown("battle menu ${b.menu}", Awaiting.INPUT)
        }
    }

    private fun option(index: Int, label: String) = Entry("option:${index}", label)

    // endregion

    // region Pokémon and battle

    private fun PartyMon.toCommon() = CommonPartyMon(
        id = MonId(personality, otId),
        slot = slot,
        species = Named(SpeciesId(species), speciesName),
        nickname = nickname?.takeIf { it != speciesName && !isEgg },
        level = level,
        hp = hp,
        maxHp = maxHp,
        status = HgssStatuses.major(statusRaw),
        types = types,
        heldItem = heldItem?.let { Named(ItemId(heldItemId), it) },
        ability = ability,
        moves = moves.map { KnownMove(Named(MoveId(it.id), it.name), it.pp, it.maxPp, it.type) },
        stats = mapOf(
            Stat.HP to maxHp,
            Stat.ATTACK to (stats["atk"] ?: 0),
            Stat.DEFENSE to (stats["def"] ?: 0),
            Stat.SPEED to (stats["speed"] ?: 0),
            Stat.SP_ATTACK to (stats["spAtk"] ?: 0),
            Stat.SP_DEFENSE to (stats["spDef"] ?: 0),
        ),
        exp = exp,
        expToNextLevel = null,
        isEgg = isEgg,
    )

    private fun battle(b: BattleInfo, party: List<CommonPartyMon>): BattleState {
        val battlers = (b.player + b.opponents).map { battler ->
            val ref = HgssStatuses.battlerRef(battler.battlerId)
            BattlerState(
                ref = ref,
                mon = if (ref.isPlayerSide) MonId(battler.personality, battler.otId) else null,
                species = Named(SpeciesId(battler.species), battler.speciesName),
                nickname = battler.nickname?.takeIf { it != battler.speciesName },
                level = battler.level,
                hp = battler.hp,
                maxHp = battler.maxHp,
                status = HgssStatuses.major(battler.statusRaw),
                volatile = HgssStatuses.volatile(battler.status2, battler.moveEffects, battler.counters),
                statStages = battler.statStages.mapNotNull { (name, value) ->
                    STAGE_NAMES[name]?.takeIf { value != 0 }?.let { it to value }
                }.toMap(),
                types = battler.types,
                moves = battler.moves.map { KnownMove(Named(MoveId(it.id), it.name), it.pp, it.maxPp, it.type) },
            )
        }
        val bySlot = party.associateBy { it.slot }
        return BattleState(
            kind = if (b.isWild) BattleKind.WILD else BattleKind.TRAINER,
            isDouble = b.isDoubles,
            actor = if (b.awaitingInput) BattlerRef.PLAYER_LEFT else null,
            battlers = battlers,
            trainers = b.trainers.map { "${it.trainerClass} ${it.name}".trim() },
            partyOrder = b.partyOrder.mapNotNull { bySlot[it]?.id },
            message = b.message,
        )
    }

    // endregion

    private companion object {
        val FIELD_MODES = setOf(GameMode.OVERWORLD, GameMode.FIELD_BUSY, GameMode.SCRIPT, GameMode.DIALOGUE, GameMode.START_MENU)

        val STAGE_NAMES = mapOf(
            "atk" to BattleStat.ATTACK, "def" to BattleStat.DEFENSE, "speed" to BattleStat.SPEED,
            "spAtk" to BattleStat.SP_ATTACK, "spDef" to BattleStat.SP_DEFENSE,
            "accuracy" to BattleStat.ACCURACY, "evasion" to BattleStat.EVASION,
        )

        /** FIGHT on top (0); BAG (1), RUN (2), POKéMON (3) on the row below (battle_input.c). */
        val BATTLE_MAIN_TOPOLOGY = Topology.of(
            mapOf(
                0 to mapOf(Button.LEFT to 1, Button.DOWN to 2, Button.RIGHT to 3),
                1 to mapOf(Button.UP to 0, Button.RIGHT to 2),
                2 to mapOf(Button.UP to 0, Button.LEFT to 1, Button.RIGHT to 3),
                3 to mapOf(Button.UP to 0, Button.LEFT to 2),
            ),
        )

        /** Moves in a 2x2 grid (0 1 / 2 3), CANCEL (4) below. */
        val FIGHT_TOPOLOGY = Topology.of(
            mapOf(
                0 to mapOf(Button.RIGHT to 1, Button.DOWN to 2),
                1 to mapOf(Button.LEFT to 0, Button.DOWN to 3),
                2 to mapOf(Button.UP to 0, Button.RIGHT to 3, Button.DOWN to 4),
                3 to mapOf(Button.UP to 1, Button.LEFT to 2, Button.DOWN to 4),
                4 to mapOf(Button.UP to 2),
            ),
        )
    }
}
