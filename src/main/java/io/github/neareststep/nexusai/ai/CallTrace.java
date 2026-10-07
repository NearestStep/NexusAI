package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.api.RequestOrigin;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One generation, from the entrance that accepted it through to the transport that sends it.
 * Token accounting and events read this later. The consumer is {@code nexusai} until a plugin
 * calls the Java API.
 */
public record CallTrace(
        long requestId,
        RequestOrigin origin,
        String consumer,
        UUID playerId,
        String promptId,
        String label,
        long startedNanos
) {
    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadLocal<Consumer<CallTrace>> DELIVERED = new ThreadLocal<>();

    public CallTrace {
        if (origin == null) {
            throw new IllegalArgumentException("origin");
        }
        if (consumer == null || consumer.isBlank()) {
            consumer = "nexusai";
        }
        promptId = promptId == null ? "" : promptId;
        label = label == null ? "" : label;
    }

    /**
     * A new id and a start time of {@link System#nanoTime()}. The consumer is {@code nexusai}.
     */
    public static CallTrace start(RequestOrigin origin, UUID playerId, String promptId, String label) {
        return new CallTrace(
                IDS.incrementAndGet(),
                origin,
                "nexusai",
                playerId,
                promptId,
                label,
                System.nanoTime());
    }

    /**
     * Tests install a sink on the calling thread. A transport calls {@link #delivered} when the
     * trace reaches it. Production leaves the sink empty, and the call is then a no-op.
     */
    public static void capture(Consumer<CallTrace> sink) {
        if (sink == null) {
            DELIVERED.remove();
        } else {
            DELIVERED.set(sink);
        }
    }

    /** Drops the sink installed by {@link #capture}. */
    public static void endCapture() {
        DELIVERED.remove();
    }

    /** Called by a transport on the thread that is about to send. */
    public static void delivered(CallTrace trace) {
        if (trace == null) {
            return;
        }
        Consumer<CallTrace> sink = DELIVERED.get();
        if (sink != null) {
            sink.accept(trace);
        }
    }
}
