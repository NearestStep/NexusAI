package io.github.neareststep.nexusai.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Which context providers a named prompt asks for.
 * Absent means the prompt is unchanged from 1.0.x: same text, same cache key.
 */
public final class PromptContext {

    private static final PromptContext NONE = new PromptContext(false, List.of());
    private static final PromptContext ALL = new PromptContext(true, List.of());

    private final boolean all;
    private final List<String> ids;

    private PromptContext(boolean all, List<String> ids) {
        this.all = all;
        this.ids = ids;
    }

    public static PromptContext none() {
        return NONE;
    }

    public static PromptContext all() {
        return ALL;
    }

    public static PromptContext of(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return NONE;
        }
        return new PromptContext(false, List.copyOf(ids));
    }

    public boolean active() {
        return all || !ids.isEmpty();
    }

    public boolean includesAll() {
        return all;
    }

    public List<String> ids() {
        return ids;
    }

    public boolean includes(String providerId) {
        return all || ids.contains(providerId);
    }

    public static PromptContext parse(Object raw, String promptId, List<String> warnings) {
        if (raw == null) {
            return none();
        }
        if (raw instanceof String text) {
            return parseToken(promptId, text, warnings);
        }
        if (raw instanceof List<?> list) {
            List<String> ids = new ArrayList<>();
            boolean sawAll = false;
            for (Object item : list) {
                String token = item == null ? "" : String.valueOf(item).trim();
                if (token.isEmpty()) {
                    continue;
                }
                if ("all".equalsIgnoreCase(token)) {
                    sawAll = true;
                    continue;
                }
                if (!io.github.neareststep.nexusai.api.NexusContextProvider.validId(token)) {
                    warnings.add("Prompt '" + promptId + "' has invalid context id '" + token + "'. It was ignored.");
                    continue;
                }
                if (!ids.contains(token)) {
                    ids.add(token);
                }
            }
            if (sawAll && ids.isEmpty()) {
                return all();
            }
            if (sawAll) {
                warnings.add("Prompt '" + promptId + "' mixes context: all with ids. Only the ids are used.");
            }
            return of(ids);
        }
        warnings.add("Prompt '" + promptId + "' has an invalid context value. It was ignored.");
        return none();
    }

    private static PromptContext parseToken(String promptId, String text, List<String> warnings) {
        String token = text == null ? "" : text.trim();
        if (token.isEmpty()) {
            return none();
        }
        if ("all".equalsIgnoreCase(token)) {
            return all();
        }
        if (!io.github.neareststep.nexusai.api.NexusContextProvider.validId(token)) {
            warnings.add("Prompt '" + promptId + "' has invalid context '" + token + "'. It was ignored.");
            return none();
        }
        return of(List.of(token));
    }
}
