package io.github.neareststep.nexusai.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells a stalled test host apart from a slow plugin during one load window.
 *
 * <p>A stalled host (the whole process paused, then resumed) shows up as a long gap between
 * heartbeats that tick work does not explain, as wall and monotonic clocks drifting apart, or as
 * the window running far past its length. A slow plugin shows up as long ticks instead, so the
 * time Paper reports for ticks inside a gap is subtracted before the gap is judged.
 *
 * <p>Pure bookkeeping on values passed in, so it runs the same in tests and on the server thread.
 */
final class HostStallDetector {

    /** System property with the threshold in milliseconds. {@code 0} or less turns detection off. */
    static final String PROPERTY = "nexusai.load.stallMillis";
    static final long DEFAULT_THRESHOLD_MILLIS = 5_000L;

    private final long thresholdMillis;
    private boolean started;
    private long startMono;
    private long startWall;
    private long lastBeatMono;
    private double tickMillisSinceBeat;
    private long maxGapMillis;
    private long maxDriftMillis;
    private long windowMillis = -1L;
    private long expectedMillis;

    HostStallDetector(long thresholdMillis) {
        this.thresholdMillis = thresholdMillis;
    }

    static long thresholdFromProperty(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_THRESHOLD_MILLIS;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_THRESHOLD_MILLIS;
        }
    }

    /** First heartbeat of the window. */
    void start(long monoNanos, long wallMillis) {
        started = true;
        startMono = monoNanos;
        startWall = wallMillis;
        lastBeatMono = monoNanos;
        tickMillisSinceBeat = 0.0d;
    }

    /** Duration Paper reported for a finished tick. */
    void tick(double tickMillis) {
        if (started && tickMillis > 0.0d) {
            tickMillisSinceBeat += tickMillis;
        }
    }

    /** One heartbeat on the server thread. */
    void beat(long monoNanos, long wallMillis) {
        if (!started) {
            start(monoNanos, wallMillis);
            return;
        }
        long gap = (monoNanos - lastBeatMono) / 1_000_000L;
        long unexplained = gap - (long) tickMillisSinceBeat;
        maxGapMillis = Math.max(maxGapMillis, unexplained);
        maxDriftMillis = Math.max(maxDriftMillis, drift(monoNanos, wallMillis));
        lastBeatMono = monoNanos;
        tickMillisSinceBeat = 0.0d;
    }

    /** Last heartbeat of the window, with the length the window was meant to have. */
    void end(long monoNanos, long wallMillis, long expectedWindowMillis) {
        if (!started) {
            return;
        }
        beat(monoNanos, wallMillis);
        windowMillis = (monoNanos - startMono) / 1_000_000L;
        expectedMillis = expectedWindowMillis;
    }

    private long drift(long monoNanos, long wallMillis) {
        long mono = (monoNanos - startMono) / 1_000_000L;
        long wall = wallMillis - startWall;
        return Math.abs(wall - mono);
    }

    List<String> reasons() {
        List<String> reasons = new ArrayList<>();
        if (thresholdMillis <= 0L || !started) {
            return reasons;
        }
        if (maxGapMillis > thresholdMillis) {
            reasons.add("tick gap " + maxGapMillis + " ms not covered by tick work > " + thresholdMillis + " ms");
        }
        if (maxDriftMillis > thresholdMillis) {
            reasons.add("wall and monotonic clocks drifted " + maxDriftMillis + " ms > " + thresholdMillis + " ms");
        }
        if (windowMillis >= 0L && windowMillis - expectedMillis > thresholdMillis) {
            reasons.add("window ran " + windowMillis + " ms, expected " + expectedMillis + " ms");
        }
        return reasons;
    }

    boolean invalid() {
        return !reasons().isEmpty();
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        List<String> reasons = reasons();
        map.put("invalid", !reasons.isEmpty());
        map.put("thresholdMillis", thresholdMillis);
        map.put("maxTickGapMillis", maxGapMillis);
        map.put("clockDriftMillis", maxDriftMillis);
        map.put("windowMillis", windowMillis);
        map.put("expectedWindowMillis", expectedMillis);
        map.put("reasons", reasons);
        return map;
    }
}
