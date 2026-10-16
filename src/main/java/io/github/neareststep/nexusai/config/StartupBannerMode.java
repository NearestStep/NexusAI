package io.github.neareststep.nexusai.config;

import java.util.Locale;

/**
 * How much of the startup console banner to print.
 * {@link #FULL} is the default brand banner. {@link #COMPACT} is two short lines.
 * {@link #OFF} prints nothing.
 */
public enum StartupBannerMode {
    FULL("full"),
    COMPACT("compact"),
    OFF("off");

    private final String wire;

    StartupBannerMode(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * @return the mode, or {@code null} when {@code raw} is non-blank and not a known name
     */
    public static StartupBannerMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (StartupBannerMode mode : values()) {
            if (mode.wire.equals(normalized)) {
                return mode;
            }
        }
        return null;
    }
}
