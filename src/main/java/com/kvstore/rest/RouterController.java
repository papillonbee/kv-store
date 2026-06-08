package com.kvstore.rest;


import com.kvstore.exception.NodeUnreachableException;
import com.kvstore.router.KeyRouter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/**
 * RouterController — proxies key-shaped requests to the node chosen by
 * {@link KeyRouter#nodeFor(String)}, and fans {@code GET /kv} out across all
 * nodes concatenating their NDJSON. Lives in the {@code router} profile.
 */
@RestController
@RequestMapping("/kv")
@Profile("router")
public class RouterController {

    private final KeyRouter router;
    private final HttpClient client;

    public RouterController(KeyRouter router) {
        this.router = router;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @GetMapping(produces = "application/x-ndjson")
    public ResponseEntity<String> listFanOut() {
        StringBuilder out = new StringBuilder();
        for (String node : router.all()) {
            HttpResponse<String> resp = send(node, "/kv", "GET", null, null, BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new NodeUnreachableException("node " + node + " returned " + resp.statusCode());
            }
            out.append(resp.body());
            if (!resp.body().isEmpty() && !resp.body().endsWith("\n")) {
                out.append('\n');
            }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/x-ndjson"))
            .body(out.toString());
    }

    @GetMapping("/{key}")
    public ResponseEntity<byte[]> getProxy(@PathVariable("key") String key, HttpServletRequest req) {
        return proxy(key, "GET", null, req);
    }

    @PutMapping("/{key}")
    public ResponseEntity<byte[]> putProxy(@PathVariable("key") String key,
                                           @RequestBody(required = false) byte[] body,
                                           HttpServletRequest req) {
        return proxy(key, "PUT", body, req);
    }

    @PatchMapping("/{key}")
    public ResponseEntity<byte[]> patchProxy(@PathVariable("key") String key,
                                             @RequestBody(required = false) byte[] body,
                                             HttpServletRequest req) {
        return proxy(key, "PATCH", body, req);
    }

    // ---------- proxy plumbing ----------

    private ResponseEntity<byte[]> proxy(String key, String method, byte[] body, HttpServletRequest req) {
        String node = router.nodeFor(key);
        String path = "/kv/" + key;
        String query = req.getQueryString();
        HttpResponse<byte[]> resp = send(node, path, method, body, query, BodyHandlers.ofByteArray());
        String contentType = resp.headers().firstValue("Content-Type").orElse("application/json");
        return ResponseEntity.status(resp.statusCode())
            .header("Content-Type", contentType)
            .body(resp.body());
    }

    private <T> HttpResponse<T> send(String node, String path, String method, byte[] body,
                                     String query, HttpResponse.BodyHandler<T> handler) {
        URI uri = URI.create(node + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
        switch (method) {
            case "GET"   -> builder.GET();
            case "PUT"   -> builder.PUT(BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
            case "PATCH" -> builder.method("PATCH", BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
            default      -> throw new NodeUnreachableException("unexpected method: " + method);
        }
        try {
            return client.send(builder.build(), handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeUnreachableException("interrupted while contacting " + node, e);
        } catch (IOException e) {
            throw new NodeUnreachableException("node " + node + " unreachable: " + e.getMessage(), e);
        }
    }
}
