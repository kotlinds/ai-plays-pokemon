package me.nathanfallet.aiplayspokemon.emulator.libretro

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * A libretro core we know how to run: where to download it and which options to give it.
 *
 * Adding another emulator (e.g. mGBA for GBA Pokémon games) is mostly adding an entry here.
 */
enum class LibretroCoreSpec(
    /** File name on the libretro buildbot, without the platform extension (e.g. "melonds_libretro"). */
    val buildbotName: String,
    /** Core options answered through RETRO_ENVIRONMENT_GET_VARIABLE; missing keys use the core's defaults. */
    val options: Map<String, String>,
) {
    /**
     * melonDS 0.9.3 (libretro port). We don't use the newer "melonDS DS" core because its ARM JIT
     * installs signal handlers that conflict with the JVM's own, which crashes the process.
     * The interpreter is plenty fast: ~4x real time on an Apple Silicon Mac.
     */
    MELONDS(
        buildbotName = "melonds_libretro",
        options = mapOf(
            "melonds_console_mode" to "DS",
            "melonds_boot_directly" to "enabled", // skip the DS firmware menu (FreeBIOS can't show it anyway)
            "melonds_threaded_renderer" to "disabled", // frames must be complete when retro_run() returns
            "melonds_screen_layout" to "Top/Bottom", // 256x384: top screen above bottom screen
            "melonds_touch_mode" to "Touch", // the touch screen is driven by the libretro pointer device
            "melonds_language" to "English",
        ),
    ),
    ;

    companion object {
        /** Picks the core able to run a ROM, from its file extension. */
        fun forRom(rom: Path): LibretroCoreSpec = when (rom.fileName.toString().substringAfterLast('.').lowercase()) {
            "nds" -> MELONDS
            else -> throw IllegalArgumentException("No emulator core configured for $rom")
        }
    }

    /** Downloads the core from the libretro buildbot into [directory] if it isn't there yet. */
    fun resolve(directory: Path): Path {
        val platform = Platform.current()
        val target = directory.resolve("$buildbotName.${platform.extension}")
        if (Files.exists(target)) return target

        Files.createDirectories(directory)
        val url = "https://buildbot.libretro.com/nightly/${platform.buildbotPath}/latest/$buildbotName.${platform.extension}.zip"
        println("Downloading libretro core from $url")
        val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw IOException("Failed to download $url (HTTP ${response.statusCode()})")

        // The zip contains a single file: the core library.
        ZipInputStream(response.body()).use { zip ->
            val entry = zip.nextEntry ?: throw IOException("Empty archive at $url")
            val temporary = directory.resolve("${entry.name}.part")
            Files.copy(zip, temporary, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    /** Platform naming used by the libretro buildbot. */
    private enum class Platform(val buildbotPath: String, val extension: String) {
        MAC_ARM64("apple/osx/arm64", "dylib"),
        MAC_X64("apple/osx/x86_64", "dylib"),
        LINUX_X64("linux/x86_64", "so"),
        WINDOWS_X64("windows/x86_64", "dll");

        companion object {
            fun current(): Platform {
                val os = System.getProperty("os.name").lowercase()
                val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
                return when {
                    "mac" in os -> if (arm) MAC_ARM64 else MAC_X64
                    "win" in os -> WINDOWS_X64
                    else -> LINUX_X64
                }
            }
        }
    }
}
