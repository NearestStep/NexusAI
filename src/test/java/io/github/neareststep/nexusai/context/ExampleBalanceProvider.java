package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.api.NexusContextProvider;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Example context provider. A global-region timer reads online balances into a map.
 * On Paper that timer is the main thread. On Folia each balance is read on the region
 * owner's thread. {@link #provide} only returns that map and does not touch Bukkit.
 * Vault is optional: {@code Economy#getBalance} is called by reflection when the plugin is installed.
 * Compile this class against the NexusAI jar ({@code compileOnly}). Register with {@code softdepend: [NexusAI]}.
 */
public final class ExampleBalanceProvider implements NexusContextProvider, Listener {

    private final ConcurrentHashMap<UUID, String> balances = new ConcurrentHashMap<>();
    private Plugin owner;
    private ScheduledTask refreshTask;

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public Duration timeout() {
        return Duration.ofMillis(100);
    }

    @Override
    public CompletableFuture<String> provide(ContextRequest request) {
        if (request == null || request.playerId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(balances.get(request.playerId()));
    }

    public void register(Plugin plugin) {
        this.owner = plugin;
        Bukkit.getServicesManager().register(NexusContextProvider.class, this, plugin, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.refreshTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> refresh(), 100L, 100L);
    }

    public void shutdown(Plugin plugin) {
        Bukkit.getServicesManager().unregister(NexusContextProvider.class, this);
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer() != null) {
            balances.remove(event.getPlayer().getUniqueId());
        }
    }

    /** Test seam. The timer calls this for each online player. */
    public void remember(UUID playerId, double balance) {
        if (playerId == null) {
            return;
        }
        balances.put(playerId, format(balance));
    }

    private void refresh() {
        Plugin plugin = owner;
        if (plugin == null) {
            return;
        }
        Object economy = economy();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null) {
                continue;
            }
            player.getScheduler().run(plugin, task -> remember(player.getUniqueId(), balance(economy, player)), null);
        }
    }

    private static Object economy() {
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(type);
            return registration == null ? null : registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static double balance(Object economy, Player player) {
        if (economy == null || player == null) {
            return 0;
        }
        try {
            Object value = economy.getClass().getMethod("getBalance", org.bukkit.OfflinePlayer.class)
                    .invoke(economy, player);
            return value instanceof Number number ? number.doubleValue() : 0;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return 0;
        }
    }

    static String format(double balance) {
        long coins = Math.round(balance);
        if (Math.abs(coins) >= 1000) {
            return "~" + Math.round(coins / 1000.0) + "k";
        }
        return "~" + coins;
    }
}
