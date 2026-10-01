package io.github.neareststep.nexusai.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Copies bundled {@code lang/*.yml} files into {@code plugins/NexusAI/lang}.
 * An existing file is never overwritten.
 */
public final class LocaleFiles {

    public static final List<String> BUNDLED = List.of(
            "cs", "de", "en", "es", "fr", "it", "ja", "ko", "nl", "pl", "pt_BR", "ru", "tr", "uk", "zh_CN"
    );

    private LocaleFiles() {
    }

    /**
     * @param opener supplies the bundled bytes for a locale code, or {@code null} when the jar has no such file
     * @return locale codes that were written
     */
    public static List<String> extractMissing(Path langDir, Function<String, InputStream> opener) throws IOException {
        if (langDir == null || opener == null) {
            return List.of();
        }
        Files.createDirectories(langDir);
        List<String> written = new ArrayList<>();
        for (String code : BUNDLED) {
            Path target = langDir.resolve(code + ".yml");
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = opener.apply(code)) {
                if (in == null) {
                    continue;
                }
                Files.copy(in, target);
                written.add(code);
            }
        }
        return List.copyOf(written);
    }
}
