package com.ecommerce.platform;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** How to upload: send {@code method} to {@code url} with exactly these headers and the announced number of bytes. */
public record PresignedUpload(URI url, String method, Map<String, String> headers, Instant expiresAt) {

    public PresignedUpload {
        headers = Map.copyOf(headers);
    }
}
