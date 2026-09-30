package io.github.neareststep.nexusai.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * One startup pass over {@code config.yml}: migrate, then append missing default keys.
 * Both steps share a single backup of the file as it was at the start of the pass.
 */
public final class ConfigStartup {

    public record Outcome(boolean valid, List<String> addedKeys, Path backup) {
        public Outcome {
            addedKeys = addedKeys == null ? List.of() : List.copyOf(addedKeys);
        }

        static Outcome invalid() {
            return new Outcome(false, List.of(), null);
        }

        static Outcome unchanged() {
            return new Outcome(true, List.of(), null);
        }
    }

    private ConfigStartup() {
    }

    /**
     * @param defaults jar {@code config.yml} text used to append missing keys
     */
    public static Outcome prepareConfig(Path file, String defaults, Logger logger) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return Outcome.unchanged();
        }
        boolean migrated = !ConfigMigrator.migrateFile(file, ConfigMigrator::migrateConfig, logger).isEmpty();
        String existing = Files.readString(file, StandardCharsets.UTF_8);
        ConfigMerger.Result result = ConfigMerger.mergeMissing(existing, defaults == null ? "" : defaults);
        if (!result.valid()) {
            return Outcome.invalid();
        }
        if (result.addedKeys().isEmpty()) {
            return Outcome.unchanged();
        }
        Path backup = null;
        if (migrated) {
            Files.writeString(file, result.yaml(), StandardCharsets.UTF_8);
        } else {
            backup = FileBackup.replace(file, result.yaml());
        }
        return new Outcome(true, result.addedKeys(), backup);
    }
}
