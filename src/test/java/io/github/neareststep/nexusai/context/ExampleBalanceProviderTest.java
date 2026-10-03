package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.ContextRequest;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExampleBalanceProviderTest {

    @Test
    void provideReturnsTheRoundedCacheWithoutReadingThePlayer() throws Exception {
        ExampleBalanceProvider provider = new ExampleBalanceProvider();
        UUID player = UUID.randomUUID();
        provider.remember(player, 12347.18);
        assertEquals("~12k", ExampleBalanceProvider.format(12347.18));
        assertEquals("~12k", provider.provide(new ContextRequest(
                player, "Steve", "world", "shop_tip", ContextRequest.Purpose.PLACEHOLDER)).get());
        assertNull(provider.provide(new ContextRequest(
                UUID.randomUUID(), "Alex", "world", "shop_tip", ContextRequest.Purpose.PLACEHOLDER)).get());
        assertEquals("economy", provider.id());
        assertEquals(10, provider.priority());
    }

    @Test
    void missingPlayerIsEmpty() throws ExecutionException, InterruptedException {
        assertNull(new ExampleBalanceProvider().provide(null).get());
    }
}
