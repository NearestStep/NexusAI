package io.github.neareststep.nexusai.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.neareststep.nexusai.ai.dto.ChatCompletionRequest;
import io.github.neareststep.nexusai.ai.dto.ChatCompletionResponse;
import io.github.neareststep.nexusai.config.PluginConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenAI-compatible chat completions client.
 */
public final class OpenAiProvider implements AiProvider {

    private final PluginConfig config;
    private final ExecutorService executor;
    private final Logger logger;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiProvider(PluginConfig config, ExecutorService executor, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .executor(executor)
                .build();
    }

    OpenAiProvider(PluginConfig config, ExecutorService executor, Logger logger, HttpClient httpClient, ObjectMapper objectMapper) {
        this.config = config;
        this.executor = executor;
        this.logger = logger;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public CompletableFuture<String> complete(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        return CompletableFuture.supplyAsync(() -> doComplete(prompt), executor);
    }

    private String doComplete(String prompt) {
        try {
            URI parsedUri = URI.create(config.getBaseUrl() + "/chat/completions");

            ChatCompletionRequest body = new ChatCompletionRequest(
                    config.getModel(),
                    List.of(new ChatCompletionRequest.Message("user", prompt))
            );
            byte[] json = objectMapper.writeValueAsBytes(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(parsedUri)
                    .timeout(config.getReadTimeout())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "NexusAI (Paper-plugin; Java-HttpClient)")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body() == null ? "" : response.body();
            boolean htmlBody = looksLikeHtml(responseBody);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 403 && htmlBody) {
                    logger.warning("HTTP 403 returned an HTML page (likely Cloudflare/WAF), not a JSON API error. "
                            + "Check api.base-url host, server IP allowlists, and that the endpoint accepts this client. "
                            + "host=" + parsedUri.getHost());
                } else if (response.statusCode() == 403) {
                    logger.warning("OpenAI returned HTTP 403 Forbidden. "
                            + "This often means the API key/account cannot access the endpoint "
                            + "from this server (region/org restriction). "
                            + "Try another api.base-url or a key with access.");
                } else if (response.statusCode() == 402) {
                    logger.warning("OpenAI returned HTTP 402 Insufficient Balance. "
                            + "The API accepted the key, but the provider account has no credit. "
                            + "Top up billing on the provider (or switch api.key / api.base-url), then retry.");
                }
                throw new IllegalStateException("OpenAI HTTP " + response.statusCode() + ": " + truncate(responseBody));
            }

            if (htmlBody) {
                throw new IllegalStateException("API returned HTML instead of JSON from " + parsedUri.getHost());
            }

            ChatCompletionResponse parsed = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
            if (parsed.getChoices() == null || parsed.getChoices().isEmpty()
                    || parsed.getChoices().getFirst().getMessage() == null
                    || parsed.getChoices().getFirst().getMessage().getContent() == null) {
                throw new IllegalStateException("OpenAI response missing choices/message/content");
            }
            return parsed.getChoices().getFirst().getMessage().getContent().trim();
        } catch (Exception e) {
            logger.log(Level.WARNING, "OpenAI request failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String head = body.length() > 64 ? body.substring(0, 64).toLowerCase(Locale.ROOT) : body.toLowerCase(Locale.ROOT);
        return head.contains("<!doctype html") || head.contains("<html");
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
