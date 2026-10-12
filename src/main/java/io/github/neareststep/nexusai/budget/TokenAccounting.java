package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ResponseUsage;

/**
 * Records one HTTP attempt on the shared ledger. No-ops when the attempt carried no usage
 * and no length estimate, so a 4xx or 5xx without a {@code usage} object is not counted.
 */
public final class TokenAccounting {

    private static final TokenAccounting NONE = new TokenAccounting(null);

    private final TokenLedgerStore store;

    public TokenAccounting(TokenLedgerStore store) {
        this.store = store;
    }

    public static TokenAccounting none() {
        return NONE;
    }

    public void record(
            ResponseUsage usage,
            CallTrace trace,
            String providerId,
            int queueIndex,
            boolean dedicatedFallback,
            ModelQueue queue,
            String model
    ) {
        if (store == null || usage == null || (!usage.reported() && !usage.estimated())) {
            return;
        }
        boolean fallback = dedicatedFallback || queueIndex < 0;
        String key = "";
        if (!fallback && queue != null) {
            key = queue.rowStorageId(queueIndex);
        }
        if (key == null || key.isBlank()) {
            fallback = true;
            key = ModelQueue.fallbackStorageId(providerId, model);
        }
        store.record(usage, trace, providerId, key, fallback);
    }
}
