package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.ai.KeyRing;
import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.command.SenderTasks;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.context.ContextBlock;
import io.github.neareststep.nexusai.context.ContextVariables;
import io.github.neareststep.nexusai.prompt.PromptContext;
import io.github.neareststep.nexusai.i18n.MessageService;
import io.github.neareststep.nexusai.placeholder.VarSubstitutor;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.net.http.HttpClient;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Bukkit-facing dialogue sessions. Model calls run off the region thread.
 * Commands run on the global region scheduler or the player's entity scheduler.
 */
public final class DialogueService {

    private final NexusAI plugin;
    private final ExecutorService httpExecutor;
    private final ScheduledExecutorService scheduler;
    private final MemoryStore memory;
    private final DialogueEngine engine;
    private final SummaryStats summaryStats;
    private final ConcurrentHashMap<UUID, RecentChat> recentChat = new ConcurrentHashMap<>();
    /** Spelling of the character id that opened the current session. Later lines reuse it. */
    private final ConcurrentHashMap<UUID, String> talkLabels = new ConcurrentHashMap<>();
    private ScheduledFuture<?> sweep;
    private ScheduledFuture<?> save;

    public DialogueService(NexusAI plugin, ExecutorService httpExecutor, ScheduledExecutorService scheduler) {
        this.plugin = plugin;
        this.httpExecutor = httpExecutor;
        this.scheduler = scheduler;
        this.memory = new MemoryStore();
        DialogueSettings initial = plugin.getPluginConfig().dialogueSettings();
        if (initial.persistMemory()) {
            memory.load(memoryFile(), System.currentTimeMillis(), initial.memoryExpiryMillis(), plugin.getLogger());
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(plugin.getPluginConfig().getConnectTimeout()).build();
        DialogueTransport transport = new DialogueTransport(
                plugin::getPluginConfig, http, plugin.getHttpPool().gate());
        DialogueRouter router = new DialogueRouter(
                ignored -> plugin.getPluginConfig(),
                ignored -> plugin.getModelQueue(),
                this::ring,
                transport,
                new DialogueRouter.Admission() {
                    @Override
                    public Optional<String> admit(UUID playerId, String key) {
                        if (plugin.getAiHttpClient() == null) {
                            return Optional.of("NexusAI is not ready");
                        }
                        return plugin.getAiHttpClient().tryAdmit(playerId, key);
                    }

                    @Override
                    public void success(String key) {
                        if (plugin.getAiHttpClient() != null) {
                            plugin.getAiHttpClient().recordAdmissionSuccess(key);
                        }
                    }

                    @Override
                    public void failure(String key, Throwable error) {
                        if (plugin.getAiHttpClient() != null) {
                            plugin.getAiHttpClient().recordAdmissionFailure(key, error);
                        }
                    }
                },
                plugin.getLogger(),
                System::currentTimeMillis
        );
        this.summaryStats = new SummaryStats(ZoneId.systemDefault());
        DialogueSummary summaries = new DialogueSummary(
                memory,
                router::route,
                summaryStats,
                plugin.getLogger(),
                httpExecutor
        );
        this.engine = new DialogueEngine(
                memory,
                new SessionBook(),
                new ActionGate(),
                new DialogueBudget(),
                new GreetingCache(),
                router::route,
                this::runAction,
                new ActionLog(plugin.getLogger(), new File(plugin.getDataFolder(), "actions.log"),
                        () -> plugin.getPluginConfig().dialogueSettings().actionLog()),
                ZoneId.systemDefault(),
                summaries
        );
    }

    public String summaryStatus(long nowMillis) {
        return summaryStats.text(plugin.getPluginConfig().dialogueSettings().summaryEnabled(), nowMillis);
    }

    public void resetSummaryStats() {
        summaryStats.reset();
    }

    public void start() {
        this.sweep = scheduler.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.SECONDS);
        this.save = scheduler.scheduleWithFixedDelay(this::saveMemory, 30, 30, TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (sweep != null) {
            sweep.cancel(false);
        }
        if (save != null) {
            save.cancel(false);
        }
        saveMemory();
    }

    public boolean capturesChat(UUID player) {
        return plugin.getPluginConfig().dialogueSettings().dialogueEnabled() && engine.sessions().has(player);
    }

    /**
     * Paper can deliver the same chat line as both the Adventure event and the legacy event.
     *
     * @return {@code false} when this exact line was already accepted a moment ago
     */
    public boolean consumeChat(UUID player, String text) {
        long now = System.nanoTime();
        String line = text == null ? "" : text;
        RecentChat previous = recentChat.put(player, new RecentChat(line, now));
        return previous == null || !previous.text.equals(line) || now - previous.at > 750_000_000L;
    }

    /**
     * Public API entry. A blank message opens a session and completes with the greeting.
     * The NPC line is not written to chat; session status still is.
     */
    public CompletableFuture<String> talk(Player player, String characterId, String message) {
        boolean open = message == null || message.isBlank();
        return submit(player, characterId, message == null ? "" : message, false, false, open);
    }

    public void fromCommand(Player player, String characterId, String message) {
        boolean open = message == null || message.isBlank();
        submit(player, characterId, message == null ? "" : message, false, true, open);
    }

    public void acceptChat(Player player, String text) {
        Optional<SessionBook.Session> session = engine.sessions().get(player.getUniqueId());
        if (session.isEmpty()) {
            return;
        }
        String stored = talkLabels.get(player.getUniqueId());
        String id = sessionLabel(stored, session.get().characterId());
        submit(player, id, text, true, true, false);
    }

    public void end(Player player) {
        if (player == null) {
            return;
        }
        try {
            httpExecutor.execute(() -> present(player, engine.talk(endRequest(player)), true, false));
        } catch (RejectedExecutionException rejected) {
            plugin.getLogger().fine(HttpPool.QUEUE_FULL);
        }
    }

    public void quit(UUID player) {
        engine.sessions().close(player);
        if (player != null) {
            talkLabels.remove(player);
        }
    }

    public void onMove(Player player, Location to) {
        if (player == null || to == null || to.getWorld() == null) {
            return;
        }
        engine.move(player.getUniqueId(), to.getWorld().getName(), to.getX(), to.getY(), to.getZ())
                .ifPresent(closed -> plugin.getMessageService().send(player, "talk.left"));
    }

    private CompletableFuture<String> submit(
            Player player,
            String characterId,
            String message,
            boolean sessionChat,
            boolean notifyReply,
            boolean notifyStart
    ) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (player == null) {
            future.completeExceptionally(new IllegalArgumentException("player"));
            return future;
        }
        Runnable run = () -> {
            String typed = characterId == null ? "" : characterId;
            String id = lookupId(typed);
            boolean opening = !sessionChat && (message == null || message.isBlank());
            String display = opening ? typed : sessionLabel(talkLabels.get(player.getUniqueId()), typed);
            Prepared prepared = build(player, id, message, sessionChat);
            try {
                if (prepared.contextRequest == null || plugin.getContextService() == null) {
                    httpExecutor.execute(() -> talkPrepared(
                            player, future, typed, display, prepared.request, notifyReply, notifyStart));
                    return;
                }
                plugin.getContextService().collect(prepared.contextRequest, prepared.selection).whenComplete((wrapped, error) -> {
                    try {
                        httpExecutor.execute(() -> deliverContext(
                                player, future, typed, display, prepared, wrapped, error, notifyReply, notifyStart));
                    } catch (RejectedExecutionException rejected) {
                        rejectDialogue(player, future, notifyReply, rejected);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                rejectDialogue(player, future, notifyReply, rejected);
            }
        };
        if (owns(player)) {
            run.run();
        } else {
            player.getScheduler().run(plugin, task -> run.run(), () ->
                    future.completeExceptionally(new IllegalStateException("player unavailable")));
        }
        return future;
    }

    /**
     * Catalog ids are {@code [a-z0-9_-]}, so lookup is case-insensitive.
     * The string the player typed is kept for chat display.
     */
    static String lookupId(String typed) {
        return typed == null ? "" : typed.toLowerCase(Locale.ROOT);
    }

    /**
     * One spelling for the rest of a session. The command that opened it wins.
     * A later chat line passes the lowercased session id; this returns the opening spelling
     * when it is the same character. A different id keeps what was just typed.
     */
    static String sessionLabel(String remembered, String typed) {
        if (remembered != null && !remembered.isEmpty() && lookupId(remembered).equals(lookupId(typed))) {
            return remembered;
        }
        return typed == null ? "" : typed;
    }

    /**
     * Shows {@code typed} in {@code talk.started}, {@code talk.unknown-character}, and the
     * character name. The engine still matches on {@link #lookupId(String)}.
     */
    static TalkResult withDisplayId(TalkResult result, String typed) {
        if (result == null || typed == null || typed.isEmpty() || typed.equals(result.characterId())) {
            return result;
        }
        return new TalkResult(result.code(), typed, result.text(), result.error(), result.limit());
    }

    private String present(Player player, TalkResult result, boolean notifyReply, boolean notifyStart) {
        String line = shown(result);
        SenderTasks.run(plugin, player, () -> deliver(player, result, notifyReply, notifyStart), plugin.getLogger());
        return line;
    }

    private void deliver(Player player, TalkResult result, boolean notifyReply, boolean notifyStart) {
        MessageService messages = plugin.getMessageService();
        if ((notifyReply || notifyStart) && result.code() == TalkCode.STARTED) {
            messages.send(player, "talk.started", Map.of("id", result.characterId()));
        }
        boolean npcLine = result.code() == TalkCode.REPLY || result.code() == TalkCode.REPLIES || result.code() == TalkCode.STARTED;
        if (notifyReply && npcLine && result.text() != null && !result.text().isBlank() && result.code() != TalkCode.STARTED) {
            messages.send(player, "talk.reply", Map.of("character", result.characterId(), "reply", result.text()));
        }
        if (notifyReply && result.code() == TalkCode.STARTED && result.text() != null && !result.text().isBlank()) {
            messages.send(player, "talk.reply", Map.of("character", result.characterId(), "reply", result.text()));
        }
        if (!notifyReply && result.code() != TalkCode.STARTED) {
            return;
        }
        switch (result.code()) {
            case DISABLED -> messages.send(player, "talk.disabled");
            case UNKNOWN -> messages.send(player, "talk.unknown-character", Map.of("id", result.characterId()));
            case NO_SESSION -> messages.send(player, "talk.no-session");
            case ENDED -> messages.send(player, "talk.ended");
            case COOLDOWN -> messages.send(player, "talk.cooldown");
            case TOO_LONG -> messages.send(player, "talk.too-long", Map.of("max", Integer.toString(result.limit())));
            case REPLIES -> messages.send(player, "talk.replies");
            case DAILY -> messages.send(player, "talk.daily");
            case BUSY -> messages.send(player, "talk.busy");
            case FAILED -> messages.send(player, "talk.failed", Map.of("error", result.error()));
            case EMPTY -> messages.send(player, "talk.empty");
            default -> {
            }
        }
    }

    private String shown(TalkResult result) {
        MessageService messages = plugin.getMessageService();
        return switch (result.code()) {
            case STARTED, REPLY, REPLIES -> result.text();
            case DISABLED -> messages.format("talk.disabled");
            case UNKNOWN -> messages.format("talk.unknown-character", Map.of("id", result.characterId()));
            case NO_SESSION -> messages.format("talk.no-session");
            case ENDED -> messages.format("talk.ended");
            case COOLDOWN -> messages.format("talk.cooldown");
            case TOO_LONG -> messages.format("talk.too-long", Map.of("max", Integer.toString(result.limit())));
            case DAILY -> messages.format("talk.daily");
            case BUSY -> messages.format("talk.busy");
            case FAILED -> messages.format("talk.failed", Map.of("error", result.error()));
            case EMPTY -> messages.format("talk.empty");
        };
    }

    private void deliverContext(
            Player player,
            CompletableFuture<String> future,
            String typed,
            String display,
            Prepared prepared,
            String wrapped,
            Throwable error,
            boolean notifyReply,
            boolean notifyStart
    ) {
        if (error != null) {
            plugin.getLogger().warning("Dialogue failed: " + error.getMessage());
            future.completeExceptionally(error);
            return;
        }
        DialogueEngine.TalkRequest request = prepared.request;
        if (wrapped != null && !wrapped.isBlank()) {
            request = request.withSystem(ContextBlock.spliceSystem(request.system(), prepared.instruction, wrapped));
        }
        talkPrepared(player, future, typed, display, request, notifyReply, notifyStart);
    }

    private void rejectDialogue(
            Player player,
            CompletableFuture<String> future,
            boolean notifyReply,
            RejectedExecutionException rejected
    ) {
        plugin.getLogger().fine(HttpPool.QUEUE_FULL);
        future.completeExceptionally(new AiRequestException(
                AiErrorKind.LOCAL_LIMIT, 0, HttpPool.QUEUE_FULL, rejected));
        if (notifyReply) {
            plugin.getMessageService().send(player, "talk.busy");
        }
    }

    private void talkPrepared(
            Player player,
            CompletableFuture<String> future,
            String typed,
            String display,
            DialogueEngine.TalkRequest request,
            boolean notifyReply,
            boolean notifyStart
    ) {
        try {
            TalkResult result = withDisplayId(engine.talk(request), display);
            if (result.code() == TalkCode.STARTED) {
                talkLabels.put(player.getUniqueId(), typed);
            } else if (result.code() == TalkCode.ENDED || result.code() == TalkCode.NO_SESSION) {
                talkLabels.remove(player.getUniqueId());
            }
            future.complete(present(player, result, notifyReply, notifyStart));
        } catch (Throwable thrown) {
            plugin.getLogger().warning("Dialogue failed: " + thrown.getMessage());
            future.completeExceptionally(thrown);
        }
    }

    private Prepared build(Player player, String characterId, String message, boolean sessionChat) {
        PluginConfig config = plugin.getPluginConfig();
        NamedPrompt prompt = plugin.getPromptCatalog().find(characterId).orElse(null);
        Location location = player.getLocation();
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        if (prompt == null) {
            return new Prepared(new DialogueEngine.TalkRequest(
                    player.getUniqueId(), player.getName(), characterId, message, sessionChat, false, true,
                    "", config.getFallback(), DialogueProfile.absent(), List.of(), config.dialogueSettings(),
                    GenerationOverrides.none(), config.defaultFormatId(), world,
                    location.getX(), location.getY(), location.getZ(), ignored -> false, System.currentTimeMillis()),
                    null, PromptContext.none(), "");
        }
        String format = prompt.format() == null ? config.defaultFormatId() : config.normalizeFormat(prompt.format());
        Map<String, Boolean> grants = new LinkedHashMap<>();
        for (CharacterAction action : prompt.actions()) {
            if (action.permission() != null) {
                grants.put(action.permission(), player.hasPermission(action.permission()));
            }
        }
        Predicate<String> permissions = node -> Boolean.TRUE.equals(grants.get(node));
        String fallback = prompt.fallback() != null ? prompt.fallback() : config.getFallback();
        String instruction = formatInstruction(prompt, config);
        String summaryBlock = "";
        if (config.dialogueSettings().summaryEnabled()) {
            summaryBlock = DialogueSummary.block(memory.summary(
                    player.getUniqueId(),
                    characterId,
                    System.currentTimeMillis(),
                    config.dialogueSettings().memoryExpiryMillis()
            ));
        }
        DialogueEngine.TalkRequest request = new DialogueEngine.TalkRequest(
                player.getUniqueId(),
                player.getName(),
                characterId,
                message,
                sessionChat,
                false,
                false,
                characterSystem(prompt, config, player, summaryBlock),
                fallback,
                prompt.dialogue(),
                prompt.actions(),
                config.dialogueSettings(),
                prompt.overrides().withFormat(format),
                format,
                world,
                location.getX(),
                location.getY(),
                location.getZ(),
                permissions,
                System.currentTimeMillis()
        );
        ContextRequest contextRequest = null;
        PromptContext selection = PromptContext.none();
        if (prompt.context().active()
                && config.contextSettings().enabled()
                && plugin.getContextService() != null) {
            ContextRequest.Purpose purpose = message == null || message.isBlank()
                    ? ContextRequest.Purpose.TALK_GREETING
                    : ContextRequest.Purpose.TALK;
            contextRequest = new ContextRequest(player.getUniqueId(), player.getName(), world, characterId, purpose);
            selection = prompt.context();
        }
        return new Prepared(request, contextRequest, selection, instruction);
    }

    static String characterSystem(NamedPrompt prompt, PluginConfig config, Player player) {
        return characterSystem(prompt, config, player, "");
    }

    /**
     * {@code summaryBlock} is empty unless dialogue summaries are on and this pair has a summary.
     * It sits after the character sheet and before the format instruction. A context block belongs
     * in that same gap, after the summary.
     */
    static String characterSystem(NamedPrompt prompt, PluginConfig config, Player player, String summaryBlock) {
        String sheet = prompt.render(
                template -> VarSubstitutor.resolve(player, template),
                ContextVariables.capture(player)
        );
        String admin = prompt.overrides().systemPrompt(config.getSystemPrompt());
        String instruction = formatInstruction(prompt, config);
        StringBuilder system = new StringBuilder();
        if (admin != null && !admin.isBlank()) {
            system.append(admin).append("\n\n");
        }
        system.append(sheet);
        if (summaryBlock != null && !summaryBlock.isEmpty()) {
            system.append("\n\n").append(summaryBlock);
        }
        if (instruction != null && !instruction.isBlank()) {
            system.append("\n\n").append(instruction);
        }
        return system.toString();
    }

    static String formatInstruction(NamedPrompt prompt, PluginConfig config) {
        String formatId = prompt.format() == null ? config.defaultFormatId() : prompt.format();
        String instruction = config.presetFor(formatId).instruction();
        return instruction == null ? "" : instruction;
    }

    private DialogueEngine.TalkRequest endRequest(Player player) {
        return new DialogueEngine.TalkRequest(
                player.getUniqueId(), player.getName(), "", "", false, true, false, "", "",
                DialogueProfile.absent(), List.of(), plugin.getPluginConfig().dialogueSettings(),
                GenerationOverrides.none(), null, "", 0, 0, 0, ignored -> false, System.currentTimeMillis());
    }

    private String runAction(UUID playerId, CharacterAction action, String command) {
        CompletableFuture<String> done = new CompletableFuture<>();
        try {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online == null) {
                    done.complete("failed: player unavailable");
                    return;
                }
                if (action.console()) {
                    done.complete(dispatch(true, online, command));
                    return;
                }
                online.getScheduler().run(plugin, scheduled -> done.complete(dispatch(false, online, command)), () ->
                        done.complete("failed: player unavailable"));
            });
            return done.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "failed: timed out";
        } catch (Exception e) {
            return "failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String dispatch(boolean console, Player player, String command) {
        try {
            boolean ok = console
                    ? Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                    : player.performCommand(command);
            return ok ? "ran" : "failed: command was not accepted";
        } catch (Throwable thrown) {
            String message = thrown.getMessage() == null ? thrown.getClass().getSimpleName() : thrown.getMessage();
            return "failed: " + message;
        }
    }

    private void sweep() {
        List<SessionBook.Session> expired = engine.sweep(System.currentTimeMillis());
        if (expired.isEmpty()) {
            return;
        }
        try {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                for (SessionBook.Session session : expired) {
                    Player player = Bukkit.getPlayer(session.playerId());
                    if (player == null) {
                        continue;
                    }
                    player.getScheduler().run(plugin, scheduled ->
                            plugin.getMessageService().send(player, "talk.timeout"), () -> { });
                }
            });
        } catch (Throwable thrown) {
            plugin.getLogger().fine("Skipped dialogue timeout delivery: " + thrown.getMessage());
        }
    }

    private void saveMemory() {
        try {
            DialogueSettings settings = plugin.getPluginConfig().dialogueSettings();
            if (!settings.persistMemory()) {
                return;
            }
            memory.save(memoryFile(), plugin.getLogger(), settings.summaryEnabled());
        } catch (Throwable thrown) {
            plugin.getLogger().fine("Skipped dialogue memory save: " + thrown.getMessage());
        }
    }

    private File memoryFile() {
        return new File(plugin.getDataFolder(), "dialogue-memory.yml");
    }

    private KeyRing ring(String providerId) {
        if (plugin.getAiHttpClient() == null) {
            return new KeyRing(List.of());
        }
        return plugin.getAiHttpClient().sharedRing(providerId);
    }

    private static boolean owns(Player player) {
        try {
            if (Bukkit.getServer() == null) {
                return true;
            }
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private record RecentChat(String text, long at) {
    }

    private record Prepared(
            DialogueEngine.TalkRequest request,
            ContextRequest contextRequest,
            PromptContext selection,
            String instruction
    ) {
    }
}
