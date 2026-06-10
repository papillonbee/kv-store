package com.kvstore.persistence;


import com.kvstore.model.KvEntry;
import com.kvstore.store.KvStore;
import com.kvstore.util.JacksonUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * End-to-end persistence tests: put/patch through the real service, close it,
 * reopen, verify state was rebuilt from snapshot + WAL.
 */
class PersistenceTest {

    @Test
    void walReplayRebuildsStateAfterRestart(@TempDir Path dir) throws IOException {
        // First lifetime
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvStore store = new KvStore(p);
            store.put("user:42", "{\"name\":\"Ari\",\"points\":10}");
            store.put("user:43", "{\"name\":\"Bo\"}");
            store.patch("user:42", "{\"rank\":\"gold\"}");   // version 1
            store.patch("user:42", "{\"points\":20}");        // version 2
        }

        // Second lifetime — no snapshot was taken, so recovery is pure WAL replay.
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvStore store = new KvStore(p);
            KvEntry ari = store.get("user:42");
            Assertions.assertNotNull(ari);
            Assertions.assertEquals(2, ari.getVersion());
            Assertions.assertEquals(
                JacksonUtil.parse("{\"name\":\"Ari\",\"points\":20,\"rank\":\"gold\"}"),
                ari.getValue());

            KvEntry bo = store.get("user:43");
            Assertions.assertEquals(0, bo.getVersion());
            Assertions.assertEquals(JacksonUtil.parse("{\"name\":\"Bo\"}"), bo.getValue());
        }
    }

    @Test
    void snapshotAndTruncateLetsFutureRestartsSkipWal(@TempDir Path dir) throws IOException {
        // First lifetime: write, snapshot, write more
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvStore store = new KvStore(p);
            store.put("a", "{\"v\":1}");
            store.put("b", "{\"v\":2}");
            p.snapshot(store.snapshotState());   // <-- snapshot at this point
            store.put("c", "{\"v\":3}");          // after-snapshot WAL entry
        }

        // After snapshot the WAL should be small (only "c"); the snapshot file
        // carries a and b.
        Assertions.assertTrue(java.nio.file.Files.exists(dir.resolve("wal.log")));

        // Second lifetime: recovery rebuilds a, b from snapshot and c from WAL.
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvStore store = new KvStore(p);
            Assertions.assertEquals(JacksonUtil.parse("{\"v\":1}"), store.get("a").getValue());
            Assertions.assertEquals(JacksonUtil.parse("{\"v\":2}"), store.get("b").getValue());
            Assertions.assertEquals(JacksonUtil.parse("{\"v\":3}"), store.get("c").getValue());
        }
    }

    @Test
    void emptyDirectoryStartsFresh(@TempDir Path dir) throws IOException {
        try (Persistence p = Persistence.openAndRecover(dir)) {
            Assertions.assertTrue(p.recoveredState().isEmpty());
            KvStore store = new KvStore(p);
            Assertions.assertNull(store.get("anything"));
        }
    }

    @Test
    void versionsAreContiguousAcrossRestart(@TempDir Path dir) throws IOException {
        // Bug guard: if the restart-time KvStore didn't start from the recovered
        // version, the next write would set version=0 again — silently overwriting.
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvStore store = new KvStore(p);
            store.put("k", "{\"v\":1}");      // version 0
            store.put("k", "{\"v\":2}");      // version 1
        }
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvStore store = new KvStore(p);
            // After replay, "k" must be at version 1. A conditional PUT with
            // ifVersion=1 must succeed; with anything else, must 409.
            KvEntry e = store.put("k", "{\"v\":3}", 1);
            Assertions.assertEquals(2, e.getVersion());
        }
    }

    @Test
    void persistenceIsTransparentToClientBehavior(@TempDir Path dir) throws IOException {
        // Sanity: the service behaves identically with or without persistence
        // attached, for the simple cases the existing test suite exercises.
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvStore store = new KvStore(p);
            store.put("k", "{\"a\":1}");
            store.patch("k", "{\"b\":2}");
            KvEntry e = store.get("k");
            Assertions.assertEquals(JacksonUtil.parse("{\"a\":1,\"b\":2}"), e.getValue());
            Assertions.assertEquals(1, e.getVersion());
        }
    }

    @Test
    void recoveredStateMatchesLiveStateExactly(@TempDir Path dir) throws IOException {
        Map<String, KvEntry> live;
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvStore store = new KvStore(p);
            store.put("a", "{\"v\":1}");
            store.patch("a", "{\"w\":2}");
            store.put("b", "[1,2,3]");
            store.patch("b", "{\"replace\":true}");   // non-object existing → replace
            live = store.snapshotState();
        }

        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvStore store = new KvStore(p);
            Map<String, KvEntry> recovered = store.snapshotState();
            Assertions.assertEquals(live.keySet(), recovered.keySet());
            for (String k : live.keySet()) {
                Assertions.assertEquals(live.get(k).getValue(),   recovered.get(k).getValue(),   "value mismatch for " + k);
                Assertions.assertEquals(live.get(k).getVersion(), recovered.get(k).getVersion(), "version mismatch for " + k);
            }
        }
    }
}
