package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.EventLog
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
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
        // Text: a field / sign / phone page once it is fully printed, each battle message once.
        val screen = now.screen
        if (screen is Screen.Dialogue && screen.source != TextSource.BATTLE && screen.awaiting == Awaiting.INPUT && screen.text != lastText) {
            lastText = screen.text
            log.append { GameEvent.TextShown(it, frame, screen.source, screen.speaker, screen.text) }
        }
        if (screen !is Screen.Dialogue) lastText = null
        val message = now.battle?.message?.takeIf { it.isNotBlank() }
        if (message != null && message != lastBattleMessage) {
            lastBattleMessage = message
            log.append { GameEvent.TextShown(it, frame, TextSource.BATTLE, null, message) }
        }
        if (now.battle == null) lastBattleMessage = null

        if (before == null) return
        val from = before.screen.kind
        val to = screen.kind
        if (from != to) log.append { GameEvent.ScreenChanged(it, frame, from, to) }

        // Party: new Pokémon, evolutions, level ups (by stable id, so reordering isn't a change).
        val old = before.party.associateBy { it.id }
        for (mon in now.party) {
            val was = old[mon.id]
            when {
                was == null -> if (before.party.isNotEmpty() || now.party.size == 1) {
                    log.append { GameEvent.PokemonObtained(it, frame, mon.id, mon.displayName) }
                }
                was.species != mon.species && !mon.isEgg && !was.isEgg ->
                    log.append { GameEvent.Evolved(it, frame, mon.id, was.species.name, mon.species.name) }
                mon.level > was.level -> log.append { GameEvent.LevelUp(it, frame, mon.id, mon.level) }
            }
        }

        // Bag: quantities that went up.
        val oldItems = before.bag?.flatMap { it.items }?.associate { it.item.id to it.quantity }
        if (oldItems != null) {
            now.bag?.flatMap { it.items }?.forEach { stack ->
                val delta = stack.quantity - (oldItems[stack.item.id] ?: 0)
                if (delta > 0) log.append { GameEvent.ItemReceived(it, frame, stack.item.name, delta) }
            }
        }

        // Badges.
        val oldBadges = before.player?.badges.orEmpty().toSet()
        now.player?.badges.orEmpty().filter { it !in oldBadges }.forEach { badge ->
            if (before.player != null) log.append { GameEvent.BadgeReceived(it, frame, badge) }
        }
    }
}

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
        is Screen.Animation -> "animation:${kind.name.lowercase()}"
        is Screen.Overworld -> "overworld"
        is Screen.Battle -> "battle"
        is Screen.Intro -> "intro"
        is Screen.Unknown -> "unknown"
    }
