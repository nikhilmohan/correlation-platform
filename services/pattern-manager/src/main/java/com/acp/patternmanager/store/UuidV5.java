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
