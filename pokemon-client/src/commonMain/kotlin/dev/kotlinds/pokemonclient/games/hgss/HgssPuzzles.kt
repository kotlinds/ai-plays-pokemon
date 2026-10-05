package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.state.PlatformEffect
import dev.kotlinds.pokemonclient.state.PlatformTrigger
import dev.kotlinds.pokemonclient.state.PuzzlePlatform
import dev.kotlinds.pokemonclient.state.PuzzleBoulderHole
import dev.kotlinds.pokemonclient.state.PuzzleBarrier
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleSwitch
import dev.kotlinds.pokemonclient.state.PuzzleTeleport
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.TeleportKind

/**
 * The map puzzles of HeartGold / SoulSilver whose live state changes the walkable layout, read from RAM (script
 * variables, flags, the save's `Gymmick` slot) with the rules taken from the decomp:
 *
 * - **Goldenrod Tunnel B2F** (under the Radio Tower, `MAP_GOLDENROD_TUNNEL_B2F`): 10 shutters whose state is
 *   `VAR_TEMP_x4000 + N` (0 open, 1 closed, set on entry: gates 2 and 9 closed), toggled by 3 switches, plus a
 *   purple shutter opened once by its switch (`FLAG_OPENED_GOLDENROD_PURPLE_GATE`).
 *   files/fielddata/script/scr_seq/scr_seq_0096_D37R0104.s; tiles from the shutters' objects (zone_event 194).
 * - **Azalea Gym** (`MAP_AZALEA_GYM`): 4 Spinarak carts on 12 stations; stepping on a station's trigger with a cart
 *   rides it to another station, the route chosen by 2 lever bits. `Gymmick.azalea` {u8 spiders[4]; int
 *   switches} (include/gymmick.h, src/gymmick_init.c InitAzaleaGym), routes in asm/overlay_04.s
 *   `ov04_022575A4` (per station, per lever state: count, arrival station, path) and `ov04_022575D4` (station
 *   tiles); ride: `BeginAzaleaGymSpinarakRide` / `ov04_02254724`, levers: `FlipAzaleaGymSwitch`.
 * - **Blackthorn Gym** (`MAP_BLACKTHORN_GYM`): three platforms on the lava, turned and slid by trigger tiles
 *   ([HgssBlackthornGym]).
 * - **Ice Path B1F** (`MAP_ICE_PATH_B1F`): four Strength boulders, each dropping through its own hole to B2F.
 * - **Ilex Forest** (`MAP_ILEX_FOREST`): the two lost Farfetch'd to herd and catch ([HgssIlexFarfetchd]).
 * - **Warp pads** (any map): coordinate triggers whose script warps within the map ([Area.scriptWarps]), e.g. the
 *   Blackthorn Gym exit pads (`VAR_UNK_4111` == 0, never set) and the Team Rocket HQ B1F trap tile.
 * - **Violet Gym lift**, **Ecruteak Gym** invisible floor and candles, **Cianwood Gym** winch, **Vermilion Gym** trash
 *   cans: see [HgssGymPuzzles].
 * - Gyms whose mechanism isn't modeled (Blackthorn platforms, Fuchsia invisible walls): [PuzzleState.unmodeled].
 */
object HgssPuzzles {

    /** `MAP_GOLDENROD_TUNNEL_B2F` (D37R0104). */
    const val GOLDENROD_TUNNEL_B2F = 201

    /** `MAP_AZALEA_GYM` (T23GYM0102). */
    const val AZALEA_GYM = 180

    /** Reads of the game the puzzles need (an [HgssReader] in the game; fakes in tests). */
    interface Reads {
        fun variable(id: Int): Int?
        fun flag(id: Int): Boolean?
        fun gymmick(): ByteArray?
    }

    /** [Reads] from an [HgssReader]. */
    fun reads(reader: HgssReader): Reads = object : Reads {
        override fun variable(id: Int) = reader.variable(id)
        override fun flag(id: Int) = reader.flag(id)
        override fun gymmick() = reader.gymmick()
    }

    /**
     * The puzzle of zone [mapId] right now, or null when the map has none. [area] gives the warp pads (ROM);
     * [objects] are the map's live objects (local id and position), for puzzles about people (the Farfetch'd).
     */
    fun read(mapId: Int, reads: Reads, area: Area?, objects: List<HgssIlexFarfetchd.ObjectAt> = emptyList()): PuzzleState? {
        val pads = pads(mapId, reads, area)
        val main = when (mapId) {
            GOLDENROD_TUNNEL_B2F -> goldenrodTunnel(reads)
            AZALEA_GYM -> azaleaGym(reads)
            HgssBlackthornGym.MAP -> blackthornGym(reads, area)
            ICE_PATH_B1F -> icePathBoulders(reads)
            HgssIlexFarfetchd.MAP -> HgssIlexFarfetchd.herds(reads, objects, area).takeIf { it.isNotEmpty() }
                ?.let { PuzzleState(PuzzleKind.HERDING, HgssIlexFarfetchd.RULE, herds = it) }
            else -> HgssGymPuzzles.read(mapId, reads, area)
        }
        val unmodeled = HgssGymPuzzles.unmodeled(reads)
        return when {
            main != null -> main.copy(teleports = main.teleports + pads, unmodeled = main.unmodeled ?: unmodeled)
            pads.isNotEmpty() -> PuzzleState(PuzzleKind.TELEPORT_PADS, PADS_RULE, teleports = pads, unmodeled = unmodeled)
            unmodeled != null -> PuzzleState(PuzzleKind.UNMODELED, UNMODELED_RULE, unmodeled = unmodeled)
            else -> null
        }
    }

    private const val UNMODELED_RULE =
        "This map has a mechanism the route planner doesn't model (see unmodeled): routes may not find the way through it."

    // region Warp pads

    private const val PADS_RULE =
        "Stepping on a teleport's tiles warps you to its destination on this map (an exit pad, or a trap that sends you back)."

    /** The script warps of [mapId] whose trigger is active right now (its variable has the awaited value). */
    private fun pads(mapId: Int, reads: Reads, area: Area?): List<PuzzleTeleport> {
        if (area == null) return emptyList()
        return area.scriptWarps.filter { it.zone == mapId }.mapNotNull { warp ->
            val trigger = area.triggers.firstOrNull { it.zone == mapId && it.id == warp.trigger } ?: return@mapNotNull null
            if (reads.variable(trigger.variable) != trigger.value) return@mapNotNull null
            val from = (trigger.x until trigger.x + maxOf(1, trigger.width)).flatMap { x ->
                (trigger.y until trigger.y + maxOf(1, trigger.height)).map { y -> PuzzleTile(x, y) }
            }
            PuzzleTeleport("teleport:${warp.trigger}", TeleportKind.PAD, from, PuzzleTile(warp.x, warp.y))
        }
    }

    // endregion

    // region Goldenrod Tunnel B2F

    /** `VAR_TEMP_x4000`: shutter N's state is variable 0x4000 + N. */
    private const val GATE_VAR_BASE = 0x4000

    /** `GATE_OPEN` / `GATE_CLOSED` (files/fielddata/script/scr_seq/event_D37R0104.h). */
    private const val GATE_OPEN = 0

    /** `FLAG_OPENED_GOLDENROD_PURPLE_GATE` (include/constants/flags.h). */
    const val FLAG_PURPLE_GATE = 0x9B

    /** Id of the purple shutter (not numbered by the game: after gates 0..9). */
    private const val PURPLE_GATE = 10

    /**
     * Tiles each shutter blocks when closed: where its objects stand once closed (the halves `gateN_left/right`
     * plus the `stop` object, or `gateN_top`, each moved 2 tiles by the closing movement).
     */
    private val GATE_TILES: Map<Int, List<PuzzleTile>> = mapOf(
        0 to row(2..4, 8), 1 to row(14..16, 8), 2 to listOf(PuzzleTile(6, 10)), 3 to listOf(PuzzleTile(12, 10)),
        4 to row(2..4, 14), 5 to row(8..10, 14), 6 to row(14..16, 14), 7 to listOf(PuzzleTile(6, 16)),
        8 to listOf(PuzzleTile(12, 16)), 9 to listOf(PuzzleTile(18, 16)), PURPLE_GATE to row(20..22, 14),
    )

    /** The 4 switches: sign (bg event) index, tile, and the shutters they toggle (scr_seq_D37R0104_000..003). */
    private data class TunnelSwitch(val sign: Int, val tile: PuzzleTile, val gates: List<Int>)

    private val TUNNEL_SWITCHES = listOf(
        TunnelSwitch(0, PuzzleTile(8, 8), listOf(2, 3, 4, 5, 7)), // blue
        TunnelSwitch(1, PuzzleTile(9, 8), listOf(0, 2, 4, 6, 8, 9)), // green
        TunnelSwitch(2, PuzzleTile(10, 8), listOf(1, 3, 5, 6, 7, 8)), // red
        TunnelSwitch(3, PuzzleTile(23, 14), listOf(PURPLE_GATE)), // purple: opens once
    )

    private const val TUNNEL_RULE =
        "Each switch (switch:0..2, pressed with A facing north) toggles its shutters: every open one closes and every " +
            "closed one opens. A closed shutter blocks its tiles. switch:3 opens shutter:10 for good."

    private fun goldenrodTunnel(reads: Reads): PuzzleState {
        val purpleOpen = reads.flag(FLAG_PURPLE_GATE) == true
        val barriers = GATE_TILES.map { (gate, tiles) ->
            val open = if (gate == PURPLE_GATE) purpleOpen else (reads.variable(GATE_VAR_BASE + gate) ?: GATE_OPEN) == GATE_OPEN
            PuzzleBarrier("shutter:$gate", open, tiles)
        }
        val switches = TUNNEL_SWITCHES.map { s ->
            val purple = s.gates == listOf(PURPLE_GATE)
            PuzzleSwitch(
                id = "switch:${s.sign}",
                targets = listOf("sign:${s.sign}"),
                tiles = listOf(s.tile),
                toggles = s.gates.map { "shutter:$it" },
                oneShot = purple,
                used = purple && purpleOpen,
            )
        }
        return PuzzleState(PuzzleKind.SHUTTER_SWITCHES, TUNNEL_RULE, switches, barriers)
    }

    private fun row(xs: IntRange, y: Int) = xs.map { PuzzleTile(it, y) }

    // endregion

    // region Azalea Gym

    /** `GYMMICK_AZALEA` (include/gymmick.h). */
    private const val GYMMICK_AZALEA = 5

    /** Station tiles (`ov04_022575D4`): where the carts stop. */
    private val STATIONS = listOf(
        PuzzleTile(3, 31), PuzzleTile(9, 31), PuzzleTile(15, 31),
        PuzzleTile(3, 24), PuzzleTile(9, 24), PuzzleTile(15, 24),
        PuzzleTile(3, 16), PuzzleTile(9, 16), PuzzleTile(15, 16),
        PuzzleTile(3, 9), PuzzleTile(9, 9), PuzzleTile(15, 9),
    )

    /**
     * Stations whose ride runs its path backwards (the jump table of `BeginAzaleaGymSpinarakRide`): their trigger
     * is the station tile itself and the player gets off two tiles south of the arrival station (placed one tile
     * south, then a step south). The others are entered from the tile south of the station and the player gets off
     * one tile north of the arrival station (placed on it, then a step north).
     */
    private val REVERSED = setOf(3, 4, 5, 9, 10, 11)

    /**
     * Arrival station per station and lever state (`switches` 0..3), null when the cart doesn't go anywhere
     * (`ov04_022575A4`: the `{count, arrival, path}` entries; count 0 = no route).
     */
    private val ROUTES: List<List<Int?>> = listOf(
        listOf(4, 4, 4, 4), listOf(5, 5, 5, 5), listOf(3, 3, 3, 3),
        listOf(2, 2, 2, 2), listOf(0, 0, 0, 0), listOf(1, 1, 1, 1),
        listOf(null, 9, null, 10), listOf(9, 11, 10, 11), listOf(null, null, null, null),
        listOf(7, 6, null, null), listOf(null, null, 7, 6), listOf(null, 7, null, 7),
    )

    /** Levers: switch 0 = the 3 levers of bit 0 (bg events 0..2), switch 1 = the lever of bit 1 (bg event 3). */
    private val LEVERS = listOf(
        listOf(0 to PuzzleTile(4, 7), 1 to PuzzleTile(11, 8), 2 to PuzzleTile(11, 18)),
        listOf(3 to PuzzleTile(2, 18)),
    )

    private const val AZALEA_RULE =
        "Step on a cart station's tile (teleport cart:N) while a Spinarak cart waits there to ride it to another " +
            "station; the cart stays at the arrival. Levers (switch:0 = sign:0..2, switch:1 = sign:3) each toggle one " +
            "bit that changes the routes from the upper stations (cart:6..11). Carts and levers reset on entering the gym."

    /** The trigger tile of [station] (the zone's coordinate events 0..11). */
    fun azaleaTrigger(station: Int): PuzzleTile = STATIONS[station].let { if (station in REVERSED) it else PuzzleTile(it.x, it.y + 1) }

    /** Where the player stands after riding from [from] to [to]. */
    fun azaleaLanding(from: Int, to: Int): PuzzleTile = STATIONS[to].let { if (from in REVERSED) PuzzleTile(it.x, it.y + 2) else PuzzleTile(it.x, it.y - 1) }

    /** The arrival station of a ride from [station] with lever bits [switches], or null. */
    fun azaleaRoute(station: Int, switches: Int): Int? = ROUTES.getOrNull(station)?.getOrNull(switches and 3)

    private fun azaleaGym(reads: Reads): PuzzleState? {
        val gymmick = reads.gymmick() ?: return null
        if (u32(gymmick, 0) != GYMMICK_AZALEA) return null
        val carts = (0 until 4).map { gymmick[4 + it].toInt() and 0xFF }
        val switches = u32(gymmick, 8) and 3
        val rides = carts.distinct().filter { it in STATIONS.indices }.sorted().mapNotNull { station ->
            val to = azaleaRoute(station, switches) ?: return@mapNotNull null
            PuzzleTeleport("cart:$station", TeleportKind.CART_RIDE, listOf(azaleaTrigger(station)), azaleaLanding(station, to))
        }
        val levers = LEVERS.mapIndexed { bit, signs ->
            PuzzleSwitch(
                id = "switch:$bit",
                targets = signs.map { "sign:${it.first}" },
                tiles = signs.map { it.second },
                flipped = switches shr bit and 1 == 1,
                toggles = STATIONS.indices.filter { s -> azaleaRoute(s, switches) != azaleaRoute(s, switches xor (1 shl bit)) }.map { "cart:$it" },
            )
        }
        return PuzzleState(PuzzleKind.CART_RIDES, AZALEA_RULE, levers, teleports = rides)
    }

    // region Ice Path B1F

    /** `MAP_ICE_PATH_B1F` (D39R0102). */
    const val ICE_PATH_B1F = 237

    /**
     * Boulder object, hole, and the boulder's `FLAG_HIDE_ICE_PATH_BOULDER_n_INIT` (set once it fell): the table
     * `ov01_02206A14` (asm/overlay_01_021F1AFC.s) checked after each Strength push on this map (`ov01_021F1F8C`): the
     * boulder is deleted, its INIT flag set and its `..._FALLEN` flag cleared, so it shows on B2F below the hole.
     */
    private val ICE_PATH_HOLES = listOf(
        Triple(0, PuzzleTile(11, 10), 0x1EA), Triple(1, PuzzleTile(10, 18), 0x1EB),
        Triple(2, PuzzleTile(18, 7), 0x1EC), Triple(3, PuzzleTile(19, 19), 0x1ED),
    )

    private const val ICE_PATH_RULE =
        "Push each Strength boulder into its own hole (boulder person:N into the hole listed with it; another hole " +
            "refuses it): it drops to B2F, where the fallen boulders are the stoppers that make the slides on the ice " +
            "lead to the stairs down (boulder person:0 into the hole at 11,10 is the one the way to B3F needs). go_to plans " +
            "these pushes when Strength can be used."

    private fun icePathBoulders(reads: Reads): PuzzleState = PuzzleState(
        PuzzleKind.BOULDER_HOLES, ICE_PATH_RULE,
        boulderHoles = ICE_PATH_HOLES.map { (id, hole, flag) -> PuzzleBoulderHole("person:$id", hole, reads.flag(flag) == true) },
    )

    // endregion

    // region Blackthorn Gym

    private const val BLACKTHORN_RULE =
        "Platforms over the lava: walk on their tiles. Stepping on a platform's pivot turns it a quarter turn " +
            "clockwise; stepping on the tile on either side of the pivot slides it its width that way, with you on it. " +
            "A platform blocked by the floor or another platform doesn't move. go_to plans the rides by itself."

    private fun blackthornGym(reads: Reads, area: Area?): PuzzleState? {
        if (area == null) return null
        val poses = reads.gymmick()?.let(HgssBlackthornGym::poses) ?: return null
        val gym = HgssBlackthornGym(poses, area)
        val platforms = poses.mapIndexed { i, pose ->
            val (pivot, forward, backward) = gym.triggers(pose)
            val (fx, fy) = gym.forward(pose)
            fun direction(dx: Int, dy: Int) = Direction.entries.first { it.dx == dx && it.dy == dy }
            fun possible(tile: Pair<Int, Int>) = gym.ride(poses, tile.first, tile.second)?.moved == true
            PuzzlePlatform(
                id = "platform:$i",
                pivot = PuzzleTile(pose.x, pose.y),
                rotation = pose.rotation,
                tiles = gym.walkTiles(listOf(pose)).map { PuzzleTile(it.first, it.second) }.sortedWith(compareBy({ it.y }, { it.x })),
                triggers = listOf(
                    PlatformTrigger(PuzzleTile(pivot.first, pivot.second), PlatformEffect.RotateClockwise, possible(pivot)),
                    PlatformTrigger(PuzzleTile(forward.first, forward.second), PlatformEffect.Slide(direction(fx, fy), gym.width(i)), possible(forward)),
                    PlatformTrigger(PuzzleTile(backward.first, backward.second), PlatformEffect.Slide(direction(-fx, -fy), gym.width(i)), possible(backward)),
                ),
            )
        }
        return PuzzleState(PuzzleKind.MOVING_PLATFORMS, BLACKTHORN_RULE, platforms = platforms, mechanics = gym)
    }

    // endregion

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    // endregion
}
