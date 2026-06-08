package com.kvstore.exception;

/**
 * Thrown when an {@code ifVersion} precondition does not match the current state
 * of a key — either the key does not exist, or its current version differs from
 * the one supplied by the caller. Maps to HTTP 409.
 */
public class VersionConflictException extends KvStoreException {

    private final Long currentVersion;
    private final long expectedVersion;

    public VersionConflictException(Long currentVersion, long expectedVersion) {
        super(String.format("ifVersion=%d does not match current=%s", expectedVersion, currentVersion));
        this.currentVersion = currentVersion;
        this.expectedVersion = expectedVersion;
    }

    public Long currentVersion() {
        return currentVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    @Override
    public int httpStatus() {
        return 409;
    }
}
