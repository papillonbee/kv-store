package com.kvstore.util;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kvstore.exception.BadRequestException;

/**
 * JacksonUtil
 *
 * @author papan.yongmalwong
 * @version JacksonUtil.java v1.0 2026-06-07
 */
public class JacksonUtil {

    private static final ObjectMapper mapper = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Top-level shallow merge: result has every field from {@code existing}, with
     * any same-keyed field replaced by the corresponding field from {@code delta}.
     * Nested objects are not recursively merged — delta values overwrite wholesale.
     */
    public static ObjectNode shallowMerge(ObjectNode existing, ObjectNode delta) {
        ObjectNode result = existing.deepCopy();
        result.setAll(delta);
        return result;
    }

    public static ObjectNode objectNode() {
        return mapper.createObjectNode();
    }

    public static String writeValue(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("serialize failed", e);
        }
    }

    public static <T> T readValue(String s, Class<T> type) {
        try {
            return mapper.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("deserialize failed: " + e.getOriginalMessage(), e);
        }
    }

    public static String stringify(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
