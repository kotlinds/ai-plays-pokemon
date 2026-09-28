package me.nathanfallet.aiplayspokemon.emulator.libretro

import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import me.nathanfallet.aiplayspokemon.emulator.Frame
import java.nio.file.Path

/**
 * A libretro core loaded in-process: the lowest layer of the emulator.
 *
 * This class only translates between libretro's C world and Kotlin:
 * - frames produced by the core are converted to ARGB pixels and handed to [video],
 * - audio samples are handed to [audio],
 * - input queries are answered by [input],
 * - "environment" questions (directories, options, pixel format...) are answered here.
 *
 * It is NOT thread-safe: every method must be called from the same thread (the emulator thread,
 * see [me.nathanfallet.aiplayspokemon.emulator.Emulator]). libretro cores also keep global state,
 * so only one instance may exist per process.
 */
class LibretroCore(
    corePath: Path,
    private val systemDirectory: Path,
    private val saveDirectory: Path,
    private val options: Map<String, String>,
    private val video: (Frame) -> Unit,
    private val audio: (samples: ShortArray, frames: Int) -> Unit,
    private val input: InputSource,
    private val log: (String) -> Unit = ::println,
) : AutoCloseable {

    /** Answers the core's input queries (see [Retro] for device/button ids). */
    fun interface InputSource {
        fun state(port: Int, device: Int, index: Int, id: Int): Short
    }

    private val api: LibretroApi = Native.load(corePath.toString(), LibretroApi::class.java)

    /** Pixel format requested by the core through SET_PIXEL_FORMAT (libretro default is 0RGB1555). */
    private var pixelFormat = Retro.PIXEL_FORMAT_0RGB1555

    /** Native strings handed to the core must outlive the call, so we keep them here. */
    private val nativeStrings = mutableMapOf<String, Memory>()

    /** Reused buffer for audio batches to avoid allocating on every frame. */
    private var audioBuffer = ShortArray(4096)

    /** Keeps the game bytes alive while the core runs (some cores read from the buffer lazily). */
    private var gameData: Memory? = null

    // Callbacks are stored in fields: if they were garbage collected, the core would call freed memory.
    // The environment callback returns a C `bool` (1 byte): JNA would marshal a Kotlin Boolean as a
    // 4-byte int (true = -1), so we return a Byte explicitly.
    private val environmentCallback = EnvironmentCallback { cmd, data -> if (onEnvironment(cmd, data)) 1 else 0 }
    private val videoCallback = VideoRefreshCallback(::onVideoRefresh)
    private val audioSampleCallback = AudioSampleCallback { left, right -> audio(shortArrayOf(left, right), 1) }
    private val audioBatchCallback = AudioSampleBatchCallback(::onAudioBatch)
    private val inputPollCallback = InputPollCallback { }
    private val inputStateCallback = InputStateCallback(input::state)
    private val logCallback = LogCallback { level, format -> format?.let { log("[core:${logLevel(level)}] ${it.trimEnd()}") } }

    /** Name/version reported by the core, handy for logs and the UI. */
    val systemInfo: RetroSystemInfo = RetroSystemInfo().also { api.retro_get_system_info(it) }

    /** Video/audio timings, available after [loadGame]. */
    lateinit var avInfo: RetroSystemAvInfo
        private set

    init {
        require(api.retro_api_version() == 1) { "Unsupported libretro API version ${api.retro_api_version()}" }
        // The environment callback must be set before retro_init(), the others before retro_run().
        api.retro_set_environment(environmentCallback)
        api.retro_init()
        api.retro_set_video_refresh(videoCallback)
        api.retro_set_audio_sample(audioSampleCallback)
        api.retro_set_audio_sample_batch(audioBatchCallback)
        api.retro_set_input_poll(inputPollCallback)
        api.retro_set_input_state(inputStateCallback)
    }

    /** Loads a game (ROM). Must be called once before [run]. */
    fun loadGame(romPath: Path) {
        val info = RetroGameInfo().apply {
            path = romPath.toAbsolutePath().toString()
            if (!systemInfo.need_fullpath) {
                // Cores that don't need a path want the whole file in memory.
                val bytes = romPath.toFile().readBytes()
                gameData = Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
                data = gameData
                size = bytes.size.toLong()
            }
        }
        check(api.retro_load_game(info)) { "The core failed to load $romPath" }
        avInfo = RetroSystemAvInfo().also { api.retro_get_system_av_info(it) }
        api.retro_set_controller_port_device(0, Retro.DEVICE_JOYPAD)
    }

    /** Emulates exactly one frame. Callbacks ([video], [audio], [input]) fire during this call. */
    fun run() = api.retro_run()

    fun reset() = api.retro_reset()

    /** Returns a copy of a memory region (e.g. [Retro.MEMORY_SYSTEM_RAM]), or null if the core doesn't expose it. */
    fun readMemory(id: Int): ByteArray? {
        val size = api.retro_get_memory_size(id)
        val pointer = api.retro_get_memory_data(id) ?: return null
        if (size <= 0) return null
        return pointer.getByteArray(0, size.toInt())
    }

    /** Overwrites a memory region (used to restore battery saves). */
    fun writeMemory(id: Int, bytes: ByteArray): Boolean {
        val size = api.retro_get_memory_size(id)
        val pointer = api.retro_get_memory_data(id) ?: return false
        if (size <= 0) return false
        pointer.write(0, bytes, 0, minOf(bytes.size.toLong(), size).toInt())
        return true
    }

    /** Serializes the whole emulator state (a "save state"). */
    fun saveState(): ByteArray? {
        val size = api.retro_serialize_size()
        if (size <= 0) return null
        val buffer = ByteArray(size.toInt())
        return if (api.retro_serialize(buffer, size)) buffer else null
    }

    fun loadState(state: ByteArray): Boolean = api.retro_unserialize(state, state.size.toLong())

    override fun close() {
        api.retro_unload_game()
        api.retro_deinit()
    }

    // region Callback implementations

    private fun onVideoRefresh(data: Pointer?, width: Int, height: Int, pitch: Long) {
        // A null frame means "same as the previous frame" (frame duping): nothing to do.
        if (data == null || width <= 0 || height <= 0) return
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * pitch
            when (pixelFormat) {
                Retro.PIXEL_FORMAT_XRGB8888 -> {
                    data.read(rowOffset, pixels, y * width, width)
                    for (x in 0 until width) pixels[y * width + x] = pixels[y * width + x] or OPAQUE
                }

                else -> {
                    val row = ShortArray(width)
                    data.read(rowOffset, row, 0, width)
                    for (x in 0 until width) {
                        pixels[y * width + x] = if (pixelFormat == Retro.PIXEL_FORMAT_RGB565) {
                            rgb565ToArgb(row[x].toInt())
                        } else {
                            rgb1555ToArgb(row[x].toInt())
                        }
                    }
                }
            }
        }
        video(Frame(width, height, pixels))
    }

    private fun onAudioBatch(data: Pointer, frames: Long): Long {
        val samples = (frames * 2).toInt() // stereo: interleaved left/right
        if (audioBuffer.size < samples) audioBuffer = ShortArray(samples)
        data.read(0, audioBuffer, 0, samples)
        audio(audioBuffer, frames.toInt())
        return frames
    }

    /**
     * The core asks the frontend something. Returning false means "unsupported", which cores must handle,
     * so we only implement what melonDS DS (and most other cores) actually need.
     */
    private fun onEnvironment(cmd: Int, data: Pointer?): Boolean {
        return when (cmd and Retro.ENV_EXPERIMENTAL.inv()) {
            Retro.ENV_GET_CAN_DUPE -> data?.setByte(0, 1).let { true }
            Retro.ENV_SET_PERFORMANCE_LEVEL,
            Retro.ENV_SET_INPUT_DESCRIPTORS,
            Retro.ENV_SET_CONTROLLER_INFO,
            Retro.ENV_SET_MEMORY_MAPS,
            Retro.ENV_SET_SERIALIZATION_QUIRKS,
            Retro.ENV_SET_CORE_OPTIONS_DISPLAY,
            Retro.ENV_SET_SUPPORT_NO_GAME,
            Retro.ENV_SET_VARIABLES -> true

            Retro.ENV_GET_SYSTEM_DIRECTORY -> data?.setPointer(0, nativeString(systemDirectory.toString())).let { true }
            Retro.ENV_GET_SAVE_DIRECTORY -> data?.setPointer(0, nativeString(saveDirectory.toString())).let { true }

            Retro.ENV_SET_PIXEL_FORMAT -> {
                val format = data?.getInt(0) ?: return false
                if (format != Retro.PIXEL_FORMAT_XRGB8888 && format != Retro.PIXEL_FORMAT_RGB565) return false
                pixelFormat = format
                true
            }

            Retro.ENV_GET_VARIABLE -> {
                if (data == null) return false
                val variable = RetroVariable(data)
                val value = options[variable.key] ?: return false
                data.setPointer(Native.POINTER_SIZE.toLong(), nativeString(value))
                true
            }

            Retro.ENV_GET_VARIABLE_UPDATE -> data?.setByte(0, 0).let { true }

            Retro.ENV_GET_LOG_INTERFACE -> {
                data?.setPointer(0, CallbackReference.getFunctionPointer(logCallback)) ?: return false
                true
            }

            Retro.ENV_SET_SYSTEM_AV_INFO -> {
                if (data != null) avInfo = RetroSystemAvInfo(data)
                true
            }

            Retro.ENV_SET_GEOMETRY -> true // Frames carry their own size, so we don't need to track it.
            Retro.ENV_GET_LANGUAGE -> data?.setInt(0, 0).let { true } // RETRO_LANGUAGE_ENGLISH
            Retro.ENV_GET_AUDIO_VIDEO_ENABLE -> data?.setInt(0, 0b11).let { true } // video + audio enabled
            Retro.ENV_GET_INPUT_BITMASKS -> false // We answer button by button, which every core supports.
            Retro.ENV_GET_CORE_OPTIONS_VERSION -> data?.setInt(0, 0).let { true } // Legacy options (GET_VARIABLE)
            else -> false
        }
    }

    // endregion

    private fun nativeString(value: String): Memory = nativeStrings.getOrPut(value) {
        Memory(value.toByteArray().size + 1L).also { it.setString(0, value) }
    }

    private fun logLevel(level: Int) = when (level) {
        0 -> "debug"
        1 -> "info"
        2 -> "warn"
        else -> "error"
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()

        fun rgb565ToArgb(value: Int): Int {
            val r = (value shr 11) and 0x1F
            val g = (value shr 5) and 0x3F
            val b = value and 0x1F
            return OPAQUE or ((r shl 3 or (r shr 2)) shl 16) or ((g shl 2 or (g shr 4)) shl 8) or (b shl 3 or (b shr 2))
        }

        fun rgb1555ToArgb(value: Int): Int {
            val r = (value shr 10) and 0x1F
            val g = (value shr 5) and 0x1F
            val b = value and 0x1F
            return OPAQUE or ((r shl 3 or (r shr 2)) shl 16) or ((g shl 3 or (g shr 2)) shl 8) or (b shl 3 or (b shr 2))
        }
    }
}
