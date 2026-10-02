import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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

    // Decision models: Jev (JSON over the JDK HTTP client) and LLMs through Koog
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koog.agents) // OpenAI, Anthropic, Ollama clients
    implementation(libs.koog.openrouter) // OpenRouter: Gemini, Mistral, DeepSeek, Llama... behind one key
    runtimeOnly(libs.slf4j.nop) // Koog logs through SLF4J: silence it

    // Emulator: loads a libretro core (melonDS) as a native library
    implementation(libs.jna)

    // MCP server: lets an external agent (e.g. Claude Code) play through tools
    implementation(libs.mcp.server)
    implementation(libs.ktor.server.cio)

    // Nintendo DS ROM parsing (game code...), from the kotlinds organization
    implementation(libs.kotlinds.rom)

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
