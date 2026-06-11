package com.kvstore.persistence;


import com.fasterxml.jackson.databind.JsonNode;

/**
 * One write logged to the WAL. Serialised as a single JSON line. The {@code seq}
 * field provides a monotonic ordering and is the cursor used to truncate WAL
 * entries after a snapshot.
 *
 * <p>{@code op} is "PUT" or "PATCH". {@code patch} duplicates {@code op}="PATCH"
 * for serialisation symmetry, but only the value/version actually applied
 * matters during replay — both ops end up setting {@code value} and
 * {@code version}, so replay just reapplies the stored final state.
 */
public record WalEntry(
    long seq,
    String op,
    String key,
    JsonNode value,
    long version,
    boolean patch
) {}
