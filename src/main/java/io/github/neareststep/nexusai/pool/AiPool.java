package io.github.neareststep.nexusai.pool;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * @return {@code false} when this prompt already holds the same text
     */
    public boolean add(String prompt, String answer) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(answer, "answer");
        ConcurrentLinkedDeque<String> queue = pools.computeIfAbsent(prompt, ignored -> new ConcurrentLinkedDeque<>());
        synchronized (queue) {
            if (contains(queue, answer)) {
                return false;
            }
            queue.addLast(answer);
            return true;
        }
    }

    public int size(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ConcurrentLinkedDeque<String> queue = pools.get(prompt);
        return queue == null ? 0 : queue.size();
    }

    public Set<String> prompts() {
        return pools.keySet();
    }

    public List<String> copy(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ConcurrentLinkedDeque<String> queue = pools.get(prompt);
        if (queue == null) {
            return List.of();
        }
        return new ArrayList<>(queue);
    }

    private static boolean contains(ConcurrentLinkedDeque<String> queue, String answer) {
        for (String existing : queue) {
            if (answer.equals(existing)) {
                return true;
            }
        }
        return false;
    }

    public void replace(String prompt, List<String> answers) {
        Objects.requireNonNull(prompt, "prompt");
        ConcurrentLinkedDeque<String> queue = new ConcurrentLinkedDeque<>();
        if (answers != null) {
            for (String answer : answers) {
                if (answer != null && !answer.isBlank() && !contains(queue, answer)) {
                    queue.addLast(answer);
                }
            }
        }
        if (queue.isEmpty()) {
            pools.remove(prompt);
        } else {
            pools.put(prompt, queue);
        }
    }
}
