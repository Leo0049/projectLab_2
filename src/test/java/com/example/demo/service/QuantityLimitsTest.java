package com.example.demo.service;

import com.example.demo.exception.CustomException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuantityLimitsTest {
    @Test
    void acceptsOnlyIntegerQuantitiesWithinTheSupportedRange() {
        assertEquals(1, QuantityLimits.parse("1"));
        assertEquals(99, QuantityLimits.parse(99));
        assertEquals(1, QuantityLimits.validate(1));
        assertEquals(99, QuantityLimits.validate(99));

        assertThrows(CustomException.class, () -> QuantityLimits.parse("-1"));
        assertThrows(CustomException.class, () -> QuantityLimits.parse("0"));
        assertThrows(CustomException.class, () -> QuantityLimits.parse("100"));
        assertThrows(CustomException.class, () -> QuantityLimits.parse("1.5"));
        assertThrows(CustomException.class, () -> QuantityLimits.parse("invalid"));
    }
}
