package com.itemgraph.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Deterministic compact JSON representation shared by component writes and filters. */
public final class CanonicalJson {
    private CanonicalJson() {}

    public static String encode(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key, child) ->
                    sorted.add(key, com.google.gson.JsonParser.parseString(encode(child))));
            return sorted.toString();
        }
        if (value.isJsonArray()) {
            return value.getAsJsonArray().asList().stream().map(CanonicalJson::encode)
                    .collect(Collectors.joining(",", "[", "]"));
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                BigDecimal number = new BigDecimal(primitive.getAsString()).stripTrailingZeros();
                if (number.precision() > 128 || Math.abs(number.scale()) > 128) {
                    throw new IllegalArgumentException("JSON number exceeds canonical bounds");
                }
                return number.toPlainString();
            }
        }
        return value.toString();
    }
}
