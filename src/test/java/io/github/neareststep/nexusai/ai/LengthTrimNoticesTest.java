package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
