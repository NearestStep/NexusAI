package io.github.neareststep.nexusai.config;

import java.util.List;

/**
 * One configured provider: wire type, endpoint, and the resolved API keys (never logged in full).
 * The four-argument constructor remains for callers that do not name a {@link KeySource}.
 */
public record ProviderSettings(String id, String type, String url, List<String> apiKeys, KeySource keySource) {

    public ProviderSettings(String id, String type, String url, List<String> apiKeys) {
        this(id, type, url, apiKeys, KeySource.CONFIG);
    }

    public ProviderSettings {
        id = id == null ? "" : id;
        type = type == null || type.isBlank() ? ProviderCatalog.typeFor(id) : type;
        url = url == null ? "" : url;
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
        if (keySource == null) {
            keySource = KeySource.CONFIG;
        }
    }

    public boolean hasKeys() {
        for (String key : apiKeys) {
            if (key != null && !key.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
