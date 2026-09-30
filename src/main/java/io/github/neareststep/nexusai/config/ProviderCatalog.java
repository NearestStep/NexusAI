package io.github.neareststep.nexusai.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Official endpoints and wire types for the bundled providers.
 * {@code gemini} keeps its own type. Every other bundled provider speaks the OpenAI chat-completions API.
 */
public final class ProviderCatalog {

    public static final String TYPE_OPENAI = "openai-compatible";
    public static final String TYPE_GEMINI = "gemini";

    public static final List<String> IDS = List.of(
            "openai", "groq", "cerebras", "gemini", "deepseek", "ollama", "openrouter");

    private static final Map<String, String> URLS = Map.of(
            "openai", "https://api.openai.com/v1",
            "groq", "https://api.groq.com/openai/v1",
            "cerebras", "https://api.cerebras.ai/v1",
            "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "deepseek", "https://api.deepseek.com",
            "ollama", "http://localhost:11434/v1",
            "openrouter", "https://openrouter.ai/api/v1"
    );

    private ProviderCatalog() {
    }

    public static String officialUrl(String providerId) {
        if (providerId == null) {
            return URLS.get("openai");
        }
        return URLS.getOrDefault(providerId.toLowerCase(Locale.ROOT), URLS.get("openai"));
    }

    public static String typeFor(String providerId) {
        if (providerId != null && providerId.equalsIgnoreCase(TYPE_GEMINI)) {
            return TYPE_GEMINI;
        }
        return TYPE_OPENAI;
    }

    /**
     * Accepts {@code openai-compatible}, {@code gemini}, and the historical provider ids.
     * Unknown values stay on the OpenAI-compatible client.
     */
    public static String normalizeType(String raw, String providerId) {
        if (raw == null || raw.isBlank()) {
            return typeFor(providerId);
        }
        String type = raw.trim().toLowerCase(Locale.ROOT);
        if (TYPE_GEMINI.equals(type)) {
            return TYPE_GEMINI;
        }
        return TYPE_OPENAI;
    }
}
