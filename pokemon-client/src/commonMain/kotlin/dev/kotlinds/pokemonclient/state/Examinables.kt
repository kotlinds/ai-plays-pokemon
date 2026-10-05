package dev.kotlinds.pokemonclient.state

/**
 * Something on the map that answers A but that the game doesn't draw as a person or an object: an invisible map
 * object with a script (the Machine Part "by the pool" of the Cerulean Gym). It stands on its tile like a wall.
 *
 * Whether an agent may know about it is a knowledge question (the views decide): with a walkthrough it is listed with
 * what it is; without, only when the game shows a [cue] on its tile, and then only as "something to examine";
 * otherwise it stays unknown, like a hidden item.
 */
data class FieldExaminable(
    /** Stable, language-independent id: `examine:<event id>` (the map's local id of the object). */
    val id: String,
    /** What it is, for an agent allowed to know (display only): the item it gives, or "something to examine". */
    val label: String,
    val kind: ExaminableKind,
    val x: Int,
    val y: Int,
    /** For an [ExaminableKind.ITEM]: the item examining it gives. */
    val item: Named<ItemId>? = null,
    /** True when the game draws something on its tile that invites a look; false when nothing shows it is there. */
    val cue: Boolean = false,
)

/** What examining a [FieldExaminable] does. */
enum class ExaminableKind {
    /** Examining it picks up an item (its script gives one). */
    ITEM,

    /** Examining it runs a script: a message, a scene... (something to examine). */
    SPOT,
}
