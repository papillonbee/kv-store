package com.kvstore.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kvstore.exception.VersionConflictException;
import com.kvstore.model.KvEntry;
import com.kvstore.util.JacksonUtil;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KvService
 *
 * @author papan.yongmalwong
 * @version KvService.java v1.0 2026-06-07
 */
public class KvService {

    private final ConcurrentMap<String, KvEntry> store = new ConcurrentHashMap<>();

    /**
     * Snapshot of currently-known keys. ConcurrentHashMap's keySet view is
     * weakly consistent under concurrent mutation; copying into a fresh Set
     * gives callers a stable iteration list.
     */
    public Set<String> keys() {
        return Set.copyOf(store.keySet());
    }

    public KvEntry get(String key) {
        KvEntry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        // snapshot value+version atomically under the entry lock so callers
        // never observe a value from one version paired with another version
        synchronized (entry.getLock()) {
            return new KvEntry(entry.getValue().deepCopy(), entry.getVersion());
        }
    }

    public KvEntry put(String key, String value) {
        return save(key, value, null, false);
    }

    public KvEntry put(String key, String value, long ifVersion) {
        return save(key, value, ifVersion, false);
    }

    public KvEntry patch(String key, String value) {
        return save(key, value, null, true);
    }

    public KvEntry patch(String key, String value, long ifVersion) {
        return save(key, value, ifVersion, true);
    }

    private KvEntry save(String key, String value, Long ifVersion, boolean patch) {
        JsonNode incoming = JacksonUtil.parse(value);
        // Capture the snapshot inside compute() so the returned state is exactly
        // what THIS write produced, not whatever the next writer set it to.
        AtomicReference<KvEntry> snapshot = new AtomicReference<>();
        store.compute(key, (k, existing) -> {
            KvEntry stored = existing == null
                ? createEntry(ifVersion, incoming)
                : updateEntry(existing, ifVersion, patch, incoming);
            snapshot.set(new KvEntry(stored.getValue().deepCopy(), stored.getVersion()));
            return stored;
        });
        return snapshot.get();
    }

    private static KvEntry createEntry(Long ifVersion, JsonNode incoming) {
        // absent: any ifVersion precondition is unsatisfiable
        if (ifVersion != null) {
            throw new VersionConflictException(null, ifVersion);
        }
        return new KvEntry(incoming);
    }

    private static KvEntry updateEntry(KvEntry existing, Long ifVersion, boolean patch, JsonNode incoming) {
        synchronized (existing.getLock()) {
            requireVersionMatches(existing.getVersion(), ifVersion);
            existing.setValue(nextValue(existing.getValue(), incoming, patch));
            existing.setVersion(existing.getVersion() + 1);
            return existing;
        }
    }

    private static void requireVersionMatches(long current, Long expected) {
        if (expected != null && expected != current) {
            throw new VersionConflictException(current, expected);
        }
    }

    /**
     * For PATCH where both existing and delta are JSON objects, shallow-merge.
     * Anything else (PUT, or PATCH against a non-object): replace wholesale.
     */
    private static JsonNode nextValue(JsonNode existing, JsonNode incoming, boolean patch) {
        if (patch && existing.isObject() && incoming.isObject()) {
            return JacksonUtil.shallowMerge((ObjectNode) existing, (ObjectNode) incoming);
        }
        return incoming;
    }
}
