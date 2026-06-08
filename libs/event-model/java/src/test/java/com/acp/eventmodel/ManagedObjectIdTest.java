package com.acp.eventmodel;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Criteria 15, 16 (Java side): {@code managedObjectId} valid format accepted / invalid rejected.
 * Mirrors the Python {@code test_managed_object_id.py}.
 */
class ManagedObjectIdTest {

    // Criterion 15 — valid forms accepted (one per known objectType, plus spec examples).
    @ParameterizedTest(name = "valid managedObjectId accepted: {0}")
    @ValueSource(strings = {
            "Node:PE1",
            "LineCard:PE1-LC2",
            "Port:PE1-LC2-P3",
            "IPLink:LINK-AB-01",
            "IGPAdjacency:ADJ-001",
            "LSP:LSP-PE1-PE2",
            "VPNService:VPN-CUST-7",
            "FiberSpan:SPAN-AB-01",
            "SRLG:SRLG-42"})
    void validAccepted(String value) {
        Assertions.assertTrue(ManagedObjectId.isValid(value));
        ManagedObjectId moi = Assertions.assertDoesNotThrow(() -> ManagedObjectId.parse(value));
        Assertions.assertEquals(value, moi.toString());
    }

    // Criterion 16 — four invalid sub-cases all rejected.
    @ParameterizedTest(name = "invalid managedObjectId rejected: [{0}]")
    @ValueSource(strings = {
            "Switch:X1",        // (a) unknown objectType
            "Port:",            // (b) empty id
            "PE1-LC2-P3",       // (c) no colon separator
            ""})                // (d) empty string
    void invalidRejected(String value) {
        Assertions.assertFalse(ManagedObjectId.isValid(value));
        Assertions.assertThrows(ManagedObjectIdException.class, () -> ManagedObjectId.validate(value));
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

    @Test
    void allNineKnownTypes() {
        Assertions.assertEquals(9, ManagedObjectId.KNOWN_OBJECT_TYPES.size());
    }
}
