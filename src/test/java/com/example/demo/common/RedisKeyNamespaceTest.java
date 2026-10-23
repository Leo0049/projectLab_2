package com.example.demo.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** REDIS_KEY_NAMESPACE 的格式與 prod 防呆（ADR-0013，純邏輯）。 */
class RedisKeyNamespaceTest {

    @Test
    void blankOrMissingNamespaceMeansNoPrefix() {
        assertEquals("", RedisKeyNamespace.validate(null));
        assertEquals("", RedisKeyNamespace.validate("   "));
        assertEquals("", RedisKeyNamespace.prefixFor(""));
    }

    @Test
    void wellFormedNamespacesAreAccepted() {
        assertEquals("prod", RedisKeyNamespace.validate("prod"));
        assertEquals("staging-2", RedisKeyNamespace.validate("staging-2"));
        assertEquals("a", RedisKeyNamespace.validate("a"));
        assertEquals("x".repeat(32), RedisKeyNamespace.validate("x".repeat(32)));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertEquals("prod", RedisKeyNamespace.validate("  prod\t"));
    }

    @Test
    void malformedNamespacesAreRejectedInEveryProfile() {
        String[] malformed = {"Prod", "1prod", "-prod", "pr:od", "pr od", "prod_1", "prod.v1", "x".repeat(33)};
        for (String value : malformed) {
            assertThrows(IllegalStateException.class, () -> RedisKeyNamespace.validate(value), value);
            assertThrows(IllegalStateException.class, () -> RedisKeyNamespace.resolveForProfile(false, value), value);
        }
    }

    @Test
    void productionRequiresANamespace() {
        assertThrows(IllegalStateException.class, () -> RedisKeyNamespace.resolveForProfile(true, null));
        assertThrows(IllegalStateException.class, () -> RedisKeyNamespace.resolveForProfile(true, "  "));
    }

    @Test
    void productionAcceptsAWellFormedNamespaceAndPrefixSeparatesIt() {
        assertEquals("prod", RedisKeyNamespace.resolveForProfile(true, "prod"));
        assertEquals("prod:", RedisKeyNamespace.prefixFor("prod"));
    }
}
