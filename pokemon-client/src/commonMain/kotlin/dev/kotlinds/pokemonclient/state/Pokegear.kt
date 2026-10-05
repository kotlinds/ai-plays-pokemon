package dev.kotlinds.pokemonclient.state

import dev.kotlinds.pokemonclient.console.TouchPoint

/**
 * A radio programme of the Pokégear, by its game id (HGSS `RADIO_STATION_*`, include/constants/radio_station.h).
 * [wire] is the language-independent id agents use (`tune_radio station:poke_flute`).
 */
enum class RadioStation {
    POKEMON_MUSIC,
    POKEMON_TALK,
    POKEMON_SEARCH_PARTY,
    SERIAL_RADIO_DRAMA,
    BUENAS_PASSWORD,
    TRAINER_PROFILES,
    THAT_TOWN_THESE_PEOPLE,

    /** Kanto with the Expansion Card: wakes the sleeping Snorlax of Route 11 (talk to it while it plays). */
    POKE_FLUTE,

    /** Only in the Ruins of Alph. */
    UNOWN,

    /** The whole dial during Team Rocket's Radio Tower takeover. */
    TEAM_ROCKET,

    /** The whole dial around Mahogany Town until the Rocket hideout is cleared. */
    MAHOGANY_SIGNAL,

    /** Ads between two programmes. */
    COMMERCIALS,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(raw: String): RadioStation? = entries.firstOrNull { it.wire == raw.lowercase().removePrefix("station:") }
    }
}

/** Which stations the dial receives where the player is (HGSS `RadioStationSelection`). */
enum class RadioBand {
    JOHTO,
    KANTO,

    /** Kanto with the Expansion Card: adds two channels, among them the Poké Flute. */
    KANTO_EXPANSION,

    /** No signal here (caves, buildings that block it, Kanto before the Power Plant is restored). */
    NO_SIGNAL,
    RUINS_OF_ALPH,
    TEAM_ROCKET,
    MAHOGANY,
}

/**
 * One channel of the radio dial: the [stations] it airs (several when the programme depends on the hour), the point
 * of the dial for a clear signal ([touch], within [clearRadius] pixels), static up to [radius] pixels. [preset]: the
 * button beside the dial that tunes it in one touch, when there is one (else the dial's cursor must be dragged).
 */
data class RadioChannel(
    val index: Int,
    val stations: List<RadioStation>,
    val touch: TouchPoint,
    val clearRadius: Int,
    val radius: Int,
    val preset: TouchPoint? = null,
)

/**
 * The Pokégear radio: the [band] received here and its [channels], the [cursor] on the dial, the channel tuned
 * ([tuned], an index of [channels], null between channels), whether the signal is [clear] (else static), what airs
 * ([station], [title], [host], the last [line] printed) and whether the cursor is on the app bar ([inAppBar]).
 * [playing]: the radio programme whose music plays (it goes on after the Pokégear is closed).
 */
data class PokegearRadio(
    val band: RadioBand,
    val channels: List<RadioChannel>,
    val cursor: TouchPoint,
    val tuned: Int?,
    val clear: Boolean,
    val station: RadioStation?,
    val title: String? = null,
    val host: String? = null,
    val line: String? = null,
    val inAppBar: Boolean = false,
    val playing: RadioStation? = null,
)

/** A card the Pokégear can gain (HGSS: the Map Card, the Radio Card, the Expansion Card for Kanto's stations). */
enum class PokegearCard { MAP, RADIO, EXPANSION }

/** An item Mom bought with the money she saves, waiting at the delivery man of any Poké Mart (talk to him). */
data class MomParcel(val item: Named<ItemId>, val quantity: Int)
