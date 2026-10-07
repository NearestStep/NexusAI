package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.api.GenerationRequest;
import io.github.neareststep.nexusai.api.GenerationRequestFacts;
import io.github.neareststep.nexusai.context.ContextVariables;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.prompt.NamedPrompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the user prompt the same way a {@code cached_} placeholder does.
 * Request variables are wrapped and never expanded as PlaceholderAPI.
 * Prompt variables that contain {@code %} use the values already resolved on the region thread.
 */
final class PromptAssembly {

    private PromptAssembly() {
    }

    static String userPrompt(String template, NamedPrompt named, GenerationRequest request, PlayerFacts facts) {
        String body = template == null ? "" : template;
        Map<String, String> wrapped = new LinkedHashMap<>();
        Set<String> userNames = new LinkedHashSet<>();
        PlayerFacts snapshot = facts == null ? PlayerFacts.none() : facts;
        if (named != null) {
            for (Map.Entry<String, String> entry : named.vars().entrySet()) {
                userNames.add(entry.getKey());
                if (request.vars().containsKey(entry.getKey())) {
                    continue;
                }
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (value.indexOf('%') >= 0) {
                    value = snapshot.resolvedPromptVars().getOrDefault(entry.getKey(), "");
                }
                wrapped.put(entry.getKey(), PlayerInput.wrap(value));
            }
        }
        for (Map.Entry<String, String> entry : request.vars().entrySet()) {
            userNames.add(entry.getKey());
            wrapped.put(entry.getKey(), PlayerInput.wrap(entry.getValue() == null ? "" : entry.getValue()));
        }
        String rendered = substitute(body, wrapped);
        return ContextVariables.apply(rendered, userNames, snapshot.builtins());
    }

    /**
     * Appends {@code ov:} and 16 hex digits when the request sets system prompt, temperature,
     * or max tokens. Prompt-file values do not, so the key stays the placeholder key.
     */
    static String knowledgeToken(GenerationRequest request, String token) {
        String base = token == null ? "" : token;
        if (request == null
                || (!GenerationRequestFacts.systemPromptSpecified(request)
                && !GenerationRequestFacts.temperatureSpecified(request)
                && !GenerationRequestFacts.maxTokensSpecified(request))) {
            return base;
        }
        StringBuilder canon = new StringBuilder();
        if (GenerationRequestFacts.systemPromptSpecified(request)) {
            String system = GenerationRequestFacts.systemPromptRaw(request);
            canon.append("system=").append(system == null ? "" : system).append('\n');
        }
        if (GenerationRequestFacts.temperatureSpecified(request)) {
            canon.append("temperature=").append(GenerationRequestFacts.temperatureRaw(request)).append('\n');
        }
        if (GenerationRequestFacts.maxTokensSpecified(request)) {
            canon.append("maxTokens=").append(GenerationRequestFacts.maxTokensRaw(request)).append('\n');
        }
        String hex = KnowledgeBase.sha256(canon.toString());
        if (base.isBlank()) {
            return "ov:" + hex;
        }
        return base + "ov:" + hex;
    }

    /**
     * Longest key first, same rule as {@code NamedPrompt}. A token with no value is left as written.
     */
    static String substitute(String template, Map<String, String> values) {
        if (template == null || template.isEmpty() || values == null || values.isEmpty() || template.indexOf('{') < 0) {
            return template == null ? "" : template;
        }
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort((left, right) -> Integer.compare(right.length(), left.length()));
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                pattern.append('|');
            }
            pattern.append(Pattern.quote("{" + keys.get(i) + "}"));
        }
        Matcher matcher = Pattern.compile(pattern.toString()).matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String key = token.substring(1, token.length() - 1);
            String value = values.getOrDefault(key, token);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
