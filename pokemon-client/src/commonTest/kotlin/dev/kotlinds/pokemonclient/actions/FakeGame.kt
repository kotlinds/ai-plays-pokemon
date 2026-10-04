package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.GameMode
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.Observation
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.data.GameData
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import kotlinx.serialization.json.JsonObject

/**
 * A scripted game for testing recipes without an emulator: the current [screen] reacts to button presses through
 * [onPress] (called once per new press, on the frame the "game" reads it, like the real games do).
 */
class FakeGame(var screen: Screen, var state: (Screen) -> GameState = { GameState(0, it, null, emptyList(), null, null, null) }) : PokemonGame {

    /** The maps, for recipes that walk (none by default). */
    override var world: dev.kotlinds.pokemonclient.world.WorldSource? = null

    /** Reaction of the scripted game to a button press. */
    var onPress: (Button, Screen) -> Screen = { _, current -> current }

    /** Reaction of the scripted game to a new touch of the bottom screen. */
    var onTouch: (TouchPoint, Screen) -> Screen = { _, current -> current }

    /** Every press the game registered, in order. */
    val presses = mutableListOf<Button>()

    /** Reaction of the scripted game to each emulated frame (frame number, current screen). */
    var onFrame: (Long, Screen) -> Screen = { _, current -> current }

    /** Where the stylus was on every frame it was down, in order. */
    val touchFrames = mutableListOf<TouchPoint>()

    /** Every new touch the game registered, in order. */
    val touches = mutableListOf<TouchPoint>()
    private var touching: TouchPoint? = null

    override val name = "Fake"

    /** The bicycle's item id (none by default). */
    override var bicycleItem: Int? = null

    /** The game's data, when a test needs some (see [StubGameData]). */
    override var data: GameData? = null
    override fun observe(memory: Memory) = Observation(GameMode.UNKNOWN, null, "", JsonObject(emptyMap()))
    override fun state(memory: Memory): GameState = state(screen)

    /** Held buttons as the game sees them (updated by the console every frame). */
    var held: Set<Button> = emptySet()
    override val inputProbe = InputProbe { held }

    val console = object : ConsolePort {
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override val revision get() = frame
        override fun step(frames: Int, input: InputFrame) = repeat(frames) {
            frame++
            val pressed = input.buttons - held
            held = input.buttons
            pressed.forEach { button ->
                presses += button
                screen = onPress(button, screen)
            }
            val touch = input.touch
            if (touch != null) touchFrames += touch
            if (touch != null && touching == null) {
                touches += touch
                screen = onTouch(touch, screen)
            }
            touching = touch
            screen = onFrame(frame, screen)
        }
        override fun memorySize(region: MemoryRegion) = 16
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
        override fun framebuffer(): Frame? = null
        override fun saveState() = ByteArray(0)
        override fun loadState(state: ByteArray) = true
    }

    fun scope() = ActionScope(console, inputProbe)
    fun context() = PlanContext(scope(), this)
}
