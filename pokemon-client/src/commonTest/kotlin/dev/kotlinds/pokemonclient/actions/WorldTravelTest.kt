package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.GameMode
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Trigger
import dev.kotlinds.pokemonclient.world.TriggerWarp
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A simulated dungeon of several floors (one area per zone, or one area shared by outdoor zones), like the real
 * game: holding a direction for [STEP_FRAMES] frames moves the player one tile; stepping on a door or a hole, or
 * pressing the exit direction on a ladder, moves the player to the other floor only after a transition of
 * [transitionFrames] frames (a fade), during which the player doesn't move; stepping on an active scene trigger opens
 * a dialogue.
 */
private class FloorsGame(
    val areas: Map<Int, Area>,
    var zone: Int,
    var x: Int,
    var y: Int,
    val transitionFrames: Int = 60,
    val people: Map<Int, List<FieldObject>> = emptyMap(),
) : PokemonGame {
    var facing = Direction.SOUTH
    var scene = false
    private var heldFor = 0
    private var held: Set<Button> = emptySet()
    private var pending: Triple<Int, Int, Int>? = null
    private var pendingIn = 0
    val zonesVisited = mutableListOf(zone)

    override val name = "Floors"
    override val world = object : WorldSource {
        override fun areaOf(zoneId: Int) = areas[zoneId]
        override val zoneCount get() = 10
    }
    override fun zoneName(id: Int) = "Floor $id"
    override fun scriptVariable(memory: Memory, id: Int) = 0
    override val inputProbe = InputProbe { held }
    override fun observe(memory: Memory) = Observation(GameMode.UNKNOWN, null, "", JsonObject(emptyMap()))
    override fun state(memory: Memory): GameState {
        val field = FieldState(zone, "Floor $zone", x, y, 0, facing, MovementMode.WALK, moving = false, objects = people[zone].orEmpty())
        val screen = if (scene) Screen.Dialogue(TextSource.FIELD, null, "A scene!", Awaiting.INPUT) else Screen.Overworld(null, Awaiting.INPUT)
        return GameState(0, screen, null, emptyList(), null, null, field)
    }

    private fun area() = areas.getValue(zone)

    private fun arrive(toZone: Int, warp: Int) {
        val target = areas.getValue(toZone).warps.first { it.zone == toZone && it.id == warp }
        schedule(toZone, target.x, target.y)
    }

    private fun schedule(toZone: Int, toX: Int, toY: Int) {
        pending = Triple(toZone, toX, toY)
        pendingIn = transitionFrames
    }

    val console = object : ConsolePort {
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override val revision get() = frame
        override fun step(frames: Int, input: InputFrame) = repeat(frames) {
            frame++
            held = input.buttons
            pending?.let { (z, tx, ty) ->
                if (--pendingIn <= 0) {
                    zone = z; x = tx; y = ty; pending = null
                    zonesVisited += z
                }
                return@repeat
            }
            val direction = DIRECTIONS.entries.firstOrNull { it.key in input.buttons }?.value
            if (direction == null || scene) {
                heldFor = 0
                return@repeat
            }
            facing = direction
            if (++heldFor < STEP_FRAMES) return@repeat
            heldFor = 0
            val here = area()
            // A ladder / exit mat: pressing its direction takes it.
            here.warps.firstOrNull { it.zone == zone && it.x == x && it.y == y && it.exitDirection == direction }?.let {
                arrive(it.targetZone, it.targetWarp)
                return@repeat
            }
            val nx = x + direction.dx
            val ny = y + direction.dy
            val tile = here.tile(nx, ny) ?: return@repeat
            val free = (!tile.blocked || tile.kind == TileKind.Door) && people[zone].orEmpty().none { it.x == nx && it.y == ny }
            if (!free) return@repeat
            x = nx
            y = ny
            here.warps.firstOrNull { it.zone == zone && it.x == x && it.y == y && it.exitDirection == null }?.let { arrive(it.targetZone, it.targetWarp) }
            here.triggerWarps.firstOrNull { it.zone == zone && it.x == x && it.y == y }?.let { schedule(it.targetZone, it.toX, it.toY) }
            if (here.triggers.any { it.zone == zone && it.x == x && it.y == y } && here.triggerWarps.none { it.x == x && it.y == y }) scene = true
            here.zoneAt(x, y)?.let { if (it != zone) { zone = it; zonesVisited += it } }
        }
        override fun memorySize(region: MemoryRegion) = 16
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
        override fun framebuffer(): Frame? = null
        override fun saveState() = ByteArray(0)
        override fun loadState(state: ByteArray) = true
    }

    fun context() = PlanContext(ActionScope(console, inputProbe), this)

    companion object {
        const val STEP_FRAMES = 4
        val DIRECTIONS = mapOf(Button.UP to Direction.NORTH, Button.DOWN to Direction.SOUTH, Button.LEFT to Direction.WEST, Button.RIGHT to Direction.EAST)
    }
}

/**
 * An area drawn in ASCII: '.' floor, '#' wall, 'D' a door, 'L' a ladder (taken by pressing north on it), 'O' a hole,
 * 'T' a scene trigger. [zones] gives the zone of each tile for shared areas ('1', '2'... as drawn), else [zone].
 */
private fun floor(
    zone: Int,
    rows: List<String>,
    warps: List<Warp> = emptyList(),
    holes: List<TriggerWarp> = emptyList(),
    triggers: List<Trigger> = emptyList(),
    zones: List<String>? = null,
): Area {
    val width = rows.maxOf { it.length }
    val tiles = Array<TileInfo?>(width * rows.size) { i ->
        when (rows[i / width].getOrElse(i % width) { '#' }) {
            '#' -> TileInfo(true, TileKind.Wall)
            'D' -> TileInfo(true, TileKind.Door)
            else -> TileInfo(false, TileKind.Floor)
        }
    }
    val zoneArray = zones?.let { z -> IntArray(width * rows.size) { i -> z[i / width][i % width].digitToInt() } }
        ?: IntArray(width * rows.size) { zone }
    return Area(zone, "Floor $zone", 0, 0, width, rows.size, tiles, warps = warps, triggers = triggers, zones = zoneArray, triggerWarps = holes)
}

class WorldTravelTest {

    /**
     * Floor 1: the start (0,0) is walled off from the goal (4,0); a ladder at (0,2) leads up to floor 2, which comes
     * back down by a hole at (4,1) landing at (4,1) on floor 1, next to the goal.
     */
    private fun dungeon(transition: Int = 60) = FloorsGame(
        mapOf(
            1 to floor(1, listOf(".#...", ".#...", ".#..."), warps = listOf(Warp(1, 0, 0, 2, 2, 0, Direction.SOUTH))),
            2 to floor(2, listOf(".....", "....."), warps = listOf(Warp(2, 0, 0, 0, 1, 0, Direction.NORTH)), holes = listOf(TriggerWarp(2, 0, 4, 1, 1, 4, 1)), triggers = listOf(Trigger(2, 0, 4, 1, 1, 1, 1, 0x4000, 0))),
        ),
        zone = 1, x = 0, y = 0, transitionFrames = transition,
    )

    @Test
    fun goToGoesThroughALadderAndAHoleWhenTheMapAloneHasNoWay() {
        val game = dungeon()
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        assertEquals(Triple(1, 4, 0), Triple(game.zone, game.x, game.y), done.detail)
        assertEquals(listOf(1, 2, 1), game.zonesVisited)
        assertTrue("warp:0" in done.detail!! && "hole:0" in done.detail!!, done.detail)
    }

    @Test
    fun aLadderWhoseFadeOutlastsTheStepIsNotATimeout() {
        // The map changes 90 frames after the press: longer than a step's wait (the false TIMEOUT of the notes).
        val game = dungeon(transition = 90)
        game.x = 0; game.y = 1
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:0"), game.context()))
        assertEquals(2, game.zone, done.detail)
    }

    @Test
    fun goToATileOnAnotherFloorByName() {
        val game = dungeon()
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(3, 0, null, map = "Floor 2"), game.context()))
        assertEquals(Triple(2, 3, 0), Triple(game.zone, game.x, game.y))
    }

    @Test
    fun goToAMapByNameStopsOnEnteringIt() {
        val game = dungeon()
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 2"), game.context()))
        assertEquals(2, game.zone)
    }

    @Test
    fun exitEastCrossesIntoTheNeighbouringZoneOfASharedArea() {
        val outdoor = floor(1, listOf("......", "..#..."), zones = listOf("111222", "111222"))
        val game = FloorsGame(mapOf(1 to outdoor, 2 to outdoor), zone = 1, x = 0, y = 1)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "exit:east"), game.context()))
        assertEquals(2, game.zone, done.detail)
        assertEquals(3, game.x)
        // By name too, and an unknown direction lists the exits.
        val back = FloorsGame(mapOf(1 to outdoor, 2 to outdoor), zone = 1, x = 0, y = 0)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 2"), back.context()))
        assertEquals(2, back.zone)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "exit:north"), FloorsGame(mapOf(1 to outdoor, 2 to outdoor), 1, 0, 0).context()))
        assertEquals(listOf("exit:east (Floor 2)"), assertIs<ActionError.InvalidParameter>(failed.error).allowed)
    }

    @Test
    fun frontierWalksNextToTheNearestWayOutAwayFromThePlayer() {
        val game = dungeon()
        game.zone = 2; game.x = 0; game.y = 1
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "frontier"), game.context()))
        // The ladder down is next to the player: the hole's surroundings are the frontier; the walk stops before it.
        assertEquals(2, game.zone, done.detail)
        assertTrue(game.x to game.y in setOf(3 to 1, 4 to 0), "${game.x},${game.y}")
    }

    @Test
    fun aSceneTriggerIsWalkedOntoWhenItIsTheDestinationAndTheOutcomeSaysSo() {
        val room = floor(1, listOf("....."), triggers = listOf(Trigger(1, 0, 3, 0, 1, 1, 7, 0x4000, 0)))
        val game = FloorsGame(mapOf(1 to room), zone = 1, x = 0, y = 0)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(3, 0, null), game.context()))
        assertTrue("started a scene" in done.detail!!, done.detail)
        assertTrue(game.scene)
    }

    @Test
    fun aSceneTriggerInTheOnlyWayIsCrossedAndReported() {
        val corridor = floor(1, listOf("....."), triggers = listOf(Trigger(1, 0, 2, 0, 1, 1, 7, 0x4000, 0)))
        val game = FloorsGame(mapOf(1 to corridor), zone = 1, x = 0, y = 0)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        val error = assertIs<ActionError.Interrupted>(failed.error)
        assertTrue("scene trigger at 2,0" in error.performed, error.performed)
    }

    @Test
    fun aPersonInTheOnlyWayIsNamed() {
        val guard = FieldObject("person:8", "guard", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 2, 0, Direction.SOUTH)
        val game = FloorsGame(mapOf(1 to floor(1, listOf("....."))), zone = 1, x = 0, y = 0, people = mapOf(1 to listOf(guard)))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertTrue("person:8" in error.message && "talk to them" in error.message, error.message)
    }
}
