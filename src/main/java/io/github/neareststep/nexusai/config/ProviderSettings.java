package io.github.neareststep.nexusai.config;

import java.util.List;

/**
 * One configured provider: wire type, endpoint, and the resolved API keys (never logged in full).
 */
public record ProviderSettings(String id, String type, String url, List<String> apiKeys) {

    public ProviderSettings {
        id = id == null ? "" : id;
        type = type == null || type.isBlank() ? ProviderCatalog.typeFor(id) : type;
        url = url == null ? "" : url;
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
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
