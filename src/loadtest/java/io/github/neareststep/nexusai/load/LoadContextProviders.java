package io.github.neareststep.nexusai.load;

import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.api.NexusContextProvider;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The three providers from the load-test spec: a fast cache read, a provider that blocks
 * {@code provide()} for two seconds, and a provider that throws. Each one counts calls that
 * landed on the server thread.
 */
final class LoadContextProviders {

    final Probe fast = new Probe("fast", 10, 0L, false, "coins ~12k");
    final Probe slow = new Probe("slow", 50, 2_000L, false, "slow-value");
    final Probe boom = new Probe("boom", 80, 0L, true, "unused");

    int mainThreadCalls() {
        return fast.mainThread.get() + slow.mainThread.get() + boom.mainThread.get();
    }

    static final class Probe implements NexusContextProvider {
        private final String id;
        private final int priority;
        private final long sleepMillis;
        private final boolean explode;
        private final String text;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger mainThread = new AtomicInteger();

        Probe(String id, int priority, long sleepMillis, boolean explode, String text) {
            this.id = id;
            this.priority = priority;
            this.sleepMillis = sleepMillis;
            this.explode = explode;
            this.text = text;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Duration timeout() {
            return sleepMillis > 0 ? Duration.ofMillis(sleepMillis) : Duration.ofMillis(50);
        }

        @Override
        public CompletableFuture<String> provide(ContextRequest request) {
            calls.incrementAndGet();
            if (Bukkit.isPrimaryThread()) {
                mainThread.incrementAndGet();
            }
            if (explode) {
                throw new IllegalStateException("load-boom-secret");
            }
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return CompletableFuture.completedFuture(text);
        }
    }
}
