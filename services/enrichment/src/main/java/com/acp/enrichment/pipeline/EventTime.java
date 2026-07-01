package com.acp.enrichment.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

/**
 * Resolves the <b>logical event time</b> of an alarm from its ISO-8601 {@code raisedAt} string for
 * the windowed filter stages (dedup, self-clear, flap-damp).
 *
 * <p>Windowing on the alarm's own {@code raisedAt} — not the wall-clock arrival time — is what makes
 * P2 HISTORY batch-replay correct: the Simulator replays the whole corpus in &lt;1s wall-clock while
 * the alarms' {@code raisedAt} span hours, so wall-clock windowing over-collapses distinct alarms.
 * In P3 LIVE mode {@code raisedAt} ≈ wall-clock arrival, so raisedAt-windowing is also correct
 * there. If {@code raisedAt} is missing or unparseable, the injected {@link Clock} is the safe
 * fallback (preserves live-mode behaviour and never throws inside a filter stage).
 */
final class EventTime {

    private EventTime() {}

    /**
     * @param raisedAt the alarm's ISO-8601 {@code raisedAt} (may be {@code null}/blank/malformed)
     * @param fallback the clock used when {@code raisedAt} cannot be parsed
     * @return the parsed instant, or {@code fallback.instant()} when parsing is not possible
     */
    static Instant of(String raisedAt, Clock fallback) {
        Instant parsed = parse(raisedAt);
        return parsed != null ? parsed : fallback.instant();
    }

    /** @return the parsed instant, or {@code null} if {@code raisedAt} is absent/unparseable. */
    static Instant parse(String raisedAt) {
        if (raisedAt == null || raisedAt.isBlank()) {
            return null;
        }
        String s = raisedAt.trim();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // Fall through to offset/zoned forms (e.g. "+00:00", named zone).
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fall through.
        }
        try {
            return ZonedDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
