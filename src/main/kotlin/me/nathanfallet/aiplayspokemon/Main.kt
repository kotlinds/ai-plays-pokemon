package me.nathanfallet.aiplayspokemon

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.emulator.libretro.LibretroCoreSpec
import me.nathanfallet.aiplayspokemon.emulator.libretro.LibretroEmulator
import me.nathanfallet.aiplayspokemon.game.PokemonGames
import me.nathanfallet.aiplayspokemon.ui.App
import me.nathanfallet.aiplayspokemon.ui.AppController
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/**
 * Entry point. Wires the layers together:
 *
 *   emulator (libretro core)  →  game (reads RAM into an Observation)
 *        ↑ buttons                     ↓
 *   agent (PokemonPlayer)  ←  decision model (Jev, or a local LLM)
 *
 * and shows everything in a Compose window.
 */
fun main(args: Array<String>) {
    val config = AppConfig.load(args)
    val rom = config.romPath ?: chooseRom()?.also(config::saveRomPath) ?: return

    val emulator = LibretroEmulator(LibretroCoreSpec.forRom(rom), rom, config.dataDirectory)
    val game = PokemonGames.detect(rom)
    emulator.start()

    application {
        val scope = rememberCoroutineScope()
        val controller = remember { AppController(emulator, game, config, scope) }
        Window(
            onCloseRequest = {
                controller.player.value?.pause()
                emulator.close() // unloads the game, which writes the in-game save to disk
                exitApplication()
            },
            title = "AI plays Pokémon",
            state = rememberWindowState(size = DpSize(1040.dp, 840.dp)),
            onKeyEvent = controller::onKeyEvent,
        ) {
            App(controller)
        }
    }
}

/** Asks for a ROM with the native file picker (the choice is remembered in the config file). */
private fun chooseRom(): Path? {
    val dialog = FileDialog(null as Frame?, "Choose a Pokémon ROM (.nds)", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".nds", ignoreCase = true) }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return Path.of(dialog.directory, file)
}
