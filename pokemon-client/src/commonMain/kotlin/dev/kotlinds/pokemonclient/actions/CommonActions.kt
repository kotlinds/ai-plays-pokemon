package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import kotlinx.serialization.json.booleanOrNull
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Screen
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The actions shared by every game: their specs and common recipes. */
object CommonActions {

    private val both = setOf(ActionMode.PURE, ActionMode.ASSISTED)
    private val assisted = setOf(ActionMode.ASSISTED)

    val press = ActionDefinition(GameAction.Press::class, spec(
        name = "press",
        description = "Press one button once, like a human (A confirms / talks / advances text, B cancels, X opens the menu, D-pad moves).",
        parameters = listOf(Parameter("button", ParameterType.STRING, "The button.", values = Button.entries.map { it.name.lowercase() })),
        modes = both,
        availability = { Availability.Available() },
        parse = { GameAction.Press(button(it)) },
        enumerate = { Button.entries.map(GameAction::Press) },
    ), BasicPlans.press)

    val touch = ActionDefinition(GameAction.Touch::class, spec(
        name = "touch",
        description = "Touch the bottom (touch) screen at x (0-255), y (0-191) pixels.",
        parameters = listOf(
            Parameter("x", ParameterType.INTEGER, "Horizontal position, 0 (left) to 255."),
            Parameter("y", ParameterType.INTEGER, "Vertical position, 0 (top) to 191."),
        ),
        modes = both,
        availability = { Availability.Available() },
        parse = { GameAction.Touch(TouchPoint(int(it, "x", 0..255), int(it, "y", 0..191))) },
    ), BasicPlans.touch)

    val wait = ActionDefinition(GameAction.Wait::class, spec(
        name = "wait",
        description = "Let the game run: a number of frames (60 per second), or until it waits for you again when omitted.",
        parameters = listOf(Parameter("frames", ParameterType.INTEGER, "Frames to wait (1-1800).", required = false)),
        modes = both,
        availability = { Availability.Available() },
        parse = { json -> GameAction.Wait(json["frames"]?.jsonPrimitive?.intOrNull?.also { check(it, "frames", 1..1800) }) },
        enumerate = { listOf(GameAction.Wait()) },
    ), BasicPlans.wait)

    val advanceDialogue = ActionDefinition(GameAction.AdvanceDialogue::class, spec(
        name = "advance_dialogue",
        description = "Read the messages through to the end (every page is returned), stopping at the first choice.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            if (state.screen is Screen.Dialogue || state.screen is Screen.PressToContinue) Availability.Available() else Availability.Hidden
        },
        parse = { GameAction.AdvanceDialogue },
        enumerate = { listOf(GameAction.AdvanceDialogue) },
    ), BasicPlans.advanceDialogue)

    val choose = ActionDefinition(GameAction.Choose::class, spec(
        name = "choose",
        description = "Choose an entry of the menu on screen by its id (the cursor is moved and checked for you).",
        parameters = listOf(Parameter("entry", ParameterType.STRING, "Id of the entry, from the menu's entries.")),
        modes = assisted,
        availability = { state ->
            val menu = state.screen as? Screen.Selectable ?: return@spec Availability.Hidden
            Availability.Available(mapOf("entry" to menu.entries.filter { it.selectable }.map { Choice(it.id, it.label) }))
        },
        parse = { GameAction.Choose(string(it, "entry")) },
        enumerate = { state -> (state.screen as? Screen.Selectable)?.entries?.filter { it.selectable }?.map { GameAction.Choose(it.id) }.orEmpty() },
    ), BasicPlans.choose)

    val attack = ActionDefinition(GameAction.Attack::class, spec(
        name = "attack",
        description = "Use a move of the active Pokémon this turn (in double battles, also give the target).",
        parameters = listOf(
            Parameter("move", ParameterType.STRING, "The move: its id (move:33) or its name."),
            Parameter("target", ParameterType.STRING, "Double battles: the target.", required = false, values = BattlerRef.entries.map { it.wire }),
        ),
        modes = assisted,
        availability = { state ->
            val battle = state.battle ?: return@spec Availability.Hidden
            if (state.screen !is Screen.BattleCommand) return@spec Availability.Hidden
            val actor = battle.battlers.firstOrNull { it.ref == (battle.actor ?: BattlerRef.PLAYER_LEFT) } ?: return@spec Availability.Hidden
            val usable = actor.moves.filter { it.pp > 0 }
            if (usable.isEmpty()) {
                Availability.Unavailable(UnavailableReason.NO_PP, "${actor.species.name} has no PP left", "Struggle is used through FIGHT with raw buttons")
            } else {
                Availability.Available(mapOf("move" to usable.map { Choice("move:${it.move.id.value}", "${it.move.name} (${it.type ?: "?"}, ${it.pp}/${it.maxPp} PP)") }))
            }
        },
        parse = { json -> GameAction.Attack(MoveRef(string(json, "move")), json["target"]?.jsonPrimitive?.contentOrNull?.let(::battler)) },
        enumerate = { state ->
            val battle = state.battle ?: return@spec emptyList()
            val actor = battle.battlers.firstOrNull { it.ref == (battle.actor ?: BattlerRef.PLAYER_LEFT) } ?: return@spec emptyList()
            val foes = if (battle.isDouble) battle.battlers.filter { !it.ref.isPlayerSide && it.hp > 0 }.map { it.ref } else listOf(null)
            actor.moves.filter { it.pp > 0 }.flatMap { m -> foes.map { GameAction.Attack(MoveRef("move:${m.move.id.value}"), it) } }
        },
    ), BasicPlans.attack)

    val run = ActionDefinition(GameAction.Run::class, spec(
        name = "run",
        description = "Flee the wild battle.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            val battle = state.battle
            when {
                battle == null || state.screen !is Screen.BattleCommand -> Availability.Hidden
                battle.kind != BattleKind.WILD -> Availability.Unavailable(UnavailableReason.TRAINER_BATTLE, "There's no running from a trainer battle")
                else -> Availability.Available()
            }
        },
        parse = { GameAction.Run },
        enumerate = { listOf(GameAction.Run) },
    ), BasicPlans.run)

    val keepBattling = ActionDefinition(GameAction.KeepBattling::class, spec(
        name = "keep_battling",
        description = "Keep your Pokémon in when the opponent is about to send in a new one.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            if ((state.screen as? Screen.ListMenu)?.kind == MenuKind.BATTLE_SWITCH_OR_KEEP) Availability.Available() else Availability.Hidden
        },
        parse = { GameAction.KeepBattling },
        enumerate = { listOf(GameAction.KeepBattling) },
    ), BasicPlans.keepBattling)

    val reorderParty = ActionDefinition(GameAction.ReorderParty::class, spec(
        name = "reorder_party",
        description = "Move a Pokémon of your party to a position (1 = the lead, sent out first in battles): it swaps places with the Pokémon there.",
        parameters = listOf(
            Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…)."),
            Parameter("position", ParameterType.INTEGER, "New position, 1 to 6."),
        ),
        modes = assisted,
        availability = { state -> if (PartyBagPlans.inField(state) && state.party.size > 1) Availability.Available(mapOf("pokemon" to monChoices(state))) else Availability.Hidden },
        parse = { json -> GameAction.ReorderParty(mon(json, "pokemon"), int(json, "position", 1..6)) },
    ), PartyBagPlans.reorderParty)

    val takeItem = ActionDefinition(GameAction.TakeItem::class, spec(
        name = "take_item",
        description = "Take back the item held by a Pokémon.",
        parameters = listOf(Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…).")),
        modes = assisted,
        availability = { state ->
            val holders = state.party.filter { it.heldItem != null }
            when {
                !PartyBagPlans.inField(state) -> Availability.Hidden
                holders.isEmpty() -> Availability.Unavailable(UnavailableReason.NO_STOCK, "No Pokémon holds an item")
                else -> Availability.Available(mapOf("pokemon" to holders.map { Choice(it.id.toString(), "${it.displayName} (${it.heldItem?.name})") }))
            }
        },
        parse = { json -> GameAction.TakeItem(mon(json, "pokemon")) },
    ), PartyBagPlans.takeItem)

    val giveItem = ActionDefinition(GameAction.GiveItem::class, spec(
        name = "give_item",
        description = "Give an item from the bag to a Pokémon to hold.",
        parameters = listOf(
            Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…)."),
            Parameter("item", ParameterType.STRING, "The item: its id (item:17) or its name."),
        ),
        modes = assisted,
        availability = { state -> if (PartyBagPlans.inField(state)) Availability.Available(mapOf("pokemon" to monChoices(state))) else Availability.Hidden },
        parse = { json -> GameAction.GiveItem(mon(json, "pokemon"), ItemRef(string(json, "item"))) },
    ), PartyBagPlans.giveItem)

    val useItem = ActionDefinition(GameAction.UseItem::class, spec(
        name = "use_item",
        description = "Use an item from the bag (on a Pokémon when the item needs one: Potion, Antidote, Rare Candy...).",
        parameters = listOf(
            Parameter("item", ParameterType.STRING, "The item: its id (item:17) or its name."),
            Parameter("target", ParameterType.STRING, "The Pokémon's id (mon:…), for items used on a Pokémon.", required = false),
        ),
        modes = assisted,
        availability = { state -> if (PartyBagPlans.inField(state)) Availability.Available(mapOf("target" to monChoices(state))) else Availability.Hidden },
        parse = { json -> GameAction.UseItem(ItemRef(string(json, "item")), json["target"]?.jsonPrimitive?.contentOrNull?.let { monId(it) }) },
    ), PartyBagPlans.useItem)

    val saveGame = ActionDefinition(GameAction.SaveGame::class, spec(
        name = "save_game",
        description = "Save the game (start menu → SAVE). Safe to do often.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state -> if (PartyBagPlans.inField(state)) Availability.Available() else Availability.Hidden },
        parse = { GameAction.SaveGame },
    ), FieldPlans.saveGame)

    private val moveParameters = listOf(
        Parameter("avoid_tall_grass", ParameterType.BOOLEAN, "Avoid tall grass when another way exists (fewer wild battles).", required = false),
        Parameter("avoid_trainers", ParameterType.BOOLEAN, "Avoid the line of sight of trainers when another way exists.", required = false),
        Parameter("accept_one_way", ParameterType.BOOLEAN, "Allow jumping down ledges (no way back the same way).", required = false),
        Parameter("run", ParameterType.BOOLEAN, "Run (hold B) instead of walking.", required = false),
    )

    val goTo = ActionDefinition(GameAction.GoTo::class, spec(
        name = "go_to",
        description = "Walk to a tile (x, y) or to a target of this map (person:N, warp:N to go through a door / stairs, sign:N). " +
            "Stops early when something happens (battle, trainer, phone call, script).",
        parameters = listOf(
            Parameter("x", ParameterType.INTEGER, "Tile x (with y).", required = false),
            Parameter("y", ParameterType.INTEGER, "Tile y (with x).", required = false),
            Parameter("target", ParameterType.STRING, "Instead of x / y: person:N, warp:N or sign:N.", required = false),
        ) + moveParameters,
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true)) Availability.Available(mapOf("target" to targetChoices(state))) else Availability.Hidden },
        parse = { json ->
            val target = json["target"]?.jsonPrimitive?.contentOrNull
            val x = json["x"]?.jsonPrimitive?.intOrNull
            val y = json["y"]?.jsonPrimitive?.intOrNull
            if (target == null && (x == null || y == null)) throw ActionException(ActionError.InvalidParameter("target", "missing", listOf("x and y", "target")))
            GameAction.GoTo(x, y, target, moveOptions(json))
        },
    ), MovePlans.goTo)

    val explore = ActionDefinition(GameAction.Explore::class, spec(
        name = "explore",
        description = "Walk as far as possible in a direction (to the edge of what is reachable: a map exit, an obstacle...).",
        parameters = listOf(Parameter("direction", ParameterType.STRING, "north, south, west or east.", values = Direction.entries.map { it.name.lowercase() })) + moveParameters,
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true)) Availability.Available() else Availability.Hidden },
        parse = { json ->
            val raw = string(json, "direction")
            val direction = Direction.parse(raw) ?: throw ActionException(ActionError.InvalidParameter("direction", raw, Direction.entries.map { it.name.lowercase() }))
            GameAction.Explore(direction, moveOptions(json))
        },
    ), MovePlans.explore)

    val interact = ActionDefinition(GameAction.Interact::class, spec(
        name = "interact",
        description = "Walk next to a person, sign or item of this map, face it and press A (talk, read, pick up).",
        parameters = listOf(Parameter("target", ParameterType.STRING, "person:N or sign:N.")),
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true)) Availability.Available(mapOf("target" to targetChoices(state))) else Availability.Hidden },
        parse = { json -> GameAction.Interact(string(json, "target")) },
    ), MovePlans.interact)

    val findEncounter = ActionDefinition(GameAction.FindEncounter::class, spec(
        name = "find_encounter",
        description = "Walk to the nearest tall grass of this map and pace in it until a wild Pokémon appears.",
        parameters = emptyList(),
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true)) Availability.Available() else Availability.Hidden },
        parse = { GameAction.FindEncounter },
    ), MovePlans.findEncounter)

    val heal = ActionDefinition(GameAction.Heal::class, spec(
        name = "heal",
        description = "Heal the party at the nurse of this Pokémon Center (walks to the counter, answers YES).",
        parameters = emptyList(),
        modes = assisted,
        availability = { state ->
            when {
                !MovePlans.canWalk(state, hasWorld = true) -> Availability.Hidden
                state.field?.objects?.any { it.role == PersonRole.NURSE } != true -> Availability.Hidden
                else -> Availability.Available()
            }
        },
        parse = { GameAction.Heal },
    ), FieldPlans.heal)

    val deposit = ActionDefinition(GameAction.Deposit::class, spec(
        name = "deposit",
        description = "Deposit a party Pokémon in the PC of this building (walks to the PC; first box with room).",
        parameters = listOf(Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…).")),
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true) && state.party.size > 1) Availability.Available(mapOf("pokemon" to monChoices(state))) else Availability.Hidden },
        parse = { json -> GameAction.Deposit(mon(json, "pokemon")) },
    ), PcPlans.deposit)

    val withdraw = ActionDefinition(GameAction.Withdraw::class, spec(
        name = "withdraw",
        description = "Take a Pokémon out of the PC of this building into the party (looks through every box).",
        parameters = listOf(Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…), as seen in the PC.")),
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true) && state.party.size < 6) Availability.Available() else Availability.Hidden },
        parse = { json -> GameAction.Withdraw(mon(json, "pokemon")) },
    ), PcPlans.withdraw)

    val buy = ActionDefinition(GameAction.Buy::class, spec(
        name = "buy",
        description = "Buy an item from the clerk of this Poké Mart (walks to the counter).",
        parameters = listOf(
            Parameter("item", ParameterType.STRING, "The item: its id (item:4) or its name."),
            Parameter("quantity", ParameterType.INTEGER, "How many, 1 to 99.", required = false),
        ),
        modes = assisted,
        availability = { state ->
            if (MovePlans.canWalk(state, hasWorld = true) && state.field?.objects?.any { it.role == PersonRole.CLERK } == true) Availability.Available()
            else Availability.Hidden
        },
        parse = { json -> GameAction.Buy(ItemRef(string(json, "item")), json["quantity"]?.jsonPrimitive?.intOrNull?.also { check(it, "quantity", 1..99) } ?: 1) },
    ), ShopPlans.buy)

    val switch = ActionDefinition(GameAction.Switch::class, spec(
        name = "switch",
        description = "Send another Pokémon into battle (instead of attacking, or to replace a fainted one).",
        parameters = listOf(Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…).")),
        modes = assisted,
        availability = { state ->
            if (state.battle == null || !BattlePlans.canSwitch(state)) return@spec Availability.Hidden
            val active = state.battle.partyOrder.firstOrNull()
            val choices = state.party.filter { it.id != active && !it.fainted && !it.isEgg }.map { Choice(it.id.toString(), "${it.displayName} Lv${it.level} ${it.hp}/${it.maxHp}") }
            if (choices.isEmpty()) Availability.Unavailable(UnavailableReason.NO_STOCK, "No other Pokémon can battle") else Availability.Available(mapOf("pokemon" to choices))
        },
        parse = { json -> GameAction.Switch(mon(json, "pokemon")) },
    ), BattlePlans.switch)

    val throwBall = ActionDefinition(GameAction.ThrowBall::class, spec(
        name = "throw_ball",
        description = "Throw a Poké Ball at the wild Pokémon (wild battles only).",
        parameters = listOf(Parameter("ball", ParameterType.STRING, "The ball: its id (item:4) or its name.")),
        modes = assisted,
        availability = { state ->
            val battle = state.battle ?: return@spec Availability.Hidden
            if (state.screen !is Screen.BattleCommand) return@spec Availability.Hidden
            if (battle.trainers.isNotEmpty()) return@spec Availability.Unavailable(UnavailableReason.TRAINER_BATTLE, "You can't catch a trainer's Pokémon")
            val balls = state.bag.orEmpty().firstOrNull { it.name == "balls" }?.items.orEmpty()
            if (balls.isEmpty()) Availability.Unavailable(UnavailableReason.NO_STOCK, "No Poké Balls in the bag", "buy some at a Poké Mart")
            else Availability.Available(mapOf("ball" to balls.map { Choice("item:${it.item.id.value}", "${it.item.name} x${it.quantity}") }))
        },
        parse = { json -> GameAction.ThrowBall(ItemRef(string(json, "ball"))) },
    ), BattlePlans.throwBall)

    val learnMove = ActionDefinition(GameAction.LearnMove::class, spec(
        name = "learn_move",
        description = "A Pokémon wants to learn a new move but knows four: forget one of them, or keep the old moves (no `forget`).",
        parameters = listOf(Parameter("forget", ParameterType.STRING, "The move to forget (id or name); omit to keep the old moves.", required = false)),
        modes = assisted,
        availability = { state ->
            if (!BattlePlans.isLearnPrompt(state)) return@spec Availability.Hidden
            // On the list itself, the moves the game lets go of (not HMs, not the new one).
            val list = state.screen as? Screen.MoveSelect
            val choices = list?.entries?.filter { it.selectable && it.id.startsWith("move:") && it.id != "move:${list.newMove?.id?.value}" }
                ?.map { Choice(it.id, it.label) }
            Availability.Available(choices?.let { mapOf("forget" to it) } ?: emptyMap())
        },
        parse = { json -> GameAction.LearnMove(json["forget"]?.jsonPrimitive?.contentOrNull?.let(::MoveRef)) },
    ), BattlePlans.learnMove)

    val fish = ActionDefinition(GameAction.Fish::class, spec(
        name = "fish",
        description = "Cast a rod once towards the water you face (A is pressed exactly when something bites). Ends hooked (wild battle) or with nothing.",
        parameters = listOf(Parameter("rod", ParameterType.STRING, "The rod: its id or its name (Old Rod, Good Rod, Super Rod).")),
        modes = assisted,
        availability = { state ->
            if (!MovePlans.canWalk(state, hasWorld = true)) return@spec Availability.Hidden
            val rods = state.bag.orEmpty().flatMap { it.items }.filter { it.item.id.value in RODS }
            if (rods.isEmpty()) Availability.Hidden else Availability.Available(mapOf("rod" to rods.map { Choice("item:${it.item.id.value}", it.item.name) }))
        },
        parse = { json -> GameAction.Fish(ItemRef(string(json, "rod"))) },
    ), FieldPlans.fish)

    /** Old Rod, Good Rod, Super Rod (Gen 4 item ids). */
    private val RODS = setOf(445, 446, 447)

    val enterText = ActionDefinition(GameAction.EnterText::class, spec(
        name = "enter_text",
        description = "Type a name on the naming keyboard (nickname, box name...): replaces what is there, then OK.",
        parameters = listOf(Parameter("text", ParameterType.STRING, "The text to type.")),
        modes = assisted,
        availability = { state -> if (state.screen is Screen.Keyboard) Availability.Available() else Availability.Hidden },
        parse = { json -> GameAction.EnterText(json["text"]?.jsonPrimitive?.contentOrNull ?: throw ActionException(ActionError.InvalidParameter("text", "missing"))) },
    ), TextPlans.enterText)

    val teach = ActionDefinition(GameAction.Teach::class, spec(
        name = "teach",
        description = "Teach a TM / HM from the bag to a Pokémon (give `forget` when it already knows four moves).",
        parameters = listOf(
            Parameter("item", ParameterType.STRING, "The machine: its id (item:328) or its name (TM01)."),
            Parameter("pokemon", ParameterType.STRING, "The Pokémon's id (mon:…)."),
            Parameter("forget", ParameterType.STRING, "The move to forget when it knows four (id or name).", required = false),
        ),
        modes = assisted,
        availability = { state ->
            val machines = state.bag.orEmpty().firstOrNull { it.name == "tms_hms" }?.items.orEmpty()
            if (!PartyBagPlans.inField(state) || machines.isEmpty()) Availability.Hidden
            else Availability.Available(mapOf("item" to machines.map { Choice("item:${it.item.id.value}", it.item.name) }, "pokemon" to monChoices(state)))
        },
        parse = { json -> GameAction.Teach(ItemRef(string(json, "item")), mon(json, "pokemon"), json["forget"]?.jsonPrimitive?.contentOrNull?.let(::MoveRef)) },
    ), PartyBagPlans.teach)

    val useKeyItem = ActionDefinition(GameAction.UseKeyItem::class, spec(
        name = "use_key_item",
        description = "Use a key item from the bag (Bicycle, Itemfinder, Squirtbottle...).",
        parameters = listOf(Parameter("item", ParameterType.STRING, "The key item: its id or its name.")),
        modes = assisted,
        availability = { state ->
            val keys = state.bag.orEmpty().firstOrNull { it.name == "key_items" }?.items.orEmpty()
            if (!PartyBagPlans.inField(state) || keys.isEmpty()) Availability.Hidden
            else Availability.Available(mapOf("item" to keys.map { Choice("item:${it.item.id.value}", it.item.name) }))
        },
        parse = { json -> GameAction.UseKeyItem(ItemRef(string(json, "item"))) },
    ), PartyBagPlans.useKeyItem)

    val registerItem = ActionDefinition(GameAction.RegisterItem::class, spec(
        name = "register_item",
        description = "Register a key item you use often (Bicycle, rod...): the first registered item is then used with Y, without the bag.",
        parameters = listOf(Parameter("item", ParameterType.STRING, "The key item: its id or its name.")),
        modes = assisted,
        availability = { state ->
            val keys = state.bag.orEmpty().firstOrNull { it.name == "key_items" }?.items.orEmpty()
            if (!PartyBagPlans.inField(state) || keys.isEmpty()) Availability.Hidden
            else Availability.Available(mapOf("item" to keys.map { Choice("item:${it.item.id.value}", it.item.name + if (state.registeredItems.firstOrNull() == it.item.id) " (on Y)" else "") }))
        },
        parse = { json -> GameAction.RegisterItem(ItemRef(string(json, "item"))) },
    ), PartyBagPlans.registerItem)

    val fly = ActionDefinition(GameAction.Fly::class, spec(
        name = "fly",
        description = "Fly to a town already visited (needs a Pokémon knowing Fly and its badge; outdoors only).",
        parameters = listOf(Parameter("destination", ParameterType.STRING, "fly:<map id> as listed on the fly map, or the town's name.")),
        modes = assisted,
        availability = { state -> if (MovePlans.canWalk(state, hasWorld = true)) Availability.Available() else Availability.Hidden },
        parse = { json -> GameAction.Fly(string(json, "destination")) },
    ), FieldPlans.fly)

    /** Every common action, in the order they are listed to agents. */
    val definitions: List<ActionDefinition<*>> get() =
        listOf(advanceDialogue, choose, enterText, attack, switch, throwBall, learnMove, run, keepBattling, goTo, explore, interact, findEncounter, heal, fly, fish, buy, deposit, withdraw, reorderParty, useItem, giveItem, takeItem, teach, useKeyItem, registerItem, saveGame, press, touch, wait)

    // region Helpers

    private fun <A : GameAction> spec(
        name: String,
        description: String,
        parameters: List<Parameter>,
        modes: Set<ActionMode>,
        availability: (GameState) -> Availability,
        parse: (JsonObject) -> A,
        enumerate: (GameState) -> List<A> = { emptyList() },
    ): ActionSpec<A> = object : ActionSpec<A> {
        override val name = name
        override val description = description
        override val parameters = parameters
        override val modes = modes
        override fun availability(state: GameState) = availability(state)
        override fun parse(json: JsonObject) = parse(json)
        override fun enumerate(state: GameState) = enumerate(state)
    }

    private fun string(json: JsonObject, key: String): String =
        json[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw ActionException(ActionError.InvalidParameter(key, "missing"))

    private fun int(json: JsonObject, key: String, range: IntRange): Int {
        val value = json[key]?.jsonPrimitive?.intOrNull ?: throw ActionException(ActionError.InvalidParameter(key, "missing"))
        return value.also { check(it, key, range) }
    }

    private fun check(value: Int, key: String, range: IntRange) {
        if (value !in range) throw ActionException(ActionError.InvalidParameter(key, value.toString(), listOf("${range.first}..${range.last}")))
    }

    private fun targetChoices(state: GameState) = state.field?.objects.orEmpty()
        .filter { it.kind != FieldObjectKind.FOLLOWER }
        .map { Choice(it.id, "${it.label} at ${it.x},${it.y}") }

    private fun bool(json: JsonObject, key: String) = json[key]?.jsonPrimitive?.booleanOrNull ?: false

    private fun moveOptions(json: JsonObject) = MoveOptions(
        avoidTallGrass = bool(json, "avoid_tall_grass"),
        avoidTrainers = bool(json, "avoid_trainers"),
        acceptOneWay = bool(json, "accept_one_way"),
        run = bool(json, "run"),
    )

    private fun monChoices(state: GameState) = state.party.map { Choice(it.id.toString(), "${it.displayName} Lv${it.level}") }

    private fun monId(raw: String): MonId = MonId.parse(raw) ?: throw ActionException(ActionError.InvalidParameter("pokemon", raw))

    private fun mon(json: JsonObject, key: String): MonId = monId(string(json, key))

    private fun button(json: JsonObject): Button {
        val raw = string(json, "button")
        return Button.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw ActionException(ActionError.InvalidParameter("button", raw, Button.entries.map { it.name.lowercase() }))
    }

    private fun battler(raw: String): BattlerRef = BattlerRef.entries.firstOrNull { it.wire == raw }
        ?: throw ActionException(ActionError.InvalidParameter("target", raw, BattlerRef.entries.map { it.wire }))

    // endregion
}
