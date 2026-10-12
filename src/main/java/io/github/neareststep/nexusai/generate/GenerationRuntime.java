package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.context.ContextService;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * One consistent view of cache, gate, provider, and config.
 * {@code generate} captures this once. A reload publishes a new instance; in-flight calls keep the old one.
 */
@ApiStatus.Internal
public final class GenerationRuntime {

    private final PluginConfig config;
    private final AiCache cache;
    private final RequestGate gate;
    private final AiDiagnostics diagnostics;
    private final AiProvider provider;
    private final AiHttpClient http;
    private final PromptCatalog catalog;
    private final KnowledgeBase knowledge;
    private final ContextService context;

    public GenerationRuntime(
            PluginConfig config,
            AiCache cache,
            RequestGate gate,
            AiDiagnostics diagnostics,
            AiProvider provider,
            AiHttpClient http,
            PromptCatalog catalog,
            KnowledgeBase knowledge,
            ContextService context
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.http = Objects.requireNonNull(http, "http");
        this.catalog = catalog == null ? PromptCatalog.empty() : catalog;
        this.knowledge = knowledge == null ? KnowledgeBase.empty() : knowledge;
        this.context = context;
    }

    PluginConfig config() {
        return config;
    }

    AiCache cache() {
        return cache;
    }

    RequestGate gate() {
        return gate;
    }

    AiDiagnostics diagnostics() {
        return diagnostics;
    }

    AiProvider provider() {
        return provider;
    }

    AiHttpClient http() {
        return http;
    }

    PromptCatalog catalog() {
        return catalog;
    }

    KnowledgeBase knowledge() {
        return knowledge;
    }

    ContextService context() {
        return context;
    }
}
