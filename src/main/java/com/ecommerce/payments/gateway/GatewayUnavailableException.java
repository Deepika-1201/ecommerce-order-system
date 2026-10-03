package com.ecommerce.payments.gateway;

/**
 * The gateway could not answer now: a timeout, a connection failure, {@code 5xx}, {@code 429}, or the same key still
 * in progress. The outcome may be unknown, so the call is repeated with the same key (LLD §7.3).
 */
public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message) {
        super(message);
    }

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
