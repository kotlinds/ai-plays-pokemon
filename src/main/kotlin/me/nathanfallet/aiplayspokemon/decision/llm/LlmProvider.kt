package me.nathanfallet.aiplayspokemon.decision.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * The LLM providers we can play with, all through [Koog](https://github.com/JetBrains/koog).
 *
 * OpenRouter gives access to most other models (Gemini, Mistral, DeepSeek, Llama, Qwen...) with a
 * single key; Ollama runs models locally.
 */
enum class LlmProvider(
    val label: String,
    /** A sensible default model id for the provider (any id the provider accepts works). */
    val defaultModel: String,
    /** Environment variable read for the API key, or null when no key is needed. */
    val apiKeyVariable: String?,
) {
    OPENAI("OpenAI", "gpt-5-mini", "OPENAI_API_KEY"),
    ANTHROPIC("Anthropic", "claude-haiku-4-5", "ANTHROPIC_API_KEY"),
    OPENROUTER("OpenRouter", "google/gemini-2.5-flash", "OPENROUTER_API_KEY"),
    OLLAMA("Ollama (local)", "gemma3:4b", null),
    ;

    val needsApiKey: Boolean get() = apiKeyVariable != null

    /** Creates the Koog executor talking to this provider. */
    fun executor(apiKey: String?): MultiLLMPromptExecutor {
        fun key() = requireNotNull(apiKey) { "$label needs an API key ($apiKeyVariable)" }
        return when (this) {
            OPENAI -> MultiLLMPromptExecutor(LLMProvider.OpenAI to OpenAILLMClient(key()))
            ANTHROPIC -> MultiLLMPromptExecutor(LLMProvider.Anthropic to AnthropicLLMClient(key()))
            OPENROUTER -> MultiLLMPromptExecutor(LLMProvider.OpenRouter to OpenRouterLLMClient(key()))
            OLLAMA -> MultiLLMPromptExecutor(LLMProvider.Ollama to OllamaClient())
        }
    }

    /** Describes a model of this provider for Koog: we only need plain text completion. */
    fun model(id: String): LLModel = when (this) {
        OPENAI -> LLModel(LLMProvider.OpenAI, id, listOf(LLMCapability.Completion, LLMCapability.OpenAIEndpoint.Completions))
        ANTHROPIC -> LLModel(LLMProvider.Anthropic, id, listOf(LLMCapability.Completion), maxOutputTokens = 1024)
        OPENROUTER -> LLModel(LLMProvider.OpenRouter, id, listOf(LLMCapability.Completion))
        OLLAMA -> LLModel(LLMProvider.Ollama, id, listOf(LLMCapability.Completion))
    }
}
