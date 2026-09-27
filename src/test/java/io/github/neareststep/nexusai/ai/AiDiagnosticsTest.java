package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDiagnosticsTest {

    @Test
    void warningsAreRateLimitedButLastErrorUpdates() {
        AtomicLong clock = new AtomicLong(10_000L);
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("nexusai-diagnostics-" + clock.get());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), clock::get);
        diagnostics.report(AiErrorKind.RATE_LIMIT, "HTTP 429");
        diagnostics.report(AiErrorKind.RATE_LIMIT, "HTTP 429 again");
        assertEquals(1, warnings.size());
        assertTrue(diagnostics.lastError().contains("429 again"));
        assertTrue(warnings.getFirst().contains("rate limit"));
        assertTrue(warnings.getFirst().contains("paused"));

        clock.addAndGet(30_000L);
        diagnostics.report(AiErrorKind.TIMEOUT, "timed out");
        assertEquals(2, warnings.size());
        assertTrue(diagnostics.lastError().contains("timed out"));
    }

    @Test
    void localLimitIsNotAProviderError() {
        AiDiagnostics diagnostics = new AiDiagnostics(Logger.getLogger("nexusai-diagnostics-local"), Duration.ofSeconds(1));
        diagnostics.report(AiErrorKind.LOCAL_LIMIT, "local");
        assertEquals(null, diagnostics.lastError());
    }
}
