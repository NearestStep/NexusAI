package io.github.neareststep.nexusai.dialogue;

import java.util.ArrayList;
import java.util.List;

/**
 * Per player and character transcript. A turn is one player line plus the assistant lines after it.
 * The model sees at most the configured number of turns, then a character cap drops the oldest lines.
 */
public final class TurnMemory {

    private final List<Line> lines = new ArrayList<>();
    private final List<Line> pendingFold = new ArrayList<>();
    private long updatedAt;
    private String summary = "";
    private long summaryUpdatedAt;
    private boolean summaryInFlight;
    private int epoch;

    public synchronized void add(String role, String text, long nowMillis) {
        lines.add(new Line(role, text == null ? "" : text));
        updatedAt = nowMillis;
    }

    public synchronized void expire(long nowMillis, long expiryMillis) {
        if (expiryMillis > 0 && updatedAt > 0 && nowMillis - updatedAt >= expiryMillis) {
            lines.clear();
            pendingFold.clear();
            summary = "";
            summaryUpdatedAt = 0L;
            summaryInFlight = false;
            updatedAt = 0L;
            epoch++;
        }
    }

    /**
     * Keeps the last {@code turns} player lines and the assistant lines that belong to them,
     * then drops oldest lines until {@code maxChars} holds.
     */
    public synchronized void trim(int turns, int maxChars) {
        trim(turns, maxChars, false);
    }

    /**
     * Same window as {@link #trim(int, int)}. When {@code fold} is true, dropped lines accumulate
     * in {@link #pendingView()} instead of being discarded. The kept window is unchanged.
     */
    public synchronized void trim(int turns, int maxChars, boolean fold) {
        List<Line> dropped = fold ? new ArrayList<>() : null;
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
            if (dropped != null) {
                dropped.addAll(new ArrayList<>(lines.subList(0, start)));
            }
            lines.subList(0, start).clear();
        }
        int cap = Math.max(1, maxChars);
        while (chars() > cap && lines.size() > 1) {
            Line removed = lines.remove(0);
            if (dropped != null) {
                dropped.add(removed);
            }
        }
        if (!lines.isEmpty() && chars() > cap) {
            Line only = lines.get(0);
            int keep = Math.max(0, only.text().length() - (chars() - cap));
            String text = only.text();
            if (dropped != null && keep < text.length()) {
                dropped.add(new Line(only.role(), text.substring(0, text.length() - keep)));
            }
            lines.set(0, new Line(only.role(), text.substring(text.length() - keep)));
        }
        if (dropped != null && !dropped.isEmpty()) {
            absorb(dropped, cap);
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
        load(stored, updatedAt, "", 0L);
    }

    public synchronized void load(List<Line> stored, long updatedAt, String summary, long summaryUpdatedAt) {
        lines.clear();
        pendingFold.clear();
        summaryInFlight = false;
        epoch++;
        if (stored != null) {
            lines.addAll(stored);
        }
        this.updatedAt = updatedAt;
        this.summary = summary == null ? "" : summary;
        this.summaryUpdatedAt = this.summary.isBlank() ? 0L : summaryUpdatedAt;
    }

    public synchronized String summary() {
        return summary;
    }

    public synchronized long summaryUpdatedAt() {
        return summaryUpdatedAt;
    }

    public synchronized List<Line> pendingView() {
        return List.copyOf(pendingFold);
    }

    public synchronized int pendingUserTurns() {
        return userTurns(pendingFold);
    }

    public synchronized boolean summaryInFlight() {
        return summaryInFlight;
    }

    public synchronized int epoch() {
        return epoch;
    }

    /**
     * Starts one summary when enough player lines have been folded and none is already running.
     * The buffered lines move into the claim. Lines dropped while that call is in flight stay here.
     */
    public synchronized Fold claim(int thresholdTurns) {
        if (summaryInFlight || userTurns(pendingFold) < Math.max(1, thresholdTurns)) {
            return null;
        }
        summaryInFlight = true;
        List<Line> folded = List.copyOf(pendingFold);
        pendingFold.clear();
        return new Fold(summary, folded, epoch);
    }

    /**
     * @return false when this transcript was expired or replaced while the call was in flight
     */
    public synchronized boolean completeSummary(String text, long nowMillis, int claimEpoch) {
        if (claimEpoch != epoch) {
            return false;
        }
        summary = text == null ? "" : text;
        summaryUpdatedAt = summary.isBlank() ? 0L : nowMillis;
        summaryInFlight = false;
        return !summary.isBlank();
    }

    /**
     * Drops the fold buffer and keeps the stored summary. Matches a plain trim from 1.0.2.
     */
    public synchronized void failSummary(int claimEpoch) {
        if (claimEpoch != epoch) {
            return;
        }
        pendingFold.clear();
        summaryInFlight = false;
    }

    private void absorb(List<Line> dropped, int maxChars) {
        pendingFold.addAll(dropped);
        int cap = Math.max(1, maxChars);
        while (pendingChars() > cap && pendingFold.size() > 1) {
            pendingFold.remove(0);
        }
        if (!pendingFold.isEmpty() && pendingChars() > cap) {
            Line only = pendingFold.get(0);
            int keep = Math.max(0, only.text().length() - (pendingChars() - cap));
            String text = only.text();
            pendingFold.set(0, new Line(only.role(), text.substring(Math.max(0, text.length() - keep))));
        }
    }

    private int pendingChars() {
        int total = 0;
        for (Line line : pendingFold) {
            total += line.text().length();
        }
        return total;
    }

    private static int userTurns(List<Line> source) {
        int users = 0;
        for (Line line : source) {
            if ("user".equals(line.role())) {
                users++;
            }
        }
        return users;
    }

    public record Line(String role, String text) {
    }

    public record Fold(String previousSummary, List<Line> lines, int epoch) {
        public Fold {
            previousSummary = previousSummary == null ? "" : previousSummary;
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
}
