package io.github.neareststep.nexusai.load;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
