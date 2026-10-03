package dev.kotlinds.pokemonclient.console

/**
 * The emulated console, as seen by the Pokémon client: the only way the library touches an emulator.
 *
 * The library never depends on a particular emulator. The application implements this port (for example
 * on top of a libretro core) and hands it to the client. Everything here is synchronous and driven by the
 * caller: **nothing advances unless [step] is called**. This is what makes actions deterministic (the same
 * starting state and the same inputs give the same result) and lets an action react on the exact frame
 * something appears on screen.
 *
 * Implementations are not thread-safe: a port must be used from a single thread (the "console thread"
 * owned by the application, see the app's `ConsoleHost`).
 */
interface ConsolePort {

    /** The kind of console behind this port (screens, memory, buttons). */
    val platform: Platform

    /** Number of frames emulated since the game was loaded: the client's clock. */
    val frame: Long

    /**
     * Changes every time the memory may have changed: after each emulated frame and after [loadState] (which
     * doesn't move the [frame] clock). Readers cache memory copies on it, never on [frame].
     */
    val revision: Long

    /**
     * Emulates exactly [frames] frames, with [input] held during each of them.
     *
     * Passing [InputFrame.NONE] releases every button and the touch screen.
     */
    fun step(frames: Int = 1, input: InputFrame = InputFrame.NONE)

    /** Size in bytes of a memory [region], or 0 when the emulator doesn't expose it. */
    fun memorySize(region: MemoryRegion): Int

    /**
     * Copies [length] bytes of [region] starting at [offset] into [into]. Always consistent: nothing runs
     * during the copy since frames only advance in [step].
     */
    fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray)

    /** The last frame produced by the console (both screens), or null before the first one. */
    fun framebuffer(): Frame?

    /** Serializes the whole machine state (CPU, RAM, devices). */
    fun saveState(): ByteArray

    /** Restores a state produced by [saveState]; returns false when the emulator rejects it. */
    fun loadState(state: ByteArray): Boolean
}

/** Consoles the client knows how to drive. */
enum class Platform(
    /** Width of the screens, in pixels. */
    val screenWidth: Int,
    /** Height of one screen, in pixels. */
    val screenHeight: Int,
    /** Whether the console has a touch screen (the bottom one). */
    val hasTouchScreen: Boolean,
) {
    /** Nintendo DS: two 256x192 screens, the bottom one is a touch screen. */
    NINTENDO_DS(screenWidth = 256, screenHeight = 192, hasTouchScreen = true),
}

/** Memory regions a [ConsolePort] can expose. */
enum class MemoryRegion {
    /** The console's main RAM (4 MB on the Nintendo DS, mapped at 0x02000000 on the ARM9 bus). */
    MAIN_RAM,
}

/**
 * One video frame: ARGB pixels (0xAARRGGBB), row-major.
 *
 * On the Nintendo DS both screens are stacked: the top screen above the bottom (touch) screen.
 */
class Frame(val width: Int, val height: Int, val pixels: IntArray)
