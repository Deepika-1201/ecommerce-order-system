package com.ecommerce.platform;

/**
 * A request to run once per key: {@code scope} separates key spaces (usually the caller's id), {@code operation}
 * names the endpoint, and {@code body} is the parsed request body, which with the operation forms the fingerprint.
 */
public record IdempotentRequest(String scope, String key, String operation, Object body) {

    public static IdempotentRequest of(String scope, String key, String operation, Object body) {
        return new IdempotentRequest(scope, key, operation, body);
    }
}
