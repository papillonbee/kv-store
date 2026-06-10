package com.kvstore.persistence;


import com.kvstore.util.JacksonUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests for the append-only WAL file. No Spring, no service.
 */
class WalTest {

    @Test
    void emptyFileReadsAsEmptyList(@TempDir Path dir) throws IOException {
        try (Wal wal = new Wal(dir.resolve("wal.log"))) {
            Assertions.assertTrue(wal.readAll().isEmpty());
        }
    }

    @Test
    void appendThenReadAllRoundtrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wal.log");
        try (Wal wal = new Wal(file)) {
            wal.append(new WalEntry(1, "PUT",   "k1", JacksonUtil.parse("{\"v\":1}"), 0, false));
            wal.append(new WalEntry(2, "PUT",   "k2", JacksonUtil.parse("[1,2,3]"),    0, false));
            wal.append(new WalEntry(3, "PATCH", "k1", JacksonUtil.parse("{\"v\":2}"), 1, true));
        }
        try (Wal wal = new Wal(file)) {
            List<WalEntry> entries = wal.readAll();
            Assertions.assertEquals(3, entries.size());
            Assertions.assertEquals(1, entries.get(0).seq());
            Assertions.assertEquals("PUT", entries.get(0).op());
            Assertions.assertEquals("k1", entries.get(0).key());
            Assertions.assertEquals(JacksonUtil.parse("{\"v\":1}"), entries.get(0).value());
            Assertions.assertEquals("PATCH", entries.get(2).op());
            Assertions.assertTrue(entries.get(2).patch());
        }
    }

    @Test
    void truncateClearsTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wal.log");
        try (Wal wal = new Wal(file)) {
            wal.append(new WalEntry(1, "PUT", "k", JacksonUtil.parse("{}"), 0, false));
            wal.truncate();
            Assertions.assertTrue(wal.readAll().isEmpty());

            // And we can still append after truncate.
            wal.append(new WalEntry(2, "PUT", "k", JacksonUtil.parse("{}"), 0, false));
            Assertions.assertEquals(1, wal.readAll().size());
        }
    }

    @Test
    void corruptTrailingLineIsSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wal.log");
        try (Wal wal = new Wal(file)) {
            wal.append(new WalEntry(1, "PUT", "k", JacksonUtil.parse("{}"), 0, false));
        }
        // Simulate a crash mid-write: an incomplete trailing line.
        Files.writeString(file,
            Files.readString(file, StandardCharsets.UTF_8) + "{\"seq\":2,\"op\":\"PU",
            StandardCharsets.UTF_8);

        try (Wal wal = new Wal(file)) {
            List<WalEntry> entries = wal.readAll();
            Assertions.assertEquals(1, entries.size(), "corrupt tail line should be silently dropped");
            Assertions.assertEquals(1, entries.get(0).seq());
        }
    }

    @Test
    void corruptMidlineLineFailsRecovery(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wal.log");
        try (Wal wal = new Wal(file)) {
            wal.append(new WalEntry(1, "PUT", "k1", JacksonUtil.parse("{}"), 0, false));
            wal.append(new WalEntry(2, "PUT", "k2", JacksonUtil.parse("{}"), 0, false));
        }
        // Corrupt the FIRST line — that's real corruption, not a crashed write.
        String contents = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, "garbage{not json\n" + contents.substring(contents.indexOf('\n') + 1),
            StandardCharsets.UTF_8);

        try (Wal wal = new Wal(file)) {
            Assertions.assertThrows(IOException.class, wal::readAll);
        }
    }
}
