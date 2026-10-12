package io.github.neareststep.nexusai.api.event;

import io.github.neareststep.nexusai.api.RequestOrigin;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Fired after admission (limits and quotas) and before the first HTTP attempt.
 * <p>
 * Cancel to stop the call. No HTTP is sent, and a quota reservation is released.
 * {@link #setCancelReason(String)} is masked and becomes {@code GenerationError.message()}
 * on the following {@link NexusGenerateFailEvent} ({@code CANCELLED}).
 * The prompt text is not on this event. A slow handler blocks a {@code nexusai-http-*} worker.
 */
public final class NexusPreGenerateEvent extends NexusRequestEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private static volatile UnaryOperator<String> mask = value -> value == null ? "" : value;

    private final String providerId;
    private final String model;
    private final int estimatedPromptTokens;
    private boolean cancelled;
    private String cancelReason = "";

    /** Not part of the plugin API. Callers do not construct this event. */
    @ApiStatus.Internal
    public NexusPreGenerateEvent(
            long requestId,
            RequestOrigin origin,
            String consumer,
            UUID playerId,
            String promptId,
            String label,
            String providerId,
            String model,
            int estimatedPromptTokens
    ) {
        super(requestId, origin, consumer, playerId, promptId, label);
        this.providerId = providerId == null ? "" : providerId;
        this.model = model == null ? "" : model;
        this.estimatedPromptTokens = Math.max(0, estimatedPromptTokens);
    }

    /** The first provider row routing will try. */
    public String providerId() {
        return providerId;
    }

    /** The model on that row. */
    public String model() {
        return model;
    }

    /** Rough size of the prompt, {@code ceil(characters / 4)}, for the listener's own decision. */
    public int estimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Stored masked, at most 300 Unicode code points, with URL query strings removed.
     * Blank keeps the default {@code Cancelled} message on the failure event.
     */
    public void setCancelReason(String reason) {
        String raw = reason == null ? "" : reason;
        String masked;
        try {
            masked = mask.apply(raw);
        } catch (Throwable ignored) {
            masked = raw;
        }
        this.cancelReason = masked == null ? "" : masked;
    }

    /** Installed with the dispatcher so a cancel reason is masked before other listeners read it. */
    static void masker(UnaryOperator<String> next) {
        mask = next == null ? value -> value == null ? "" : value : next;
    }

    /** The reason last passed to {@link #setCancelReason(String)}, or {@code ""}. */
    public String cancelReason() {
        return cancelReason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    protected boolean sameFields(Object other) {
        NexusPreGenerateEvent that = (NexusPreGenerateEvent) other;
        return estimatedPromptTokens == that.estimatedPromptTokens
                && cancelled == that.cancelled
                && providerId.equals(that.providerId)
                && model.equals(that.model)
                && cancelReason.equals(that.cancelReason);
    }

    @Override
    protected int fieldsHash() {
        return Objects.hash(providerId, model, estimatedPromptTokens, cancelled, cancelReason);
    }

    @Override
    protected String fieldsText() {
        return ", providerId=" + providerId
                + ", model=" + model
                + ", estimatedPromptTokens=" + estimatedPromptTokens
                + ", cancelled=" + cancelled
                + ", cancelReason=" + cancelReason;
    }
}
