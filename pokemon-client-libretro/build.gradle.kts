/*
 * pokemon-client-libretro: runs the Pokémon client on a libretro core. It provides the ConsolePort adapter over
 * libretro-kmp (LibretroConsole), the known cores (pinned downloads checked by SHA-256), the save file conversions
 * between cores, and a headless bench to try actions on a ROM without the app (e.g. when adding a game).
 *
 * pokemon-client itself never depends on an emulator: this module is the only bridge.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":pokemon-client"))
    api(libs.libretro.kmp)
    implementation(libs.jna) // music during pauses: opens core files with RTLD_LOCAL (see LibretroCoreSpec.preloadIsolated)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

// The headless bench (see Bench.kt): runs commands and typed actions on a ROM, without the app, e.g.
// POKEMON_ROM=/path/rom.nds ./gradlew -q :pokemon-client-libretro:bench -PbenchArgs="<data dir>|<out dir>|load:x.state|step:1|state"
// (BENCH_WINDOW=1 shows the game live, muted).
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs the headless bench on the ROM of POKEMON_ROM."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.kotlinds.pokemonclient.libretro.bench.BenchKt"
    args = providers.gradleProperty("benchArgs").map { it.split("|") }.getOrElse(emptyList())
}
