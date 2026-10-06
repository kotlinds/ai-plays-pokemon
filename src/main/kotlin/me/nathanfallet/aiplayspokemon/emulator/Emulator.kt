package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.Platform
import kotlinx.coroutines.flow.StateFlow

/**
 * What the rest of the app (UI, AI agent) knows about an emulator.
 *
 * This is the seam between "an emulated console" and "everything that uses it": the libretro
 * implementation ([ConsoleHost] over `dev.kotlinds.pokemonclient.libretro.LibretroConsole`) is one way
 * to provide it, but another backend (a socket bridge to a standalone emulator, a GDB stub, a
 * pure-Kotlin emulator, a fake for tests...) only has to implement this interface.
 *
 * Implementations own their emulation thread: every method here is safe to call from any thread.
 */
interface Emulator : AutoCloseable {

    /** Static description of the running emulator (name, screens...). */
    val info: EmulatorInfo

    /** The latest frame produced by the console, or null before the first one. */
    val frames: StateFlow<Frame?>

    /** Running/paused state, speed and frame counter. */
    val status: StateFlow<EmulatorStatus>

    /** Starts emulating (the emulator is created paused so callers can wire everything first). */
    fun start()

    fun pause()
    fun resume()

    /** Runs as fast as possible (no frame pacing, no audio) when [enabled]. */
    fun setFastForward(enabled: Boolean)

    /** Silences the game audio. */
    fun setMuted(muted: Boolean)

    /**
     * Sets the buttons the human currently holds (agents never hold buttons: they act through the host's lease, and a
     * human input interrupts them).
     */
    fun setButtons(buttons: Set<Button>)

    /**
     * Touches the touch screen at a position expressed as a fraction (0..1) of the whole frame,
     * or releases it when [position] is null. Emulators without a touch screen ignore it.
     */
    fun touch(position: Pair<Float, Float>?)

    /** Copy of the console's main RAM, taken between two frames so it is consistent. */
    suspend fun readMainRam(): ByteArray

    /** Saves the full machine state to [slot] / restores it. Returns false when unsupported or missing. */
    suspend fun saveState(slot: Int): Boolean
    suspend fun loadState(slot: Int): Boolean

    suspend fun reset()
}


data class EmulatorInfo(
    /** Emulator name and version, e.g. "melonDS 0.9.3". */
    val name: String,
    /** Frames per second of the emulated console (~59.83 for the DS). */
    val fps: Double,
    /** The kind of console emulated: its screens (size, touch screen). */
    val platform: Platform,
)

data class EmulatorStatus(
    val running: Boolean = false,
    val fastForward: Boolean = false,
    val muted: Boolean = false,
    val frameCount: Long = 0,
    /** Measured emulation speed, in frames per second. */
    val measuredFps: Double = 0.0,
)

