package com.acp.patternmanager.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The deterministic patternId (UUIDv5) underpins consume-and-persist idempotency (criterion 10). */
class UuidV5Test {

    @Test
    void sameNameProducesSameUuid() {
        String name = "trail-1|LOS,LinkDown|w1|s1";
        assertThat(UuidV5.from(name)).isEqualTo(UuidV5.from(name));
    }

    @Test
    void differentNamesProduceDifferentUuids() {
        assertThat(UuidV5.from("a")).isNotEqualTo(UuidV5.from("b"));
    }

    @Test
    void isVersion5() {
        UUID id = UuidV5.from("trail-1|LOS,LinkDown|w1|s1");
        assertThat(id.version()).isEqualTo(5);
        assertThat(id.variant()).isEqualTo(2); // RFC 4122
    }
}
