package io.github.neareststep.nexusai.config;

import io.github.neareststep.nexusai.text.Utf16;

import java.util.List;

/**
 * Provider and model names read from configuration. A lone UTF-16 surrogate (for example a YAML
 * escape such as {@code "\ud83d"} without its pair) is not valid Unicode: it is replaced with
 * U+FFFD, and one warning names the setting, so the name sent to the provider and the names in the
 * usage files are the same.
 */
public final class ConfigIdentifiers {

    private ConfigIdentifiers() {
    }

    /**
     * @param where    the setting, for the warning, such as {@code fallback-model.model}
     * @param warnings receives one warning when {@code value} had a lone surrogate
     * @return {@code value} with lone surrogates replaced; the same instance when there were none
     */
    public static String clean(String where, String value, List<String> warnings) {
        if (!Utf16.hasLoneSurrogate(value)) {
            return value;
        }
        warnings.add(where + " has a broken character (a lone UTF-16 surrogate). It was replaced with U+FFFD.");
        return Utf16.replaceLoneSurrogates(value);
    }
}
