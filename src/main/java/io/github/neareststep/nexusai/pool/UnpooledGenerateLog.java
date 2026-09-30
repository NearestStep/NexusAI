package io.github.neareststep.nexusai.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * One warning per prompt that {@code %ainexus_generate_%} reads when that prompt is not pooled.
 * {@link #reset()} runs on {@code /nai reload}.
 */
public final class UnpooledGenerateLog {

    private final Set<String> prompts = ConcurrentHashMap.newKeySet();
    private final Logger logger;

    public UnpooledGenerateLog(Logger logger) {
        this.logger = logger == null ? Logger.getLogger("nexusai.pool") : logger;
    }

    /**
     * @param pooled {@code true} when {@code pool.enabled} is on and {@code prompt} is a {@code pool.entries} prompt
     */
    public void note(String prompt, boolean pooled) {
        if (pooled) {
            return;
        }
        String id = sanitize(prompt);
        if (id.isEmpty() || !prompts.add(id)) {
            return;
        }
        logger.warning(message(id));
    }

    public void reset() {
        prompts.clear();
    }

    public List<String> prompts() {
        List<String> ids = new ArrayList<>(prompts);
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    public static String message(String prompt) {
        return "Placeholder %ainexus_generate_" + prompt + "% was requested, but '" + prompt
                + "' is not in pool.entries (or pool.enabled is false). generate_ serves only pooled answers, "
                + "so it will always return fallback. Add it under pool.entries, or use %ainexus_cached_"
                + prompt + "% instead. Example: pool.entries: [{prompt: " + prompt
                + ", size: 3, min-threshold: 1}]. See README section \"Unique answers (pool)\".";
    }

    static String sanitize(String prompt) {
        if (prompt == null) {
            return "";
        }
        return prompt.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
