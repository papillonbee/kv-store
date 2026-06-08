package com.kvstore.rest;


import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kvstore.model.KvEntry;
import com.kvstore.service.KvService;
import com.kvstore.util.JacksonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KvController — node HTTP surface for a single node. Lives in the
 * {@code node} profile so it doesn't collide with the router's controller
 * on the same routes.
 */
@RestController
@RequestMapping("/kv")
@Profile("node")
public class KvController {

    private final KvService service;
    private final String nodeId;

    public KvController(KvService service,
                        @Value("${kvstore.node-id:node-default}") String nodeId) {
        this.service = service;
        this.nodeId = nodeId;
    }

    @GetMapping(produces = "application/x-ndjson")
    public ResponseEntity<String> listKeys() {
        StringBuilder ndjson = new StringBuilder();
        for (String key : service.keys()) {
            ObjectNode line = JacksonUtil.objectNode();
            line.put("key", key);
            line.put("node", nodeId);
            ndjson.append(JacksonUtil.stringify(line)).append('\n');
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/x-ndjson"))
            .body(ndjson.toString());
    }

    @GetMapping("/{key}")
    public ResponseEntity<ObjectNode> get(@PathVariable("key") String key) {
        KvEntry entry = service.get(key);
        if (entry == null) {
            ObjectNode err = JacksonUtil.objectNode();
            err.put("error", "not found: " + key);
            return ResponseEntity.status(404).body(err);
        }
        return ResponseEntity.ok(toResponse(key, entry));
    }

    @PutMapping("/{key}")
    public ResponseEntity<ObjectNode> put(@PathVariable("key") String key,
                                          @RequestParam(required = false) Long ifVersion,
                                          @RequestBody String body) {
        KvEntry written = ifVersion == null ? service.put(key, body) : service.put(key, body, ifVersion);
        return ResponseEntity.ok(toResponse(key, written));
    }

    @PatchMapping("/{key}")
    public ResponseEntity<ObjectNode> patch(@PathVariable("key") String key,
                                            @RequestParam(required = false) Long ifVersion,
                                            @RequestBody String body) {
        KvEntry written = ifVersion == null ? service.patch(key, body) : service.patch(key, body, ifVersion);
        return ResponseEntity.ok(toResponse(key, written));
    }

    private static ObjectNode toResponse(String key, KvEntry entry) {
        ObjectNode out = JacksonUtil.objectNode();
        out.put("key", key);
        out.set("value", entry.getValue());
        out.put("version", entry.getVersion());
        return out;
    }
}
