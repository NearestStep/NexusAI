package io.github.neareststep.nexusai.event;

import io.github.neareststep.nexusai.config.SecretMask;

import java.util.List;
import java.util.function.Supplier;

/**
 * Strings that leave NexusAI on an event: URL query values and secrets masked, then clipped.
 */
final class EventTexts {

    static final int MESSAGE_CODE_POINTS = 300;
    static final int REASON_CODE_POINTS = 240;

    private static volatile Supplier<Iterable<String>> secrets = List::of;

    private EventTexts() {
    }

    static void secrets(Supplier<Iterable<String>> source) {
        secrets = source == null ? List::of : source;
    }

    static Iterable<String> secrets() {
        Iterable<String> current = secrets.get();
        return current == null ? List.of() : current;
    }

    /** Masked message, at most 300 Unicode code points, with URL query values masked. */
    static String message(String value) {
        return clip(SecretMask.redactUrl(value == null ? "" : value, secrets()), MESSAGE_CODE_POINTS);
    }

    /** Moderation reason, at most 240 Unicode code points. */
    static String reason(String value) {
        return clip(SecretMask.redactUrl(value == null ? "" : value, secrets()), REASON_CODE_POINTS);
    }

    static String plain(String value) {
        String masked = SecretMask.redactUrl(value == null ? "" : value, secrets());
        return masked == null ? "" : masked;
    }

    private static String clip(String value, int maxCodePoints) {
        String text = value == null ? "" : value;
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }
}
