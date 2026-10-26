package io.github.neareststep.nexusai.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

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
    private boolean measuring;
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

    /** What one LOAD heartbeat means for the plugin. */
    enum LoadStep {
        /** The window reached its length and is closed; drain starts. */
        END,
        /** Still in warm-up: no MSPT samples yet. */
        WARMUP,
        /** Past warm-up: the plugin may start or keep measuring. */
        MEASURE
    }

    /** True while LOAD ticks go into the MSPT samples. */
    boolean measuring() {
        return measuring;
    }

    /** Called once by the plugin when it starts the measured part of the window. */
    void startMeasuring() {
        measuring = true;
    }

    /**
     * A finished tick in the LOAD phase. Warm-up ticks count as tick work too, so a long warm-up
     * tick is not taken for a host stall; only ticks while measuring go to {@code measured}.
     */
    void loadTick(double tickMillis, DoubleConsumer measured) {
        tick(tickMillis);
        if (measuring) {
            measured.accept(tickMillis);
        }
    }

    /**
     * One heartbeat in the LOAD phase. Once {@code elapsedMillis} reaches {@code loadMillis} the
     * window ends, measuring stops, and this returns END; before that it records the heartbeat.
     */
    LoadStep loadStep(long elapsedMillis, long loadMillis, long warmupMillis, long monoNanos, long wallMillis) {
        if (elapsedMillis >= loadMillis) {
            end(monoNanos, wallMillis, loadMillis);
            measuring = false;
            return LoadStep.END;
        }
        beat(monoNanos, wallMillis);
        return elapsedMillis >= warmupMillis ? LoadStep.MEASURE : LoadStep.WARMUP;
    }

    /** The {@code SCENARIO_INVALID} log line for an invalid window, or null when it is valid. */
    String invalidLine(String scenario) {
        List<String> reasons = reasons();
        if (reasons.isEmpty()) {
            return null;
        }
        return "SCENARIO_INVALID " + scenario + " test host stall: " + String.join("; ", reasons);
    }

    /** Hands the {@code SCENARIO_INVALID} line to {@code warn} when the window is invalid. */
    void warnIfInvalid(String scenario, Consumer<String> warn) {
        String line = invalidLine(scenario);
        if (line != null) {
            warn.accept(line);
        }
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
