package com.acp.correlationengine.incident;

import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.MatchCandidate;
import com.acp.correlationengine.model.ObservedAlarm;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Turns a winning {@link MatchCandidate} into an {@link Incident}.
 *
 * <p><b>Root-cause resolution (AC26).</b> The winning match supplies a {@code rootCauseAlarmType}
 * <em>token</em> (from {@code PatternView.rootCauseAlarmType} or the codebook scenario) — NOT an
 * alarm instance. The factory resolves it to a concrete {@code rootCauseAlarmId} by scanning the
 * matched alarm set for the alarm whose <b>{@code alarmType}</b> equals the token — the canonical
 * join key, NEVER {@code eventType} / {@code probableCause}. Every other matched {@code alarmId}
 * becomes a child. If more than one alarm carries the token, the earliest-{@code raisedAt} wins
 * (stable across replays); if none does, no incident is formed.
 *
 * <p><b>Stable idempotency (AC16).</b> {@code incidentId} is a deterministic hash of
 * {@code (trailId, patternId|codebookId, sorted matched alarmIds)}, also stored as the
 * {@code instance_fingerprint}, so re-evaluating the same matched set for the same instance yields
 * the same id and the DB unique constraint makes a duplicate persist a no-op.
 */
public class IncidentFactory {

    private final Clock clock;

    public IncidentFactory(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return the incident for {@code winner}, or empty if the {@code rootCauseAlarmType} token
     *     matches no alarm in the set (the candidate cannot name a root cause).
     */
    public Optional<Incident> build(MatchCandidate winner) {
        String rootType = winner.rootCauseAlarmType();
        Optional<ObservedAlarm> rootCause = winner.matchedAlarms().stream()
                .filter(a -> a.alarmType().equals(rootType)) // join on alarmType ONLY (AC26)
                .min(Comparator.comparingLong(ObservedAlarm::raisedAtEpochMs));
        if (rootCause.isEmpty()) {
            return Optional.empty();
        }
        String rootCauseAlarmId = rootCause.get().alarmId();
        List<String> childAlarmIds = new ArrayList<>();
        for (ObservedAlarm a : winner.matchedAlarms()) {
            if (!a.alarmId().equals(rootCauseAlarmId)) {
                childAlarmIds.add(a.alarmId());
            }
        }

        String attribution = winner.matchType() == MatchCandidate.MatchType.PATTERN
                ? winner.matchedPatternId() : winner.matchedCodebookId();
        String fingerprint = fingerprint(winner.trailId(), attribution, winner.matchedAlarms());
        String incidentId = "INC-" + fingerprint;

        return Optional.of(new Incident(
                incidentId,
                winner.trailId(),
                winner.discoveryTrailId(),
                rootCauseAlarmId,
                rootType,
                childAlarmIds,
                winner.matchedPatternId(),
                winner.matchedCodebookId(),
                winner.confidence(),
                winner.matchType(),
                fingerprint,
                clock.instant()));
    }

    /** Deterministic fingerprint = SHA-256 of trailId | attribution | sorted alarmIds. */
    static String fingerprint(String trailId, String attribution, List<ObservedAlarm> alarms) {
        List<String> sortedIds = alarms.stream().map(ObservedAlarm::alarmId).sorted().toList();
        String material = trailId + "|" + (attribution == null ? "" : attribution) + "|"
                + String.join(",", sortedIds);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
