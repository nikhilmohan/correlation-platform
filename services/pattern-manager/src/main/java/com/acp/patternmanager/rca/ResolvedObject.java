package com.acp.patternmanager.rca;

/**
 * An alarm type resolved to its graph object plus its dependency position, produced by RCA and
 * REUSED by structural validation (no redundant Topology fetch — design task 2/3). {@code resolved}
 * is false when Topology could not resolve the alarm type to any object.
 *
 * @param alarmType the alarm-type vocabulary token (from the mined sequence)
 * @param managedObjectId the resolved object id, or null when unresolved
 * @param resolved whether Topology returned an object for this alarm type
 * @param dependencyDepth bounded dependency depth from the group's inferred origin (lower = more
 *     upstream); {@link Integer#MAX_VALUE} when unknown/unresolved
 */
public record ResolvedObject(
        String alarmType,
        String managedObjectId,
        boolean resolved,
        int dependencyDepth) {
}
