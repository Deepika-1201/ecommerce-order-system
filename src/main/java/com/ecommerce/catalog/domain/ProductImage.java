package com.ecommerce.catalog.domain;

import java.util.UUID;

/** {@code position} orders the gallery; it is set when the upload is confirmed. */
public record ProductImage(
        UUID id,
        String objectKey,
        String contentType,
        long sizeBytes,
        String altText,
        ImageStatus status,
        Integer position) {
}
