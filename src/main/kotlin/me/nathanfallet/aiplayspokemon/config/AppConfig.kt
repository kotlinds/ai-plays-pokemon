package me.nathanfallet.aiplayspokemon.config

import me.nathanfallet.aiplayspokemon.decision.DecisionBackend
import me.nathanfallet.aiplayspokemon.decision.jev.JevClient
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * App settings. Each one is read from an environment variable first, then from
 * `config.properties` in [dataDirectory] (where the settings changed in the UI are saved):
 *
 * | Setting               | Environment variable                    | Config key              |
 * |-----------------------|-----------------------------------------|-------------------------|
 * | ROM                   | `POKEMON_ROM` (or first argument)       | `rom`                   |
 * | Decision model        | `DECISION_BACKEND` (`jev` / `llm`)      | `backend`               |
 * | Jev API key           | `TYPESAFE_API_KEY`                      | `typesafe.apiKey`       |
 * | Jev model             | `JEV_MODEL`                             | `typesafe.model`        |
 * | Jev endpoint          | `JEV_ENDPOINT`                          | `typesafe.endpoint`     |
 * | LLM provider          | `LLM_PROVIDER` (`openai`, `anthropic`, `openrouter`, `ollama`) | `llm.provider` |
 * | LLM model             | `LLM_MODEL`                             | `llm.<provider>.model`  |
 * | LLM API key           | `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY` | `llm.<provider>.apiKey` |
 *
 * Everything the app writes (config, downloaded cores, in-game saves, save states) lives in
 * [dataDirectory], `~/.ai-plays-pokemon` by default (`AI_PLAYS_POKEMON_DATA_DIR` to change it).
 */
class AppConfig(
    val dataDirectory: Path,
    private val properties: Properties,
    val romPath: Path?,
) {
    private val configFile get() = dataDirectory.resolve(CONFIG_FILE)

    // region Decision model

    var backend: DecisionBackend
        get() = enumSetting("DECISION_BACKEND", "backend") ?: DecisionBackend.JEV
        set(value) = save("backend", value.name.lowercase())

    // endregion

    // region Jev

    var jevApiKey: String?
        get() = setting("TYPESAFE_API_KEY", "typesafe.apiKey")
        set(value) = save("typesafe.apiKey", value)

    val jevModel: String get() = setting("JEV_MODEL", "typesafe.model") ?: JevClient.DEFAULT_MODEL

    val jevEndpoint: URI get() = setting("JEV_ENDPOINT", "typesafe.endpoint")?.let(URI::create) ?: JevClient.DEFAULT_ENDPOINT

    // endregion

    // region LLMs

    var llmProvider: LlmProvider
        get() = enumSetting("LLM_PROVIDER", "llm.provider") ?: LlmProvider.OLLAMA
        set(value) = save("llm.provider", value.name.lowercase())

    fun llmModel(provider: LlmProvider): String =
        setting("LLM_MODEL".takeIf { provider == llmProvider }, "llm.${provider.key}.model") ?: provider.defaultModel

    fun saveLlmModel(provider: LlmProvider, model: String) = save("llm.${provider.key}.model", model)

    fun llmApiKey(provider: LlmProvider): String? = setting(provider.apiKeyVariable, "llm.${provider.key}.apiKey")

    fun saveLlmApiKey(provider: LlmProvider, key: String) = save("llm.${provider.key}.apiKey", key)

    // endregion

    fun saveRomPath(path: Path) = save("rom", path.toAbsolutePath().toString())

    private val LlmProvider.key get() = name.lowercase()

    private fun setting(variable: String?, key: String): String? =
        variable?.let(System::getenv)?.takeIf { it.isNotBlank() } ?: properties.getProperty(key)?.takeIf { it.isNotBlank() }

    private inline fun <reified T : Enum<T>> enumSetting(variable: String, key: String): T? =
        setting(variable, key)?.let { value -> enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } }

    private fun save(key: String, value: String?) {
        if (value == null) properties.remove(key) else properties.setProperty(key, value)
        Files.createDirectories(dataDirectory)
        configFile.outputStream().use { properties.store(it, "AI plays Pokémon") }
    }

    companion object {
        private const val CONFIG_FILE = "config.properties"

        fun load(args: Array<String>): AppConfig {
            val dataDirectory = System.getenv("AI_PLAYS_POKEMON_DATA_DIR")?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".ai-plays-pokemon")
            val properties = Properties()
            dataDirectory.resolve(CONFIG_FILE).takeIf { it.exists() }?.inputStream()?.use(properties::load)

            val rom = args.firstOrNull() ?: System.getenv("POKEMON_ROM") ?: properties.getProperty("rom")
            return AppConfig(dataDirectory, properties, rom?.let(Path::of)?.takeIf { it.exists() })
        }
    }
}
