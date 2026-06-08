package com.kvstore.exception;

/**
 * Base type for all domain errors the HTTP layer should translate to a specific
 * status code. Anything else thrown by the service should be treated as 500.
 */
public abstract class KvStoreException extends RuntimeException {

    protected KvStoreException(String message) {
        super(message);
    }

    protected KvStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract int httpStatus();
}
