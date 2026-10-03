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
import java.util.concurrent.CompletionException;
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
    private final HttpGate gate;

    public OpenAiProvider(PluginConfig config, ExecutorService executor, Logger logger, HttpClient httpClient) {
        this(config, executor, logger, httpClient, HttpGate.unlimited());
    }

    public OpenAiProvider(
            PluginConfig config,
            ExecutorService executor,
            Logger logger,
            HttpClient httpClient,
            HttpGate gate
    ) {
        this(config, executor, logger, Objects.requireNonNull(httpClient, "httpClient"),
                new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL),
                gate);
    }

    public OpenAiProvider(PluginConfig config, ExecutorService executor, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // sendAsync uses the HttpClient executor, not nexusai-http-*. Those four threads must
        // stay free to start the next call. The gate caps how many calls are outstanding.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .build();
        this.gate = HttpGate.unlimited();
    }

    OpenAiProvider(
            PluginConfig config,
            ExecutorService executor,
            Logger logger,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            HttpGate gate
    ) {
        this.config = config;
        this.executor = executor;
        this.logger = logger;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.gate = gate == null ? HttpGate.unlimited() : gate;
    }

    @Override
    public CompletableFuture<String> complete(String prompt) {
        return complete(prompt, GenerationOverrides.none());
    }

    @Override
    public CompletableFuture<String> complete(String prompt, GenerationOverrides overrides) {
        Objects.requireNonNull(prompt, "prompt");
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        // Kept so the constructor argument stays part of the call path for callers that still pass a pool.
        Objects.requireNonNull(executor, "executor");
        return gate.schedule(() -> exchangeAsync(
                prompt, effective, config.getBaseUrl(), config.getApiKey(), null, true
        ).thenApply(ChatExchange::text));
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
        return join(gate.schedule(() -> exchangeAsync(prompt, overrides, baseUrl, apiKey, model, true)));
    }

    @Override
    public CompletableFuture<ChatExchange> exchangeAsync(
            String prompt,
            GenerationOverrides overrides,
            String baseUrl,
            String apiKey,
            String model
    ) {
        return exchangeAsync(prompt, overrides, baseUrl, apiKey, model, true);
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
        return join(gate.schedule(() -> exchangeAsync(prompt, overrides, baseUrl, apiKey, model, false)));
    }

    private CompletableFuture<ChatExchange> exchangeAsync(
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
        final GenerationOverrides callOverrides = effective;
        final HttpRequest request;
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
            request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(toAi(e, parsedUri, apiKey));
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle((response, error) -> {
            if (error != null) {
                throw toAi(unwrap(error), parsedUri, apiKey);
            }
            return readExchange(response, parsedUri, prompt, apiKey, callOverrides, filterAnswer);
        });
    }

    private ChatExchange readExchange(
            HttpResponse<String> response,
            URI parsedUri,
            String prompt,
            String apiKey,
            GenerationOverrides effective,
            boolean filterAnswer
    ) {
        try {
            String responseBody = response.body() == null ? "" : response.body();
            boolean htmlBody = looksLikeHtml(responseBody);
            Map<String, List<String>> headers = response.headers().map();

            List<String> secrets = secrets(apiKey);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpError(
                        response.statusCode(),
                        SecretMask.redact(responseBody, secrets),
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
                return new ChatExchange(SecretMask.redact(text, secrets), headers);
            }
            boolean lengthLimited = LengthCutoff.isLength(choice.getFinishReason());
            boolean allowMarkup = config.allowMarkup();
            String source = lengthLimited ? LengthCutoff.trim(text, allowMarkup) : text;
            String formatted = AnswerFormatter.format(
                    source,
                    config.isStripMarkdown(),
                    config.getMaxAnswerChars(),
                    config.getMaxAnswerLines(),
                    allowMarkup
            );
            formatted = FormatEnforcer.enforce(formatted, config.presetFor(effective.formatOr(config.defaultFormatId())));
            formatted = SecretMask.redact(formatted, secrets);
            if (PlayerInput.stripSectionSigns(text, allowMarkup).isBlank()) {
                if (PlayerInput.emptiedByMarkup(text, allowMarkup)) {
                    throw new AiRequestException(
                            AiErrorKind.MARKUP_ONLY, response.statusCode(), PlayerInput.MARKUP_ONLY, null);
                }
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
            if (lengthLimited) {
                LengthTrimNotices.note(logger, noticeId(effective, prompt));
            }
            return new ChatExchange(formatted, headers);
        } catch (AiRequestException e) {
            throw e;
        } catch (Exception e) {
            throw toAi(e, parsedUri, apiKey);
        }
    }

    private static ChatExchange join(CompletableFuture<ChatExchange> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof AiRequestException ai) {
                throw ai;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AiRequestException(AiErrorKind.OTHER, 0, cause.getMessage(), cause);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private AiRequestException toAi(Throwable error, URI parsedUri, String apiKey) {
        Throwable cause = unwrap(error);
        if (cause instanceof AiRequestException ai) {
            return ai;
        }
        if (cause instanceof HttpTimeoutException) {
            return new AiRequestException(
                    AiErrorKind.TIMEOUT,
                    0,
                    "Request timed out calling " + parsedUri.getHost() + " after " + config.getReadTimeout().toSeconds() + "s",
                    cause
            );
        }
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new AiRequestException(AiErrorKind.OTHER, 0, "Request interrupted", cause);
        }
        AiErrorKind kind = AiErrors.classify(cause);
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new AiRequestException(kind, 0, SecretMask.redact(message, secrets(apiKey)), cause);
    }

    /**
     * Prefer the stable prompt id. The rendered prompt is only a fallback for a direct call
     * that did not name one, such as a unit test.
     */
    private static String noticeId(GenerationOverrides overrides, String prompt) {
        String id = overrides == null ? null : overrides.noticeId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        return prompt;
    }

    private List<String> secrets(String apiKey) {
        List<String> secrets = new ArrayList<>();
        if (apiKey != null && !apiKey.isBlank()) {
            secrets.add(apiKey.trim());
        }
        for (String configured : config.configuredSecrets()) {
            if (configured != null && !secrets.contains(configured)) {
                secrets.add(configured);
            }
        }
        return secrets;
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
            case MARKUP_ONLY -> PlayerInput.MARKUP_ONLY;
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
