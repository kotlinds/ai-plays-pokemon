package me.nathanfallet.aiplayspokemon.emulator.libretro

import dev.kotlinds.libretrokmp.Device
import dev.kotlinds.libretrokmp.JoypadButton
import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrokmp.LibretroFrontend
import dev.kotlinds.libretrokmp.LogLevel
import dev.kotlinds.libretrokmp.MemoryType
import dev.kotlinds.libretrokmp.PointerId
import dev.kotlinds.libretrokmp.VideoFrame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ConsolePort] implemented with a libretro core, loaded through libretro-kmp.
 *
 * libretro in a nutshell: the emulator ("core", e.g. melonDS) is a native library; we are the "frontend". Each
 * call to `run()` emulates exactly one frame, during which the core calls us back to get the inputs and to hand
 * us the frame's picture and sound. That maps one-to-one onto [ConsolePort.step].
 *
 * Not thread-safe: libretro cores keep global state and must be driven from a single thread. Only the console
 * thread of [me.nathanfallet.aiplayspokemon.emulator.ConsoleHost] uses this class.
 *
 * @param onVideo called with each new frame (converted to ARGB).
 * @param onAudio called with each batch of interleaved stereo samples.
 */
class LibretroConsole(
    private val spec: LibretroCoreSpec,
    rom: Path,
    dataDirectory: Path,
    private val onVideo: (Frame) -> Unit,
    private val onAudio: (samples: ShortArray, frames: Int) -> Unit,
) : ConsolePort, AutoCloseable {

    override val platform = Platform.NINTENDO_DS

    override var frame = 0L
        private set

    override var revision = 0L
        private set

    private var input = InputFrame.NONE
    private var lastFrame: Frame? = null

    private val frontend = object : LibretroFrontend {
        override val systemDirectory = Files.createDirectories(dataDirectory.resolve("system")).toString()
        override val saveDirectory = Files.createDirectories(dataDirectory.resolve("saves")).toString()

        override fun variable(key: String): String? = spec.options[key]

        override fun onVideoFrame(frame: VideoFrame) {
            Frame(frame.width, frame.height, frame.pixels).also {
                lastFrame = it
                onVideo(it)
            }
        }

        override fun onAudio(samples: ShortArray, frames: Int) = this@LibretroConsole.onAudio(samples, frames)

        override fun inputState(port: Int, device: Int, index: Int, id: Int): Short = when {
            port != 0 -> 0
            device == Device.JOYPAD -> if (BUTTON_IDS[id]?.let { it in input.buttons } == true) 1 else 0
            device == Device.POINTER -> pointerState(id)
            else -> 0
        }

        override fun onLog(level: LogLevel, message: String) {
            if (level >= LogLevel.WARN) print("[core:${level.name.lowercase()}] $message")
        }
    }

    private val saveDirectory: Path = Path.of(frontend.saveDirectory)
    private val romBase = rom.fileName.toString().substringBeforeLast('.')

    private val core = LibretroCore(spec.resolve(dataDirectory.resolve("cores")).toString(), frontend).also {
        // The canonical in-game save is <rom>.sav; convert it to this core's own format first if needed.
        spec.saveFormat.prepare(saveDirectory, romBase)
        it.loadGame(rom.toAbsolutePath().toString())
        it.setControllerPortDevice(0, Device.JOYPAD)
    }

    /** Name and version of the core, e.g. "melonDS 0.9.3". */
    val coreName = "${core.systemInfo.libraryName} ${core.systemInfo.libraryVersion}"

    /** Frames per second of the emulated console (~59.83 for the DS). */
    val fps = core.avInfo.timing.fps

    /** Audio sample rate of the core. */
    val sampleRate = core.avInfo.timing.sampleRate

    override fun step(frames: Int, input: InputFrame) {
        this.input = input
        repeat(frames) {
            core.run()
            frame++
            revision++
        }
    }

    override fun memorySize(region: MemoryRegion): Int = core.memorySize(region.type).toInt()

    override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) {
        val memory = core.readMemory(region.type) ?: error("$coreName doesn't expose $region")
        memory.copyInto(into, 0, offset, offset + length)
    }

    override fun framebuffer(): Frame? = lastFrame

    override fun saveState(): ByteArray = core.saveState() ?: error("$coreName can't save states")

    override fun loadState(state: ByteArray): Boolean = core.loadState(state).also { revision++ }

    fun reset() = core.reset()

    /** Unloads the game (the core then writes the in-game save to disk) and the core, then syncs `<rom>.sav`. */
    override fun close() {
        core.close()
        spec.saveFormat.sync(saveDirectory, romBase)
    }

    /**
     * libretro pointer coordinates span the whole frame (both DS screens stacked) from -0x7FFF to 0x7FFF;
     * [InputFrame.touch] is in pixels of the bottom screen.
     */
    private fun pointerState(id: Int): Short {
        val point = input.touch ?: return 0
        val width = platform.screenWidth
        val height = platform.screenHeight * 2
        return when (id) {
            PointerId.X -> ((point.x * 2 + 1) * 0x7FFF / width - 0x7FFF).toShort()
            PointerId.Y -> (((point.y + platform.screenHeight) * 2 + 1) * 0x7FFF / height - 0x7FFF).toShort()
            PointerId.PRESSED -> 1
            else -> 0
        }
    }

    private val MemoryRegion.type: MemoryType
        get() = when (this) {
            MemoryRegion.MAIN_RAM -> MemoryType.SYSTEM_RAM
        }

    private companion object {
        /** libretro joypad ids -> buttons. */
        val BUTTON_IDS = mapOf(
            JoypadButton.A to Button.A,
            JoypadButton.B to Button.B,
            JoypadButton.X to Button.X,
            JoypadButton.Y to Button.Y,
            JoypadButton.L to Button.L,
            JoypadButton.R to Button.R,
            JoypadButton.START to Button.START,
            JoypadButton.SELECT to Button.SELECT,
            JoypadButton.UP to Button.UP,
            JoypadButton.DOWN to Button.DOWN,
            JoypadButton.LEFT to Button.LEFT,
            JoypadButton.RIGHT to Button.RIGHT,
        )
    }
}
