package dev.kotlinds.pokemonclient.runtime

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.TouchPoint

/**
 * The primitives an action uses to play: advance frames, press buttons, touch the screen, read the RAM.
 *
 * An action owns the console while it runs (the app gives it an exclusive "lease"): every frame it asks
 * for is emulated right away, and nothing else happens in between. So an action sees each frame and can
 * react on the exact frame something appears (a fishing bite, a cursor that moved).
 *
 * Pressing is done with [tap], which checks itself against what the game actually read (see [InputProbe]):
 * the button is held until the game has seen it, then released until the game no longer sees it. That is
 * one press, never long enough to trigger the game's key auto-repeat (the "double move" bug of holding a
 * D-pad direction until the screen changes).
 *
 * @param port the console, used from the current (console) thread only.
 * @param inputProbe reads which buttons the game has registered, from its RAM (per game).
 * @param interruption checked before every frame: returns why the action must stop now (the human took the
 *   controller, the user pressed stop...), or null to go on.
 * @param onFrame called after every emulated frame (the app paces frames for display and plays audio there).
 */
class ActionScope(
    private val port: ConsolePort,
    private val inputProbe: InputProbe,
    private val interruption: () -> Interruption? = { null },
    private val onFrame: () -> Unit = {},
) {
    /** Frames emulated by this scope so far. */
    var framesUsed = 0L
        private set

    private var ram = ByteArray(0)
    private var ramRevision = -1L

    /** Current frame number of the console. */
    val frame: Long get() = port.frame

    /** Emulates [frames] frames with [input] held. Throws [ActionInterruptedException] when interrupted. */
    fun step(frames: Int = 1, input: InputFrame = InputFrame.NONE) {
        repeat(frames) {
            interruption()?.let { throw ActionInterruptedException(it) }
            port.step(1, input)
            framesUsed++
            onFrame()
        }
    }

    /**
     * Presses [button] once: holds it until the game has read it, then releases it until the game no longer
     * sees it. Returns how the press went.
     */
    fun tap(button: Button): TapResult {
        var held = 0
        while (held < MAX_TAP_FRAMES) {
            step(1, InputFrame.of(button))
            held++
            if (button in heldButtons()) break
        }
        val registered = held < MAX_TAP_FRAMES || button in heldButtons()
        var released = 0
        while (released < MAX_TAP_FRAMES) {
            step(1)
            released++
            if (button !in heldButtons()) break
        }
        return TapResult(button, registered, heldFrames = held, releaseFrames = released)
    }

    /** Touches the bottom screen at [point] for [frames] frames, then releases it for as long. */
    fun touch(point: TouchPoint, frames: Int = TOUCH_FRAMES) {
        step(frames, InputFrame(touch = point))
        step(frames)
    }

    /**
     * Steps one frame at a time until [condition] holds on the RAM (checked after each frame), for at most
     * [maxFrames] frames. Returns true when the condition was met.
     */
    fun stepUntil(maxFrames: Int, input: InputFrame = InputFrame.NONE, condition: (Memory) -> Boolean): Boolean {
        repeat(maxFrames) {
            step(1, input)
            if (condition(memory())) return true
        }
        return false
    }

    /**
     * The main RAM as of now (copied once per [ConsolePort.revision]: each frame and each loaded state). The returned [Memory] reads a
     * buffer reused from frame to frame: read what you need before stepping again.
     */
    fun memory(): Memory {
        if (ramRevision != port.revision) {
            val size = port.memorySize(MemoryRegion.MAIN_RAM)
            if (ram.size != size) ram = ByteArray(size)
            port.read(MemoryRegion.MAIN_RAM, 0, size, ram)
            ramRevision = port.revision
        }
        return RamMemory(ram)
    }

    private fun heldButtons(): Set<Button> = inputProbe.heldButtons(memory())

    private companion object {
        /** Safety bound: a game reads its keys every frame or two, so a tap normally takes 2-4 frames. */
        const val MAX_TAP_FRAMES = 12
        const val TOUCH_FRAMES = 4
    }
}

/** How a [ActionScope.tap] went. [registered] is false when the game never saw the button (frozen game, wrong screen...). */
data class TapResult(val button: Button, val registered: Boolean, val heldFrames: Int, val releaseFrames: Int)

/** Reads, from the game's RAM, which buttons the game currently considers held. Implemented per game. */
fun interface InputProbe {
    fun heldButtons(memory: Memory): Set<Button>
}

/** Why an action was stopped before its end. */
enum class Interruption {
    /** A human pressed a button or touched the screen: they take over. */
    HUMAN,

    /** The action was cancelled (the user stopped the agent, the app is closing...). */
    CANCELLED,
}

/** Thrown by [ActionScope.step] when the action must stop; the console is left as it is. */
class ActionInterruptedException(val reason: Interruption) : RuntimeException("Action interrupted: $reason")
