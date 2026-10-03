package dev.kotlinds.pokemonclient

/**
 * Reads a text file bundled with the library (UTF-8), or null when it doesn't exist.
 *
 * Only used by the temporary tables generated from the decompilation (`hgss/` resources), which will be
 * replaced by reading the data from the ROM itself; platforms just need a way to reach their resources.
 */
internal expect fun readBundledText(path: String): String?
