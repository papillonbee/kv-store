package com.kvstore.http;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * RequestLoggingFilter — one INFO line per HTTP exchange:
 * <pre>
 *   PUT /kv/foo → 200 (4 ms)  req={"v":1}  resp={"key":"foo","value":{"v":1},"version":0}
 * </pre>
 *
 * <p>Active by default in every profile (node and router). Disable with
 * {@code --kvstore.log-requests=false}.
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>{@link OncePerRequestFilter} runs once per logical request (skips
 *       async/error re-dispatches).</li>
 *   <li>{@link ContentCachingRequestWrapper} caches the body bytes as the
 *       downstream stack reads them, so we can log the body <em>after</em> the
 *       chain runs without consuming the input stream the controller needs.</li>
 *   <li>{@link ContentCachingResponseWrapper} buffers the response so we can
 *       inspect it; we must call {@code copyBodyToResponse()} or the client
 *       gets an empty body.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(value = "kvstore.log-requests", havingValue = "true", matchIfMissing = true)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** Bodies longer than this are truncated in the log line. */
    private static final int MAX_BODY_CHARS = 500;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {

        ContentCachingRequestWrapper  wrappedReq  = new ContentCachingRequestWrapper(req);
        ContentCachingResponseWrapper wrappedResp = new ContentCachingResponseWrapper(resp);

        long startNs = System.nanoTime();
        try {
            chain.doFilter(wrappedReq, wrappedResp);
        } finally {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            logExchange(wrappedReq, wrappedResp, elapsedMs);
            // Must flush the cached body back to the real response stream —
            // otherwise the client sees an empty body.
            wrappedResp.copyBodyToResponse();
        }
    }

    private static void logExchange(ContentCachingRequestWrapper req,
                                    ContentCachingResponseWrapper resp,
                                    long elapsedMs) {
        if (!log.isInfoEnabled()) {
            return;
        }
        String method   = req.getMethod();
        String path     = req.getRequestURI();
        String query    = req.getQueryString();
        int    status   = resp.getStatus();
        String reqBody  = preview(req.getContentAsByteArray(), req.getCharacterEncoding());
        String respBody = preview(resp.getContentAsByteArray(), resp.getCharacterEncoding());

        StringBuilder line = new StringBuilder()
            .append(method).append(' ').append(path);
        if (query != null && !query.isEmpty()) {
            line.append('?').append(query);
        }
        line.append(" → ").append(status).append(" (").append(elapsedMs).append(" ms)");
        if (!reqBody.isEmpty()) {
            line.append("  req=").append(reqBody);
        }
        if (!respBody.isEmpty()) {
            line.append("  resp=").append(respBody);
        }
        log.info("{}", line);
    }

    private static String preview(byte[] body, String encoding) {
        if (body == null || body.length == 0) {
            return "";
        }
        String s;
        try {
            s = new String(body, encoding != null ? encoding : StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "[" + body.length + " bytes; unreadable]";
        }
        if (s.length() > MAX_BODY_CHARS) {
            return s.substring(0, MAX_BODY_CHARS) + "…[" + (s.length() - MAX_BODY_CHARS) + " more]";
        }
        return s;
    }
}
