package io.github.neareststep.nexusai.config;

/**
 * Schema versions stored as {@code config-version}.
 * Version {@code 0} is a 0.6.0 file that has no version key.
 */
public final class ConfigVersions {

    /**
     * prompts.yml, pool.yml, and usage.yml. Version 1 is also the 0.7.0 layout
     * (providers, model-queue, formats, quoted pools) that config.yml passes through.
     */
    public static final int CURRENT = 1;

    /**
     * config.yml. Version 2 replaces a leftover {@code api.max-tokens: 0}
     * from the 0.6.0 and 0.7.0 defaults with 256 once.
     */
    public static final int CONFIG = 2;

    private ConfigVersions() {
    }
}
