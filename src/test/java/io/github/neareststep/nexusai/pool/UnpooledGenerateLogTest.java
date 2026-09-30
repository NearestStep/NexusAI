package io.github.neareststep.nexusai.pool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnpooledGenerateLogTest {

    @Test
    void warnsOncePerPromptAndResetsOnReload() {
        Logger logger = Logger.getLogger("unpooled-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.WARNING);
        List<String> warnings = new ArrayList<>();
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
        UnpooledGenerateLog log = new UnpooledGenerateLog(logger);

        log.note("welcome", false);
        log.note("welcome", false);
        log.note("welcome", true);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith(
                "Placeholder %ainexus_generate_welcome% was requested, but 'welcome' is not in pool.entries"));
        assertTrue(warnings.getFirst().contains("pool.entries: [{prompt: welcome, size: 3, min-threshold: 1}]"));
        assertTrue(warnings.getFirst().contains("Unique answers (pool)"));
        assertEquals(List.of("welcome"), log.prompts());

        log.note("rules", false);
        assertEquals(List.of("rules", "welcome"), log.prompts());
        assertEquals(2, warnings.size());

        log.reset();
        assertTrue(log.prompts().isEmpty());
        log.note("welcome", false);
        assertEquals(3, warnings.size());
        assertEquals(List.of("welcome"), log.prompts());
    }
}