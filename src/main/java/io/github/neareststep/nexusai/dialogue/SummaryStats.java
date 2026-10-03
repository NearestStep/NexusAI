package io.github.neareststep.nexusai.dialogue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory summary counters for {@code /nai status}. They reset at local midnight and on reload.
 */
public final class SummaryStats {

    private final ZoneId zone;
    private final AtomicInteger ok = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private LocalDate day;

    public SummaryStats(ZoneId zone) {
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.day = LocalDate.now(this.zone);
    }

    public void success(long nowMillis) {
        roll(nowMillis);
        ok.incrementAndGet();
    }

    public void failure(long nowMillis) {
        roll(nowMillis);
        failed.incrementAndGet();
    }

    public void reset() {
        ok.set(0);
        failed.set(0);
        day = LocalDate.now(zone);
    }

    public int ok() {
        return ok.get();
    }

    public int failed() {
        return failed.get();
    }

    public String text(boolean enabled, long nowMillis) {
        if (!enabled) {
            return "off";
        }
        roll(nowMillis);
        return "on (" + ok.get() + " ok, " + failed.get() + " failed today)";
    }

    private void roll(long nowMillis) {
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        if (today.equals(day)) {
            return;
        }
        synchronized (this) {
            if (today.equals(day)) {
                return;
            }
            ok.set(0);
            failed.set(0);
            day = today;
        }
    }
}
