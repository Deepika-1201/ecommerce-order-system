package com.ecommerce.payments.gateway;

/**
 * The gateway refused the request (a {@code 4xx} problem with a {@code code}). It keeps that answer for the
 * idempotency key, so the same request with the same key is refused again (LLD §7.3).
 */
public class GatewayRefusedException extends RuntimeException {

    private final int status;
    private final String code;

    public GatewayRefusedException(int status, String code, String detail) {
        super(status + " " + code + (detail == null ? "" : ": " + detail));
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
