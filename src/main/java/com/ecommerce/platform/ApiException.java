package com.ecommerce.platform;

import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** An error a module reports to API clients as a problem detail with a stable {@code code} (LLD §1.5). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Duration retryAfter;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, null);
    }

    /** An error the client may retry after the given delay, sent as {@code Retry-After}. */
    public ApiException(HttpStatus status, String code, String detail, Duration retryAfter) {
        super(detail);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
