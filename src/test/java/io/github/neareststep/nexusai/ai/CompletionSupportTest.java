package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionSupportTest {

    @Test
    void logsExceptionsThrownByTheCallback() {
        Logger logger = logger();
        List<LogRecord> records = records(logger);
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletionSupport.onComplete(future, logger, "delivery failed", (value, error) -> {
            throw new IllegalStateException("scheduler");
        });
        future.complete("ok");

        assertEquals(1, records.size());
        assertTrue(records.get(0).getMessage().contains("delivery failed"));
        assertTrue(records.get(0).getThrown() instanceof IllegalStateException);
        assertEquals("scheduler", records.get(0).getThrown().getMessage());
    }

    @Test
    void doesNotLogAHandledFutureFailure() {
        Logger logger = logger();
        List<LogRecord> records = records(logger);
        CompletableFuture<String> future = new CompletableFuture<>();
        List<Throwable> seen = new ArrayList<>();
        CompletionSupport.onComplete(future, logger, "delivery failed", (value, error) -> seen.add(error));
        future.completeExceptionally(new IllegalStateException("api"));

        assertTrue(records.isEmpty());
        assertEquals(1, seen.size());
        assertTrue(seen.get(0) instanceof IllegalStateException);
    }

    private static Logger logger() {
        Logger logger = Logger.getLogger("nexusai.completion." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        return logger;
    }

    private static List<LogRecord> records(Logger logger) {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return records;
    }
}
