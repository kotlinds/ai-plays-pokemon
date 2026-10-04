package dev.kotlinds.pokemonclient.libretro

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What a [LibretroConsole] is for. */
enum class ConsoleRole {
    /** The game: uses the data directory, loads and writes back the in-game save. */
    MAIN,

    /**
     * A second, throwaway instance of the core, next to the main one (music during pauses: it plays the paused game's
     * music). It loads its own copy of the core file ([LibretroCoreSpec.resolveShadow]), works in a temporary copy of
     * `system/` and of the in-game save (deleted when closed), and never writes the game's files.
     */
    SHADOW;

    internal companion object {
        /** A temporary directory with copies of `system/` and `saves/<rom>.sav` of [dataDirectory]. */
        fun throwawayDirectory(dataDirectory: Path, romBase: String): Path {
            val directory = Files.createTempDirectory("ai-plays-pokemon-shadow")
            val system = dataDirectory.resolve("system")
            if (Files.isDirectory(system)) {
                Files.walk(system).use { files ->
                    files.forEach { file ->
                        val target = directory.resolve("system").resolve(system.relativize(file).toString())
                        if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            val save = dataDirectory.resolve("saves").resolve("$romBase.sav")
            if (Files.exists(save)) {
                Files.createDirectories(directory.resolve("saves"))
                Files.copy(save, directory.resolve("saves").resolve("$romBase.sav"))
            }
            return directory
        }
    }
}
