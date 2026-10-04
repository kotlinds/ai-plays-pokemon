import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kover)
}

group = "me.nathanfallet.aiplayspokemon"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // UI: Compose for Desktop (window, emulator screen, control panel)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    // `-Puniversal`: also ship the UI natives of every desktop platform, so the uber jar
    // (`./gradlew packageUberJarForCurrentOS -Puniversal`) runs on macOS, Linux and Windows alike.
    if (providers.gradleProperty("universal").isPresent) {
        runtimeOnly(compose.desktop.macos_arm64)
        runtimeOnly(compose.desktop.macos_x64)
        runtimeOnly(compose.desktop.linux_x64)
        runtimeOnly(compose.desktop.windows_x64)
    }

    // Decision models: Jev (JSON over the JDK HTTP client) and LLMs through Koog
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koog.agents) // OpenAI, Anthropic, Ollama clients
    implementation(libs.koog.openrouter) // OpenRouter: Gemini, Mistral, DeepSeek, Llama... behind one key
    runtimeOnly(libs.slf4j.nop) // Koog logs through SLF4J: silence it

    // The Pokémon game client library of this repository (reads the game, typed actions), and its bridge to a
    // libretro core (console adapter, DeSmuME / melonDS cores, saves, headless bench)
    implementation(project(":pokemon-client"))
    implementation(project(":pokemon-client-libretro"))

    // MCP server: lets an external agent (e.g. Claude Code) play through tools
    implementation(libs.mcp.server)
    implementation(libs.ktor.server.cio)


    // Test coverage: `./gradlew koverHtmlReport` covers the app and the pokemon-client library together.
    kover(project(":pokemon-client"))
    kover(project(":pokemon-client-libretro"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "me.nathanfallet.aiplayspokemon.MainKt"
        if (System.getProperty("os.name").startsWith("Mac")) jvmArgs += listOf("-Xdock:name=AI plays Pokémon")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AI Plays Pokemon"
            packageVersion = "1.0.0"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Development helper: runs any main class of the project, e.g.
// ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.SomeToolKt -PdevArgs="a|b|c"
tasks.register<JavaExec>("devRun") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = providers.gradleProperty("devMain")
    args = providers.gradleProperty("devArgs").map { it.split("|") }.getOrElse(emptyList())
}
