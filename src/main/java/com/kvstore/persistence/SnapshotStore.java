package com.kvstore.persistence;


import com.kvstore.util.JacksonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads/writes snapshot files in a directory. Snapshot files are named
 * {@code snapshot-{lastSeq}.json}. Writes go through a temp file +
 * atomic-rename to make readers either see the old snapshot or the new one,
 * never a half-written file.
 */
public class SnapshotStore {

    private static final Pattern SNAPSHOT_FILE = Pattern.compile("snapshot-(\\d+)\\.json");

    private final Path dir;

    public SnapshotStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    /** Return the highest-numbered snapshot if any exist. */
    public Optional<SnapshotData> loadLatest() throws IOException {
        Optional<Path> latest = listSnapshots().max(Comparator.comparingLong(SnapshotStore::seqOf));
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        String json = Files.readString(latest.get(), StandardCharsets.UTF_8);
        return Optional.of(JacksonUtil.readValue(json, SnapshotData.class));
    }

    /**
     * Write a new snapshot atomically and delete any earlier ones. Returns the
     * file that was written.
     */
    public Path write(SnapshotData snapshot) throws IOException {
        Path target = dir.resolve("snapshot-" + snapshot.lastSeq() + ".json");
        Path tmp = dir.resolve("snapshot-" + snapshot.lastSeq() + ".json.tmp");
        Files.writeString(tmp, JacksonUtil.writeValue(snapshot), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // Delete older snapshots — we only need the latest one for recovery.
        try (Stream<Path> stream = listSnapshots()) {
            stream
                .filter(p -> !p.equals(target))
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
        }
        return target;
    }

    private Stream<Path> listSnapshots() throws IOException {
        return Files.list(dir).filter(p -> SNAPSHOT_FILE.matcher(p.getFileName().toString()).matches());
    }

    private static long seqOf(Path p) {
        Matcher m = SNAPSHOT_FILE.matcher(p.getFileName().toString());
        m.matches();
        return Long.parseLong(m.group(1));
    }
}
