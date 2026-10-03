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
        mavenLocal() // dev.kotlinds:libretro-kmp, published locally for now
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The Pokémon game client library (dev.kotlinds.pokemonclient): reads Pokémon games from RAM + ROM and
// drives them through typed actions. It must never depend on an emulator, only on its own ports.
include(":pokemon-client")
