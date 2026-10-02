package com.ecommerce.platform;

import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;

/** Runs API requests with an {@code Idempotency-Key} at most once per key (ADR-010, LLD §2.8). */
public interface IdempotentRequests {

    String HEADER = "Idempotency-Key";

    /**
     * Runs {@code action} in one transaction with the key, storing its response, or replays the stored response of
     * an earlier request with the same key and body. If {@code action} throws, the key is released.
     *
     * @throws ApiException {@code 400} for a missing or malformed key, {@code 422} for a key reused with a different
     *     body, {@code 409} while another request with the key is running
     */
    ResponseEntity<?> execute(IdempotentRequest request, Supplier<? extends ResponseEntity<?>> action);
}
