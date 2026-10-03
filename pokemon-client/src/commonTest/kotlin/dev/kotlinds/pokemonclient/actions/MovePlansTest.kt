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
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The walker against a simulated field: holding a direction for [STEP_FRAMES] frames moves the player one tile
 * (when the tile is free), like the real games. [invisibleWalls] are refused by the game although the map allows
 * them; stepping on [battleTile] starts a wild battle.
 */
private class WalkingGame(
    rows: List<String>,
    var x: Int,
    var y: Int,
    val invisibleWalls: Set<Pair<Int, Int>> = emptySet(),
    val battleTile: Pair<Int, Int>? = null,
    val people: List<FieldObject> = emptyList(),
) : PokemonGame {
    val area: Area = run {
        val width = rows.maxOf { it.length }
        Area(0, "test", 0, 0, width, rows.size, Array(width * rows.size) { i ->
            when (rows[i / width].getOrElse(i % width) { '#' }) {
                '#' -> TileInfo(true, TileKind.Wall)
                '"' -> TileInfo(false, TileKind.TallGrass)
                else -> TileInfo(false, TileKind.Floor)
            }
        })
    }
    var facing = Direction.SOUTH
    var inBattle = false
    private var heldFor = 0
    private var held: Set<Button> = emptySet()
    val visited = mutableListOf(x to y)

    override val name = "Walking"
    override val world = object : WorldSource {
        override fun areaOf(zoneId: Int) = area
    }
    override val inputProbe = InputProbe { held }
    override fun observe(memory: Memory) = Observation(GameMode.UNKNOWN, null, "", JsonObject(emptyMap()))
    override fun state(memory: Memory): GameState {
        val field = FieldState(1, "test", x, y, 0, facing, MovementMode.WALK, moving = false, objects = people)
        val battle = if (inBattle) BattleState(BattleKind.WILD, false, null, emptyList(), emptyList(), emptyList(), null) else null
        val screen = if (inBattle) Screen.Battle(Awaiting.ANIMATION) else Screen.Overworld(null, Awaiting.INPUT)
        return GameState(0, screen, null, emptyList(), null, battle, field.takeIf { !inBattle })
    }

    val console = object : ConsolePort {
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override val revision get() = frame
        override fun step(frames: Int, input: InputFrame) = repeat(frames) {
            frame++
            held = input.buttons
            val direction = DIRECTIONS.entries.firstOrNull { it.key in input.buttons }?.value
            if (direction == null || inBattle) {
                heldFor = 0
                return@repeat
            }
            facing = direction
            if (++heldFor < STEP_FRAMES) return@repeat
            heldFor = 0
            val nx = x + direction.dx
            val ny = y + direction.dy
            val free = area.tile(nx, ny)?.blocked == false && (nx to ny) !in invisibleWalls && people.none { it.x == nx && it.y == ny }
            if (free) {
                x = nx
                y = ny
                visited += x to y
                if ((x to y) == battleTile) inBattle = true
            }
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

class MovePlansTest {

    @Test
    fun goToWalksAroundWallsToTheTile() {
        val game = WalkingGame(listOf(".....", ".###.", "....."), x = 0, y = 0)
        val outcome = MovePlans.goTo.run(GameAction.GoTo(4, 2, null), game.context())
        assertIs<ActionOutcome.Done>(outcome)
        assertEquals(4 to 2, game.x to game.y)
    }

    @Test
    fun aRefusedStepIsLearnedAndTheRouteComputedAgain() {
        // The map says (2,0) is free, the game refuses it: the walker goes around through the bottom row.
        val game = WalkingGame(listOf(".....", "....."), x = 0, y = 0, invisibleWalls = setOf(2 to 0))
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        assertEquals(4 to 0, game.x to game.y)
        assertTrue((2 to 0) !in game.visited)
    }

    @Test
    fun aWildBattleInterruptsTheWalk() {
        val game = WalkingGame(listOf("....."), x = 0, y = 0, battleTile = 2 to 0)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        assertEquals(InterruptionCause.WILD_BATTLE, assertIs<ActionError.Interrupted>(failed.error).by)
    }

    @Test
    fun interactStopsNextToThePersonAndFacesIt() {
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 3, 0, Direction.SOUTH)
        val game = WalkingGame(listOf("....", "...."), x = 0, y = 1, people = listOf(nurse))
        // Nothing happens on A in this fake: the plan reports it explicitly, after walking next to the person.
        val outcome = MovePlans.interact.run(GameAction.Interact("person:0"), game.context())
        assertIs<ActionOutcome.Failed>(outcome)
        assertTrue(game.x to game.y in setOf(2 to 0, 3 to 1), "next to the person, not on it: ${game.x},${game.y}")
    }

    @Test
    fun unknownTargetsListTheKnownOnes() {
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 3, 0, Direction.SOUTH)
        val game = WalkingGame(listOf("...."), x = 0, y = 0, people = listOf(nurse))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "person:9"), game.context()))
        assertEquals(listOf("person:0"), assertIs<ActionError.InvalidParameter>(failed.error).allowed)
    }

    @Test
    fun exploreGoesAsFarAsPossibleInTheDirection() {
        val game = WalkingGame(listOf("......#"), x = 0, y = 0)
        val done = assertIs<ActionOutcome.Done>(MovePlans.explore.run(GameAction.Explore(Direction.EAST), game.context()))
        assertEquals(5 to 0, game.x to game.y, done.detail)
    }

    @Test
    fun findEncounterWalksToTheGrassAndPacesUntilABattle() {
        val game = WalkingGame(listOf(".\"\""), x = 0, y = 0, battleTile = 1 to 0)
        // The battle starts on the first grass tile: that's the success of this action.
        assertEquals("wild battle", assertIs<ActionOutcome.Done>(MovePlans.findEncounter.run(GameAction.FindEncounter, game.context())).detail)
    }
}
