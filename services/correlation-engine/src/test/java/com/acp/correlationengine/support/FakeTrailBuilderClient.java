package com.acp.correlationengine.support;

import com.acp.correlationengine.generalize.TrailBuilderClient;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link TrailBuilderClient} test double. A test declares each trail's member object types
 * (or marks a trail to fail its member fetch, exercising AC41's absent-from-index path). Mirrors the
 * frozen Trail Builder API surface the {@code RestTrailBuilderClient} consumes:
 * {@code listTrailIds(snapshotId, domain)} + {@code getTrailMemberTypes(trailId)}.
 */
public final class FakeTrailBuilderClient implements TrailBuilderClient {

    private final Map<String, Set<String>> trailMemberTypes = new LinkedHashMap<>();
    private final Set<String> failingTrails = new LinkedHashSet<>();
    private final Set<String> explicitlyDeclared = new LinkedHashSet<>();
    private boolean enumerateFails = false;

    /** Declare (or replace) a trail's distinct member object types (explicit, test-authored). */
    public void declareTrail(String trailId, List<String> memberObjectTypes) {
        trailMemberTypes.put(trailId, new LinkedHashSet<>(memberObjectTypes));
        failingTrails.remove(trailId);
        explicitlyDeclared.add(trailId);
    }

    /** Auto-declare a trail's members without marking it as an explicit test-authored declaration. */
    public void autoDeclareTrail(String trailId, List<String> memberObjectTypes) {
        trailMemberTypes.put(trailId, new LinkedHashSet<>(memberObjectTypes));
        failingTrails.remove(trailId);
    }

    /** @return true if a test explicitly declared this trail (vs. the harness auto-declaring it). */
    public boolean isExplicitlyDeclared(String trailId) {
        return explicitlyDeclared.contains(trailId);
    }

    /** Remove a trail from the catalog entirely (AC37 removed-trail on a new snapshot). */
    public void removeTrail(String trailId) {
        trailMemberTypes.remove(trailId);
        failingTrails.remove(trailId);
    }

    /** Mark a trail so its member fetch fails (5xx) — the trail must then be absent from the index. */
    public void failTrail(String trailId) {
        failingTrails.add(trailId);
    }

    /** Make the whole enumerate call fail (retain last-good index). */
    public void failEnumeration(boolean fail) {
        this.enumerateFails = fail;
    }

    @Override
    public List<String> listTrailIds(String snapshotId, String domain) {
        if (enumerateFails) {
            throw new IllegalStateException("Trail Builder enumeration failed (test)");
        }
        // A trail marked failing is still enumerated (it exists) but its members fetch fails.
        Set<String> all = new LinkedHashSet<>(trailMemberTypes.keySet());
        all.addAll(failingTrails);
        return List.copyOf(all);
    }

    @Override
    public Optional<Set<String>> getTrailMemberTypes(String trailId) {
        if (failingTrails.contains(trailId)) {
            return Optional.empty(); // fetch failure — absent from index (AC41)
        }
        Set<String> types = trailMemberTypes.get(trailId);
        if (types == null) {
            return Optional.empty(); // unknown trail (e.g. 404 on a removed trail — AC37)
        }
        return Optional.of(Set.copyOf(types));
    }
}
