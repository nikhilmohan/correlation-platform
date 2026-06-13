package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * AC-13b (edge identity): the opaque {@code edgeId} round-trips back to its four-field
 * NebulaGraph key {@code (snapshotId, from, relation, to)}; a malformed token is rejected
 * (the controller maps that to 400); the derived {@code rank} is deterministic so re-insert
 * overwrites the same edge. The token leaks no NebulaGraph internal (it is a reversible encoding
 * of the addressing tuple, not a raw rank or nGQL handle).
 */
class EdgeIdTest {

    @Test
    void encodeThenDecodeRoundTrips() {
        String edgeId = EdgeId.encode("SNAP-1", "Port:p1", "HOSTS", "Interface:i1");
        EdgeId.Decoded decoded = EdgeId.decode(edgeId);
        assertThat(decoded.snapshotId()).isEqualTo("SNAP-1");
        assertThat(decoded.from()).isEqualTo("Port:p1");
        assertThat(decoded.relation()).isEqualTo("HOSTS");
        assertThat(decoded.to()).isEqualTo("Interface:i1");
    }

    @Test
    void edgeIdIsUrlSafeBase64WithoutPadding() {
        String edgeId = EdgeId.encode("SNAP-1", "Port:p1", "HOSTS", "Interface:i1");
        // URL-safe alphabet, no padding ('=') so it is a clean path segment.
        assertThat(edgeId).doesNotContain("=", "+", "/");
    }

    @Test
    void decodeRejectsMalformedToken() {
        assertThatThrownBy(() -> EdgeId.decode("!!!not-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
        // Well-formed base64url but not the 4-field shape.
        String twoField = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("only two".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> EdgeId.decode(twoField))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rankIsDeterministicAndNonNegative() {
        long r1 = EdgeId.rank("Port:p1", "HOSTS", "Interface:i1");
        long r2 = EdgeId.rank("Port:p1", "HOSTS", "Interface:i1");
        assertThat(r1).isEqualTo(r2).isNotNegative();
        // A different tuple yields a different rank (deterministic, content-derived).
        assertThat(EdgeId.rank("Port:p1", "HOSTS", "Interface:i2")).isNotEqualTo(r1);
    }
}
