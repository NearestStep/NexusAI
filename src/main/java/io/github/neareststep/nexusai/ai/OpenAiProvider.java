package io.github.neareststep.nexusai.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.neareststep.nexusai.ai.dto.ChatCompletionRequest;
import io.github.neareststep.nexusai.ai.dto.ChatCompletionResponse;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.SecretMask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenAI-compatible chat completions client.
 * Logging of classified failures happens in {@link AiDiagnostics}; this class only throws.
 */
public final class OpenAiProvider implements AiProvider, ChatCaller {

    private final PluginConfig config;
    private final ExecutorService executor;
    private final Logger logger;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiProvider(PluginConfig config, ExecutorService executor, Logger logger, HttpClient httpClient) {
        this(config, executor, logger, Objects.requireNonNull(httpClient, "httpClient"),
                new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL));
    }

    public OpenAiProvider(PluginConfig config, ExecutorService executor, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // Do not reuse the plugin HTTP pool here. doComplete() blocks on HttpClient.send,
        // and the client's own timeouts/callbacks must be able to run on a different pool.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
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
        return complete(prompt, GenerationOverrides.none());
    }

    @Override
    public CompletableFuture<String> complete(String prompt, GenerationOverrides overrides) {
        Objects.requireNonNull(prompt, "prompt");
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        return CompletableFuture.supplyAsync(() -> doComplete(prompt, effective), executor);
    }

    public static ChatCompletionRequest buildBody(PluginConfig config, String prompt, GenerationOverrides overrides) {
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        String system = blankToNull(effective.systemPrompt(config.getSystemPrompt()));
        String instruction = config.presetFor(effective.formatOr(config.defaultFormatId())).instruction();
        if (instruction != null && !instruction.isBlank()) {
            system = system == null ? instruction : system + "\n\n" + instruction;
        }
        system = PlayerInput.appendGuard(system, PlayerInput.containsWrappedInput(prompt));
        if (system.isBlank()) {
            system = null;
        }
        Double temperature = effective.temperature(config.getTemperature());
        Integer maxTokens = effective.maxTokens(config.getMaxTokens());
        String model = effective.model(config.getModel());
        Integer maxCompletionTokens = null;
        String reasoningEffort = null;
        if (ReasoningModels.isReasoning(model)) {
            reasoningEffort = config.getReasoningEffort();
            int requested = maxTokens == null
                    ? ReasoningModels.TOKEN_FLOOR
                    : Math.max(maxTokens, ReasoningModels.TOKEN_FLOOR);
            if (ReasoningModels.usesCompletionTokenCap(model)) {
                maxCompletionTokens = requested;
                maxTokens = null;
                temperature = null;
            } else {
                maxTokens = requested;
            }
        }
        List<ChatCompletionRequest.Message> messages = new ArrayList<>(2);
        if (system != null) {
            messages.add(new ChatCompletionRequest.Message("system", system));
        }
        messages.add(new ChatCompletionRequest.Message("user", prompt));
        return new ChatCompletionRequest(model, List.copyOf(messages), temperature, maxTokens, maxCompletionTokens, reasoningEffort);
    }

    /**
     * Synchronous completion against an explicit endpoint. Used by {@link RoutingProvider}.
     * {@code model} replaces the queue model when the prompt did not set its own.
     */
    public ChatExchange exchange(
            String prompt,
            GenerationOverrides overrides,
            String baseUrl,
            String apiKey,
            String model
    ) {
        return exchange(prompt, overrides, baseUrl, apiKey, model, true);
    }

    /**
     * Same HTTP call as {@link #exchange}, without answer formatting or player-input rejection.
     * Chat moderation parses the raw JSON verdict. Those filters are for text shown to players,
     * and they would drop a verdict that quotes the chat line.
     */
    public ChatExchange exchangeRaw(
            String prompt,
            GenerationOverrides overrides,
            String baseUrl,
            String apiKey,
            String model
    ) {
        return exchange(prompt, overrides, baseUrl, apiKey, model, false);
    }

    private ChatExchange exchange(
            String prompt,
            GenerationOverrides overrides,
            String baseUrl,
            String apiKey,
            String model,
            boolean filterAnswer
    ) {
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        if (model != null && !model.isBlank()) {
            effective = effective.withModel(model);
        }
        String root = baseUrl == null || baseUrl.isBlank() ? config.getBaseUrl() : baseUrl;
        URI parsedUri = URI.create(trimSlash(root) + "/chat/completions");
        logger.log(Level.FINE, "POST {0}", parsedUri);
        try {
            ChatCompletionRequest body = buildBody(config, prompt, effective);
            byte[] json = objectMapper.writeValueAsBytes(body);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(parsedUri)
                    .timeout(config.getReadTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "NexusAI (Paper-plugin; Java-HttpClient)");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body() == null ? "" : response.body();
            boolean htmlBody = looksLikeHtml(responseBody);
            Map<String, List<String>> headers = response.headers().map();

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpError(
                        response.statusCode(),
                        SecretMask.redact(responseBody, List.of(apiKey)),
                        htmlBody,
                        parsedUri,
                        retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(null)),
                        headers
                );
            }
            if (htmlBody) {
                throw new AiRequestException(
                        AiErrorKind.OTHER,
                        response.statusCode(),
                        "API returned HTML instead of JSON from " + parsedUri.getHost(),
                        null
                );
            }

            ChatCompletionResponse parsed = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
            if (parsed.getChoices() == null || parsed.getChoices().isEmpty()
                    || parsed.getChoices().getFirst().getMessage() == null) {
                throw new AiRequestException(AiErrorKind.OTHER, response.statusCode(), "OpenAI response missing choices/message/content", null);
            }
            ChatCompletionResponse.Choice choice = parsed.getChoices().getFirst();
            String text = choice.getMessage().visibleText();
            if (text == null || text.isBlank()) {
                throw new AiRequestException(AiErrorKind.OTHER, response.statusCode(), "OpenAI response missing choices/message/content", null);
            }
            if (!filterAnswer) {
                return new ChatExchange(text, headers);
            }
            boolean lengthLimited = LengthCutoff.isLength(choice.getFinishReason());
            String source = lengthLimited ? LengthCutoff.trim(text) : text;
            String formatted = AnswerFormatter.format(
                    source,
                    config.isStripMarkdown(),
                    config.getMaxAnswerChars(),
                    config.getMaxAnswerLines()
            );
            formatted = FormatEnforcer.enforce(formatted, config.presetFor(effective.formatOr(config.defaultFormatId())));
            formatted = SecretMask.redact(formatted, List.of(apiKey));
            if (PlayerInput.stripSectionSigns(text).isBlank()) {
                throw new AiRequestException(
                        AiErrorKind.EMPTY_REPLY, response.statusCode(), PlayerInput.EMPTY_REPLY, null);
            }
            String reason = PlayerInput.rejectionReason(text, prompt);
            if (reason == null) {
                reason = PlayerInput.rejectionReason(formatted, prompt);
            }
            if (reason != null) {
                throw new AiRequestException(AiErrorKind.REJECTED, response.statusCode(), reason, null);
            }
            return new ChatExchange(formatted, headers, lengthLimited ? LengthCutoff.CACHE_TTL : null);
        } catch (AiRequestException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new AiRequestException(
                    AiErrorKind.TIMEOUT,
                    0,
                    "Request timed out calling " + parsedUri.getHost() + " after " + config.getReadTimeout().toSeconds() + "s",
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiRequestException(AiErrorKind.OTHER, 0, "Request interrupted", e);
        } catch (Exception e) {
            AiErrorKind kind = AiErrors.classify(e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new AiRequestException(kind, 0, SecretMask.redact(message, List.of(apiKey)), e);
        }
    }

    private String doComplete(String prompt, GenerationOverrides overrides) {
        return exchange(prompt, overrides, config.getBaseUrl(), config.getApiKey(), null).text();
    }

    private static String trimSlash(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static long retryAfterSeconds(String header) {
        if (header == null || header.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static AiRequestException httpError(
            int status,
            String body,
            boolean html,
            URI uri,
            long retryAfterSeconds,
            Map<String, List<String>> headers
    ) {
        String truncated = truncate(body);
        AiErrorKind kind = AiErrors.classifyHttp(status, body, html);
        String host = uri.getHost() == null ? uri.toString() : uri.getHost();
        String message = switch (kind) {
            case RATE_LIMIT -> "HTTP 429 rate limit from " + host + ": " + truncated;
            case QUOTA -> "HTTP " + status + " quota or insufficient balance from " + host + ": " + truncated;
            case BAD_KEY -> status == 403
                    ? "HTTP 403 from " + host + ". The API key or account cannot access this endpoint: " + truncated
                    : "HTTP 401 unauthorized. The API key was rejected: " + truncated;
            case UNKNOWN_MODEL -> "HTTP " + status + " unknown model from " + host + ": " + truncated;
            case TIMEOUT -> "Request timed out calling " + host;
            case LOCAL_LIMIT -> "Local rate limit reached";
            case REJECTED -> "Rejected model answer from " + host;
            case EMPTY_REPLY -> PlayerInput.EMPTY_REPLY;
            case OTHER -> html && status == 403
                    ? "HTTP 403 returned an HTML page (likely a firewall) from " + host
                    : "HTTP " + status + " from " + host + ": " + truncated;
        };
        return new AiRequestException(kind, status, message, null, retryAfterSeconds, headers);
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

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
