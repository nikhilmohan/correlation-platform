package com.acp.eventmodel;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Criteria 15, 16 (Java side): {@code managedObjectId} valid format accepted / invalid rejected.
 *
 * <p>The scheme is domain-agnostic: {@code objectType} is any alphanumeric token starting with a
 * letter (the per-domain valid set is authored in the Knowledge Service, not here). So Core-IP ids
 * AND non-Core-IP ids (e.g. {@code Site:...}, {@code gNodeB:...}) are accepted; only malformed
 * shapes are rejected. Mirrors the Python {@code test_managed_object_id.py} for cross-binding
 * consistency.
 */
class ManagedObjectIdTest {

    /** The Core-IP nine (Solution Design §5) — used here only to prove non-Core-IP types validate. */
    private static final List<String> CORE_IP_NINE = List.of(
            "Node", "LineCard", "Port", "IPLink", "IGPAdjacency", "LSP", "VPNService", "FiberSpan",
            "SRLG");

    // Criterion 15 — valid forms accepted: the Core-IP MVP set (still valid under the relaxed
    // pattern) PLUS non-Core-IP examples proving the scheme is domain-agnostic.
    @ParameterizedTest(name = "valid managedObjectId accepted: {0}")
    @ValueSource(strings = {
            // Core IP MVP set
            "Node:PE1",
            "LineCard:PE1-LC2",
            "Port:PE1-LC2-P3",
            "IPLink:LINK-AB-01",
            "IGPAdjacency:ADJ-001",
            "LSP:LSP-PE1-PE2",
            "VPNService:VPN-CUST-7",
            "FiberSpan:SPAN-AB-01",
            "SRLG:SRLG-42",
            // Domain-agnostic: types not in the Core-IP set still validate.
            "Site:LON-01",
            "gNodeB:g-7"})
    void validAccepted(String value) {
        Assertions.assertTrue(ManagedObjectId.isValid(value));
        ManagedObjectId moi = Assertions.assertDoesNotThrow(() -> ManagedObjectId.parse(value));
        Assertions.assertEquals(value, moi.toString());
    }

    @Test
    void nonCoreIpObjectTypeAccepted() {
        // The relaxed pattern accepts object types outside the Core-IP nine; the per-domain valid
        // set is enforced in the Knowledge Service, not here.
        for (String value : new String[] {"Site:LON-01", "gNodeB:g-7"}) {
            ManagedObjectId moi = ManagedObjectId.parse(value);
            Assertions.assertFalse(CORE_IP_NINE.contains(moi.getObjectType()),
                    "objectType should be outside the Core-IP nine: " + moi.getObjectType());
            Assertions.assertTrue(ManagedObjectId.isValid(value));
        }
    }

    @Test
    void coreIpExampleSetIsNonNormativeButAllValid() {
        // KNOWN_OBJECT_TYPES is a reference list only; every example still validates.
        for (String objectType : ManagedObjectId.KNOWN_OBJECT_TYPES) {
            Assertions.assertTrue(ManagedObjectId.isValid(objectType + ":example-id"));
        }
        Assertions.assertTrue(ManagedObjectId.KNOWN_OBJECT_TYPES.contains("Site"));
    }

    // Criterion 16 — malformed shapes all rejected (mirrors the Python INVALID set).
    @ParameterizedTest(name = "invalid managedObjectId rejected: [{0}]")
    @ValueSource(strings = {
            "NoColon",      // no colon separator
            "Node:",        // empty id
            "Node:a:b",     // colon in id
            ":x",           // empty objectType
            "9bad:x",       // objectType must start with a letter
            "Port:PE1:P3",  // id contains a colon
            ""})            // empty string
    void invalidRejected(String value) {
        Assertions.assertFalse(ManagedObjectId.isValid(value));
        Assertions.assertThrows(ManagedObjectIdException.class, () -> ManagedObjectId.validate(value));
        Assertions.assertThrows(ManagedObjectIdException.class, () -> ManagedObjectId.parse(value));
    }

    @Test
    void nullRejected() {
        Assertions.assertFalse(ManagedObjectId.isValid(null));
        Assertions.assertThrows(ManagedObjectIdException.class, () -> ManagedObjectId.validate(null));
    }

    @Test
    void parseExposesObjectTypeAndId() {
        ManagedObjectId moi = ManagedObjectId.parse("Port:PE1-LC2-P3");
        Assertions.assertEquals("Port", moi.getObjectType());
        Assertions.assertEquals("PE1-LC2-P3", moi.getId());
    }
}
