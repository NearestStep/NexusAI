package io.github.neareststep.nexusai.load;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The places where LoadDriverPlugin hands ticks, heartbeats, and the end of a window to
 * HostStallDetector. The plugin is created without a server (no constructor runs), so only the
 * methods that do not touch Bukkit are called.
 */
class LoadDriverPluginWiringTest {
    private static final long MS = 1_000_000L;

    private LoadDriverPlugin plugin;
    private Object run;
    private HostStallDetector stall;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        plugin = (LoadDriverPlugin) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, LoadDriverPlugin.class);
        set(plugin, LoadDriverPlugin.class, "providers", new LoadContextProviders());
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        set(plugin, org.bukkit.plugin.java.JavaPlugin.class, "logger", logger);
        Class<?> runClass = Class.forName(LoadDriverPlugin.class.getName() + "$Run");
        // The Run constructor reads NexusAI through the server, so the fields this test needs are
        // set by hand: an S2 window of 60 s with 10 s of warm-up.
        run = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, runClass);
        set(run, runClass, "scenario", "S2");
        set(run, runClass, "loadMillis", 60_000L);
        set(run, runClass, "warmupMillis", LoadDriverPlugin.WARMUP_SECONDS * 1000L);
        set(run, runClass, "stall", new HostStallDetector(5_000L));
        set(run, runClass, "mspt", new DoubleSamples());
        set(run, runClass, "phase", phase("LOAD"));
        set(plugin, LoadDriverPlugin.class, "run", run);
        stall = (HostStallDetector) get(run, "stall");
    }

    @Test
    void tickDuringWarmupIsTickWorkAndNotASample() throws Exception {
        long start = System.nanoTime();
        stall.start(start, 0L);
        plugin.onTick(6_000.0d);
        stall.beat(start + 6_050L * MS, 6_050L);
        assertFalse(stall.invalid(), "the plugin did not hand the warm-up tick to the detector: " + stall.reasons());
        assertEquals(0, ((DoubleSamples) get(run, "mspt")).count());
        stall.startMeasuring();
        plugin.onTick(1.0d);
        assertEquals(1, ((DoubleSamples) get(run, "mspt")).count());
    }

    @Test
    void loadHeartbeatRecordsTheBeatAndEndsTheWindow() throws Exception {
        long nowMillis = System.nanoTime() / MS;
        set(run, run.getClass(), "startedAt", nowMillis - 1_000L);
        // The detector started 10 s ago with no beat since: the plugin's beat must show the hole.
        stall.start(System.nanoTime() - 10_000L * MS, System.currentTimeMillis() - 10_000L);
        assertEquals(HostStallDetector.LoadStep.WARMUP, plugin.loadBeat(cast(run), nowMillis));
        assertTrue(stall.invalid(), "the plugin heartbeat did not reach the detector");
        assertEquals(phase("LOAD"), get(run, "phase"));

        stall.startMeasuring();
        set(run, run.getClass(), "startedAt", nowMillis - 60_000L);
        assertEquals(HostStallDetector.LoadStep.END, plugin.loadBeat(cast(run), nowMillis));
        assertEquals(phase("DRAIN"), get(run, "phase"));
        assertEquals(nowMillis, get(run, "drainStartedAt"));
        assertFalse(stall.measuring());
        assertEquals(60_000L, stall.toMap().get("expectedWindowMillis"));
    }

    @Test
    void invalidWindowIsLogged() throws Exception {
        long start = System.nanoTime();
        stall.start(start, 0L);
        stall.beat(start + 50L * MS, 50L);
        plugin.reportInvalid(cast(run));
        assertEquals(List.of(), warnings);
        stall.beat(start + 10_100L * MS, 10_100L);
        plugin.reportInvalid(cast(run));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("WARNING SCENARIO_INVALID S2 test host stall: tick gap "), warnings.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static Object phase(String name) throws Exception {
        Class<?> phase = Class.forName(LoadDriverPlugin.class.getName() + "$Phase");
        for (Object constant : phase.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
