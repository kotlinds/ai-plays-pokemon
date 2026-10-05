package me.nathanfallet.aiplayspokemon

import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import kotlin.io.path.readBytes
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import me.nathanfallet.aiplayspokemon.config.AppConfig
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import kotlinx.coroutines.runBlocking
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import dev.kotlinds.pokemonclient.PokemonGames
import me.nathanfallet.aiplayspokemon.ui.App
import me.nathanfallet.aiplayspokemon.ui.AppController
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/**
 * Entry point. Wires the layers together:
 *
 *   emulator (libretro core)  →  game (reads RAM into the typed GameState)
 *        ↑ buttons                     ↓
 *   agent (PokemonPlayer)  ←  decision model (the AI: Jev, or an LLM through Koog)
 *
 * and shows everything in a Compose window.
 */
fun main(args: Array<String>) {
    val config = AppConfig.load(args)
    val rom = config.romPath ?: chooseRom()?.also(config::saveRomPath) ?: return

    val emulator = ConsoleHost(LibretroCoreSpec.forRom(rom.toKotlinxPath(), config.emulatorCore), rom, config.dataDirectory)
    val game = PokemonGames.detect(rom.readBytes())
    if (config.startMuted) emulator.setMuted(true)
    config.startState?.let { slot -> runBlocking { check(emulator.loadState(slot)) { "No save state in slot $slot" } } }
    emulator.start()

    application {
        val scope = rememberCoroutineScope()
        val controller = remember { AppController(emulator, game, config, scope) }
        val windowState = rememberWindowState(size = DpSize(1068.dp, 840.dp))
        Window(
            onCloseRequest = {
                controller.player.value?.pause()
                emulator.close() // unloads the game, which writes the in-game save to disk
                exitApplication()
            },
            title = "AI plays Pokémon",
            state = windowState,
            onKeyEvent = controller::onKeyEvent,
        ) {
            App(controller, windowState)
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
