package com.kvstore.persistence;


import com.fasterxml.jackson.databind.JsonNode;
import com.kvstore.model.KvEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Persistence coordinator — owns the WAL and the snapshot store, recovers state
 * on open, and schedules periodic snapshots in the background. Designed to be
 * created once on startup; the result is plugged into {@link com.kvstore.store.KvStore}.
 *
 * <p>Crash safety contract:
 * <ul>
 *   <li><b>Writes</b>: log to WAL first, then mutate the in-memory map. If the
 *       process dies between, replay recovers the state.</li>
 *   <li><b>Snapshots</b>: written via temp-file + atomic rename. A crash mid-snapshot
 *       leaves the old snapshot intact; the WAL still has every write since.</li>
 *   <li><b>WAL truncation</b>: happens only after a snapshot has been durably
 *       written. A crash between "snapshot durable" and "WAL truncated" simply
 *       leaves us with an over-long WAL — recovery is still correct, just slower.</li>
 * </ul>
 */
public class Persistence implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Persistence.class);

    private final Wal wal;
    private final SnapshotStore snapshots;
    private final AtomicLong nextSeq;
    private final ScheduledExecutorService scheduler;
    private final Map<String, KvEntry> recoveredState;

    private Persistence(Wal wal, SnapshotStore snapshots, long startSeq,
                        Map<String, KvEntry> recoveredState) {
        this.wal = wal;
        this.snapshots = snapshots;
        this.nextSeq = new AtomicLong(startSeq);
        this.recoveredState = recoveredState;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kv-store-snapshot");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Open a Persistence at {@code baseDir}, recovering any prior snapshot + WAL.
     * After this returns, {@link #recoveredState()} carries the rebuilt map.
     */
    public static Persistence openAndRecover(Path baseDir) throws IOException {
        SnapshotStore snapshots = new SnapshotStore(baseDir);
        Wal wal = new Wal(baseDir.resolve("wal.log"));

        Map<String, KvEntry> state = new HashMap<>();
        long lastSeq = 0;

        Optional<SnapshotData> latest = snapshots.loadLatest();
        if (latest.isPresent()) {
            SnapshotData snap = latest.get();
            for (SnapshotData.Entry e : snap.entries()) {
                state.put(e.key(), new KvEntry(e.value(), e.version()));
            }
            lastSeq = snap.lastSeq();
            log.info("recovered snapshot lastSeq={} entries={}", lastSeq, snap.entries().size());
        }

        List<WalEntry> walEntries = wal.readAll();
        int replayed = 0;
        for (WalEntry entry : walEntries) {
            if (entry.seq() <= lastSeq) continue; // already captured by the snapshot
            state.put(entry.key(), new KvEntry(entry.value(), entry.version()));
            lastSeq = Math.max(lastSeq, entry.seq());
            replayed++;
        }
        log.info("replayed {} WAL entries; head seq={}", replayed, lastSeq);

        return new Persistence(wal, snapshots, lastSeq + 1, state);
    }

    /** State to seed {@link com.kvstore.store.KvStore} with on construction. */
    public Map<String, KvEntry> recoveredState() {
        return recoveredState;
    }

    /**
     * Log a write. Called from inside the service's {@code compute()} lambda,
     * <b>before</b> the in-memory mutation. Returns the assigned sequence number.
     */
    public long logWrite(String op, String key, JsonNode value, long version, boolean patch) {
        long seq = nextSeq.getAndIncrement();
        try {
            wal.append(new WalEntry(seq, op, key, value, version, patch));
        } catch (IOException e) {
            throw new RuntimeException("WAL append failed", e);
        }
        return seq;
    }

    /**
     * Start the background snapshot thread. The supplier is called periodically;
     * everything it returns is durably snapshotted and the WAL is truncated.
     */
    public void schedulePeriodicSnapshots(Supplier<Map<String, KvEntry>> stateSupplier, Duration interval) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                snapshot(stateSupplier.get());
            } catch (Exception e) {
                log.error("snapshot failed", e);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Take a snapshot of the supplied state and truncate the WAL. */
    public synchronized void snapshot(Map<String, KvEntry> state) throws IOException {
        long seq = nextSeq.get() - 1; // last assigned seq
        List<SnapshotData.Entry> entries = state.entrySet().stream()
            .map(e -> new SnapshotData.Entry(e.getKey(), e.getValue().getValue(), e.getValue().getVersion()))
            .toList();
        snapshots.write(new SnapshotData(seq, entries));
        wal.truncate();
        log.info("snapshot lastSeq={} entries={}", seq, entries.size());
    }

    @Override
    public void close() throws IOException {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        wal.close();
    }
}
