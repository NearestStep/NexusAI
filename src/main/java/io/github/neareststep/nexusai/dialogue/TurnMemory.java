package io.github.neareststep.nexusai.dialogue;

import java.util.ArrayList;
import java.util.List;

/**
 * Per player and character transcript. A turn is one player line plus the assistant lines after it.
 * The model sees at most the configured number of turns, then a character cap drops the oldest lines.
 */
public final class TurnMemory {

    private final List<Line> lines = new ArrayList<>();
    private long updatedAt;

    public synchronized void add(String role, String text, long nowMillis) {
        lines.add(new Line(role, text == null ? "" : text));
        updatedAt = nowMillis;
    }

    public synchronized void expire(long nowMillis, long expiryMillis) {
        if (expiryMillis > 0 && updatedAt > 0 && nowMillis - updatedAt >= expiryMillis) {
            lines.clear();
            updatedAt = 0L;
        }
    }

    /**
     * Keeps the last {@code turns} player lines and the assistant lines that belong to them,
     * then drops oldest lines until {@code maxChars} holds.
     */
    public synchronized void trim(int turns, int maxChars) {
        int keepTurns = Math.max(1, turns);
        int users = 0;
        int start = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if ("user".equals(lines.get(i).role())) {
                users++;
                if (users == keepTurns) {
                    start = i;
                    break;
                }
            }
        }
        if (start > 0) {
            lines.subList(0, start).clear();
        }
        int cap = Math.max(1, maxChars);
        while (chars() > cap && lines.size() > 1) {
            lines.remove(0);
        }
        if (!lines.isEmpty() && chars() > cap) {
            Line only = lines.get(0);
            int keep = Math.max(0, only.text().length() - (chars() - cap));
            String text = only.text();
            lines.set(0, new Line(only.role(), text.substring(text.length() - keep)));
        }
    }

    public synchronized int chars() {
        int total = 0;
        for (Line line : lines) {
            total += line.text().length();
        }
        return total;
    }

    public synchronized int userTurns() {
        int users = 0;
        for (Line line : lines) {
            if ("user".equals(line.role())) {
                users++;
            }
        }
        return users;
    }

    public synchronized List<Line> view() {
        return List.copyOf(lines);
    }

    public synchronized long updatedAt() {
        return updatedAt;
    }

    public synchronized void load(List<Line> stored, long updatedAt) {
        lines.clear();
        if (stored != null) {
            lines.addAll(stored);
        }
        this.updatedAt = updatedAt;
    }

    public record Line(String role, String text) {
    }
}
