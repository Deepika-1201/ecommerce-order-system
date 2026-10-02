package com.ecommerce.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The product aggregate with its variants and images; {@code version} is the admin ETag (LLD §3.5). */
public record Product(
        UUID id,
        Category category,
        String title,
        String description,
        GstCategory gstCategory,
        List<ProductOption> options,
        ProductStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<Variant> variants,
        List<ProductImage> images) {

    public long activeVariants() {
        return variants.stream().filter(variant -> variant.status() == VariantStatus.ACTIVE).count();
    }
}
