package com.macrodash.analytics;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 수집기가 적재한 JSON을 안전하게 읽는 헬퍼.
 *
 * <p>모든 접근자는 값이 없거나 형이 다르면 {@code null}/빈 목록을 돌려줍니다.
 * 수집 실패를 0으로 바꿔 화면에 "보합"처럼 보이게 만들지 않기 위해서입니다.
 */
public final class Json {

    private Json() {
    }

    public static Double asDouble(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.asDouble();
    }

    public static String asText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    public static boolean asBoolean(JsonNode node, String field) {
        if (node == null) {
            return false;
        }
        JsonNode value = node.get(field);
        return value != null && value.asBoolean(false);
    }

    public static JsonNode child(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value;
    }

    public static List<JsonNode> array(JsonNode node, String field) {
        JsonNode value = child(node, field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        value.forEach(out::add);
        return out;
    }

    /** {"points":[{"date","value"}]} 형태에서 값만 추립니다. */
    public static List<Double> pointValues(JsonNode payload) {
        List<Double> values = new ArrayList<>();
        for (JsonNode point : array(payload, "points")) {
            Double value = asDouble(point, "value");
            if (value == null) {
                value = asDouble(point, "close");
            }
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /** {"points":[{"date",...}]} 형태에서 날짜만 추립니다(값이 있는 점만). */
    public static List<LocalDate> pointDates(JsonNode payload) {
        List<LocalDate> dates = new ArrayList<>();
        for (JsonNode point : array(payload, "points")) {
            Double value = asDouble(point, "value");
            if (value == null) {
                value = asDouble(point, "close");
            }
            if (value == null) {
                continue;
            }
            LocalDate date = parseDate(asText(point, "date"));
            if (date != null) {
                dates.add(date);
            }
        }
        return dates;
    }

    public static LocalDate parseDate(String text) {
        if (text == null || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}
