package com.kvstore.persistence;


import com.kvstore.util.JacksonUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only write-ahead log. One JSON object per line. Single writer (the
 * service's compute() lambda), readers only on startup recovery.
 *
 * <p>Durability note: {@link BufferedWriter#flush()} pushes bytes into the OS
 * page cache. To survive a power cut as well as a process crash we'd also need
 * {@code FileChannel.force(true)} per write — that's a 10×+ latency hit, so
 * this implementation does flush-only by default. Group commit / fsync is a
 * tuning knob worth adding later.
 */
public class Wal implements AutoCloseable {

    private final Path file;
    private BufferedWriter writer;

    public Wal(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        this.writer = openForAppend();
    }

    /** Append a single entry and flush. Caller must hold whatever lock orders writes. */
    public synchronized void append(WalEntry entry) throws IOException {
        writer.write(JacksonUtil.writeValue(entry));
        writer.newLine();
        writer.flush();
    }

    /** Read every entry currently in the file. Skips an unparseable trailing line (typical of a crash mid-write). */
    public List<WalEntry> readAll() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<WalEntry> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            try {
                out.add(JacksonUtil.readValue(line, WalEntry.class));
            } catch (RuntimeException e) {
                // Only the LAST line is allowed to be corrupt (crash mid-write).
                // Anything else is genuine corruption and we should refuse to boot.
                if (i != lines.size() - 1) {
                    throw new IOException("WAL corrupt at line " + (i + 1) + ": " + e.getMessage(), e);
                }
            }
        }
        return out;
    }

    /** Truncate the WAL to zero bytes (called after a successful snapshot). */
    public synchronized void truncate() throws IOException {
        writer.close();
        Files.write(file, new byte[0]); // truncate
        writer = openForAppend();
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }

    private BufferedWriter openForAppend() throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
