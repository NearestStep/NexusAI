package io.github.neareststep.nexusai;

import io.github.neareststep.nexusai.command.SenderTasks;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reply delivery once the plugin is stopping. */
class StopGateTest {

    @AfterEach
    void reopen() {
        StopGate.open();
    }

    @Test
    void aCommandResultAfterStopIsSkippedWithoutAWarning() {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capture(records);
        AtomicBoolean ran = new AtomicBoolean();
        StopGate.close();
        // A null plugin would throw inside the scheduler call; the gate returns before it.
        SenderTasks.run(null, console(), () -> ran.set(true), logger);
        assertFalse(ran.get());
        assertTrue(records.isEmpty(), records.toString());
        String summary = StopGate.takeSummary();
        assertTrue(summary.contains("0 AI request(s)") && summary.contains("1 reply delivery"), summary);
    }

    @Test
    void aPlayerResultAfterStopNeverReachesTheEntityScheduler() {
        List<LogRecord> records = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger scheduled = new java.util.concurrent.atomic.AtomicInteger();
        Object scheduler = Proxy.newProxyInstance(StopGateTest.class.getClassLoader(),
                new Class<?>[]{io.papermc.paper.threadedregions.scheduler.EntityScheduler.class},
                (proxy, method, args) -> {
                    scheduled.incrementAndGet();
                    return defaultValue(method.getReturnType());
                });
        CommandSender player = (CommandSender) Proxy.newProxyInstance(StopGateTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> method.getName().equals("getScheduler")
                        ? scheduler : defaultValue(method.getReturnType()));
        StopGate.close();
        SenderTasks.run(null, player, () -> { }, capture(records));
        assertEquals(0, scheduled.get());
        assertTrue(records.isEmpty(), records.toString());
    }

    @Test
    void aDisabledPluginCountsAsStoppingEvenBeforeOnDisable() {
        assertTrue(StopGate.drop(plugin(false)));
        assertFalse(StopGate.drop(plugin(true)));
        StopGate.dropped(3);
        String summary = StopGate.takeSummary();
        assertTrue(summary.startsWith("Stopped with 3 AI request(s)") && summary.contains("1 reply delivery"), summary);
        assertNull(StopGate.takeSummary());
    }

    @Test
    void aSchedulingFailureWhileRunningIsStillLogged() {
        List<LogRecord> records = new ArrayList<>();
        SenderTasks.run(null, console(), () -> { }, capture(records));
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertNull(StopGate.takeSummary());
    }

    @Test
    void httpWorkersStopWithinTheBoundEvenIfATaskIgnoresInterrupt() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < until) {
                Thread.interrupted();
            }
        });
        long started = System.nanoTime();
        NexusAI.shutdownExecutor(executor, NexusAI.HTTP_STOP_MILLIS);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(tookMillis < NexusAI.HTTP_STOP_MILLIS + 300, "took " + tookMillis + " ms");
    }

    @Test
    void aDelayedRetryDoesNotHoldTheSchedulerOnStop() {
        java.util.concurrent.ScheduledExecutorService scheduler = NexusAI.createScheduler();
        AtomicBoolean ran = new AtomicBoolean();
        scheduler.schedule(() -> ran.set(true), 2, TimeUnit.SECONDS);
        long started = System.nanoTime();
        NexusAI.shutdownExecutor(scheduler, 5_000L);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(tookMillis < 500, "took " + tookMillis + " ms");
        assertFalse(ran.get());
    }

    private static Logger capture(List<LogRecord> records) {
        Logger logger = Logger.getLogger("stop-gate-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.INFO.intValue()) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(StopGateTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Plugin plugin(boolean enabled) {
        return (Plugin) Proxy.newProxyInstance(StopGateTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isEnabled")) {
                        return enabled;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
