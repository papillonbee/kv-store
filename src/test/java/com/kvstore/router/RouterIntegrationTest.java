package com.kvstore.router;


import com.fasterxml.jackson.databind.JsonNode;
import com.kvstore.KvStoreApplication;
import com.kvstore.util.JacksonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * End-to-end multi-node Spring Boot test: spins up three node application
 * contexts and one router application context, all on random ports inside the
 * same JVM. The router proxies key-shaped requests to one node, fans out
 * {@code GET /kv} across all of them, and rolls up status codes.
 */
class RouterIntegrationTest {

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<String> nodeUrls = new ArrayList<>();
    private HttpClient client;
    private String routerBase;

    @BeforeEach
    void setUp() {
        for (int i = 1; i <= 3; i++) {
            ConfigurableApplicationContext node = new SpringApplicationBuilder(KvStoreApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("node")
                .properties("server.port=0", "kvstore.node-id=node-" + i)
                .run();
            contexts.add(node);
            nodeUrls.add("http://localhost:" + portOf(node));
        }

        ConfigurableApplicationContext router = new SpringApplicationBuilder(KvStoreApplication.class)
            .web(WebApplicationType.SERVLET)
            .profiles("router")
            .properties("server.port=0", "kvstore.nodes=" + String.join(",", nodeUrls))
            .run();
        contexts.add(router);
        routerBase = "http://localhost:" + portOf(router);

        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        // Close in reverse order so the router goes first.
        Collections.reverse(contexts);
        contexts.forEach(ConfigurableApplicationContext::close);
        contexts.clear();
        nodeUrls.clear();
    }

    @Test
    void putThroughRouterIsRoutedDeterministically() throws Exception {
        putViaRouter("apple", "{\"v\":1}");
        String owner = findOwningNode("apple");

        Assertions.assertTrue(listKeysOnNode(owner).contains("apple"));
        for (String node : nodeUrls) {
            if (node.equals(owner)) continue;
            Assertions.assertFalse(listKeysOnNode(node).contains("apple"),
                "non-owning node " + node + " unexpectedly has 'apple'");
        }
    }

    @Test
    void getThroughRouterReturnsCorrectValueAndVersion() throws Exception {
        Assertions.assertEquals(200, putViaRouter("user:42", "{\"name\":\"Ari\"}").statusCode());

        HttpResponse<String> get = client.send(
            HttpRequest.newBuilder(URI.create(routerBase + "/kv/user:42")).GET().build(),
            BodyHandlers.ofString());
        Assertions.assertEquals(200, get.statusCode());
        JsonNode body = JacksonUtil.parse(get.body());
        Assertions.assertEquals(JacksonUtil.parse("{\"name\":\"Ari\"}"), body.get("value"));
        Assertions.assertEquals(0, body.get("version").asLong());
    }

    @Test
    void getMissingThroughRouterReturns404() throws Exception {
        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder(URI.create(routerBase + "/kv/never-written")).GET().build(),
            BodyHandlers.ofString());
        Assertions.assertEquals(404, r.statusCode());
    }

    @Test
    void putWithIfVersionConflictPropagates409() throws Exception {
        putViaRouter("k", "{\"a\":1}");
        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder(URI.create(routerBase + "/kv/k?ifVersion=99"))
                .PUT(BodyPublishers.ofString("{\"a\":2}")).build(),
            BodyHandlers.ofString());
        Assertions.assertEquals(409, r.statusCode());
    }

    @Test
    void listKeysAggregatesFromAllNodes() throws Exception {
        List<String> keys = Arrays.asList("a", "b", "c", "d", "apple", "banana", "carrot", "user:42");
        for (String k : keys) {
            putViaRouter(k, "{\"v\":1}");
        }

        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder(URI.create(routerBase + "/kv")).GET().build(),
            BodyHandlers.ofString());
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/x-ndjson"));

        Set<String> seenKeys = new HashSet<>();
        Set<String> seenNodes = new HashSet<>();
        for (String line : r.body().split("\n")) {
            if (line.isBlank()) continue;
            JsonNode obj = JacksonUtil.parse(line);
            seenKeys.add(obj.get("key").asText());
            seenNodes.add(obj.get("node").asText());
        }
        Assertions.assertEquals(new HashSet<>(keys), seenKeys);
        Assertions.assertTrue(seenNodes.size() >= 2,
            "expected keys spread across ≥2 nodes, saw nodes: " + seenNodes);
    }

    @Test
    void nodeDownReturns502() throws Exception {
        // Use the same KeyRouter the router uses to find a key that maps to
        // node 0, then close that node and verify the router surfaces a
        // 502 (NodeUnreachableException → KvStoreException.httpStatus()).
        KeyRouter probe = new KeyRouter(nodeUrls);
        String victim = nodeUrls.get(0);
        String key = null;
        for (int i = 0; i < 1000; i++) {
            String candidate = "probe-" + i;
            if (probe.nodeFor(candidate).equals(victim)) {
                key = candidate;
                break;
            }
        }
        Assertions.assertNotNull(key, "couldn't find a key mapping to node 0");

        // Kill node 0. tearDown will close all contexts again — close() is
        // idempotent, so the double-close is harmless.
        contexts.get(0).close();

        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder(URI.create(routerBase + "/kv/" + key)).GET().build(),
            BodyHandlers.ofString());
        Assertions.assertEquals(502, r.statusCode());
    }

    @Test
    void patchThroughRouterMerges() throws Exception {
        putViaRouter("k", "{\"name\":\"Ari\",\"points\":10}");
        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder(URI.create(routerBase + "/kv/k"))
                .method("PATCH", BodyPublishers.ofString("{\"rank\":\"gold\"}")).build(),
            BodyHandlers.ofString());
        Assertions.assertEquals(200, r.statusCode());
        JsonNode body = JacksonUtil.parse(r.body());
        Assertions.assertEquals(JacksonUtil.parse("{\"name\":\"Ari\",\"points\":10,\"rank\":\"gold\"}"), body.get("value"));
    }

    // ---------- helpers ----------

    private HttpResponse<String> putViaRouter(String key, String body) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create(routerBase + "/kv/" + key))
                .PUT(BodyPublishers.ofString(body)).build(),
            BodyHandlers.ofString());
    }

    private String findOwningNode(String key) throws Exception {
        for (String node : nodeUrls) {
            if (listKeysOnNode(node).contains(key)) {
                return node;
            }
        }
        throw new AssertionError("no node owns key: " + key);
    }

    private Set<String> listKeysOnNode(String node) throws Exception {
        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder(URI.create(node + "/kv")).GET().build(),
            BodyHandlers.ofString());
        Set<String> keys = new HashSet<>();
        for (String line : r.body().split("\n")) {
            if (line.isBlank()) continue;
            keys.add(JacksonUtil.parse(line).get("key").asText());
        }
        return keys;
    }

    private static int portOf(ConfigurableApplicationContext ctx) {
        return ((WebServerApplicationContext) ctx).getWebServer().getPort();
    }
}
