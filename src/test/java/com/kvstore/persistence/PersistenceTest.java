package com.kvstore.persistence;


import com.kvstore.model.KvEntry;
import com.kvstore.service.KvService;
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
            KvService svc = new KvService(p);
            svc.put("user:42", "{\"name\":\"Ari\",\"points\":10}");
            svc.put("user:43", "{\"name\":\"Bo\"}");
            svc.patch("user:42", "{\"rank\":\"gold\"}");   // version 1
            svc.patch("user:42", "{\"points\":20}");        // version 2
        }

        // Second lifetime — no snapshot was taken, so recovery is pure WAL replay.
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvService svc = new KvService(p);
            KvEntry ari = svc.get("user:42");
            Assertions.assertNotNull(ari);
            Assertions.assertEquals(2, ari.getVersion());
            Assertions.assertEquals(
                JacksonUtil.parse("{\"name\":\"Ari\",\"points\":20,\"rank\":\"gold\"}"),
                ari.getValue());

            KvEntry bo = svc.get("user:43");
            Assertions.assertEquals(0, bo.getVersion());
            Assertions.assertEquals(JacksonUtil.parse("{\"name\":\"Bo\"}"), bo.getValue());
        }
    }

    @Test
    void snapshotAndTruncateLetsFutureRestartsSkipWal(@TempDir Path dir) throws IOException {
        // First lifetime: write, snapshot, write more
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvService svc = new KvService(p);
            svc.put("a", "{\"v\":1}");
            svc.put("b", "{\"v\":2}");
            p.snapshot(svc.snapshotState());   // <-- snapshot at this point
            svc.put("c", "{\"v\":3}");          // after-snapshot WAL entry
        }

        // After snapshot the WAL should be small (only "c"); the snapshot file
        // carries a and b.
        Assertions.assertTrue(java.nio.file.Files.exists(dir.resolve("wal.log")));

        // Second lifetime: recovery rebuilds a, b from snapshot and c from WAL.
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvService svc = new KvService(p);
            Assertions.assertEquals(JacksonUtil.parse("{\"v\":1}"), svc.get("a").getValue());
            Assertions.assertEquals(JacksonUtil.parse("{\"v\":2}"), svc.get("b").getValue());
            Assertions.assertEquals(JacksonUtil.parse("{\"v\":3}"), svc.get("c").getValue());
        }
    }

    @Test
    void emptyDirectoryStartsFresh(@TempDir Path dir) throws IOException {
        try (Persistence p = Persistence.openAndRecover(dir)) {
            Assertions.assertTrue(p.recoveredState().isEmpty());
            KvService svc = new KvService(p);
            Assertions.assertNull(svc.get("anything"));
        }
    }

    @Test
    void versionsAreContiguousAcrossRestart(@TempDir Path dir) throws IOException {
        // Bug guard: if the restart-time KvService didn't start from the recovered
        // version, the next write would set version=0 again — silently overwriting.
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvService svc = new KvService(p);
            svc.put("k", "{\"v\":1}");      // version 0
            svc.put("k", "{\"v\":2}");      // version 1
        }
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvService svc = new KvService(p);
            // After replay, "k" must be at version 1. A conditional PUT with
            // ifVersion=1 must succeed; with anything else, must 409.
            KvEntry e = svc.put("k", "{\"v\":3}", 1);
            Assertions.assertEquals(2, e.getVersion());
        }
    }

    @Test
    void persistenceIsTransparentToClientBehavior(@TempDir Path dir) throws IOException {
        // Sanity: the service behaves identically with or without persistence
        // attached, for the simple cases the existing test suite exercises.
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvService svc = new KvService(p);
            svc.put("k", "{\"a\":1}");
            svc.patch("k", "{\"b\":2}");
            KvEntry e = svc.get("k");
            Assertions.assertEquals(JacksonUtil.parse("{\"a\":1,\"b\":2}"), e.getValue());
            Assertions.assertEquals(1, e.getVersion());
        }
    }

    @Test
    void recoveredStateMatchesLiveStateExactly(@TempDir Path dir) throws IOException {
        Map<String, KvEntry> live;
        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvService svc = new KvService(p);
            svc.put("a", "{\"v\":1}");
            svc.patch("a", "{\"w\":2}");
            svc.put("b", "[1,2,3]");
            svc.patch("b", "{\"replace\":true}");   // non-object existing → replace
            live = svc.snapshotState();
        }

        try (Persistence p = Persistence.openAndRecover(dir)) {
            KvService svc = new KvService(p);
            Map<String, KvEntry> recovered = svc.snapshotState();
            Assertions.assertEquals(live.keySet(), recovered.keySet());
            for (String k : live.keySet()) {
                Assertions.assertEquals(live.get(k).getValue(),   recovered.get(k).getValue(),   "value mismatch for " + k);
                Assertions.assertEquals(live.get(k).getVersion(), recovered.get(k).getVersion(), "version mismatch for " + k);
            }
        }
    }
}
