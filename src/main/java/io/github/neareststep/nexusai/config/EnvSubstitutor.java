package io.github.neareststep.nexusai.config;

import java.util.Collection;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ${ENV_VAR}} placeholders. A missing variable becomes an empty string.
 * The value is never logged by this class. Missing names are collected so the caller can log them.
 */
public final class EnvSubstitutor {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvSubstitutor() {
    }

    public static String apply(String value) {
        return apply(value, System::getenv);
    }

    static String apply(String value, Function<String, String> environment) {
        return apply(value, environment, null);
    }

    static String apply(String value, Function<String, String> environment, Collection<String> missing) {
        if (value == null || value.isEmpty() || value.indexOf('$') < 0) {
            return value == null ? "" : value;
        }
        Matcher matcher = TOKEN.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String resolved = environment.apply(name);
            if (resolved == null) {
                if (missing != null) {
                    missing.add(name);
                }
                resolved = "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static boolean referencesEnv(String value) {
        return value != null && TOKEN.matcher(value).find();
    }
}
