package io.github.neareststep.nexusai.config;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ${ENV_VAR}} placeholders. A missing variable becomes an empty string.
 * The value is never logged by this class.
 */
public final class EnvSubstitutor {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvSubstitutor() {
    }

    public static String apply(String value) {
        return apply(value, System::getenv);
    }

    static String apply(String value, Function<String, String> environment) {
        if (value == null || value.isEmpty() || value.indexOf('$') < 0) {
            return value == null ? "" : value;
        }
        Matcher matcher = TOKEN.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String resolved = environment.apply(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved == null ? "" : resolved));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static boolean referencesEnv(String value) {
        return value != null && TOKEN.matcher(value).find();
    }
}
