package dev.kotlinds.pokemonclient.state

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.MovingPlatforms

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
    /** Visible state of the map that doesn't change where one walks by itself (a lift's floor, candles, a waterfall). */
    val indicators: List<PuzzleIndicator> = emptyList(),
    /** Tiles whose floor is a moving part (a lift platform): their walkable heights, replacing the map's. */
    val surfaces: List<PuzzleSurface> = emptyList(),
    /**
     * A mechanism of this map that routes don't model (moving platforms, invisible walls...), in our words; null when
     * everything that changes the way is modeled. A failed route on this map may come from it.
     */
    val unmodeled: String? = null,
    /** Moving platforms (the Blackthorn Gym's platforms on the lava): where they are and what their trigger tiles do. */
    val platforms: List<PuzzlePlatform> = emptyList(),
    /**
     * The platforms' rules, for route planning ([dev.kotlinds.pokemonclient.world.PlatformPlanner]): `go_to` rides
     * them by itself. Null on maps without moving platforms.
     */
    val mechanics: MovingPlatforms? = null,
    /** Pokémon to herd and catch (the Ilex Forest Farfetch'd): where each is and a plan to catch it. */
    val herds: List<PuzzleHerd> = emptyList(),
    /** Boulders to push into their hole (Ice Path B1F): each one drops to the floor below, where it stops slides. */
    val boulderHoles: List<PuzzleBoulderHole> = emptyList(),
)

/**
 * A Strength boulder ([boulder], `person:N`) that drops through [hole] when pushed onto it, landing on the floor below
 * ([fallen] once it did). Other boulders can't fill that hole.
 */
data class PuzzleBoulderHole(val boulder: String, val hole: PuzzleTile, val fallen: Boolean)

/** Kinds of [PuzzleState]. */
enum class PuzzleKind {
    /** Switches that open and close shutters (Goldenrod Tunnel B2F, under the Radio Tower). */
    SHUTTER_SWITCHES,

    /** Spinarak carts ridden between stations, their routes set by levers (Azalea Gym). */
    CART_RIDES,

    /** Tiles that warp the player elsewhere on the same map (Blackthorn Gym exits, Team Rocket HQ trap). */
    TELEPORT_PADS,

    /** A lift between two floors, started by stepping on its center (Violet Gym). */
    LIFT,

    /** An invisible floor over pits that send the player back to the entrance (Ecruteak Gym). */
    HIDDEN_FLOOR,

    /** A winch that stops a waterfall, which the leader trains under until then (Cianwood Gym). */
    WATERFALL_WINCH,

    /** Two hidden switches in trash cans, each opening an electric gate (Vermilion Gym). */
    TRASH_CAN_SWITCHES,

    /** Only a mechanism the routes don't model ([PuzzleState.unmodeled]). */
    UNMODELED,
    /** Platforms on the lava that turn or slide when the player steps on their trigger tiles (Blackthorn Gym). */
    MOVING_PLATFORMS,

    /**
     * Pokémon that run away when approached from the wrong side and turn towards twigs the player steps on; caught
     * by talking to them from behind (Ilex Forest Farfetch'd).
     */
    HERDING,

    /** Strength boulders to push into holes so that they land on the floor below (Ice Path B1F). */
    BOULDER_HOLES,
}

/**
 * A Pokémon to herd ([PuzzleKind.HERDING]): talking to it from a side makes it run to another spot, stepping on an
 * active twig makes it turn towards the noise; from the right spot, once it looks away, talking to it from behind
 * catches it.
 */
data class PuzzleHerd(
    /** The map object: `person:N`. */
    val id: String,
    val at: PuzzleTile,
    /** True when it looks away so that talking to it from behind (at its catch spot) catches it. */
    val blindSpot: Boolean,
    /** The twigs around it (coordinate triggers `trigger:N`): stepping on an active one makes a noise. */
    val twigs: List<PuzzleTwig>,
    /** The shortest plan to catch it from now (empty when none is known): do the steps in order. */
    val plan: List<HerdStep>,
    /** Where it looks. */
    val facing: dev.kotlinds.pokemonclient.Direction? = null,
)

/** A twig on the ground (a coordinate trigger): stepping on it while [active] makes the herded Pokémon react. */
data class PuzzleTwig(val id: String, val tiles: List<PuzzleTile>, val active: Boolean)

/** One step of a [PuzzleHerd.plan]. */
sealed interface HerdStep {
    /** Walk onto [tile] (a tile of twig [twig]): the Pokémon turns towards the noise. */
    data class StepOnTwig(val twig: String, val tile: PuzzleTile) : HerdStep

    /** Stand on [tile], face [facing] (towards the Pokémon) and press A: [outcome]. */
    data class TalkFrom(val tile: PuzzleTile, val facing: Direction, val outcome: HerdOutcome) : HerdStep
}

/** What talking to a herded Pokémon does. */
sealed interface HerdOutcome {
    /** It runs to [to]. */
    data class Flees(val to: PuzzleTile) : HerdOutcome

    /** It is caught: it goes back to its owner. */
    data object Caught : HerdOutcome
}

/**
 * A platform the player rides ([PuzzleKind.MOVING_PLATFORMS]): stepping on one of its [triggers] turns or slides it,
 * with the player on it.
 */
data class PuzzlePlatform(
    /** Stable id: `platform:N` (the game's platform index). */
    val id: String,
    /** The pivot tile. */
    val pivot: PuzzleTile,
    /** Quarter turns clockwise from its starting orientation (0..3). */
    val rotation: Int,
    /** Tiles the player can stand on right now. */
    val tiles: List<PuzzleTile>,
    /** Its trigger tiles and what each one does right now. */
    val triggers: List<PlatformTrigger>,
)

/** One trigger tile of a [PuzzlePlatform]. */
data class PlatformTrigger(
    val tile: PuzzleTile,
    val effect: PlatformEffect,
    /** False when the platform can't move that way right now (lava too narrow, another platform in the way). */
    val possible: Boolean,
)

/** What stepping on a [PlatformTrigger] does. */
sealed interface PlatformEffect {
    /** A quarter turn clockwise around the pivot. */
    data object RotateClockwise : PlatformEffect

    /** A slide of [tiles] tiles towards [direction]. */
    data class Slide(val direction: Direction, val tiles: Int) : PlatformEffect
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
    /**
     * Not visible in the game (a switch hidden in one of many identical trash cans): only shown to agents allowed a
     * walkthrough.
     */
    val hidden: Boolean = false,
)

/** A shutter or gate: its [tiles] are blocked while it is closed. */
data class PuzzleBarrier(
    /** Stable id: `shutter:N` / `gate:N` (N = the game's gate number). */
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
    /** For a lift: the height ([FieldState.height] units) the player must be at on [from] to start it. */
    val fromHeight: Int? = null,
    /** For a lift: the height ([FieldState.height] units) the player is at on [to] once it is over. */
    val toHeight: Int? = null,
)

/** Something of the map the agent can see, on or off: `lift:0` (up), `candle:N` (lit), `waterfall:0` (flowing). */
data class PuzzleIndicator(
    /** Stable id: `<kind>:N`. */
    val id: String,
    val on: Boolean,
    val tiles: List<PuzzleTile>,
    /** What [on] means, in our words ("up", "lit", "flowing"...). */
    val meaning: String,
)

/** [tiles] are a moving floor (a lift platform): the player can stand on them at each of [heights] ([FieldState.height] units). */
data class PuzzleSurface(val tiles: List<PuzzleTile>, val heights: List<Int>)

/** Kinds of [PuzzleTeleport]. */
enum class TeleportKind {
    /** A warp pad / trap tile running a warp script. */
    PAD,

    /** A cart ride (the cart moves to the arrival station). */
    CART_RIDE,

    /** A lift: the player stays on the tile and changes floor ([PuzzleTeleport.fromHeight] / [PuzzleTeleport.toHeight]). */
    LIFT,
}
