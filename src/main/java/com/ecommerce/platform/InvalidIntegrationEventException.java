package com.ecommerce.platform;

/** A permanent contract violation; no event body or contact information is included in the error. */
public class InvalidIntegrationEventException extends RuntimeException {

    public InvalidIntegrationEventException() {
        super("Integration event violates the supported contract");
    }
}