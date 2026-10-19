package io.github.neareststep.nexusai.load;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostStallDetectorTest {

    private static final long MS = 1_000_000L;

    /** Heartbeats every 50 ms with 1 ms ticks, for the given number of seconds. */
    private static long healthy(HostStallDetector detector, long mono, long wall, int seconds) {
        for (int i = 0; i < seconds * 20; i++) {
            detector.tick(1.0d);
            mono += 50L * MS;
            wall += 50L;
            detector.beat(mono, wall);
        }
        return mono;
    }

    @Test
    void healthyWindowIsValid() {
        HostStallDetector detector = new HostStallDetector(5_000L);
        detector.start(0L, 1_000L);
        long mono = healthy(detector, 0L, 1_000L, 120);
        detector.end(mono, 1_000L + mono / MS, 120_000L);
        assertFalse(detector.invalid());
        Map<String, Object> map = detector.toMap();
        assertEquals(false, map.get("invalid"));
        assertEquals(120_000L, map.get("windowMillis"));
        assertEquals(List.of(), map.get("reasons"));
    }

    @Test
    void pausedProcessWithBothClocksJumpingIsATickGap() {
        HostStallDetector detector = new HostStallDetector(5_000L);
        detector.start(0L, 0L);
        long mono = healthy(detector, 0L, 0L, 10);
        mono += 48_653L * MS;
        detector.beat(mono, mono / MS);
        assertTrue(detector.invalid());
        assertTrue(detector.reasons().get(0).startsWith("tick gap 48653 ms"), detector.reasons().toString());
    }

    @Test
    void longTicksAreTheServerNotTheHost() {
        HostStallDetector detector = new HostStallDetector(5_000L);
        detector.start(0L, 0L);
        detector.tick(9_000.0d);
        detector.beat(9_050L * MS, 9_050L);
        assertFalse(detector.invalid(), detector.reasons().toString());
    }

    @Test
    void wallClockStepWithoutMonotonicTimeIsDrift() {
        HostStallDetector detector = new HostStallDetector(5_000L);
        detector.start(0L, 0L);
        long mono = healthy(detector, 0L, 0L, 5);
        detector.beat(mono + 50L * MS, mono / MS + 286_000L);
        List<String> reasons = detector.reasons();
        assertEquals(1, reasons.size(), reasons.toString());
        assertTrue(reasons.get(0).startsWith("wall and monotonic clocks drifted"), reasons.toString());
    }

    @Test
    void overrunWindowIsReported() {
        HostStallDetector detector = new HostStallDetector(5_000L);
        detector.start(0L, 0L);
        detector.tick(130_000.0d);
        detector.end(130_000L * MS, 130_000L, 120_000L);
        assertTrue(detector.reasons().contains("window ran 130000 ms, expected 120000 ms"), detector.reasons().toString());
    }

    @Test
    void thresholdIsConfigurableAndZeroTurnsItOff() {
        HostStallDetector strict = new HostStallDetector(1_000L);
        strict.start(0L, 0L);
        strict.beat(2_000L * MS, 2_000L);
        assertTrue(strict.invalid());

        HostStallDetector off = new HostStallDetector(0L);
        off.start(0L, 0L);
        off.beat(600_000L * MS, 900_000L);
        assertFalse(off.invalid());

        assertEquals(5_000L, HostStallDetector.thresholdFromProperty(null));
        assertEquals(5_000L, HostStallDetector.thresholdFromProperty("abc"));
        assertEquals(2_500L, HostStallDetector.thresholdFromProperty(" 2500 "));
        assertEquals(0L, HostStallDetector.thresholdFromProperty("0"));
    }

    @Test
    void windowWithoutHeartbeatsIsNotJudged() {
        HostStallDetector detector = new HostStallDetector(5_000L);
        detector.end(10_000L * MS, 0L, 1_000L);
        assertFalse(detector.invalid());
        assertEquals(-1L, detector.toMap().get("windowMillis"));
    }

    @Test
    void valuesExactlyAtTheThresholdAreValid() {
        HostStallDetector gapAt = new HostStallDetector(5_000L);
        gapAt.start(0L, 0L);
        gapAt.beat(5_000L * MS, 5_000L);
        assertFalse(gapAt.invalid(), gapAt.reasons().toString());
        HostStallDetector gapOver = new HostStallDetector(5_000L);
        gapOver.start(0L, 0L);
        gapOver.beat(5_001L * MS, 5_001L);
        assertTrue(gapOver.reasons().get(0).startsWith("tick gap 5001 ms"), gapOver.reasons().toString());

        HostStallDetector driftAt = new HostStallDetector(5_000L);
        driftAt.start(0L, 0L);
        driftAt.beat(50L * MS, 5_050L);
        assertFalse(driftAt.invalid(), driftAt.reasons().toString());
        HostStallDetector driftOver = new HostStallDetector(5_000L);
        driftOver.start(0L, 0L);
        driftOver.beat(50L * MS, 5_051L);
        assertEquals(List.of("wall and monotonic clocks drifted 5001 ms > 5000 ms"), driftOver.reasons());

        HostStallDetector overrunAt = new HostStallDetector(5_000L);
        overrunAt.start(0L, 0L);
        overrunAt.tick(6_000.0d);
        overrunAt.end(6_000L * MS, 6_000L, 1_000L);
        assertFalse(overrunAt.invalid(), overrunAt.reasons().toString());
        HostStallDetector overrunOver = new HostStallDetector(5_000L);
        overrunOver.start(0L, 0L);
        overrunOver.tick(6_001.0d);
        overrunOver.end(6_001L * MS, 6_001L, 1_000L);
        assertEquals(List.of("window ran 6001 ms, expected 1000 ms"), overrunOver.reasons());
    }

    /**
     * Heartbeats and ticks the way LoadDriverPlugin feeds them: every 50 ms, 1 ms ticks, 2 s of
     * warm-up, one optional hole. Measured ticks land in {@code samples}.
     */
    private static HostStallDetector loadWindow(long holeMillis, List<Double> samples) {
        HostStallDetector detector = new HostStallDetector(5_000L);
        detector.start(0L, 1_000_000L);
        long elapsed = 0L;
        long wall = 1_000_000L;
        HostStallDetector.LoadStep step = HostStallDetector.LoadStep.WARMUP;
        while (step != HostStallDetector.LoadStep.END) {
            detector.loadTick(1.0d, samples::add);
            step = detector.loadStep(elapsed, 30_000L, 2_000L, elapsed * MS, wall + elapsed);
            if (step == HostStallDetector.LoadStep.MEASURE && !detector.measuring()) {
                detector.startMeasuring();
            }
            assertEquals(elapsed >= 30_000L ? HostStallDetector.LoadStep.END
                    : elapsed >= 2_000L ? HostStallDetector.LoadStep.MEASURE : HostStallDetector.LoadStep.WARMUP, step);
            elapsed += elapsed == 10_000L ? holeMillis : 50L;
        }
        assertFalse(detector.measuring(), "measuring did not stop at the end of the window");
        return detector;
    }

    @Test
    void loadHeartbeatsFindAHoleAndEndTheWindow() {
        List<Double> samples = new ArrayList<>();
        HostStallDetector clean = loadWindow(50L, samples);
        assertFalse(clean.invalid(), clean.reasons().toString());
        assertTrue((long) clean.toMap().get("maxTickGapMillis") < 100L, clean.toMap().toString());
        assertEquals(30_000L, clean.toMap().get("windowMillis"));
        assertEquals(30_000L, clean.toMap().get("expectedWindowMillis"));
        // Ticks from 2050 ms (after the heartbeat that started measuring) through 30000 ms.
        assertEquals(560, samples.size());

        HostStallDetector holed = loadWindow(10_000L, new ArrayList<>());
        assertTrue(holed.invalid());
        long gap = (long) holed.toMap().get("maxTickGapMillis");
        assertTrue(gap >= 9_990L && gap <= 10_000L, holed.toMap().toString());
    }

    @Test
    void longWarmupTickIsTickWorkNotAStall() {
        HostStallDetector detector = new HostStallDetector(5_000L);
        detector.start(0L, 0L);
        List<Double> samples = new ArrayList<>();
        assertEquals(HostStallDetector.LoadStep.WARMUP, detector.loadStep(0L, 120_000L, 10_000L, 0L, 0L));
        detector.loadTick(6_000.0d, samples::add);
        assertEquals(HostStallDetector.LoadStep.WARMUP, detector.loadStep(6_050L, 120_000L, 10_000L, 6_050L * MS, 6_050L));
        assertFalse(detector.invalid(), detector.reasons().toString());
        assertEquals(List.of(), samples, "a warm-up tick went into the MSPT samples");
        detector.startMeasuring();
        detector.loadTick(1.0d, samples::add);
        assertEquals(List.of(1.0d), samples);
    }

    @Test
    void invalidWindowGivesTheLogLine() {
        HostStallDetector valid = new HostStallDetector(5_000L);
        valid.start(0L, 0L);
        valid.beat(50L * MS, 50L);
        assertNull(valid.invalidLine("S1"));
        List<String> warned = new ArrayList<>();
        valid.warnIfInvalid("S1", warned::add);
        assertEquals(List.of(), warned);

        HostStallDetector stalled = new HostStallDetector(5_000L);
        stalled.start(0L, 0L);
        stalled.beat(10_013L * MS, 10_013L);
        String line = "SCENARIO_INVALID S1 test host stall: tick gap 10013 ms not covered by tick work > 5000 ms";
        assertEquals(line, stalled.invalidLine("S1"));
        stalled.warnIfInvalid("S1", warned::add);
        assertEquals(List.of(line), warned);
    }
}
