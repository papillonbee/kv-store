package com.kvstore.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kvstore.exception.BadRequestException;
import com.kvstore.exception.VersionConflictException;
import com.kvstore.model.KvEntry;
import com.kvstore.util.JacksonUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * KvServiceTest
 *
 * @author papan.yongmalwong
 * @version KvServiceTest.java v1.0 2026-06-07
 */
class KvServiceTest {

    KvService service;

    @BeforeEach
    void beforeEach() {
        service = new KvService();
    }

    @Test
    void getEmpty() {
        KvEntry entry = service.get("user:42");
        Assertions.assertNull(entry);
    }

    @Test
    void putNewObject() {
        String key = "user:42";
        String value = "{\"name\":\"Ari\",\"points\":10}";

        service.put(key, value);

        KvEntry entry = service.get(key);

        Assertions.assertNotNull(entry);
        Assertions.assertEquals(0, entry.getVersion());
        Assertions.assertEquals(JacksonUtil.parse(value), entry.getValue());
    }

    @Test
    void putReplacesExisting() {
        String key = "user:42";
        String value1 = "{\"name\":\"Ari\",\"points\":10}";
        String value2 = "{\"name\":\"Ari\",\"points\":20}";

        service.put(key, value1);

        service.put(key, value2);
        
        KvEntry entry = service.get(key);

        Assertions.assertNotNull(entry);
        Assertions.assertEquals(1, entry.getVersion());
        Assertions.assertEquals(JacksonUtil.parse(value2), entry.getValue());
    }

    @Test
    void patchShallowMergeWhenBothExistingAndDeltaAreObject() {
        String key = "user:42";
        String value1 = "{\"name\":\"Ari\",\"points\":10}";
        String value2 = "{\"rank\":\"gold\"}";

        service.put(key, value1);

        service.patch(key, value2);

        KvEntry entry = service.get(key);

        Assertions.assertNotNull(entry);
        Assertions.assertEquals(1, entry.getVersion());
        Assertions.assertEquals(JacksonUtil.parse("{\"name\":\"Ari\",\"points\":10,\"rank\":\"gold\"}"), entry.getValue());
    }

    @Test
    void concurrentSavesConverge() {
        // Without ifVersion, save() is an upsert. After save() collapses into a
        // single compute(), the bucket lock serializes the two writes: one creates,
        // the other updates. Both succeed; final state is one of the submitted
        // values; version is 1.
        String key = "user:42";
        String value1 = "{\"name\":\"Ari\",\"points\":10}";
        String value2 = "{\"name\":\"Ari\",\"points\":20}";

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CompletableFuture<Void> task1 = CompletableFuture.runAsync(() -> service.put(key, value1), executorService);
        CompletableFuture<Void> task2 = CompletableFuture.runAsync(() -> service.put(key, value2), executorService);
        CompletableFuture.allOf(task1, task2).join();

        KvEntry entry = service.get(key);
        Assertions.assertNotNull(entry);
        Assertions.assertEquals(1, entry.getVersion());
        Assertions.assertTrue(Arrays.asList(JacksonUtil.parse(value1), JacksonUtil.parse(value2)).contains(entry.getValue()));
    }

    @Test
    void ifVersionOnMissingKeyConflicts() {
        // ifVersion is an "I expect the current version to be N" precondition. If
        // the key doesn't exist, no version satisfies it → 409.
        VersionConflictException e = Assertions.assertThrows(VersionConflictException.class,
            () -> service.put("user:42", "{\"a\":1}", 0L));
        Assertions.assertEquals(409, e.httpStatus());
        Assertions.assertNull(e.currentVersion());
        Assertions.assertEquals(0L, e.expectedVersion());
        Assertions.assertNull(service.get("user:42"));
    }

    @Test
    void ifVersionMismatchOnExistingKeyConflicts() {
        service.put("k", "{\"a\":1}");
        VersionConflictException e = Assertions.assertThrows(VersionConflictException.class,
            () -> service.put("k", "{\"a\":2}", 5L));
        Assertions.assertEquals(409, e.httpStatus());
        Assertions.assertEquals(0L, e.currentVersion());
        Assertions.assertEquals(5L, e.expectedVersion());
    }

    @Test
    void invalidJsonBodyIsBadRequest() {
        BadRequestException e = Assertions.assertThrows(BadRequestException.class,
            () -> service.put("k", "{not json"));
        Assertions.assertEquals(400, e.httpStatus());
    }

    @Test
    void patchReplacesWhenExistingNotObject() {
        // Spec: shallow-merge only when both existing AND delta are JSON objects;
        // otherwise treat as replace.
        String key = "k";
        service.put(key, "[1,2,3]");
        service.patch(key, "{\"a\":1}");

        KvEntry entry = service.get(key);
        Assertions.assertEquals(JacksonUtil.parse("{\"a\":1}"), entry.getValue());
        Assertions.assertEquals(1, entry.getVersion());
    }

    @Test
    void patchReplacesWhenDeltaNotObject() {
        String key = "k";
        service.put(key, "{\"a\":1}");
        service.patch(key, "[1,2,3]");

        KvEntry entry = service.get(key);
        Assertions.assertEquals(JacksonUtil.parse("[1,2,3]"), entry.getValue());
        Assertions.assertEquals(1, entry.getVersion());
    }

    @Test
    void patchShallowMergeDoesNotRecurse() {
        // Top-level shallow only: nested objects are overwritten wholesale,
        // not recursively merged.
        String key = "k";
        service.put(key, "{\"a\":{\"x\":1,\"y\":2}}");
        service.patch(key, "{\"a\":{\"z\":3}}");

        KvEntry entry = service.get(key);
        Assertions.assertEquals(JacksonUtil.parse("{\"a\":{\"z\":3}}"), entry.getValue());
    }

    @Test
    void patchCreatesWhenMissing() {
        // Spec: PATCH on missing key creates it with delta as value, version 0.
        service.patch("k", "{\"a\":1}");
        KvEntry entry = service.get("k");
        Assertions.assertEquals(JacksonUtil.parse("{\"a\":1}"), entry.getValue());
        Assertions.assertEquals(0, entry.getVersion());
    }

    @Test
    void threeConcurrentClientsIncrementCounter() {
        String key = "user:42";
        String value = "{\"counter\":0}";

        service.put(key, value);

        try (ExecutorService executorService = Executors.newFixedThreadPool(3)) {
            List<CompletableFuture<Void>> tasks = IntStream.range(0, 300).mapToObj(i -> CompletableFuture.runAsync(() -> {
                boolean saved = false;
                do {
                    // get
                    KvEntry entry = service.get(key);
                    JsonNode node = entry.getValue();

                    // increment counter
                    if (node.isObject()) {
                        ObjectNode objectNode = (ObjectNode) node;
                        objectNode.put("counter", node.get("counter").asInt() + 1);
                    }
                    String updatedValue = JacksonUtil.stringify(node);

                    // try save
                    try {
                        service.put(key, updatedValue, entry.getVersion());
                        // abort when saved successfully
                        saved = true;
                    } catch (RuntimeException e) {
                        System.out.println(e.getMessage());
                        // absorb
                    }
                } while (!saved);
            }, executorService)).toList();

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[]{})).join();
        }

        KvEntry entry = service.get(key);
        Assertions.assertNotNull(entry);
        Assertions.assertEquals(300, entry.getVersion());
        Assertions.assertEquals(JacksonUtil.parse("{\"counter\":300}"), entry.getValue());
    }
}
