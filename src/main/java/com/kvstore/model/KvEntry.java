package com.kvstore.model;


import com.fasterxml.jackson.databind.JsonNode;

/**
 * KvEntry
 *
 * @author papan.yongmalwong
 * @version KvEntry.java v1.0 2026-06-07
 */
public class KvEntry {

    private JsonNode value;

    private long version;

    public KvEntry(JsonNode value, long version) {
        this.value = value;
        this.version = version;
    }

    public JsonNode getValue() {
        return value;
    }

    public long getVersion() {
        return version;
    }

    public void setValue(JsonNode value) {
        this.value = value;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    private final Object lock = new Object();

    public Object getLock() {
        return lock;
    }
}
