package com.kvstore.store;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kvstore.exception.VersionConflictException;
import com.kvstore.model.KvEntry;
import com.kvstore.persistence.Persistence;
import com.kvstore.util.JacksonUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KvStore — in-memory, per-key-atomic KV store with optional WAL+snapshot
 * persistence. When {@code persistence} is null, behaves as a pure in-memory
 * store (the default for tests).
 *
 * @author papan.yongmalwong
 * @version KvStore.java v1.0 2026-06-07
 */
public class KvStore {

    private final ConcurrentMap<String, KvEntry> store;
    private final Persistence persistence;

    public KvStore() {
        this(Map.of(), null);
    }

    public KvStore(Persistence persistence) {
        this(persistence != null ? persistence.recoveredState() : Map.of(), persistence);
    }

    private KvStore(Map<String, KvEntry> initialState, Persistence persistence) {
        this.store = new ConcurrentHashMap<>(initialState);
        this.persistence = persistence;
    }

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
        synchronized (entry.getLock()) {
            return new KvEntry(entry.getValue().deepCopy(), entry.getVersion());
        }
    }

    /**
     * Deep copy of the entire store for use by the snapshot thread. Keys/values
     * are stable inside their per-key lock; iteration is weakly consistent so a
     * concurrent write may or may not be observed, but the WAL still has the
     * write either way, so recovery is correct.
     */
    public Map<String, KvEntry> snapshotState() {
        Map<String, KvEntry> out = new HashMap<>();
        for (Map.Entry<String, KvEntry> e : store.entrySet()) {
            synchronized (e.getValue().getLock()) {
                out.put(e.getKey(), new KvEntry(e.getValue().getValue().deepCopy(), e.getValue().getVersion()));
            }
        }
        return out;
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

            // 3. WAL first — durability boundary. If this throws, no in-memory
            //    mutation has happened, so a retry leaves the system consistent.
            if (persistence != null) {
                persistence.logWrite(patch ? "PATCH" : "PUT", key, newValue, newVersion, patch);
            }

            // 4. Apply to memory (mutation guarded by entry.getLock() so concurrent
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

            // 5. Capture snapshot inside compute() so the returned state is exactly
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
