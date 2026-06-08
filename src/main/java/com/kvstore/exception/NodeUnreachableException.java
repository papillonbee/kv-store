package com.kvstore.exception;

/**
 * Thrown by the router when a downstream node cannot be contacted or returns
 * a non-HTTP error. Maps to HTTP 502.
 */
public class NodeUnreachableException extends KvStoreException {

    public NodeUnreachableException(String message) {
        super(message);
    }

    public NodeUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public int httpStatus() {
        return 502;
    }
}
