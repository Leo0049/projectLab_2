package com.example.demo.service;

import com.example.demo.exception.CustomException;

import java.math.BigDecimal;

/** Shared server-side bounds for customer-selected item quantities. */
public final class QuantityLimits {
    public static final int MIN = 1;
    public static final int MAX = 99;

    private QuantityLimits() {
    }

    public static int parse(Object rawValue) {
        try {
            int quantity = new BigDecimal(String.valueOf(rawValue).trim()).intValueExact();
            return validate(quantity);
        } catch (NumberFormatException | ArithmeticException | NullPointerException e) {
            throw new CustomException("400", "數量必須是 1 到 99 的整數");
        }
    }

    public static int validate(int quantity) {
        if (quantity < MIN || quantity > MAX) {
            throw new CustomException("400", "數量必須是 1 到 99 的整數");
        }
        return quantity;
    }
}
