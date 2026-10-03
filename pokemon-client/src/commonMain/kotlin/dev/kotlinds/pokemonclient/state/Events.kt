package dev.kotlinds.pokemonclient.state

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Something that happened in the game, recorded frame by frame while actions (or the human) play.
 *
 * Agents get the events since their last call: nothing shown between two calls is lost (whole dialogues,
 * battle messages, phone calls...).
 */
sealed interface GameEvent {
    /** Position in the log, increasing. */
    val seq: Long

    /** Frame at which it was seen. */
    val frame: Long

    /** A text box page or battle message was shown. */
    data class TextShown(
        override val seq: Long,
        override val frame: Long,
        val source: TextSource,
        val speaker: String?,
        val text: String,
    ) : GameEvent

    /** The screen changed kind (overworld → dialogue, battle → party grid...). */
    data class ScreenChanged(override val seq: Long, override val frame: Long, val from: String, val to: String) : GameEvent

    /** A Pokémon joined the party (capture, gift, hatch, trade). */
    data class PokemonObtained(override val seq: Long, override val frame: Long, val mon: MonId, val species: String) : GameEvent

    /** A Pokémon evolved. */
    data class Evolved(override val seq: Long, override val frame: Long, val mon: MonId, val from: String, val to: String) : GameEvent

    /** A Pokémon gained a level. */
    data class LevelUp(override val seq: Long, override val frame: Long, val mon: MonId, val level: Int) : GameEvent

    /** Items were added to the bag. */
    data class ItemReceived(override val seq: Long, override val frame: Long, val item: String, val quantity: Int) : GameEvent

    /** A badge was obtained. */
    data class BadgeReceived(override val seq: Long, override val frame: Long, val badge: String) : GameEvent

    /** The human pressed buttons or touched the screen. */
    data class HumanInput(override val seq: Long, override val frame: Long) : GameEvent
}

/**
 * The log of [GameEvent]s, read by any number of consumers, each keeping its own position ([since]).
 *
 * Written by the console thread (the [dev.kotlinds.pokemonclient.runtime.Recorder]) and read from others (the MCP
 * server...): the events are kept in an immutable list swapped atomically, so readers never see a partial update.
 * Bounded: the oldest events are dropped beyond [capacity].
 */
@OptIn(ExperimentalAtomicApi::class)
class EventLog(private val capacity: Int = 10_000) {
    private val events = AtomicReference<List<GameEvent>>(emptyList())

    /** Sequence number of the last event (0 when empty). */
    val lastSeq: Long get() = events.load().lastOrNull()?.seq ?: dropped

    private var dropped = 0L

    /** Appends an event built from its sequence number (single writer: the console thread). */
    fun append(build: (seq: Long) -> GameEvent): GameEvent {
        val current = events.load()
        val event = build((current.lastOrNull()?.seq ?: dropped) + 1)
        val next = if (current.size >= capacity) current.drop(current.size - capacity + 1) + event else current + event
        if (next.size < current.size + 1) dropped = next.first().seq - 1
        events.store(next)
        return event
    }

    /** Events after [seq] (exclusive), oldest first. */
    fun since(seq: Long): List<GameEvent> = events.load().filter { it.seq > seq }
}
