package me.nathanfallet.aiplayspokemon.emulator

import kotlinx.io.files.Path as KotlinxPath
import java.nio.file.Path

/**
 * The same path for pokemon-client-libretro, whose API is multiplatform and takes kotlinx-io paths: the app keeps
 * `java.nio.file.Path` everywhere else (config, file pickers, saves) and converts at the library's door.
 */
fun Path.toKotlinxPath(): KotlinxPath = KotlinxPath(toString())
