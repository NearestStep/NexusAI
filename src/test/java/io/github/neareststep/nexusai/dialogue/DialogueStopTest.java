package io.github.neareststep.nexusai.dialogue;

import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.StopGate;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plugin disable while a dialogue worker waits for the provider or for an action. */
class DialogueStopTest {

    @AfterEach
    void reopen() {
        StopGate.open();
    }

    @Test
    void closeReleasesAWorkerWaitingForASlowProviderAtOnce() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", http -> {
            http.getRequestBody().readAllBytes();
            received.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            http.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        try {
            PluginConfig config = config(server.getAddress().getPort());
            DialogueTransport transport = new DialogueTransport(() -> config, HttpClient.newHttpClient());
            CompletableFuture<Throwable> worker = CompletableFuture.supplyAsync(() -> {
                try {
                    transport.send(request(config));
                    return null;
                } catch (Throwable thrown) {
                    return thrown;
                }
            });
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertTrue(transport.openCount() > 0);
            long started = System.nanoTime();
            // One send in flight is one request, counted once (not the gate call and its exchange).
            assertEquals(1, transport.close());
            Throwable thrown = worker.get(2, TimeUnit.SECONDS);
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            // The read timeout is 30 s; stop must not wait for it.
            assertTrue(tookMillis < 1_000, "worker returned after " + tookMillis + " ms");
            AiRequestException stopping = assertInstanceOf(AiRequestException.class, thrown);
            assertEquals(DialogueTransport.STOPPING, stopping.getMessage());
            assertTrue(DialogueTransport.isStopping(stopping));
            assertEquals(0, transport.openCount());
            AiRequestException later = assertThrows(AiRequestException.class, () -> transport.send(request(config)));
            assertEquals(DialogueTransport.STOPPING, later.getMessage());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void anActionWaitEndsSoonAfterStopInsteadOfFiveSeconds() throws Exception {
        CompletableFuture<String> never = new CompletableFuture<>();
        CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return DialogueService.awaitAction(never);
            } catch (Exception e) {
                return "error: " + e;
            }
        });
        Thread.sleep(150L);
        long started = System.nanoTime();
        StopGate.close();
        assertEquals(DialogueService.STOPPING, waiting.get(2, TimeUnit.SECONDS));
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(tookMillis < 500, "action wait ended after " + tookMillis + " ms");
    }

    @Test
    void anActionThatFinishedIsReturnedEvenDuringStop() throws Exception {
        StopGate.close();
        assertEquals("ran", DialogueService.awaitAction(CompletableFuture.completedFuture("ran")));
    }

    @Test
    void anActionWaitStillTimesOutWhileRunning() {
        CompletableFuture<String> done = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> done.complete("ran"));
        assertEquals("ran", assertDoesNotThrowValue(done));
    }

    private static String assertDoesNotThrowValue(CompletableFuture<String> done) {
        try {
            return DialogueService.awaitAction(done);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void onlyTheStopFailureCountsAsStopping() {
        assertTrue(!DialogueTransport.isStopping(new AiRequestException(
                io.github.neareststep.nexusai.ai.AiErrorKind.TIMEOUT, 0, DialogueTransport.STOPPING, null)));
        assertTrue(!DialogueTransport.isStopping(new AiRequestException(
                io.github.neareststep.nexusai.ai.AiErrorKind.OTHER, 500, "HTTP 500", null)));
        assertTrue(!DialogueTransport.isStopping(null));
    }

    @Test
    void theTalkApiFailsWithStoppingInsteadOfBusy() {
        CompletableFuture<String> future = new CompletableFuture<>();
        assertTrue(DialogueService.failIfStopping(future, TalkResult.of(TalkCode.SHUTDOWN, "npc"), null));
        ExecutionException failed = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        IllegalStateException stopping = assertInstanceOf(IllegalStateException.class, failed.getCause());
        assertEquals("NexusAI is stopping", stopping.getMessage());

        CompletableFuture<String> thrown = new CompletableFuture<>();
        assertTrue(DialogueService.failIfStopping(thrown, null,
                new java.util.concurrent.CompletionException(DialogueTransportStop.failure())));
        assertTrue(thrown.isCompletedExceptionally());

        CompletableFuture<String> busy = new CompletableFuture<>();
        assertTrue(!DialogueService.failIfStopping(busy, TalkResult.of(TalkCode.BUSY, "npc"), null));
        assertTrue(!busy.isDone());
    }

    /** The failure a closed transport gives a send. */
    private static final class DialogueTransportStop {
        static AiRequestException failure() {
            return new AiRequestException(
                    io.github.neareststep.nexusai.ai.AiErrorKind.OTHER, 0, DialogueTransport.STOPPING, null);
        }
    }

    private static DialogueTransport.Request request(PluginConfig config) {
        return new DialogueTransport.Request(
                config.getBaseUrl(), "test-key", "gpt-4o-mini", "system",
                List.of(new DialogueProtocol.MemoryLine("user", "hi")), List.of(),
                null, null, null, null, Duration.ofSeconds(30));
    }

    private static PluginConfig config(int port) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "http://127.0.0.1:" + port + "/v1");
        yaml.set("api.key", "test-key");
        return new PluginConfig(yaml);
    }
}
