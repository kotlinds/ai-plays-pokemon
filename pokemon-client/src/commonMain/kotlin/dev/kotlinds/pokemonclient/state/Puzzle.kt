package dev.kotlinds.pokemonclient.state

/**
 * The live state of a map puzzle that changes where the player can walk: switches, the barriers they open and
 * close, and the teleports (warp pads, cart rides) usable right now. Read from the game's variables, flags and
 * puzzle data in RAM (never from the screen); games fill it for the maps they know.
 *
 * Movement actions route with it: closed [barriers] block their tiles and [teleports] are edges of the route. Agents
 * read it to know which switch to press: every id is language-independent (`switch:N`, `shutter:N`, `teleport:N`,
 * `cart:N`), and switches name the target to `interact` with (`sign:N`).
 */
data class PuzzleState(
    /** The main mechanic of this map. */
    val kind: PuzzleKind,
    /** The rule of the puzzle, written for the agent (our words, not the game's text). */
    val rule: String,
    val switches: List<PuzzleSwitch> = emptyList(),
    val barriers: List<PuzzleBarrier> = emptyList(),
    /** Teleports usable right now (their trigger is active, the cart is there...). */
    val teleports: List<PuzzleTeleport> = emptyList(),
)

/** Kinds of [PuzzleState]. */
enum class PuzzleKind {
    /** Switches that open and close shutters (Goldenrod Tunnel B2F, under the Radio Tower). */
    SHUTTER_SWITCHES,

    /** Spinarak carts ridden between stations, their routes set by levers (Azalea Gym). */
    CART_RIDES,

    /** Tiles that warp the player elsewhere on the same map (Blackthorn Gym exits, Team Rocket HQ trap). */
    TELEPORT_PADS,
}

/** A tile of the current map, in the coordinates of [FieldState.x] / [FieldState.y]. */
data class PuzzleTile(val x: Int, val y: Int)

/** A switch or lever, pressed with A (`interact` with one of [targets]). */
data class PuzzleSwitch(
    /** Stable id: `switch:N` (N = the game's switch number). */
    val id: String,
    /** Targets that press this switch (`sign:N`: examined with A, from the tile in front of it). */
    val targets: List<String>,
    /** Where the switches are (one per target). */
    val tiles: List<PuzzleTile>,
    /** For levers that keep a position: true when flipped from their initial position. Null for push buttons. */
    val flipped: Boolean? = null,
    /** What pressing it changes: ids of [PuzzleBarrier]s it toggles, or of the cart stations whose route it sets. */
    val toggles: List<String> = emptyList(),
    /** Works once only (stays pressed). */
    val oneShot: Boolean = false,
    /** Already used, for [oneShot] switches. */
    val used: Boolean = false,
)

/** A shutter or gate: its [tiles] are blocked while it is closed. */
data class PuzzleBarrier(
    /** Stable id: `shutter:N` (N = the game's gate number). */
    val id: String,
    val open: Boolean,
    val tiles: List<PuzzleTile>,
)

/** Stepping on one of [from] takes the player to [to] (same map). */
data class PuzzleTeleport(
    /** Stable id: `teleport:N` (the map trigger N) or `cart:N` (the cart station N it starts from). */
    val id: String,
    val kind: TeleportKind,
    /** Tiles that start it. */
    val from: List<PuzzleTile>,
    /** Where the player stands once it is over. */
    val to: PuzzleTile,
)

/** Kinds of [PuzzleTeleport]. */
enum class TeleportKind {
    /** A warp pad / trap tile running a warp script. */
    PAD,

    /** A cart ride (the cart moves to the arrival station). */
    CART_RIDE,
}
