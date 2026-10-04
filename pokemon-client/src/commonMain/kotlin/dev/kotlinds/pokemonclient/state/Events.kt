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

    /** A Pokémon gained a level ([name]: its nickname or species, for display). */
    data class LevelUp(override val seq: Long, override val frame: Long, val mon: MonId, val level: Int, val name: String? = null) : GameEvent

    /** Items were added to the bag. */
    data class ItemReceived(override val seq: Long, override val frame: Long, val item: String, val quantity: Int) : GameEvent

    /** A badge was obtained. */
    data class BadgeReceived(override val seq: Long, override val frame: Long, val badge: String) : GameEvent

    /** The human pressed buttons or touched the screen. */
    data class HumanInput(override val seq: Long, override val frame: Long) : GameEvent

    /**
     * A wild Pokémon was caught: it joined the party, or went to PC box [box] (0-based, named [boxName]) when the
     * party was full. [name] is its nickname if it got one, else its species.
     */
    data class Caught(
        override val seq: Long,
        override val frame: Long,
        val mon: MonId,
        val species: String,
        val name: String,
        val level: Int?,
        val box: Int? = null,
        val boxName: String? = null,
    ) : GameEvent

    /** The game put a new Pokémon in PC box [box] (0-based) by itself: a capture or a gift with a full party. */
    data class SentToBox(override val seq: Long, override val frame: Long, val mon: MonId, val name: String, val box: Int, val boxName: String) : GameEvent

    /** A Pokémon learned [move] (level up, TM / HM, tutor), forgetting [forgot] when it already knew four. */
    data class LearnedMove(override val seq: Long, override val frame: Long, val mon: MonId, val name: String, val move: String, val forgot: String? = null) : GameEvent

    /**
     * The clerk added [quantity] [item] to a purchase as a bonus (a Premier Ball for 10 Poké Balls bought at once),
     * recorded when the bonus message shows, after the [ItemReceived] of that item.
     */
    data class ShopBonus(override val seq: Long, override val frame: Long, val item: String, val quantity: Int, val itemId: ItemId? = null) : GameEvent
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

    /**
     * Appends an event built from its sequence number. Safe from several threads (the console thread records, the UI
     * thread notes human inputs): the list is swapped with a compare-and-set, retried when another append came first,
     * so no event is ever overwritten by a concurrent one.
     */
    fun append(build: (seq: Long) -> GameEvent): GameEvent {
        while (true) {
            val current = events.load()
            val base = current.lastOrNull()?.seq ?: dropped
            val event = build(base + 1)
            val next = if (current.size >= capacity) current.drop(current.size - capacity + 1) + event else current + event
            if (!events.compareAndSet(current, next)) continue
            if (next.size < current.size + 1) dropped = next.first().seq - 1
            return event
        }
    }

    /** Events after [seq] (exclusive), oldest first. */
    fun since(seq: Long): List<GameEvent> = events.load().filter { it.seq > seq }
}
