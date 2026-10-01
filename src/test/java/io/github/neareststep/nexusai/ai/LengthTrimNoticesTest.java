package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.config.GenerationOverrides;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LengthTrimNoticesTest {

    @Test
    void eachPromptIsLoggedTwicePerRun() {
        LengthTrimNotices.reset();
        Logger logger = Logger.getLogger("trim-notice-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        List<String> lines = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.INFO) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.INFO);
        logger.addHandler(handler);
        try {
            for (int i = 0; i < 4; i++) {
                LengthTrimNotices.note(logger, "harbor");
            }
            LengthTrimNotices.note(logger, "other prompt");
            assertEquals(List.of(
                    LengthTrimNotices.message("harbor"),
                    LengthTrimNotices.message("harbor"),
                    LengthTrimNotices.message("other prompt")
            ), lines);
            assertTrue(LengthTrimNotices.message("harbor").contains("hit max-tokens and was trimmed"));
            assertTrue(LengthTrimNotices.message("harbor").contains("increase max-tokens"));

            String longPrompt = "x".repeat(200);
            String label = LengthTrimNotices.label(longPrompt);
            assertTrue(label.endsWith("..."));
            assertTrue(label.length() <= 120);
            assertEquals("(blank)", LengthTrimNotices.label(" \n\t "));
        } finally {
            logger.removeHandler(handler);
            LengthTrimNotices.reset();
        }
    }

    @Test
    void colourCodesAreStrippedAndShareOneKey() {
        LengthTrimNotices.reset();
        Logger logger = logger();
        List<String> lines = lines(logger);
        try {
            LengthTrimNotices.note(logger, "har&cbor");
            LengthTrimNotices.note(logger, "har§cbor");
            LengthTrimNotices.note(logger, "harbor");
            assertEquals(List.of(
                    LengthTrimNotices.message("harbor"),
                    LengthTrimNotices.message("harbor")
            ), lines);
            for (String line : lines) {
                assertFalse(line.contains("&"));
                assertFalse(line.contains("§"));
                assertFalse(line.contains("Steve"));
            }
            assertEquals("(blank)", LengthTrimNotices.label("&c§l"));
            assertEquals("Steve", LengthTrimNotices.label("§cSteve"));
            assertFalse(LengthTrimNotices.message("a&b§c").contains("&"));
            assertFalse(LengthTrimNotices.message("a&b§c").contains("§"));
        } finally {
            LengthTrimNotices.reset();
        }
    }

    @Test
    void resetClearsTheCap() {
        LengthTrimNotices.reset();
        Logger logger = logger();
        List<String> lines = lines(logger);
        try {
            LengthTrimNotices.note(logger, "harbor");
            LengthTrimNotices.note(logger, "harbor");
            LengthTrimNotices.note(logger, "harbor");
            assertEquals(2, lines.size());
            LengthTrimNotices.reset();
            LengthTrimNotices.note(logger, "harbor");
            assertEquals(3, lines.size());
            assertEquals(LengthTrimNotices.message("harbor"), lines.get(2));
        } finally {
            LengthTrimNotices.reset();
        }
    }

    @Test
    void theKeyMapStaysBounded() {
        LengthTrimNotices.reset();
        Logger logger = logger();
        try {
            for (int i = 0; i < LengthTrimNotices.MAX_KEYS + 25; i++) {
                LengthTrimNotices.note(logger, "prompt-" + i);
            }
            assertTrue(LengthTrimNotices.tracked() <= LengthTrimNotices.MAX_KEYS);
            assertEquals(LengthTrimNotices.MAX_KEYS, LengthTrimNotices.tracked());
        } finally {
            LengthTrimNotices.reset();
            assertEquals(0, LengthTrimNotices.tracked());
        }
    }

    @Test
    void noticeIdSurvivesOverrideCopies() {
        GenerationOverrides merged = GenerationOverrides.none()
                .withNoticeId("harbor")
                .withSystemPrompt("You know Steve.")
                .withFormat("hologram");
        assertEquals("harbor", merged.noticeId());
        GenerationOverrides overlaid = GenerationOverrides.none()
                .withFormat("chat")
                .overlay(GenerationOverrides.none().withNoticeId("pool-a"));
        assertEquals("pool-a", overlaid.noticeId());
        assertEquals("chat", overlaid.formatOr("simple"));
    }

    private static Logger logger() {
        Logger logger = Logger.getLogger("trim-notice-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        return logger;
    }

    private static List<String> lines(Logger logger) {
        List<String> lines = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.INFO) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.INFO);
        logger.addHandler(handler);
        return lines;
    }
}
