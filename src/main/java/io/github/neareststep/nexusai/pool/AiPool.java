package io.github.neareststep.nexusai.pool;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-prompt queues of unique pre-generated answers.
 */
public final class AiPool {

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<String>> pools = new ConcurrentHashMap<>();

    public Optional<String> poll(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ConcurrentLinkedDeque<String> queue = pools.get(prompt);
        if (queue == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(queue.pollFirst());
    }

    public void add(String prompt, String answer) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(answer, "answer");
        pools.computeIfAbsent(prompt, ignored -> new ConcurrentLinkedDeque<>()).addLast(answer);
    }

    public int size(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ConcurrentLinkedDeque<String> queue = pools.get(prompt);
        return queue == null ? 0 : queue.size();
    }

    public Set<String> prompts() {
        return pools.keySet();
    }
}
