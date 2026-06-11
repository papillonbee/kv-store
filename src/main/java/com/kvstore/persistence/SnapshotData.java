package com.kvstore.persistence;


import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The on-disk snapshot format: a single JSON object containing every key/value
 * pair the service held at {@code lastSeq}. After loading a snapshot, replay
 * only WAL entries with {@code seq > lastSeq}.
 */
public record SnapshotData(long lastSeq, List<Entry> entries) {

    public record Entry(String key, JsonNode value, long version) {}
}
