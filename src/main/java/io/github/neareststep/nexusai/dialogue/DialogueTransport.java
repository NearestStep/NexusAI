package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiErrors;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.HttpGate;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.ai.AnswerFormatter;
import io.github.neareststep.nexusai.ai.FormatEnforcer;
import io.github.neareststep.nexusai.ai.LengthCutoff;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.SecretMask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * One HTTP chat completion that may include native tools. Placeholder requests do not use this class.
 */
public final class DialogueTransport {

    private final HttpClient httpClient;
    private final Supplier<PluginConfig> config;
    private final HttpGate gate;

    public DialogueTransport(Supplier<PluginConfig> config, HttpClient httpClient) {
        this(config, httpClient, HttpGate.unlimited());
    }

    public DialogueTransport(Supplier<PluginConfig> config, HttpClient httpClient, HttpGate gate) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.gate = gate == null ? HttpGate.unlimited() : gate;
    }

    private PluginConfig config() {
        return config.get();
    }

    public Result send(Request request) {
        Objects.requireNonNull(request, "request");
        String root = request.baseUrl() == null || request.baseUrl().isBlank() ? config().getBaseUrl() : request.baseUrl();
        URI uri = URI.create(trimSlash(root) + "/chat/completions");
        try {
            byte[] json = DialogueProtocol.requestJson(
                    request.model(),
                    request.system(),
                    request.messages(),
                    request.tools(),
                    request.temperature(),
                    request.maxTokens(),
                    request.maxCompletionTokens(),
                    request.reasoningEffort()
            );
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(request.readTimeout() == null ? config().getReadTimeout() : request.readTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "NexusAI (Paper-plugin; Java-HttpClient)");
            if (request.apiKey() != null && !request.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + request.apiKey());
            }
            HttpRequest httpRequest = builder.POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();
            HttpResponse<String> response = gate.schedule(
                    () -> httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
            ).join();
            String body = response.body() == null ? "" : response.body();
            Map<String, List<String>> headers = response.headers().map();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (DialogueProtocol.unsupportedTools(response.statusCode(), body)) {
                    throw new AiRequestException(
                            AiErrorKind.OTHER,
                            response.statusCode(),
                            "Provider does not support tools",
                            null,
                            0L,
                            headers,
                            true
                    );
                }
                AiErrorKind kind = AiErrors.classifyHttp(response.statusCode(), body, looksLikeHtml(body));
                throw new AiRequestException(
                        kind,
                        response.statusCode(),
                        "HTTP " + response.statusCode() + " from " + host(uri),
                        null,
                        retryAfter(response.headers().firstValue("Retry-After").orElse(null)),
                        headers,
                        false
                );
            }
            DialogueProtocol.ParsedCompletion parsed = DialogueProtocol.parse(body);
            if ((parsed.content() == null || parsed.content().isBlank()) && parsed.toolNames().isEmpty()) {
                throw new AiRequestException(AiErrorKind.OTHER, response.statusCode(), "OpenAI response missing choices/message/content", null);
            }
            return new Result(
                    parsed.content() == null ? "" : parsed.content(),
                    parsed.toolNames(),
                    headers,
                    parsed.finishReason()
            );
        } catch (AiRequestException e) {
            throw e;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof AiRequestException ai) {
                throw ai;
            }
            if (cause instanceof HttpTimeoutException timeout) {
                throw new AiRequestException(AiErrorKind.TIMEOUT, 0, "Request timed out calling " + host(uri), timeout);
            }
            if (HttpPool.isQueueFull(cause)) {
                throw HttpPool.queueFull(cause);
            }
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new AiRequestException(
                    AiErrors.classify(cause),
                    0,
                    SecretMask.redact(message, secrets(request.apiKey())),
                    cause);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new AiRequestException(
                    AiErrors.classify(e), 0, SecretMask.redact(message, secrets(request.apiKey())), null);
        }
    }

    public String finishText(String raw, String wrappedUser, String formatId, String apiKey) {
        return finishText(raw, wrappedUser, formatId, apiKey, null);
    }

    /**
     * Same formatting as a placeholder reply. When {@code finishReason} is {@code length}, the
     * text is cut on a sentence or word boundary before those filters run. Callers store the
     * returned text, so dialogue history matches what the player saw.
     */
    public String finishText(String raw, String wrappedUser, String formatId, String apiKey, String finishReason) {
        PluginConfig current = config();
        boolean allowMarkup = current.allowMarkup();
        String source = LengthCutoff.isLength(finishReason) ? LengthCutoff.trim(raw, allowMarkup) : raw;
        String formatted = AnswerFormatter.format(
                source,
                current.isStripMarkdown(),
                current.getMaxAnswerChars(),
                current.getMaxAnswerLines(),
                allowMarkup
        );
        formatted = FormatEnforcer.enforce(formatted, current.presetFor(formatId));
        formatted = SecretMask.redact(formatted, secrets(apiKey));
        if (raw != null && !raw.isBlank() && PlayerInput.stripSectionSigns(raw, allowMarkup).isBlank()) {
            if (PlayerInput.emptiedByMarkup(raw, allowMarkup)) {
                throw new AiRequestException(AiErrorKind.MARKUP_ONLY, 200, PlayerInput.MARKUP_ONLY, null);
            }
            throw new AiRequestException(AiErrorKind.EMPTY_REPLY, 200, PlayerInput.EMPTY_REPLY, null);
        }
        String reason = PlayerInput.rejectionReason(raw, wrappedUser);
        if (reason == null) {
            reason = PlayerInput.rejectionReason(formatted, wrappedUser);
        }
        if (reason != null) {
            throw new AiRequestException(AiErrorKind.REJECTED, 200, reason, null);
        }
        return formatted;
    }

    private static long retryAfter(String header) {
        if (header == null || header.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private List<String> secrets(String apiKey) {
        List<String> secrets = new java.util.ArrayList<>();
        if (apiKey != null && !apiKey.isBlank()) {
            secrets.add(apiKey.trim());
        }
        for (String configured : config().configuredSecrets()) {
            if (configured != null && !secrets.contains(configured)) {
                secrets.add(configured);
            }
        }
        return secrets;
    }

    private static boolean looksLikeHtml(String body) {
        String head = body.length() > 64 ? body.substring(0, 64).toLowerCase(Locale.ROOT) : body.toLowerCase(Locale.ROOT);
        return head.contains("<!doctype html") || head.contains("<html");
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? uri.toString() : uri.getHost();
    }

    private static String trimSlash(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public record Request(
            String baseUrl,
            String apiKey,
            String model,
            String system,
            List<DialogueProtocol.MemoryLine> messages,
            List<CharacterAction> tools,
            Double temperature,
            Integer maxTokens,
            Integer maxCompletionTokens,
            String reasoningEffort,
            Duration readTimeout
    ) {
    }

    public record Result(String content, List<String> toolNames, Map<String, List<String>> headers, String finishReason) {
    }
}
