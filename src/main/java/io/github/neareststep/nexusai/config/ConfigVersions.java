package io.github.neareststep.nexusai.config;

/**
 * Schema version stored as {@code config-version} in every NexusAI YAML file.
 * Version {@code 0} is a 0.6.0 file that has no version key.
 */
public final class ConfigVersions {

    /** 0.7.0 layout: providers, model-queue, formats, quoted pools. */
    public static final int CURRENT = 1;

    private ConfigVersions() {
    }
}
