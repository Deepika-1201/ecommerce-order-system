package com.ecommerce.catalog.domain;

import java.util.UUID;

/** A product in a list: the lowest active price and the first ready image (LLD §3.7). */
public record ProductSummary(
        UUID id,
        String title,
        Category category,
        ProductStatus status,
        Long minPricePaise,
        String firstImageKey) {
}
