package com.specdriven.regression.provider.jdbc;

import java.util.concurrent.TimeUnit;

/** A single operation deadline plus a checked cleanup tail; no phase can reset it. */
public final class JdbcDeadline {
    private final long started;
    public JdbcDeadline(long started) { this.started = started; }
    public long remainingNanos() {
        long remaining = started + TimeUnit.SECONDS.toNanos(5) - System.nanoTime();
        if (remaining <= 0) throw new IllegalStateException("reviewed_mariadb: operation deadline");
        return remaining;
    }
    public long cappedNanos(long maximum) { return Math.min(maximum, remainingNanos()); }
    public long cleanupDeadlineNanos(long cleanupStart) {
        return Math.min(cleanupStart + TimeUnit.SECONDS.toNanos(2), started + TimeUnit.SECONDS.toNanos(7));
    }
    public long startedNanos() { return started; }
}
