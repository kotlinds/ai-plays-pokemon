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
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveRule
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
    /** Stepping here makes a trainer see the player: the player stops, the trainer's approach scene runs. */
    val sightTile: Pair<Int, Int>? = null,
) : PokemonGame {
    val area: Area = run {
        val width = rows.maxOf { it.length }
        Area(0, "test", 0, 0, width, rows.size, Array(width * rows.size) { i ->
            when (rows[i / width].getOrElse(i % width) { '#' }) {
                '#' -> TileInfo(true, TileKind.Wall)
                '"' -> TileInfo(false, TileKind.TallGrass)
                '~' -> TileInfo(false, TileKind.Water(surfable = true, fishable = true))
                else -> TileInfo(false, TileKind.Floor)
            }
        })
    }
    var facing = Direction.SOUTH
    var inBattle = false
    var spotted = false
    private var heldFor = 0
    private var held: Set<Button> = emptySet()
    val visited = mutableListOf(x to y)

    override val name = "Walking"
    override val world = object : WorldSource {
        override fun areaOf(zoneId: Int) = area
    }
    override val inputProbe = InputProbe { held }
    override fun fieldMoveRule(move: FieldMoveKind) = FieldMoveRule(MoveId(57), "Fog")
    override fun observe(memory: Memory) = Observation(GameMode.UNKNOWN, null, "", JsonObject(emptyMap()))
    override fun state(memory: Memory): GameState {
        val field = FieldState(1, "test", x, y, 0, facing, MovementMode.WALK, moving = false, objects = people, trainerEncounter = spotted)
        val battle = if (inBattle) BattleState(BattleKind.WILD, false, null, emptyList(), emptyList(), emptyList(), null) else null
        val screen = when {
            inBattle -> Screen.Battle(Awaiting.ANIMATION)
            // The trainer walked up and talks (its intro text before the battle).
            spotted -> Screen.Dialogue(TextSource.FIELD, "Youngster Joey", "I just lost, so I'm trying to find more Pokémon.", Awaiting.INPUT)
            else -> Screen.Overworld(null, Awaiting.INPUT)
        }
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
            if (direction == null || inBattle || spotted) {
                heldFor = 0
                return@repeat
            }
            facing = direction
            if (++heldFor < STEP_FRAMES) return@repeat
            heldFor = 0
            val nx = x + direction.dx
            val ny = y + direction.dy
            val free = area.tile(nx, ny)?.let { !it.blocked && it.kind !is TileKind.Water } == true && (nx to ny) !in invisibleWalls && people.none { it.x == nx && it.y == ny }
            if (free) {
                x = nx
                y = ny
                visited += x to y
                if ((x to y) == battleTile) inBattle = true
                if ((x to y) == sightTile) spotted = true
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
    fun aTrainerSeeingThePlayerInterruptsTheWalkWithTrainerSight() {
        val game = WalkingGame(listOf("....."), x = 0, y = 0, sightTile = 2 to 0)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        assertEquals(InterruptionCause.TRAINER_SIGHT, assertIs<ActionError.Interrupted>(failed.error).by)
    }

    @Test
    fun interactStopsNextToThePersonAndFacesIt() {
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 3, 0, Direction.SOUTH)
        val game = WalkingGame(listOf("....", "...."), x = 0, y = 1, people = listOf(nurse))
        // Nothing happens on A in this fake: facing the person, that's "nothing to say", not a failure to get there.
        val outcome = MovePlans.interact.run(GameAction.Interact("person:0"), game.context())
        assertTrue("nothing to say" in assertIs<ActionOutcome.Done>(outcome).detail.orEmpty(), outcome.toString())
        assertTrue(game.x to game.y in setOf(2 to 0, 3 to 1), "next to the person, not on it: ${game.x},${game.y}")
    }

    @Test
    fun unknownTargetsListTheKnownOnes() {
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 3, 0, Direction.SOUTH)
        val game = WalkingGame(listOf("...."), x = 0, y = 0, people = listOf(nurse))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "person:9"), game.context()))
        assertEquals(listOf("person:0", "frontier"), assertIs<ActionError.InvalidParameter>(failed.error).allowed)
    }

    @Test
    fun findEncounterWalksToTheGrassAndPacesUntilABattle() {
        val game = WalkingGame(listOf(".\"\""), x = 0, y = 0, battleTile = 1 to 0)
        // The battle starts on the first grass tile: that's the success of this action.
        assertEquals("wild battle", assertIs<ActionOutcome.Done>(MovePlans.findEncounter.run(GameAction.FindEncounter, game.context())).detail)
    }

    @Test
    fun aStraightLineIsWalkedHoldingTheDirection() {
        // 12 tiles east: held all along (a step every STEP_FRAMES frames), with one stillness check at the end
        // instead of one per tile.
        val game = WalkingGame(listOf("............."), x = 0, y = 0)
        game.facing = Direction.EAST
        val before = game.console.frame
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(12, 0, null), game.context()))
        assertEquals(12 to 0, game.x to game.y)
        val frames = game.console.frame - before
        assertTrue(frames < 12 * WalkingGame.STEP_FRAMES + 30, "took $frames frames")
        assertEquals((0..12).map { it to 0 }, game.visited)
    }

    @Test
    fun aTurnStartsANewSegmentAndTheWalkEndsOnTheTarget() {
        val game = WalkingGame(listOf(".....", "....."), x = 0, y = 0)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 1, null), game.context()))
        assertEquals(4 to 1, game.x to game.y)
        assertEquals(5, game.visited.size - 1, "no tile walked twice: ${game.visited}")
    }

    @Test
    fun waterWithoutSurfSaysWhereToUseItFromAndWhatIsMissing() {
        val game = WalkingGame(listOf("..~~.."), x = 0, y = 0)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(5, 0, null), game.context()))
        val hint = assertIs<ActionError.Unavailable>(failed.error).hint.orEmpty()
        assertTrue("from 1,0 facing east" in hint, hint)
        assertTrue("no Pokémon" in hint, hint)
    }

    @Test
    fun stepWalksStraightAndSaysWhereItWasBlocked() {
        val game = WalkingGame(listOf("...#."), x = 0, y = 0)
        // Facing south: the step turns east by itself and walks both tiles.
        assertIs<ActionOutcome.Done>(MovePlans.step.run(GameAction.Step(Direction.EAST, 2), game.context()))
        assertEquals(2 to 0, game.x to game.y)
        val blocked = assertIs<ActionOutcome.Failed>(MovePlans.step.run(GameAction.Step(Direction.EAST, 2), game.context()))
        assertTrue("at 2,0" in assertIs<ActionError.Unavailable>(blocked.error).detail, blocked.error.toString())
    }
}
