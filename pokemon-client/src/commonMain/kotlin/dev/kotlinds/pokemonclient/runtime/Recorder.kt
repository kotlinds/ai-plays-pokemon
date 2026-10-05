package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.EventLog
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.TextSource

/**
 * Watches the game frame by frame and turns what changes into [GameEvent]s in [log]: every text page shown,
 * every battle message, screen changes, Pokémon obtained / evolved / leveled up, items and badges received.
 *
 * Called by the app after emulated frames (agent actions and free play alike), so nothing shown between two
 * agent calls is lost. Decoding every frame isn't needed: text pages and messages stay on screen for many
 * frames, so [every] frames is enough.
 */
class Recorder(
    private val game: PokemonGame,
    val log: EventLog = EventLog(),
    private val every: Int = 2,
    /**
     * Whether species `from` can evolve into `to` (the game's evolution data), or null when the game can't tell:
     * only a species change to an evolution is an evolution, anything else is a torn reading.
     */
    private val evolvesInto: (from: SpeciesId, to: SpeciesId) -> Boolean? = { from, to ->
        game.data?.species(from)?.evolutions?.any { it.target == to }
    },
) {
    private var previous: GameState? = null
    private var lastText: String? = null
    private var lastBattleMessage: String? = null

    /** A battle message read once, recorded when the next poll reads it again (see [record]). */
    private var pendingBattleMessage: String? = null

    /**
     * The last valid reading of every Pokémon seen in the party (by id, kept after it leaves the party): party events
     * compare against it, never against a single previous frame, so a reading caught mid-rewrite or a Pokémon going
     * to the PC and back can't make up "reached level 89" or "obtained" events. Levels only go up.
     */
    private val known = mutableMapOf<MonId, PartyMon>()

    /** Every species each Pokémon was seen as (a Pokémon never evolves back: a return to one of them is a torn reading). */
    private val pastSpecies = mutableMapOf<MonId, MutableSet<SpeciesId>>()

    /** Readings with a party so far, up to [SEED_POLLS]. */
    private var partyPolls = 0

    /** Every Pokémon seen in the PC boxes (null before the first reading of the boxes), see [recordStorage]. */
    private var stored: MutableSet<MonId>? = null

    /** New box Pokémon read once, waiting for a second identical reading. */
    private val pendingStored = mutableMapOf<MonId, dev.kotlinds.pokemonclient.state.BoxMon>()

    /** True from a wild battle until the player walks again: a Pokémon obtained meanwhile was caught. */
    private var afterWildBattle = false

    /** Bag increases waiting for confirmation (by item id), see [recordItems]. */
    private val pendingItems = mutableMapOf<ItemId, PendingItem>()

    /** An increase of [delta] to [quantity] of [name] first seen at [since]. */
    private data class PendingItem(val name: String, val delta: Int, val quantity: Int, val since: Long)

    /** Bag increases first seen lately (item, name, delta, frame), for [recordShopBonus]. */
    private val recentIncreases = mutableListOf<RecentIncrease>()

    private data class RecentIncrease(val item: ItemId, val name: String, val delta: Int, val since: Long)

    /** First frame of the last shop message ("Here you are! Thank you!"): the clerk's bonus is added after it. */
    private var shopMessageStart: Long? = null

    /** When the game last changed in a way a player would see (text, menu, cursor, position, HP...). */
    val progress = ProgressClock()

    /** [ProgressClock.key] of the previous state. */
    private var previousKey: Any? = null

    /** The last state decoded by the recorder (null before the first one). */
    val latest: GameState? get() = previous

    /** Call after each emulated frame. */
    fun onFrame(frame: Long, memory: () -> Memory) {
        if (frame % every != 0L) return
        val state = runCatching { game.state(memory()) }.getOrNull() ?: return
        val lastSeq = log.lastSeq
        record(frame, previous, state)
        val key = ProgressClock.key(state)
        val recorded = log.lastSeq != lastSeq
        if (key != previousKey || recorded) {
            progress.progressed(if (recorded) log.since(lastSeq).filterIsInstance<GameEvent.TextShown>().lastOrNull()?.text?.replace('\n', ' ') else null)
        }
        previousKey = key
        previous = state
    }

    /** Records a human input (the human pressed something while watching). */
    fun humanInput(frame: Long) {
        log.append { GameEvent.HumanInput(it, frame) }
    }

    private fun record(frame: Long, before: GameState?, now: GameState) {
        // Text: a field / sign / phone page once it is fully printed (waiting for A, or for a fanfare like "ACE found
        // one PP Up!"), each battle message once.
        val screen = now.screen
        val message = now.battle?.message?.takeIf { it.isNotBlank() }
        if (screen is Screen.Dialogue && screen.awaiting != Awaiting.TEXT_PRINTING && screen.text != lastText && screen.text.isNotBlank() &&
            (screen.source != TextSource.BATTLE || screen.awaiting == Awaiting.INPUT && screen.text != message && screen.text != lastBattleMessage)
        ) {
            // In battle, only a message waiting for A that isn't the battle message: the battle bag / party screens'
            // own messages ("It won't have any effect.", "There's no will to battle!"), printed in their own buffer
            // (battle messages never wait for A: they are recorded below).
            lastText = screen.text
            log.append { GameEvent.TextShown(it, frame, screen.source, screen.speaker, screen.text) }
        }
        // A message an app shows and waits on (the clerk's "Here you are! Thank you!", the bonus line): once, like a
        // dialogue page. Not the panels that carry their own data (level-up stats, Pokédex page).
        val pressed = screen as? Screen.PressToContinue
        val pressedText = pressed?.text?.takeIf { it.isNotBlank() && pressed.reason in RECORDED_MESSAGES }
        if (pressedText != null && pressedText != lastText) {
            lastText = pressedText
            log.append { GameEvent.TextShown(it, frame, TextSource.MENU, null, pressedText) }
        }
        // A sign's text shows in a banner while the player can still walk (read with A, or walking into the sign).
        val banner = (screen as? Screen.Overworld)?.banner?.takeIf { it.isNotBlank() && screen.awaiting == Awaiting.INPUT }
        if (banner != null && banner != lastText) {
            lastText = banner
            log.append { GameEvent.TextShown(it, frame, TextSource.SIGN, null, banner) }
        }
        // A short transition (a fade while the box stays up) doesn't end the text: it isn't recorded twice. Nor does
        // the yes/no menu shown over that text: once answered, the box shows the question again for a few frames
        // (the nurse's "Would you like to rest your Pokémon?").
        // A script's multichoice menu is drawn over the box that keeps its question ("Which PC should be accessed?"):
        // once a choice is made the box shows it again, not a new text.
        val askingLastText = (screen is Screen.YesNo && lastText?.let { sameText(it, screen.question) } == true) ||
            (screen is Screen.ListMenu && screen.kind == dev.kotlinds.pokemonclient.state.MenuKind.MULTICHOICE && lastText != null)
        if (screen !is Screen.Dialogue && pressedText == null && banner == null && screen !is Screen.Animation && !askingLastText) lastText = null
        recordBattleMessage(frame, message)
        if (now.battle == null) lastBattleMessage = null

        if (before == null) {
            now.party.forEach { known[it.id] = it; pastSpecies.getOrPut(it.id) { mutableSetOf() } += it.species.id }
            return
        }
        val from = before.screen.kind
        val to = screen.kind
        if (from != to) log.append { GameEvent.ScreenChanged(it, frame, from, to) }

        // Party: new Pokémon, evolutions, level ups (by stable id, so reordering isn't a change).
        // A demo battle shows someone else's party and bag: nothing of it is the player's.
        if (now.battle?.kind == BattleKind.DEMO || before.battle?.kind == BattleKind.DEMO) return
        // A Pokémon obtained from a wild battle until the player walks again (Pokédex, nickname, PC transfer in
        // between) is a capture.
        if (now.battle?.kind == BattleKind.WILD) afterWildBattle = true
        else if (now.battle == null && screen is Screen.Overworld && screen.awaiting == Awaiting.INPUT) afterWildBattle = false
        recordParty(frame, now.party, inBattle = now.battle != null)
        recordStorage(frame, now)

        // Bag: quantities that went up, once they stay up for a moment (see [recordItems]).
        recordItems(frame, before, now)
        recordShopBonus(frame, before.screen, screen)

        // Pokégear cards (not bag items: never an item event).
        val oldCards = before.player?.pokegearCards
        val newCards = now.player?.pokegearCards
        if (oldCards != null && newCards != null) {
            newCards.filter { it !in oldCards }.forEach { card -> log.append { GameEvent.PokegearUpgraded(it, frame, card) } }
        }

        // Badges.
        val oldBadges = before.player?.badges.orEmpty().toSet()
        now.player?.badges.orEmpty().filter { it !in oldBadges }.forEach { badge ->
            if (before.player != null) log.append { GameEvent.BadgeReceived(it, frame, badge) }
        }
    }

    /** The same text, whatever its line breaks (a menu's question and the box's page are wrapped differently). */
    private fun sameText(a: String, b: String?): Boolean = b != null && a.split(WHITESPACE).filter { it.isNotEmpty() } == b.split(WHITESPACE).filter { it.isNotEmpty() }

    /**
     * Records each battle message once (from `bs->msgBuffer`, read every [every] frames). The emulated frame can end
     * while the game is still writing the buffer: such a half-written read ("The foe's MAGNETON fai") is always a
     * beginning of the next read. So a reading is recorded once it is read again unchanged, or once a reading that
     * doesn't continue it replaces it: a message is never lost because it was shown for a single poll (a turn whose
     * waits were cut short by a press), and a half-written read is never recorded.
     */
    private fun recordBattleMessage(frame: Long, message: String?) {
        val pending = pendingBattleMessage
        if (message != null && message == pending) {
            recordBattle(frame, message)
            pendingBattleMessage = null
            return
        }
        if (pending != null && (message == null || !message.startsWith(pending))) recordBattle(frame, pending)
        pendingBattleMessage = message?.takeIf { it != lastBattleMessage }
    }

    private fun recordBattle(frame: Long, message: String) {
        lastBattleMessage = message
        log.append { GameEvent.TextShown(it, frame, TextSource.BATTLE, null, message) }
    }

    /**
     * Items received: quantities that went up and are still up [ITEM_CONFIRM_FRAMES] frames later. During a battle
     * the game uses a copy of the bag and writes it back a frame after the battle ends: in between, the save's
     * older bag shows (balls thrown not yet taken out), which must not look like items received.
     */
    private fun recordItems(frame: Long, before: GameState, now: GameState) {
        val stacks = now.bag?.flatMap { it.items } ?: return
        val current = stacks.associate { it.item.id to it.quantity }
        val old = before.bag?.flatMap { it.items }?.associate { it.item.id to it.quantity }
        if (old != null) stacks.forEach { stack ->
            val delta = stack.quantity - (old[stack.item.id] ?: 0)
            if (delta > 0) {
                val pending = pendingItems[stack.item.id]
                pendingItems[stack.item.id] = PendingItem(stack.item.name, delta + (pending?.delta ?: 0), stack.quantity, pending?.since ?: frame)
            }
        }
        val iterator = pendingItems.iterator()
        while (iterator.hasNext()) {
            val (id, pending) = iterator.next()
            val quantity = current[id] ?: 0
            when {
                quantity < pending.quantity -> iterator.remove()
                frame - pending.since >= ITEM_CONFIRM_FRAMES -> {
                    log.append { GameEvent.ItemReceived(it, pending.since, pending.name, pending.delta) }
                    recentIncreases += RecentIncrease(id, pending.name, pending.delta, pending.since)
                    iterator.remove()
                }
            }
        }
    }

    /**
     * The clerk's bonus ([ContinueReason.SHOP_BONUS], a Premier Ball for 10 Poké Balls): when its message shows, the
     * items received since the purchase's "Here you are! Thank you!" started are the bonus (the game adds the bought
     * items before that message, the bonus when it's dismissed, src/overlay_03/shop_menu.c:978). Recorded as
     * [GameEvent.ShopBonus], after their [GameEvent.ItemReceived].
     */
    private fun recordShopBonus(frame: Long, before: Screen, now: Screen) {
        recentIncreases.removeAll { frame - it.since > RECENT_INCREASE_FRAMES }
        val reason = (now as? Screen.PressToContinue)?.reason
        val wasReason = (before as? Screen.PressToContinue)?.reason
        if (reason == ContinueReason.MESSAGE && wasReason != ContinueReason.MESSAGE) shopMessageStart = frame
        if (reason != ContinueReason.SHOP_BONUS || wasReason == ContinueReason.SHOP_BONUS) return
        val start = shopMessageStart ?: return
        // A bonus still pending (not yet lasted long enough) is the bonus too.
        val pending = pendingItems.map { (id, p) -> RecentIncrease(id, p.name, p.delta, p.since) }
        (recentIncreases + pending).filter { it.since >= start }.groupBy { it.item }.forEach { (id, increases) ->
            log.append { GameEvent.ShopBonus(it, frame, increases.first().name, increases.sumOf { i -> i.delta }, id) }
        }
        shopMessageStart = null
    }

    /**
     * Party events from [party] (the state's party: only valid readings, see HgssStateMapper), against [known]: a
     * Pokémon never seen before was obtained, a new species is an evolution, a level above the highest seen is a
     * level up. Two "new" Pokémon are only a torn reading and are left out: one with the personality of a known
     * Pokémon but another trainer id (a personality is unique), and an Egg in battle (Eggs never come from battles).
     */
    private fun recordParty(frame: Long, party: List<PartyMon>, inBattle: Boolean) {
        // Right after a save is loaded, the party becomes readable slot by slot: the first readings only teach the
        // recorder who is there (nobody can be obtained within a second of loading).
        if (party.isNotEmpty() && partyPolls < SEED_POLLS) {
            partyPolls++
            party.forEach { mon ->
                known[mon.id] = known[mon.id]?.takeIf { it.level > mon.level }?.let { mon.copy(level = it.level) } ?: mon
                pastSpecies.getOrPut(mon.id) { mutableSetOf() } += mon.species.id
            }
            return
        }
        for (mon in party) {
            val was = known[mon.id]
            if (was == null && isTornReading(mon, inBattle)) continue
            when {
                // Withdrawn from the PC: not a new Pokémon.
                was == null && stored?.contains(mon.id) == true -> Unit
                was == null -> if (known.isNotEmpty() || party.size == 1) {
                    log.append { GameEvent.PokemonObtained(it, frame, mon.id, mon.displayName) }
                    if (afterWildBattle) log.append { GameEvent.Caught(it, frame, mon.id, mon.species.name, mon.displayName, mon.level) }
                }
                was.species != mon.species && !mon.isEgg && !was.isEgg -> {
                    // Only a real evolution counts; any other species change (a reading caught while the game
                    // rewrites the Pokémon, an evolution scene showing the old species again) is ignored, and the
                    // last valid species is kept.
                    if (!isEvolution(mon.id, was, mon)) continue
                    log.append { GameEvent.Evolved(it, frame, mon.id, was.species.name, mon.species.name) }
                }
                mon.level > was.level -> log.append { GameEvent.LevelUp(it, frame, mon.id, mon.level, mon.displayName) }
            }
            // A new moveset is only taken once read the same twice (see [recordMoves]); until then the known one stays.
            val moves = if (was != null && !mon.isEgg && !was.isEgg) recordMoves(frame, was, mon) else mon.moves
            pastSpecies.getOrPut(mon.id) { mutableSetOf() } += mon.species.id
            known[mon.id] = (if (was != null && mon.level < was.level) mon.copy(level = was.level) else mon).let { if (it.moves == moves) it else it.copy(moves = moves) }
        }
    }

    /**
     * Moves [now] knows that it didn't know as [was]: learned, replacing the one that is gone (if any). Returns the
     * moves to remember. A different set of moves must read the same on two polls in a row before it counts: a reading
     * caught while the game rewrites the Pokémon (the nurse restoring PP) can pass every check by accident, and must
     * not make up "learned MOVE_57918, forgot Strength" (then the reverse).
     */
    private fun recordMoves(frame: Long, was: PartyMon, now: PartyMon): List<dev.kotlinds.pokemonclient.state.KnownMove> {
        if (was.moves.isEmpty() || now.moves.isEmpty()) return now.moves
        val before = was.moves.map { it.move.id }.toSet()
        val after = now.moves.map { it.move.id }.toSet()
        if (before == after) {
            pendingMoves.remove(now.id)
            return now.moves
        }
        val ids = now.moves.map { it.move.id }
        if (pendingMoves[now.id] != ids) {
            pendingMoves[now.id] = ids
            return was.moves
        }
        pendingMoves.remove(now.id)
        val forgotten = was.moves.filter { it.move.id !in after }.toMutableList()
        now.moves.filter { it.move.id !in before }.forEach { learned ->
            val forgot = forgotten.removeFirstOrNull()
            log.append { GameEvent.LearnedMove(it, frame, now.id, now.displayName, learned.move.name, forgot?.move?.name) }
        }
        return now.moves
    }

    /** A new set of moves read once per Pokémon, waiting for a second identical reading (see [recordMoves]). */
    private val pendingMoves = mutableMapOf<MonId, List<dev.kotlinds.pokemonclient.state.MoveId>>()

    /**
     * New Pokémon in the PC boxes that were never in the party: the game sent them there (a capture or a gift with a
     * full party). A Pokémon deposited from the party isn't one. A new one must read the same on two polls (a box
     * slot caught mid-write never makes an event), and more than [MAX_NEW_STORED] at once is another save being
     * loaded, not something received.
     */
    private fun recordStorage(frame: Long, now: GameState) {
        val storage = now.storage ?: return
        val seen = stored ?: run {
            stored = storage.mons.map { it.id }.toMutableSet()
            return
        }
        val fresh = storage.mons.filter { it.id !in seen }
        pendingStored.keys.retainAll(fresh.map { it.id }.toSet())
        if (fresh.size > MAX_NEW_STORED) {
            seen += fresh.map { it.id }
            return
        }
        for (mon in fresh) {
            if (mon.id in known) {
                seen += mon.id
                continue
            }
            if (pendingStored.put(mon.id, mon) != mon) continue
            seen += mon.id
            pendingStored.remove(mon.id)
            val boxName = storage.boxes.firstOrNull { it.index == mon.box }?.name ?: "BOX ${mon.box + 1}"
            log.append { GameEvent.SentToBox(it, frame, mon.id, mon.displayName, mon.box, boxName) }
            if (afterWildBattle) log.append { GameEvent.Caught(it, frame, mon.id, mon.species.name, mon.displayName, mon.level, mon.box, boxName) }
        }
    }

    /**
     * True when [now] is an evolution of [was]: never back to a species this Pokémon already was, and, when the game
     * knows its evolutions, to one of the species [was] evolves into.
     */
    private fun isEvolution(id: MonId, was: PartyMon, now: PartyMon): Boolean {
        if (now.species.id in pastSpecies[id].orEmpty()) return false
        return evolvesInto(was.species.id, now.species.id) ?: true
    }

    /** A "new" Pokémon that can't be one (see [recordParty]): a personality is unique, Eggs never come from battles. */
    private fun isTornReading(mon: PartyMon, inBattle: Boolean): Boolean =
        (mon.isEgg && inBattle) || known.keys.any { it.personality == mon.id.personality && it != mon.id }
}

/** Spaces and line breaks, for [Recorder] text comparisons. */
private val WHITESPACE = Regex("\\s+")

/** The [Screen.PressToContinue] messages recorded as text (the others are panels with their own data). */
private val RECORDED_MESSAGES = setOf(ContinueReason.MESSAGE, ContinueReason.SHOP_BONUS)

/** Readings with a party during which the recorder only learns the party (see [Recorder.recordParty]). */
internal const val SEED_POLLS = 30

/** More new boxed Pokémon than this at once is another save loaded, not a Pokémon sent to the PC. */
private const val MAX_NEW_STORED = 2

/** How long a bag increase is remembered for [Recorder.recordShopBonus] (the bonus message prints for ~45 frames). */
private const val RECENT_INCREASE_FRAMES = 300L

/** Frames a bag increase must last before it is recorded as items received. */
private const val ITEM_CONFIRM_FRAMES = 8L

/** A short name for the kind of screen, for [GameEvent.ScreenChanged] and logs. */
val Screen.kind: String
    get() = when (this) {
        is Screen.YesNo -> "yes_no"
        is Screen.ListMenu -> "menu:${kind.name.lowercase()}"
        is Screen.BattleCommand -> "battle_command"
        is Screen.MoveSelect -> "move_select:${context.name.lowercase()}"
        is Screen.TargetSelect -> "target_select"
        is Screen.PartyGrid -> "party:${purpose.name.lowercase()}"
        is Screen.ContextMenu -> "context_menu"
        is Screen.Bag -> "bag"
        is Screen.Keyboard -> "keyboard"
        is Screen.PcBox -> "pc_box"
        is Screen.Shop -> "shop"
        is Screen.FlyMap -> "fly_map"
        is Screen.Quantity -> "quantity"
        is Screen.PressToContinue -> "press_to_continue:${reason.name.lowercase()}"
        is Screen.Dialogue -> "dialogue:${source.name.lowercase()}"
        is Screen.Evolution -> "evolution"
        is Screen.StarterChoice -> "starter_choice"
        is Screen.Animation -> "animation:${kind.name.lowercase()}"
        is Screen.Overworld -> "overworld"
        is Screen.Battle -> "battle"
        is Screen.Intro -> "intro"
        is Screen.Viewer -> "viewer:${app.name.lowercase()}"
        is Screen.Unknown -> "unknown"
    }
