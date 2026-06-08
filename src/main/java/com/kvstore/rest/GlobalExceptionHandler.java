package com.kvstore.rest;


import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kvstore.exception.BadRequestException;
import com.kvstore.exception.KvStoreException;
import com.kvstore.util.JacksonUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * GlobalExceptionHandler — turns service exceptions into JSON responses with
 * the correct status code, so controllers never have to think about it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(KvStoreException.class)
    public ResponseEntity<ObjectNode> handleKvStoreException(KvStoreException e) {
        return ResponseEntity.status(e.httpStatus()).body(errorBody(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ObjectNode> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        // e.g. ?ifVersion=abc → Spring fails to coerce to Long → translate to our 400 shape
        String msg = e.getName() + " must be of type " + (e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "valid");
        return handleKvStoreException(new BadRequestException(msg, e));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ObjectNode> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        // DELETE on /kv/{key} (no @DeleteMapping) → must return 405, not 500.
        return ResponseEntity.status(405).body(errorBody(e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ObjectNode> handleNoResource(NoResourceFoundException e) {
        // Spring throws this when a path matches no controller AND no static
        // resource. The right status is 404, not 500.  Most common cause:
        // the @Profile-gated controller isn't registered (wrong profile active).
        return ResponseEntity.status(404).body(errorBody("not found: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ObjectNode> handleAny(Exception e) {
        return ResponseEntity.status(500).body(errorBody("internal error: " + e.getMessage()));
    }

    private static ObjectNode errorBody(String message) {
        ObjectNode body = JacksonUtil.objectNode();
        body.put("error", message);
        return body;
    }
}
