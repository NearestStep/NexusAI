package io.github.neareststep.nexusai.event;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.event.NexusPreGenerateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDispatcherTest {

    private EventSupport support;

    @AfterEach
    void tearDown() {
        if (support != null) {
            support.close();
        }
        EventDispatcher.install(null);
    }

    @Test
    void noListenersDoesNotConstructOrDeliverAnEvent() {
        Logger logger = logger("dispatch-quiet");
        List<String> warnings = warnings(logger);
        EventDispatcher dispatcher = EventDispatcher.create(logger, List::of, event -> {
            throw new AssertionError("created " + event);
        }, System::nanoTime, () -> false);
        EventDispatcher.install(dispatcher);
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "harbor", "");
        assertEquals(EventDispatcher.PreOutcome.GO, dispatcher.pre(trace, "openai", "gpt-4o-mini", 3));
        assertTrue(warnings.isEmpty());
        assertEquals(0, dispatcher.attempts(trace.requestId()));
    }

    @Test
    void listenersReceiveTheEventOncePerRequest() {
        support = EventSupport.register("Audit");
        Logger logger = logger("dispatch-once");
        EventDispatcher dispatcher = EventDispatcher.create(
                logger, List::of, support.events::add, System::nanoTime, () -> false);
        EventDispatcher.install(dispatcher);
        CallTrace trace = CallTrace.start(RequestOrigin.API, "Quests", null, "quests:intro", "intro");
        dispatcher.pre(trace, "openai", "gpt-4o-mini", 4);
        dispatcher.pre(trace, "openai", "gpt-4o-mini", 4);
        assertEquals(1, support.events.size());
        assertTrue(support.events.get(0) instanceof NexusPreGenerateEvent);
        NexusPreGenerateEvent event = (NexusPreGenerateEvent) support.events.get(0);
        assertEquals(trace.requestId(), event.requestId());
        assertEquals(RequestOrigin.API, event.origin());
        assertEquals("Quests", event.consumer());
    }

    @Test
    void slowListenerWarnsOncePerFiveMinutesAndNamesThePlugin() {
        support = EventSupport.register("SlowStats");
        Logger logger = logger("dispatch-slow");
        List<String> warnings = warnings(logger);
        AtomicLong clock = new AtomicLong(1_000L);
        EventDispatcher dispatcher = EventDispatcher.create(logger, List::of, event -> {
            clock.addAndGet(60_000_000L);
        }, clock::get, () -> false);
        EventDispatcher.install(dispatcher);
        CallTrace first = CallTrace.start(RequestOrigin.API, "Quests", null, "", "");
        dispatcher.pre(first, "openai", "gpt-4o-mini", 1);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("NexusPreGenerateEvent"), warnings.get(0));
        assertTrue(warnings.get(0).contains("SlowStats"), warnings.get(0));

        CallTrace second = CallTrace.start(RequestOrigin.API, "Quests", null, "", "");
        dispatcher.pre(second, "openai", "gpt-4o-mini", 1);
        assertEquals(1, warnings.size(), warnings.toString());

        clock.addAndGet(EventDispatcher.WARN_GAP_NANOS);
        CallTrace third = CallTrace.start(RequestOrigin.API, "Quests", null, "", "");
        dispatcher.pre(third, "openai", "gpt-4o-mini", 1);
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(1).contains("SlowStats"), warnings.get(1));
    }

    private static Logger logger(String name) {
        Logger logger = Logger.getLogger(name + "-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        return logger;
    }

    private static List<String> warnings(Logger logger) {
        List<String> lines = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING && record.getMessage() != null) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return lines;
    }
}
