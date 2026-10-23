package com.example.demo.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 鍵序列化器的前綴、還原與邊界（ADR-0013，純邏輯）。 */
class NamespacedRedisKeySerializerTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void serializedKeyCarriesTheNamespacePrefixOnTheWire() {
        NamespacedRedisKeySerializer s = new NamespacedRedisKeySerializer("prod");
        assertArrayEquals(bytes("prod:lock:checkout:42"), s.serialize("lock:checkout:42"));
    }

    @Test
    void emptyNamespaceLeavesKeysUnchanged() {
        NamespacedRedisKeySerializer s = new NamespacedRedisKeySerializer("");
        assertEquals("", s.prefix());
        assertArrayEquals(bytes("lock:checkout:42"), s.serialize("lock:checkout:42"));
    }

    @Test
    void deserializeRestoresTheLogicalKey() {
        NamespacedRedisKeySerializer s = new NamespacedRedisKeySerializer("prod");
        assertEquals("spin:done:1:2026-10-22", s.deserialize(s.serialize("spin:done:1:2026-10-22")));
    }

    @Test
    void deserializeLeavesAKeyFromAnotherNamespaceUnchanged() {
        NamespacedRedisKeySerializer s = new NamespacedRedisKeySerializer("prod");
        assertEquals("staging:lock:job:x", s.deserialize(bytes("staging:lock:job:x")));
    }

    @Test
    void nullPassesThroughInBothDirections() {
        NamespacedRedisKeySerializer s = new NamespacedRedisKeySerializer("prod");
        assertNull(s.serialize(null));
        assertNull(s.deserialize(null));
    }

    @Test
    void keysContainingColonsSurviveTheRoundTrip() {
        NamespacedRedisKeySerializer s = new NamespacedRedisKeySerializer("prod");
        assertEquals("idem:checkout:7:abc:def", s.deserialize(s.serialize("idem:checkout:7:abc:def")));
    }

    @Test
    void malformedNamespaceIsRejectedAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new NamespacedRedisKeySerializer("Prod"));
    }
}
