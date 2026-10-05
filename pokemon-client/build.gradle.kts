plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

group = "dev.kotlinds"
version = "0.1.0"

/**
 * Pokémon game client: reads a Pokémon game from its RAM (and ROM) and drives it through typed actions.
 *
 * Kotlin Multiplatform with the JVM target only for now: commonMain keeps the code free of java.* APIs so
 * more targets can be added when the library is published. Code that still needs the JVM (resource tables
 * generated from the decompilation, replaced by ROM reads later) lives in jvmMain.
 *
 * Hard rule: no emulator dependency here (no libretro, no JNA). The emulator is reached through the
 * ports defined in this module (see `ConsolePort`), implemented by the app.
 */
kotlin {
    jvmToolchain(21)
    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
                // ROM tests run only when POKEMON_ROM is set: make it an input so results with and without a ROM
                // aren't taken for each other from the build cache.
                inputs.property("pokemonRom", System.getenv("POKEMON_ROM").orEmpty())
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinds.rom)
            implementation(libs.kotlinds.narc)
            implementation(libs.kotlinds.compression)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
