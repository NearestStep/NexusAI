package io.github.neareststep.nexusai.load;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.api.NexusAIApi;
import io.github.neareststep.nexusai.context.ContextService;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RCON entry point {@code naiload <scenario> <rate> <seconds> <viewers>}.
 * The command returns immediately. MSPT, call time, and the HTTP pool are sampled on the
 * server thread. The report is {@code plugins/NexusAI-LoadDriver/report.json}.
 */
public final class LoadDriverPlugin extends JavaPlugin implements CommandExecutor, Listener {

    /** Survives a retry and the next scenario, so a repeated literal is not a cache hit. */
    private static final AtomicInteger MISS_SEQUENCE = new AtomicInteger();

    static final int WARMUP_SECONDS = 10;
    static final int DRAIN_SECONDS = 30;
    static final int POOL_BURST_PER_TICK = 40;
    static final String HIT = "%ainexus_cached_load_hit%";
    static final String CONTEXT = "%ainexus_cached_load_ctx%";

    private static final Set<String> SCENARIOS = Set.of(
            "baseline", "S1", "S2", "S2-over", "S3", "S4", "S5", "S-pool");

    private LoadContextProviders providers;
    private Run run;
    private BukkitTask heartbeat;

    @Override
    public void onEnable() {
        String missing = TickEventProbe.failureIfMissing(getClass().getClassLoader());
        if (missing != null) {
            getLogger().severe(missing);
            throw new IllegalStateException(missing);
        }
        if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create the load-driver data folder.");
        }
        providers = new LoadContextProviders();
        NexusAIApi.registerContextProvider(this, providers.fast);
        NexusAIApi.registerContextProvider(this, providers.slow);
        NexusAIApi.registerContextProvider(this, providers.boom);
        if (getCommand("naiload") != null) {
            getCommand("naiload").setExecutor(this);
        } else {
            getLogger().severe("Command naiload is missing from plugin.yml.");
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new TickMonitor(this), this);
        getLogger().info("NexusAI-LoadDriver enabled. Context providers: fast, slow, boom.");
    }

    @Override
    public void onDisable() {
        if (heartbeat != null) {
            heartbeat.cancel();
            heartbeat = null;
        }
        if (providers != null) {
            NexusAIApi.unregisterContextProvider(this, providers.fast.id());
            NexusAIApi.unregisterContextProvider(this, providers.slow.id());
            NexusAIApi.unregisterContextProvider(this, providers.boom.id());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 4) {
            sender.sendMessage("usage: naiload <scenario> <rate> <seconds> <viewers>");
            return true;
        }
        String scenario = args[0];
        if (!SCENARIOS.contains(scenario)) {
            sender.sendMessage("unknown scenario " + scenario
                    + ". expected baseline, S1, S2, S2-over, S3, S4, S5, S-pool");
            return true;
        }
        int rate;
        int seconds;
        int viewers;
        try {
            rate = Integer.parseInt(args[1]);
            seconds = Integer.parseInt(args[2]);
            viewers = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("rate, seconds, and viewers must be integers");
            return true;
        }
        if (rate < 0 || rate > 100_000 || seconds < 1 || seconds > 900 || viewers < 0 || viewers > 500) {
            sender.sendMessage("rate 0..100000, seconds 1..900, viewers 0..500");
            return true;
        }
        if (run != null) {
            sender.sendMessage("naiload busy with " + run.scenario);
            return true;
        }
        Run next = new Run(scenario, rate, seconds, viewers);
        if (("S4".equals(scenario) || "S5".equals(scenario)) && Bukkit.getOnlinePlayers().isEmpty()) {
            next.error = scenario + " needs online players (mode B). No bot is connected.";
            next.skipped = true;
            next.phase = Phase.DONE;
            run = next;
            getLogger().info("SCENARIO_START " + scenario + " rate=" + rate + " seconds=" + seconds
                    + " viewers=" + viewers);
            finish(next, false);
            sender.sendMessage("naiload skipped " + scenario + ": no online players");
            return true;
        }
        run = next;
        getLogger().info("SCENARIO_START " + scenario + " rate=" + rate + " seconds=" + seconds
                + " viewers=" + viewers);
        heartbeat = Bukkit.getScheduler().runTaskTimer(this, this::heartbeat, 1L, 1L);
        sender.sendMessage("naiload queued " + scenario + " for " + seconds + "s");
        return true;
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Run current = run;
        if (current == null || !"S5".equals(current.scenario) || event.getMessage() == null) {
            return;
        }
        if (event.getMessage().toLowerCase(Locale.ROOT).startsWith("/nai talk")) {
            current.talkCommands++;
        }
    }

    /**
     * Paper computes {@code getTickDuration} before {@code ServerTickEndEvent}.
     * Placeholder work has to run earlier in the same tick, from the scheduler, or MSPT stays flat.
     */
    void onTick(double tickMillis) {
        Run current = run;
        if (current == null || current.phase != Phase.LOAD || !current.measuring) {
            return;
        }
        current.mspt.add(tickMillis);
    }

    private void heartbeat() {
        Run current = run;
        if (current == null || current.phase == Phase.DONE) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            if (current.phase == Phase.WARM) {
                warm(current, now);
                return;
            }
            if (current.phase == Phase.LOAD) {
                long elapsed = now - current.startedAt;
                if (elapsed >= current.loadMillis) {
                    current.phase = Phase.DRAIN;
                    current.measuring = false;
                    current.drainStartedAt = now;
                } else {
                    boolean measure = elapsed >= current.warmupMillis;
                    if (measure && !current.threadsSeen) {
                        // One tick of bookkeeping stays outside the MSPT window.
                        sampleThreads(current);
                        return;
                    }
                    if (measure && !current.measuring) {
                        current.measuring = true;
                        getLogger().info("SCENARIO_MEASURE " + current.scenario);
                    }
                    if (now - current.lastAux >= 1000L) {
                        current.lastAux = now;
                        samplePool(current, measure);
                    }
                    produce(current, measure);
                    return;
                }
            }
            if (current.phase == Phase.DRAIN) {
                boolean settled = poolIdle();
                if (settled || now - current.drainStartedAt >= current.drainMillis) {
                    finish(current, settled);
                }
            }
        } catch (Throwable thrown) {
            noteError(current, thrown);
            finish(current, false);
        }
    }

    private void warm(Run current, long now) {
        if (now - current.warmStartedAt > 30_000L) {
            current.error = "cache did not return pong within 30s";
            finish(current, false);
            return;
        }
        current.warmAttempts++;
        String value = PlaceholderAPI.setPlaceholders((OfflinePlayer) null, HIT);
        if (value != null && value.contains("pong")) {
            current.phase = Phase.LOAD;
            current.startedAt = now;
            current.lastAux = now;
            current.rejectedAtStart = rejectedNow();
            getLogger().info("SCENARIO_LOAD " + current.scenario);
        }
    }

    private void produce(Run current, boolean measure) {
        if ("S-pool".equals(current.scenario)) {
            for (int i = 0; i < POOL_BURST_PER_TICK; i++) {
                resolve(current, missToken(MISS_SEQUENCE.incrementAndGet()), null, measure);
            }
            current.unique += POOL_BURST_PER_TICK;
            return;
        }
        if (current.rate <= 0) {
            return;
        }
        current.carry += current.rate / 20.0d;
        int count = (int) current.carry;
        current.carry -= count;
        List<Player> players = "S4".equals(current.scenario)
                ? new ArrayList<>(Bukkit.getOnlinePlayers())
                : List.of();
        if ("S4".equals(current.scenario) && players.isEmpty()) {
            current.error = "S4 lost its online players";
            finish(current, false);
            return;
        }
        for (int i = 0; i < count; i++) {
            if ("S1".equals(current.scenario)) {
                resolve(current, HIT, null, measure);
                continue;
            }
            if ("S4".equals(current.scenario)) {
                Player player = players.get(current.playerCursor % players.size());
                current.playerCursor++;
                resolve(current, CONTEXT, player, measure);
                continue;
            }
            String token = missToken(MISS_SEQUENCE.incrementAndGet());
            current.unique++;
            int copies = "S3".equals(current.scenario) ? 1 : 3;
            for (int copy = 0; copy < copies; copy++) {
                resolve(current, token, null, measure);
            }
        }
    }

    private void resolve(Run current, String token, Player player, boolean measure) {
        long start = System.nanoTime();
        try {
            if (player == null) {
                PlaceholderAPI.setPlaceholders((OfflinePlayer) null, token);
            } else {
                PlaceholderAPI.setPlaceholders(player, token);
            }
        } catch (Throwable thrown) {
            noteError(current, thrown);
        } finally {
            current.resolutions++;
            if (measure) {
                current.calls.add(System.nanoTime() - start);
            }
        }
    }

    private void samplePool(Run current, boolean measure) {
        HttpPool.Snapshot snap = snapshot();
        if (snap != null) {
            current.maxWorkerQueued = Math.max(current.maxWorkerQueued, snap.workerQueued());
            current.maxInFlight = Math.max(current.maxInFlight, snap.inFlight());
            current.maxHttpWaiting = Math.max(current.maxHttpWaiting, snap.httpWaiting());
            current.maxWorkerActive = Math.max(current.maxWorkerActive, snap.workerActive());
            current.workerQueueCapacity = snap.workerQueueCapacity();
            current.maxInFlightCap = snap.maxInFlight();
            current.httpWaitCapacity = snap.httpWaitCapacity();
            current.workerRejected = snap.workerRejected();
            current.httpRejected = snap.httpRejected();
            if (current.series.size() < 48 && (current.series.isEmpty()
                    || System.currentTimeMillis() - current.lastSeries >= 5_000L)) {
                current.lastSeries = System.currentTimeMillis();
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("t", (System.currentTimeMillis() - current.startedAt) / 1000L);
                point.put("queued", snap.workerQueued());
                point.put("inFlight", snap.inFlight());
                point.put("waiting", snap.httpWaiting());
                point.put("workerRejected", snap.workerRejected());
                point.put("httpRejected", snap.httpRejected());
                current.series.add(point);
            }
        }
        if (measure) {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps.length > 0) {
                if (!current.tpsSeen || tps[0] < current.tpsMin) {
                    current.tpsMin = tps[0];
                }
                current.tpsSeen = true;
                current.tpsLast = tps[0];
            }
        }
    }

    private void sampleThreads(Run current) {
        int http = 0;
        int context = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isAlive()) {
                continue;
            }
            String name = thread.getName();
            if (name.startsWith("nexusai-http-")) {
                http++;
            } else if (name.startsWith("nexusai-context-")) {
                context++;
            }
        }
        if (!current.threadsSeen) {
            current.httpMin = http;
            current.contextMin = context;
            current.threadsSeen = true;
        }
        current.httpMin = Math.min(current.httpMin, http);
        current.httpMax = Math.max(current.httpMax, http);
        current.contextMin = Math.min(current.contextMin, context);
        current.contextMax = Math.max(current.contextMax, context);
        current.httpLast = http;
        current.contextLast = context;
    }

    private void finish(Run current, boolean settled) {
        if (current.phase == Phase.DONE && current.reported) {
            return;
        }
        current.phase = Phase.DONE;
        current.settled = settled;
        current.drainActualMillis = current.drainStartedAt == 0L
                ? 0L
                : Math.max(0L, System.currentTimeMillis() - current.drainStartedAt);
        samplePool(current, false);
        current.reported = true;
        if (heartbeat != null) {
            heartbeat.cancel();
            heartbeat = null;
        }
        sampleThreads(current);
        try {
            writeReport(current);
        } catch (IOException ex) {
            getLogger().severe("Could not write report.json: " + ex.getMessage());
        }
        getLogger().info("SCENARIO_END " + current.scenario);
        if (run == current) {
            run = null;
        }
    }

    private void writeReport(Run current) throws IOException {
        if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
            throw new IOException("data folder is missing");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scenario", current.scenario);
        root.put("finished", true);
        root.put("skipped", current.skipped);
        root.put("error", current.error == null ? "" : current.error);
        root.put("mode", "S4".equals(current.scenario) || "S5".equals(current.scenario) ? "B" : "A");
        root.put("rate", current.rate);
        root.put("seconds", current.seconds);
        root.put("viewers", current.viewers);
        root.put("warmupSeconds", current.warmupMillis / 1000L);
        root.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
        root.put("resolutions", current.resolutions);
        root.put("uniquePrompts", current.unique);
        root.put("talkCommands", current.talkCommands);
        root.put("note", note(current));

        Map<String, Object> mspt = new LinkedHashMap<>();
        mspt.put("samples", current.mspt.count());
        mspt.put("mean", current.mspt.mean());
        mspt.put("p95", current.mspt.percentile(0.95d));
        mspt.put("p99", current.mspt.percentile(0.99d));
        mspt.put("max", current.mspt.max());
        root.put("mspt", mspt);

        Map<String, Object> calls = new LinkedHashMap<>();
        calls.put("samples", current.calls.count());
        calls.put("p50", current.calls.percentile(0.50d));
        calls.put("p99", current.calls.percentile(0.99d));
        calls.put("max", current.calls.max());
        root.put("callNanos", calls);

        root.put("tpsMin", current.tpsSeen ? current.tpsMin : -1.0d);
        root.put("tpsLast", current.tpsSeen ? current.tpsLast : -1.0d);

        Map<String, Object> threads = new LinkedHashMap<>();
        threads.put("httpMin", current.threadsSeen ? current.httpMin : current.httpLast);
        threads.put("httpMax", current.httpMax);
        threads.put("httpLast", current.httpLast);
        threads.put("contextMin", current.threadsSeen ? current.contextMin : current.contextLast);
        threads.put("contextMax", current.contextMax);
        threads.put("contextLast", current.contextLast);
        root.put("threads", threads);

        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("workerQueueCapacity", current.workerQueueCapacity);
        pool.put("maxInFlightCap", current.maxInFlightCap);
        pool.put("httpWaitCapacity", current.httpWaitCapacity);
        pool.put("maxWorkerQueued", current.maxWorkerQueued);
        pool.put("maxInFlight", current.maxInFlight);
        pool.put("maxHttpWaiting", current.maxHttpWaiting);
        pool.put("maxWorkerActive", current.maxWorkerActive);
        pool.put("workerRejected", current.workerRejected);
        pool.put("httpRejected", current.httpRejected);
        pool.put("rejectedAtStart", current.rejectedAtStart);
        pool.put("rejectedDelta", Math.max(0L, current.workerRejected + current.httpRejected - current.rejectedAtStart));
        root.put("pool", pool);

        Map<String, Object> drain = new LinkedHashMap<>();
        drain.put("settled", current.settled);
        drain.put("millis", current.drainActualMillis);
        root.put("drain", drain);

        Map<String, Object> context = new LinkedHashMap<>();
        int fastCalls = providers.fast.calls.get() - current.fastAtStart;
        int slowCalls = providers.slow.calls.get() - current.slowAtStart;
        int boomCalls = providers.boom.calls.get() - current.boomAtStart;
        int fastMain = providers.fast.mainThread.get() - current.fastMainAtStart;
        int slowMain = providers.slow.mainThread.get() - current.slowMainAtStart;
        int boomMain = providers.boom.mainThread.get() - current.boomMainAtStart;
        context.put("fastCalls", fastCalls);
        context.put("slowCalls", slowCalls);
        context.put("boomCalls", boomCalls);
        context.put("fastOnMainThread", fastMain);
        context.put("slowOnMainThread", slowMain);
        context.put("boomOnMainThread", boomMain);
        context.put("provideOnMainThread", fastMain + slowMain + boomMain);
        context.put("rows", contextRows());
        root.put("context", context);
        root.put("errors", current.errors);
        root.put("series", current.series);

        String json = JsonMaps.object(root);
        Path path = new File(getDataFolder(), "report.json").toPath();
        Path tmp = path.resolveSibling("report.json.tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Map<String, Object>> contextRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        NexusAI nexus = nexus();
        if (nexus == null || nexus.getContextService() == null) {
            return rows;
        }
        for (ContextService.StatusRow row : nexus.getContextService().status(System.currentTimeMillis())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("plugin", row.pluginName());
            item.put("suspended", row.suspended());
            item.put("timeouts", row.timeouts());
            item.put("timeoutMillis", row.timeoutMillis());
            item.put("line", row.format());
            rows.add(item);
        }
        return rows;
    }

    private static String note(Run current) {
        if ("S4".equals(current.scenario)) {
            return "Mode B. cached_ with context: all is resolved for each online player on the server thread.";
        }
        if ("S5".equals(current.scenario)) {
            return "Mode B. Bots send /nai talk. This driver only records MSPT and talk commands.";
        }
        if ("S-pool".equals(current.scenario)) {
            return "Burst of unique cache misses. A full HTTP pool must fail with HTTP queue is full.";
        }
        if ("S1".equals(current.scenario)) {
            return "Mode A. One warmed cached_ placeholder, resolved on the server thread at the requested rate. "
                    + "Viewers are synthetic: PlaceholderAPI is called with a null player.";
        }
        return "Mode A. Unique literal cached_ prompts, null player. S2 and S2-over resolve each prompt three times "
                + "so in-flight dedup is visible. S3 resolves each prompt once.";
    }

    private static String missToken(int id) {
        return "%ainexus_cached_Load miss " + id + ".%";
    }

    private boolean poolIdle() {
        HttpPool.Snapshot snap = snapshot();
        return snap != null && snap.inFlight() == 0 && snap.workerQueued() == 0 && snap.httpWaiting() == 0;
    }

    private long rejectedNow() {
        HttpPool.Snapshot snap = snapshot();
        if (snap == null) {
            return 0L;
        }
        return snap.workerRejected() + snap.httpRejected();
    }

    private HttpPool.Snapshot snapshot() {
        NexusAI nexus = nexus();
        if (nexus == null || nexus.getHttpPool() == null) {
            return null;
        }
        return nexus.getHttpPool().snapshot();
    }

    private NexusAI nexus() {
        Plugin plugin = getServer().getPluginManager().getPlugin("NexusAI");
        if (plugin instanceof NexusAI nexus) {
            return nexus;
        }
        return null;
    }

    private static void noteError(Run current, Throwable thrown) {
        String message = thrown.getClass().getSimpleName() + ": " + thrown.getMessage();
        if (current.errors.size() < 8 && !current.errors.contains(message)) {
            current.errors.add(message);
        }
        if (current.error == null || current.error.isBlank()) {
            current.error = message;
        }
    }

    private enum Phase {
        WARM,
        LOAD,
        DRAIN,
        DONE
    }

    private final class Run {
        private final String scenario;
        private final int rate;
        private final int seconds;
        private final int viewers;
        private final long loadMillis;
        private final long warmupMillis;
        private final long drainMillis;
        private final long warmStartedAt = System.currentTimeMillis();
        private final int fastAtStart = providers.fast.calls.get();
        private final int slowAtStart = providers.slow.calls.get();
        private final int boomAtStart = providers.boom.calls.get();
        private final int fastMainAtStart = providers.fast.mainThread.get();
        private final int slowMainAtStart = providers.slow.mainThread.get();
        private final int boomMainAtStart = providers.boom.mainThread.get();
        private final DoubleSamples mspt = new DoubleSamples();
        private final LongSamples calls = new LongSamples();
        private final List<String> errors = new ArrayList<>();
        private final List<Map<String, Object>> series = new ArrayList<>();

        private Phase phase;
        private long startedAt;
        private long lastAux;
        private long lastSeries;
        private long drainStartedAt;
        private long drainActualMillis;
        private long rejectedAtStart;
        private double carry;
        private int unique;
        private int resolutions;
        private int warmAttempts;
        private int playerCursor;
        private int talkCommands;
        private boolean skipped;
        private boolean settled;
        private boolean reported;
        private boolean measuring;
        private boolean threadsSeen;
        private boolean tpsSeen;
        private String error = "";
        private int httpMin = Integer.MAX_VALUE;
        private int httpMax;
        private int httpLast;
        private int contextMin = Integer.MAX_VALUE;
        private int contextMax;
        private int contextLast;
        private double tpsMin = 20.0d;
        private double tpsLast = 20.0d;
        private int maxWorkerQueued;
        private int maxInFlight;
        private int maxHttpWaiting;
        private int maxWorkerActive;
        private int workerQueueCapacity;
        private int maxInFlightCap;
        private int httpWaitCapacity;
        private long workerRejected;
        private long httpRejected;

        private Run(String scenario, int rate, int seconds, int viewers) {
            this.scenario = scenario;
            this.rate = rate;
            this.seconds = seconds;
            this.viewers = viewers;
            this.loadMillis = seconds * 1000L;
            boolean shortWindow = "baseline".equals(scenario) || "S-pool".equals(scenario);
            this.warmupMillis = shortWindow ? 0L : WARMUP_SECONDS * 1000L;
            if ("baseline".equals(scenario)) {
                this.drainMillis = 0L;
            } else if ("S-pool".equals(scenario)) {
                this.drainMillis = 15_000L;
            } else {
                this.drainMillis = DRAIN_SECONDS * 1000L;
            }
            this.phase = "S1".equals(scenario) ? Phase.WARM : Phase.LOAD;
            this.startedAt = this.warmStartedAt;
            this.lastAux = this.startedAt;
            this.rejectedAtStart = rejectedNow();
        }
    }
}
