package com.example.demo.service;

import com.example.demo.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@Component
public class OpeningHoursValidator {

    private static final Set<String> DAY_KEYS = Set.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final Map<String, String> DAY_NAMES = Map.of(
        "mon", "星期一", "tue", "星期二", "wed", "星期三", "thu", "星期四",
        "fri", "星期五", "sat", "星期六", "sun", "星期日"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String normalize(Object value) {
        if (value == null) {
            throw new CustomException("400", "營業時間不能為空");
        }

        try {
            JsonNode root = value instanceof String
                    ? objectMapper.readTree((String) value)
                    : objectMapper.valueToTree(value);

            validateRoot(root);
            return objectMapper.writeValueAsString(root);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException("400", "營業時間格式無效");
        }
    }

    private void validateRoot(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new CustomException("400", "營業時間必須是一個物件");
        }

        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String key = fields.next();
            if (!DAY_KEYS.contains(key)) {
                throw new CustomException("400", "營業時間包含不支援的日期金鑰: " + key);
            }
        }

        for (String day : DAY_KEYS) {
            JsonNode dayNode = root.get(day);
            if (dayNode == null || !dayNode.isObject()) {
                throw new CustomException("400", "營業時間缺少日期設定: " + getDayName(day));
            }
            validateDay(day, dayNode);
        }
    }

    private void validateDay(String day, JsonNode dayNode) {
        JsonNode closedNode = dayNode.get("closed");
        if (closedNode == null || !closedNode.isBoolean()) {
            throw new CustomException("400", getDayName(day) + " 的店休狀態 (closed) 必須是布林值");
        }

        boolean closed = closedNode.asBoolean();
        JsonNode openNode = dayNode.get("open");
        JsonNode closeNode = dayNode.get("close");

        if (closed) {
            if (isNullNode(openNode) && isNullNode(closeNode)) {
                return;
            }
            if (!isTextNode(openNode) || !isTextNode(closeNode)) {
                throw new CustomException("400", getDayName(day) + " 的開關店時間格式應為 HH:mm 或為空");
            }
            LocalTime open = parseTime(day, "開門時間", openNode.asText());
            LocalTime close = parseTime(day, "關門時間", closeNode.asText());
            if (!close.isAfter(open)) {
                throw new CustomException("400", getDayName(day) + " 的關門時間必須在開門時間之後");
            }
            return;
        }

        if (!isTextNode(openNode) || !isTextNode(closeNode)) {
            throw new CustomException("400", getDayName(day) + " 的開關店時間格式應為 HH:mm");
        }

        LocalTime open = parseTime(day, "開門時間", openNode.asText());
        LocalTime close = parseTime(day, "關門時間", closeNode.asText());
        if (!close.isAfter(open)) {
            throw new CustomException("400", getDayName(day) + " 的關門時間必須在開門時間之後");
        }
    }

    private LocalTime parseTime(String day, String fieldName, String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new CustomException("400", getDayName(day) + " 的 " + fieldName + " 必須符合格式 HH:mm");
        }
    }

    private String getDayName(String key) {
        return DAY_NAMES.getOrDefault(key, key);
    }

    private boolean isNullNode(JsonNode node) {
        return node == null || node.isNull();
    }

    private boolean isTextNode(JsonNode node) {
        return node != null && node.isTextual();
    }
}
