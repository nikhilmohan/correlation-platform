package com.acp.correlationengine.generalize;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Config-switchable client for the Trail Builder read API (spec OQ-G3 resolved). Built + mocked
 * against Trail Builder's published {@code openapi.json} ({@code ListTrailsResponse},
 * {@code TrailDetail}, {@code TrailMember}). Called only at compatibility-index build time (startup,
 * {@code patterns.approved}, {@code trails.built}) — never per alarm.
 *
 * <p>Fetch resilience (spec AC41): {@link #getTrailMemberTypes} returns {@link Optional#empty()} on
 * a per-trail fetch failure (after bounded retry) so that trail is simply <b>absent</b> from the
 * index — no corruption, no false positives. {@link #listTrailIds} throws on a total enumerate
 * failure so the caller can retain the last-good index rather than swap in an empty one.
 */
public interface TrailBuilderClient {

    /**
     * Enumerate the trail ids for a snapshot + domain (paged {@code GET /trails}).
     *
     * @throws RuntimeException if enumeration fails entirely (the caller retains the last-good index)
     */
    List<String> listTrailIds(String snapshotId, String domain);

    /**
     * @return the distinct member {@code objectType}s of {@code trailId} ({@code GET /trails/{id}}),
     *     or {@link Optional#empty()} if the trail cannot be fetched (5xx/timeout/404) within retry
     *     bounds — the trail is then absent from the index (AC41, AC37 removed-trail).
     */
    Optional<Set<String>> getTrailMemberTypes(String trailId);
}
