package me.nathanfallet.aiplayspokemon.emulator.libretro

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.Structure

/*
 * JNA mapping of the libretro C API (see libretro.h in the libretro-common repository).
 *
 * libretro is a plain C interface implemented by emulator "cores" (melonDS DS, DeSmuME, mGBA, ...).
 * A frontend (us) loads the core as a shared library, registers a few callbacks (video, audio,
 * input, environment), loads a game, then calls `retro_run()` once per emulated frame.
 *
 * Only the parts of the API we need are mapped here. Names intentionally mirror the C names so
 * that the libretro documentation can be followed directly.
 */

/** Functions exported by every libretro core. */
@Suppress("FunctionName")
interface LibretroApi : Library {
    fun retro_api_version(): Int

    fun retro_set_environment(callback: EnvironmentCallback)
    fun retro_set_video_refresh(callback: VideoRefreshCallback)
    fun retro_set_audio_sample(callback: AudioSampleCallback)
    fun retro_set_audio_sample_batch(callback: AudioSampleBatchCallback)
    fun retro_set_input_poll(callback: InputPollCallback)
    fun retro_set_input_state(callback: InputStateCallback)

    fun retro_init()
    fun retro_deinit()

    fun retro_get_system_info(info: RetroSystemInfo)
    fun retro_get_system_av_info(info: RetroSystemAvInfo)
    fun retro_set_controller_port_device(port: Int, device: Int)

    fun retro_load_game(game: RetroGameInfo): Boolean
    fun retro_unload_game()
    fun retro_reset()
    fun retro_run()

    fun retro_serialize_size(): Long
    fun retro_serialize(data: ByteArray, size: Long): Boolean
    fun retro_unserialize(data: ByteArray, size: Long): Boolean

    fun retro_get_memory_data(id: Int): Pointer?
    fun retro_get_memory_size(id: Int): Long
}

// region Callbacks (C function pointers the core calls back into)

/** `bool (*)(unsigned cmd, void *data)`: the core asks the frontend for things (directories, options...). */
fun interface EnvironmentCallback : Callback {
    fun invoke(cmd: Int, data: Pointer?): Byte
}

/** `void (*)(const void *data, unsigned width, unsigned height, size_t pitch)`: a frame is ready. */
fun interface VideoRefreshCallback : Callback {
    fun invoke(data: Pointer?, width: Int, height: Int, pitch: Long)
}

/** `void (*)(int16_t left, int16_t right)`: one stereo audio sample. */
fun interface AudioSampleCallback : Callback {
    fun invoke(left: Short, right: Short)
}

/** `size_t (*)(const int16_t *data, size_t frames)`: a batch of interleaved stereo samples. */
fun interface AudioSampleBatchCallback : Callback {
    fun invoke(data: Pointer, frames: Long): Long
}

/** `void (*)(void)`: the core is about to read input for this frame. */
fun interface InputPollCallback : Callback {
    fun invoke()
}

/** `int16_t (*)(unsigned port, unsigned device, unsigned index, unsigned id)`: state of one input. */
fun interface InputStateCallback : Callback {
    fun invoke(port: Int, device: Int, index: Int, id: Int): Short
}

/** `void (*)(enum retro_log_level level, const char *fmt, ...)`: variadic args are ignored. */
fun interface LogCallback : Callback {
    fun invoke(level: Int, format: String?)
}

// endregion

// region Structures

@Structure.FieldOrder("library_name", "library_version", "valid_extensions", "need_fullpath", "block_extract")
class RetroSystemInfo : Structure() {
    @JvmField var library_name: String? = null
    @JvmField var library_version: String? = null
    @JvmField var valid_extensions: String? = null
    @JvmField var need_fullpath: Boolean = false
    @JvmField var block_extract: Boolean = false
}

@Structure.FieldOrder("base_width", "base_height", "max_width", "max_height", "aspect_ratio")
open class RetroGameGeometry : Structure {
    constructor() : super()
    constructor(pointer: Pointer) : super(pointer) { read() }

    @JvmField var base_width: Int = 0
    @JvmField var base_height: Int = 0
    @JvmField var max_width: Int = 0
    @JvmField var max_height: Int = 0
    @JvmField var aspect_ratio: Float = 0f

    class ByValue : RetroGameGeometry(), Structure.ByValue
}

@Structure.FieldOrder("fps", "sample_rate")
open class RetroSystemTiming : Structure() {
    @JvmField var fps: Double = 0.0
    @JvmField var sample_rate: Double = 0.0

    class ByValue : RetroSystemTiming(), Structure.ByValue
}

@Structure.FieldOrder("geometry", "timing")
class RetroSystemAvInfo : Structure {
    constructor() : super()
    constructor(pointer: Pointer) : super(pointer) { read() }

    @JvmField var geometry: RetroGameGeometry.ByValue = RetroGameGeometry.ByValue()
    @JvmField var timing: RetroSystemTiming.ByValue = RetroSystemTiming.ByValue()
}

@Structure.FieldOrder("path", "data", "size", "meta")
class RetroGameInfo : Structure() {
    @JvmField var path: String? = null
    @JvmField var data: Pointer? = null
    @JvmField var size: Long = 0
    @JvmField var meta: String? = null
}

/** `struct retro_variable { const char *key; const char *value; }` used by GET_VARIABLE. */
@Structure.FieldOrder("key", "value")
class RetroVariable(pointer: Pointer) : Structure(pointer) {
    @JvmField var key: String? = null
    @JvmField var value: Pointer? = null

    init {
        read()
    }
}

/** `struct retro_log_callback { retro_log_printf_t log; }` used by GET_LOG_INTERFACE. */
@Structure.FieldOrder("log")
class RetroLogCallback(pointer: Pointer) : Structure(pointer) {
    @JvmField var log: LogCallback? = null
}

// endregion

/** Numeric constants from libretro.h. */
@Suppress("unused")
object Retro {
    // Devices
    const val DEVICE_NONE = 0
    const val DEVICE_JOYPAD = 1
    const val DEVICE_POINTER = 6

    // Joypad button ids (RETRO_DEVICE_ID_JOYPAD_*)
    const val JOYPAD_B = 0
    const val JOYPAD_Y = 1
    const val JOYPAD_SELECT = 2
    const val JOYPAD_START = 3
    const val JOYPAD_UP = 4
    const val JOYPAD_DOWN = 5
    const val JOYPAD_LEFT = 6
    const val JOYPAD_RIGHT = 7
    const val JOYPAD_A = 8
    const val JOYPAD_X = 9
    const val JOYPAD_L = 10
    const val JOYPAD_R = 11
    const val JOYPAD_MASK = 256

    // Pointer (touch screen) ids
    const val POINTER_X = 0
    const val POINTER_Y = 1
    const val POINTER_PRESSED = 2

    // Memory ids for retro_get_memory_data
    const val MEMORY_SAVE_RAM = 0
    const val MEMORY_SYSTEM_RAM = 2

    // Pixel formats
    const val PIXEL_FORMAT_0RGB1555 = 0
    const val PIXEL_FORMAT_XRGB8888 = 1
    const val PIXEL_FORMAT_RGB565 = 2

    // Environment commands (only the ones we answer; others get `false` = "not supported")
    const val ENV_EXPERIMENTAL = 0x10000
    const val ENV_GET_CAN_DUPE = 3
    const val ENV_SET_PERFORMANCE_LEVEL = 8
    const val ENV_GET_SYSTEM_DIRECTORY = 9
    const val ENV_SET_PIXEL_FORMAT = 10
    const val ENV_SET_INPUT_DESCRIPTORS = 11
    const val ENV_GET_VARIABLE = 15
    const val ENV_SET_VARIABLES = 16
    const val ENV_GET_VARIABLE_UPDATE = 17
    const val ENV_SET_SUPPORT_NO_GAME = 18
    const val ENV_GET_LOG_INTERFACE = 27
    const val ENV_GET_SAVE_DIRECTORY = 31
    const val ENV_SET_SYSTEM_AV_INFO = 32
    const val ENV_SET_CONTROLLER_INFO = 35
    const val ENV_SET_MEMORY_MAPS = 36
    const val ENV_SET_GEOMETRY = 37
    const val ENV_GET_LANGUAGE = 39
    const val ENV_SET_SERIALIZATION_QUIRKS = 44
    const val ENV_GET_AUDIO_VIDEO_ENABLE = 47
    const val ENV_GET_INPUT_BITMASKS = 51
    const val ENV_GET_CORE_OPTIONS_VERSION = 52
    const val ENV_SET_CORE_OPTIONS_DISPLAY = 55
}
