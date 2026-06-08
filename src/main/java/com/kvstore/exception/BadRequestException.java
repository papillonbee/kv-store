package com.kvstore.exception;

/**
 * Thrown when the caller supplies input the service cannot process — for example
 * a request body that is not valid JSON. Maps to HTTP 400.
 */
public class BadRequestException extends KvStoreException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public int httpStatus() {
        return 400;
    }
}
