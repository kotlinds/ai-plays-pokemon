package dev.kotlinds.pokemonclient.state

/**
 * What kind of thing a [Blocker] is, when it is a known mechanism (typed, for agents and tests; the [Blocker.reason]
 * says the same in words).
 */
sealed interface BlockerCause {
    /**
     * A locked door standing on its tiles: it opens when talked to (A) once the player knows its password(s).
     * [from] are the targets who give them (trainers telling it once beaten, a Pokémon repeating it); [known] is true
     * once the game counts the password as heard, so talking to the door opens it now.
     */
    data class PasswordDoor(val from: List<String>, val known: Boolean) : BlockerCause

    /** A Pokémon standing in the way, battled with A: it leaves once it faints (or is caught). */
    data class WildPokemon(val speciesId: Int) : BlockerCause

    /**
     * A Pokémon asleep in the way: it wakes when talked to (A) while the radio plays [station] (the Pokégear radio,
     * `tune_radio`), then battles; it leaves once it faints (or is caught).
     */
    data class SleepingPokemon(val speciesId: Int, val station: RadioStation) : BlockerCause
}
