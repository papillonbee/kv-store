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
        AtomicReference<KvEntry> snapshot = new AtomicReference<>();
        store.compute(key, (k, existing) -> {
            // 1. CAS check
            requireVersionPrecondition(existing, ifVersion);

            // 2. Compute the new state (no mutation yet)
            JsonNode newValue = (existing == null)
                ? incoming
                : nextValue(existing.getValue(), incoming, patch);
            long newVersion = (existing == null) ? 0 : existing.getVersion() + 1;

            // 3. Apply to memory (mutation guarded by entry.getLock() so concurrent
            //    get() can't observe a torn value/version pair).
            KvEntry stored;
            if (existing == null) {
                stored = new KvEntry(newValue, newVersion);
            } else {
                synchronized (existing.getLock()) {
                    existing.setValue(newValue);
                    existing.setVersion(newVersion);
                }
                stored = existing;
            }

            // 4. Capture snapshot inside compute() so the returned state is exactly
            //    what THIS write produced.
            snapshot.set(new KvEntry(newValue.deepCopy(), newVersion));
            return stored;
        });
        return snapshot.get();
    }

    private static void requireVersionPrecondition(KvEntry existing, Long ifVersion) {
        if (ifVersion == null) return;
        if (existing == null) {
            throw new VersionConflictException(null, ifVersion);
        }
        if (ifVersion != existing.getVersion()) {
            throw new VersionConflictException(existing.getVersion(), ifVersion);
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
