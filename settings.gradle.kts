rootProject.name = "ai-plays-pokemon"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The Pokémon game client library (dev.kotlinds.pokemonclient): reads Pokémon games from RAM + ROM and
// drives them through typed actions. It must never depend on an emulator, only on its own ports.
include(":pokemon-client")

// Runs the client on a libretro core (ConsolePort adapter, cores, saves) and its headless bench: the only bridge
// between the client and an emulator.
include(":pokemon-client-libretro")
