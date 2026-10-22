package io.github.neareststep.nexusai;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plugin stop state shared by every path that hands a reply back to the server.
 * Paper marks the plugin disabled before {@code onDisable} runs, so from that moment no task can
 * be scheduled. Paths check this gate first and drop the reply quietly; disable logs one line
 * with the count. The class is loaded at enable, so a late check never needs the plugin
 * classloader.
 */
public final class StopGate {

    private static volatile boolean stopping;
    private static final AtomicInteger CANCELLED = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();

    /** The message every stop failure carries: cancelled requests, refused sends, the talk API. */
    public static final String MESSAGE = "NexusAI is stopping";

    private StopGate() {
    }

    /**
     * True when {@code error}, or one of its causes, is a stop failure. Its request was already
     * counted as cancelled, so a caller must not deliver it, and the loss is not counted twice.
     */
    public static boolean isStopFailure(Throwable error) {
        for (Throwable at = error; at != null; at = at.getCause() == at ? null : at.getCause()) {
            if (MESSAGE.equals(at.getMessage())) {
                return true;
            }
        }
        return false;
    }

    /** Plugin enable: replies are delivered again. */
    public static void open() {
        CANCELLED.set(0);
        SKIPPED.set(0);
        stopping = false;
    }

    /** Plugin disable: from now on replies are dropped. */
    public static void close() {
        stopping = true;
    }

    public static boolean stopping() {
        return stopping;
    }

    /**
     * True when the reply must not be scheduled: the plugin is stopping or Bukkit already
     * disabled it. A true answer counts one skipped delivery.
     */
    public static boolean drop(Plugin plugin) {
        if (stopping || (plugin != null && !plugin.isEnabled())) {
            SKIPPED.incrementAndGet();
            return true;
        }
        return false;
    }

    /** Same as {@link #drop(Plugin)} for a scheduling call that already failed. */
    public static boolean dropFailed(Plugin plugin) {
        return drop(plugin);
    }

    /** Counts requests a component cancelled on stop. */
    public static void dropped(int count) {
        if (count > 0) {
            CANCELLED.addAndGet(count);
        }
    }

    /**
     * The one disable line, or {@code null} when nothing was lost. Resets both counts.
     */
    public static String takeSummary() {
        int cancelled = CANCELLED.getAndSet(0);
        int skipped = SKIPPED.getAndSet(0);
        if (cancelled == 0 && skipped == 0) {
            return null;
        }
        return "Stopped with " + cancelled + " AI request(s) still in flight (cancelled) and "
                + skipped + " reply delivery(ies) skipped; replies cannot reach players once the server stops.";
    }
}
