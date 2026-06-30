package com.acp.enrichment.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A test {@link Clock} whose instant can be advanced deterministically (no real waits). */
public final class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this.now = start;
        this.zone = ZoneId.of("UTC");
    }

    public static MutableClock atEpoch() {
        return new MutableClock(Instant.parse("2026-06-11T10:00:00Z"));
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        return new MutableClock(now);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
