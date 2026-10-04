package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.EventLog
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.Screen
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

    /** Readings with a party so far, up to [SEED_POLLS]. */
    private var partyPolls = 0

    /** Bag increases waiting for confirmation (by item id), see [recordItems]. */
    private val pendingItems = mutableMapOf<ItemId, PendingItem>()

    /** An increase of [delta] to [quantity] of [name] first seen at [since]. */
    private data class PendingItem(val name: String, val delta: Int, val quantity: Int, val since: Long)

    /** The last state decoded by the recorder (null before the first one). */
    val latest: GameState? get() = previous

    /** Call after each emulated frame. */
    fun onFrame(frame: Long, memory: () -> Memory) {
        if (frame % every != 0L) return
        val state = runCatching { game.state(memory()) }.getOrNull() ?: return
        record(frame, previous, state)
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
        if (screen is Screen.Dialogue && screen.source != TextSource.BATTLE && screen.awaiting != Awaiting.TEXT_PRINTING && screen.text != lastText) {
            lastText = screen.text
            log.append { GameEvent.TextShown(it, frame, screen.source, screen.speaker, screen.text) }
        }
        // A sign's text shows in a banner while the player can still walk (read with A, or walking into the sign).
        val banner = (screen as? Screen.Overworld)?.banner?.takeIf { it.isNotBlank() && screen.awaiting == Awaiting.INPUT }
        if (banner != null && banner != lastText) {
            lastText = banner
            log.append { GameEvent.TextShown(it, frame, TextSource.SIGN, null, banner) }
        }
        // A short transition (a fade while the box stays up) doesn't end the text: it isn't recorded twice.
        if (screen !is Screen.Dialogue && banner == null && screen !is Screen.Animation) lastText = null
        // A battle message is recorded once two polls in a row read the same text: the emulated frame can end while
        // the game is still writing its message buffer, and such a half-written read ("The foe's MAGNETON fai") is
        // never read twice. Messages stay on screen far longer than two polls.
        val message = now.battle?.message?.takeIf { it.isNotBlank() }
        if (message != null && message != lastBattleMessage) {
            if (message == pendingBattleMessage) {
                lastBattleMessage = message
                pendingBattleMessage = null
                log.append { GameEvent.TextShown(it, frame, TextSource.BATTLE, null, message) }
            } else {
                pendingBattleMessage = message
            }
        } else {
            pendingBattleMessage = null
        }
        if (now.battle == null) lastBattleMessage = null

        if (before == null) {
            now.party.forEach { known[it.id] = it }
            return
        }
        val from = before.screen.kind
        val to = screen.kind
        if (from != to) log.append { GameEvent.ScreenChanged(it, frame, from, to) }

        // Party: new Pokémon, evolutions, level ups (by stable id, so reordering isn't a change).
        // A demo battle shows someone else's party and bag: nothing of it is the player's.
        if (now.battle?.kind == BattleKind.DEMO || before.battle?.kind == BattleKind.DEMO) return
        recordParty(frame, now.party, inBattle = now.battle != null)

        // Bag: quantities that went up, once they stay up for a moment (see [recordItems]).
        recordItems(frame, before, now)

        // Badges.
        val oldBadges = before.player?.badges.orEmpty().toSet()
        now.player?.badges.orEmpty().filter { it !in oldBadges }.forEach { badge ->
            if (before.player != null) log.append { GameEvent.BadgeReceived(it, frame, badge) }
        }
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
                    iterator.remove()
                }
            }
        }
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
            party.forEach { mon -> known[mon.id] = known[mon.id]?.takeIf { it.level > mon.level }?.let { mon.copy(level = it.level) } ?: mon }
            return
        }
        for (mon in party) {
            val was = known[mon.id]
            if (was == null && isTornReading(mon, inBattle)) continue
            when {
                was == null -> if (known.isNotEmpty() || party.size == 1) {
                    log.append { GameEvent.PokemonObtained(it, frame, mon.id, mon.displayName) }
                }
                was.species != mon.species && !mon.isEgg && !was.isEgg ->
                    log.append { GameEvent.Evolved(it, frame, mon.id, was.species.name, mon.species.name) }
                mon.level > was.level -> log.append { GameEvent.LevelUp(it, frame, mon.id, mon.level, mon.displayName) }
            }
            known[mon.id] = if (was != null && mon.level < was.level) mon.copy(level = was.level) else mon
        }
    }

    /** A "new" Pokémon that can't be one (see [recordParty]): a personality is unique, Eggs never come from battles. */
    private fun isTornReading(mon: PartyMon, inBattle: Boolean): Boolean =
        (mon.isEgg && inBattle) || known.keys.any { it.personality == mon.id.personality && it != mon.id }
}

/** Readings with a party during which the recorder only learns the party (see [Recorder.recordParty]). */
internal const val SEED_POLLS = 30

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
        is Screen.Unknown -> "unknown"
    }
