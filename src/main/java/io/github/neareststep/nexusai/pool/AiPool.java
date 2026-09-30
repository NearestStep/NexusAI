package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.ai.PlayerInput;

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
    /** Answers already accepted for a prompt. Survives {@link #poll(String)} until {@link #replace}. */
    private final ConcurrentHashMap<String, Set<String>> remembered = new ConcurrentHashMap<>();

    public Optional<String> poll(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ConcurrentLinkedDeque<String> queue = pools.get(prompt);
        if (queue == null) {
            return Optional.empty();
        }
        return clean(queue.pollFirst());
    }

    /**
     * The next stored answer without removing it. Used when a live call cannot answer
     * and a cached placeholder still has a pooled line to show.
     */
    public Optional<String> peek(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ConcurrentLinkedDeque<String> queue = pools.get(prompt);
        if (queue == null) {
            return Optional.empty();
        }
        return clean(queue.peekFirst());
    }

    /**
     * @return {@code false} when this prompt has already accepted the same finished text, including after it was polled
     */
    public boolean add(String prompt, String answer) {
        return add(prompt, answer, false);
    }

    /**
     * @param allowRepeat {@code true} for a personalized template that still contains a configured {@code {token}}.
     *                     Each copy is one delivery; substitution happens when a player reads it.
     * @return {@code false} when a finished answer was already accepted for this prompt
     */
    public boolean add(String prompt, String answer, boolean allowRepeat) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(answer, "answer");
        answer = PlayerInput.stripSectionSigns(answer).trim();
        if (answer.isEmpty()) {
            return false;
        }
        ConcurrentLinkedDeque<String> queue = pools.computeIfAbsent(prompt, ignored -> new ConcurrentLinkedDeque<>());
        Set<String> seen = remembered.computeIfAbsent(prompt, ignored -> ConcurrentHashMap.newKeySet());
        synchronized (queue) {
            if (!allowRepeat && !seen.add(answer)) {
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

    private static Optional<String> clean(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = PlayerInput.stripSectionSigns(raw).trim();
        return cleaned.isEmpty() ? Optional.empty() : Optional.of(cleaned);
    }

    public List<String> copy(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        ConcurrentLinkedDeque<String> queue = pools.get(prompt);
        if (queue == null) {
            return List.of();
        }
        return new ArrayList<>(queue);
    }

    public void replace(String prompt, List<String> answers) {
        Objects.requireNonNull(prompt, "prompt");
        ConcurrentLinkedDeque<String> queue = new ConcurrentLinkedDeque<>();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        if (answers != null) {
            for (String answer : answers) {
                if (answer == null) {
                    continue;
                }
                String cleaned = PlayerInput.stripSectionSigns(answer).trim();
                if (!cleaned.isEmpty()) {
                    seen.add(cleaned);
                    queue.addLast(cleaned);
                }
            }
        }
        if (queue.isEmpty()) {
            pools.remove(prompt);
            remembered.remove(prompt);
        } else {
            pools.put(prompt, queue);
            remembered.put(prompt, seen);
        }
    }
}
