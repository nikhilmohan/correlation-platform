package com.acp.patternmanager.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Deterministic name-based UUID (RFC 4122 version 5, SHA-1) generator. Used to derive a stable
 * {@code patternId} from the mining provenance {@code (trailId, sequence, sourceWindowId, snapshotId)}
 * so a redelivered mined event upserts the SAME row (idempotency safety net, design task 8).
 */
public final class UuidV5 {

    /** A fixed namespace UUID for pattern ids (arbitrary but stable). */
    private static final UUID NAMESPACE = UUID.fromString("6b6d1f8e-3f2a-5b7c-9d4e-1a2b3c4d5e6f");

    private UuidV5() {
    }

    /**
     * [ANCHOR-CONSOL] The anchor-identity {@code patternId} for an ANCHORED pattern: a deterministic
     * UUIDv5 over the anchor identity {@code (domain, snapshotId, codebookVersion, anchorScenarioId)}.
     * All mined events for one fault-origin (across sub-runs) map to this same id, so the
     * consolidation upserts them into one Pattern Store row. Scoping to {@code snapshotId} +
     * {@code codebookVersion} re-mints identity when the topology snapshot / codebook changes.
     *
     * @return the deterministic anchor-identity pattern id
     */
    public static UUID anchorIdentity(String domain, String snapshotId, String codebookVersion,
            String anchorScenarioId) {
        String name = nz(domain) + "|" + nz(snapshotId) + "|" + nz(codebookVersion) + "|"
                + nz(anchorScenarioId);
        return from(name);
    }

    /**
     * [SIG-FOLD] The cascade-signature {@code patternId} for an UNEXPLAINED pattern: a deterministic
     * UUIDv5 over the cascade signature {@code (sequence, domain, snapshotId)}. The same ordered
     * cascade shape — regardless of which trail it was mined from or which mining window it came
     * from — maps to the SAME id, so cross-trail / cross-window occurrences of one signature FOLD into
     * ONE Pattern Store row (occurrence + extent + impact metrics accumulate).
     *
     * <p>The name-string is {@code join(sequence, ",") + "|" + domain + "|" + snapshotId}. Sequence
     * order and repeats are SIGNIFICANT (no normalization — OQ-SF-2 DEFERRED): {@code ["A","B","A"]}
     * → {@code "A,B,A"}; {@code ["B","A","C"]} → {@code "B,A,C"} — both distinct from {@code "A,B,C"}.
     * {@code domain} and {@code snapshotId} are {@code nz()}-guarded exactly as {@link #anchorIdentity},
     * sharing its RFC-4122 v5 (SHA-1) NAMESPACE + null-handling. {@code trailId} and
     * {@code sourceWindowId} are DROPPED from the key — that is the fix.
     *
     * @return the deterministic cascade-signature pattern id
     */
    public static UUID signatureIdentity(java.util.List<String> sequence, String domain,
            String snapshotId) {
        String name = String.join(",", sequence) + "|" + nz(domain) + "|" + nz(snapshotId);
        return from(name);
    }

    /**
     * [ANCHOR-CONSOL] The per-event {@code patternId} for an UNEXPLAINED pattern (no anchor to
     * consolidate on): a deterministic UUIDv5 over the mining provenance
     * {@code (trailId, sequence, sourceWindowId, snapshotId)}. Each unexplained cascade stayed a
     * distinct row.
     *
     * <p><b>Retired from the live path</b> by [SIG-FOLD]: unexplained patterns now fold by
     * {@link #signatureIdentity}. Kept for identity-space non-collision tests + the collapse migration
     * that re-keys legacy {@code perEventIdentity} rows onto {@code signatureIdentity}.
     *
     * @return the deterministic per-event pattern id
     */
    public static UUID perEventIdentity(String trailId, java.util.List<String> sequence,
            String sourceWindowId, String snapshotId) {
        String name = nz(trailId) + "|" + String.join(",", sequence) + "|" + nz(sourceWindowId)
                + "|" + nz(snapshotId);
        return from(name);
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    /**
     * @param name the name to hash within the pattern namespace
     * @return a deterministic version-5 UUID
     */
    public static UUID from(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(toBytes(NAMESPACE));
            md.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            byte[] uuidBytes = new byte[16];
            System.arraycopy(hash, 0, uuidBytes, 0, 16);
            // Set version (5) and variant (RFC 4122) bits.
            uuidBytes[6] &= 0x0f;
            uuidBytes[6] |= 0x50;
            uuidBytes[8] &= 0x3f;
            uuidBytes[8] |= (byte) 0x80;
            return fromBytes(uuidBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] out = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> (8 * (7 - i)));
            out[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return out;
    }

    private static UUID fromBytes(byte[] b) {
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (b[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (b[i] & 0xff);
        }
        return new UUID(msb, lsb);
    }
}
