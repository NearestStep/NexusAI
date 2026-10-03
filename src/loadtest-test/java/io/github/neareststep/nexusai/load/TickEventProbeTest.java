package io.github.neareststep.nexusai.load;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickEventProbeTest {

    @Test
    void missingEventNamesThePaperClassAndRefusesToEnable() {
        ClassLoader empty = new ClassLoader(null) {
        };
        String failure = TickEventProbe.failureIfMissing(empty);
        assertEquals(TickEventProbe.MISSING, failure);
        assertTrue(failure.contains("ServerTickEndEvent"));
        assertTrue(failure.contains("getTickDuration"));
        assertTrue(failure.contains(TickEventProbe.CLASS_NAME));
        assertTrue(failure.contains("Refusing to enable"));
    }

    @Test
    void paperApiOnTheTestClasspathProvidesTheEvent() {
        assertNull(TickEventProbe.failureIfMissing(TickEventProbe.class.getClassLoader()));
    }
}
