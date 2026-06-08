package com.kvstore.rest;


import com.fasterxml.jackson.databind.JsonNode;
import com.kvstore.KvStoreApplication;
import com.kvstore.util.JacksonUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashSet;
import java.util.Set;

/**
 * End-to-end Spring MVC tests against a node. Spins up a real
 * embedded Tomcat on a random port. Uses JDK HttpClient so PATCH works without
 * extra Apache HttpClient dependency.
 */
@SpringBootTest(
    classes = KvStoreApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"kvstore.node-id=node-test"}
)
@ActiveProfiles("node")
class KvControllerIntegrationTest {

    @LocalServerPort int port;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp() {
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + port;
    }

    @Test
    void getMissingReturns404() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/kv/missing")).GET().build());
        Assertions.assertEquals(404, r.statusCode());
        Assertions.assertEquals("not found: missing", JacksonUtil.parse(r.body()).get("error").asText());
    }

    @Test
    void putThenGetRoundtrips() throws Exception {
        HttpResponse<String> put = send(HttpRequest.newBuilder(URI.create(base + "/kv/user:42"))
            .header("Content-Type", "application/json")
            .PUT(BodyPublishers.ofString("{\"name\":\"Ari\",\"points\":10}"))
            .build());
        Assertions.assertEquals(200, put.statusCode());
        JsonNode putBody = JacksonUtil.parse(put.body());
        Assertions.assertEquals("user:42", putBody.get("key").asText());
        Assertions.assertEquals(0, putBody.get("version").asLong());
        Assertions.assertEquals(JacksonUtil.parse("{\"name\":\"Ari\",\"points\":10}"), putBody.get("value"));

        HttpResponse<String> get = send(HttpRequest.newBuilder(URI.create(base + "/kv/user:42")).GET().build());
        Assertions.assertEquals(200, get.statusCode());
        Assertions.assertEquals(putBody, JacksonUtil.parse(get.body()));
    }

    @Test
    void putWithIfVersionMismatchReturns409() throws Exception {
        // Unique key per test: @SpringBootTest caches the context across tests,
        // so the KvService is shared. Sharing "k" across tests would interfere.
        String k = "ifv-mismatch";
        send(HttpRequest.newBuilder(URI.create(base + "/kv/" + k))
            .PUT(BodyPublishers.ofString("{\"a\":1}")).build());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/kv/" + k + "?ifVersion=5"))
            .PUT(BodyPublishers.ofString("{\"a\":2}")).build());
        Assertions.assertEquals(409, r.statusCode());
    }

    @Test
    void putWithMatchingIfVersionAdvancesVersion() throws Exception {
        String k = "ifv-match";
        send(HttpRequest.newBuilder(URI.create(base + "/kv/" + k))
            .PUT(BodyPublishers.ofString("{\"a\":1}")).build());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/kv/" + k + "?ifVersion=0"))
            .PUT(BodyPublishers.ofString("{\"a\":2}")).build());
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertEquals(1, JacksonUtil.parse(r.body()).get("version").asLong());
    }

    @Test
    void patchMergesTopLevel() throws Exception {
        String k = "patch-merge";
        send(HttpRequest.newBuilder(URI.create(base + "/kv/" + k))
            .PUT(BodyPublishers.ofString("{\"name\":\"Ari\",\"points\":10}")).build());

        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/kv/" + k))
            .method("PATCH", BodyPublishers.ofString("{\"rank\":\"gold\"}")).build());
        Assertions.assertEquals(200, r.statusCode());
        JsonNode body = JacksonUtil.parse(r.body());
        Assertions.assertEquals(JacksonUtil.parse("{\"name\":\"Ari\",\"points\":10,\"rank\":\"gold\"}"), body.get("value"));
        Assertions.assertEquals(1, body.get("version").asLong());
    }

    @Test
    void patchCreatesWhenMissing() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/kv/new"))
            .method("PATCH", BodyPublishers.ofString("{\"a\":1}")).build());
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertEquals(0, JacksonUtil.parse(r.body()).get("version").asLong());
    }

    @Test
    void invalidJsonReturns400() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/kv/k"))
            .PUT(BodyPublishers.ofString("{not json")).build());
        Assertions.assertEquals(400, r.statusCode());
    }

    @Test
    void invalidIfVersionReturns400() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/kv/k?ifVersion=abc"))
            .PUT(BodyPublishers.ofString("{\"a\":1}")).build());
        Assertions.assertEquals(400, r.statusCode());
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/kv/k")).DELETE().build());
        Assertions.assertEquals(405, r.statusCode());
    }

    @Test
    void listKeysReturnsNdjsonTaggedWithNodeId() throws Exception {
        // Note: Spring caches the context across tests in this class, so the store
        // may already contain keys from earlier tests. Use unique key prefixes here
        // so the assertion focuses on what THIS test wrote.
        send(HttpRequest.newBuilder(URI.create(base + "/kv/list-a")).PUT(BodyPublishers.ofString("{\"v\":1}")).build());
        send(HttpRequest.newBuilder(URI.create(base + "/kv/list-b")).PUT(BodyPublishers.ofString("{\"v\":2}")).build());

        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/kv")).GET().build());
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/x-ndjson"));

        Set<String> seenKeys = new HashSet<>();
        for (String line : r.body().split("\n")) {
            if (line.isBlank()) continue;
            JsonNode obj = JacksonUtil.parse(line);
            seenKeys.add(obj.get("key").asText());
            Assertions.assertEquals("node-test", obj.get("node").asText());
        }
        Assertions.assertTrue(seenKeys.contains("list-a") && seenKeys.contains("list-b"),
            "expected list-a, list-b in: " + seenKeys);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, BodyHandlers.ofString());
    }
}
