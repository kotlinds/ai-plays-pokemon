package dev.kotlinds.pokemonclient

/** JVM: bundled files are classpath resources (src/jvmMain/resources). */
internal actual fun readBundledText(path: String): String? =
    object {}.javaClass.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
