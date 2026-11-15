package com.osakaresearch.spark.ai

/**
 * Wire shape for a provider — either OpenAI-style chat completions or
 * Anthropic's /v1/messages. Minimax, LM Studio, ollama, groq, together.ai
 * and most self-hosted gateways all speak OpenAI-shape over a swappable
 * base URL, so they collapse into [Shape.OpenAI] + a custom host.
 */
enum class Shape { OpenAI, Anthropic }

enum class AiProvider(
    val id: String,
    val displayName: String,
    val shape: Shape,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    OpenAI(
        id = "openai",
        displayName = "OpenAI",
        shape = Shape.OpenAI,
        defaultBaseUrl = "https://api.openai.com",
        defaultModel = "gpt-4o",
    ),
    Anthropic(
        id = "anthropic",
        displayName = "Anthropic",
        shape = Shape.Anthropic,
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-4-6",
    ),
    Minimax(
        id = "minimax",
        displayName = "MiniMax",
        shape = Shape.OpenAI,
        defaultBaseUrl = "https://api.minimax.chat",
        defaultModel = "MiniMax-Text-01",
    ),
    Custom(
        id = "custom",
        displayName = "Custom (OpenAI-compatible)",
        shape = Shape.OpenAI,
        defaultBaseUrl = "",
        defaultModel = "",
    );

    companion object {
        fun byId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: OpenAI
    }
}
