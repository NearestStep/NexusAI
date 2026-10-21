package io.github.neareststep.nexusai.event;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.TokenUsage;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R40-2 and R40-1: a refused or failing callEvent is logged, rate-limited, and never thrown to the request. */
class EventDeliveryFailureTest {

    private EventSupport support;
    private final List<LogRecord> records = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000L);

    @AfterEach
    void tearDown() {
        if (support != null) {
            support.close();
        }
        EventDispatcher.install(null);
    }

    @Test
    void failureIsLoggedAsWarningWithTheMessageAndTheTraceOnlyAtFine() {
        support = EventSupport.register("Delivery");
        EventDispatcher dispatcher = dispatcher(event -> {
            throw new IllegalStateException(event.getEventName() + " may only be triggered asynchronously.");
        }, () -> false, () -> false);
        post(dispatcher);
        List<LogRecord> warnings = at(Level.WARNING);
        assertEquals(1, warnings.size());
        String text = warnings.get(0).getMessage();
        assertTrue(text.contains("NexusPostGenerateEvent"), text);
        assertTrue(text.contains("IllegalStateException"), text);
        assertTrue(text.contains("may only be triggered asynchronously"), text);
        assertNull(warnings.get(0).getThrown());
        List<LogRecord> fine = at(Level.FINE);
        assertEquals(1, fine.size());
        assertTrue(fine.get(0).getThrown() instanceof IllegalStateException);
    }

    @Test
    void warningsAreLimitedPerWindowWithOneNote() {
        support = EventSupport.register("Delivery");
        EventDispatcher dispatcher = dispatcher(event -> {
            throw new IllegalStateException("boom");
        }, () -> false, () -> false);
        for (int i = 0; i < 10; i++) {
            post(dispatcher);
        }
        List<LogRecord> warnings = at(Level.WARNING);
        assertEquals(EventDispatcher.FAILURE_WARNINGS + 1, warnings.size());
        assertTrue(warnings.get(EventDispatcher.FAILURE_WARNINGS).getMessage().contains("are not logged for 5 minutes"));
        assertEquals(10, at(Level.FINE).size());

        clock.addAndGet(EventDispatcher.WARN_GAP_NANOS);
        post(dispatcher);
        assertEquals(EventDispatcher.FAILURE_WARNINGS + 2, at(Level.WARNING).size());
    }

    @Test
    void whileStoppingFailuresStayAtFine() {
        support = EventSupport.register("Delivery");
        EventDispatcher dispatcher = dispatcher(event -> {
            throw new IllegalStateException("stopping");
        }, () -> true, () -> false);
        post(dispatcher);
        assertEquals(0, at(Level.WARNING).size());
        assertEquals(1, at(Level.FINE).size());
    }

    @Test
    void asyncEventOnTheServerThreadIsSkippedNotThrown() {
        support = EventSupport.register("Delivery");
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean primary = new AtomicBoolean(true);
        EventDispatcher dispatcher = dispatcher(event -> calls.incrementAndGet(), () -> false, primary::get);
        post(dispatcher);
        assertEquals(0, calls.get());
        assertEquals(0, at(Level.WARNING).size());
        assertTrue(at(Level.FINE).stream().anyMatch(record -> record.getMessage().startsWith("Skipped NexusPostGenerateEvent")));
        primary.set(false);
        post(dispatcher);
        assertEquals(1, calls.get());
    }

    private void post(EventDispatcher dispatcher) {
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "", "");
        dispatcher.post(trace, "pong", "openai", "m", false, TokenUsage.none(), "stop", 1, null);
    }

    private EventDispatcher dispatcher(Consumer<Event> caller, java.util.function.BooleanSupplier stopping,
                                       java.util.function.BooleanSupplier primary) {
        Logger logger = Logger.getLogger("event-delivery-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return EventDispatcher.create(logger, List::of, caller, clock::get, stopping, primary);
    }

    private List<LogRecord> at(Level level) {
        return records.stream().filter(record -> record.getLevel().equals(level)).toList();
    }
}
