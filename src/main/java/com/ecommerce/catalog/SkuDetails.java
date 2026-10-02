package com.ecommerce.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A SKU as it is now. {@code purchasable} means an active variant of an active product. The list price includes GST
 * (ADR-018); {@code imageKey}, the product's first image, may be {@code null}.
 */
public record SkuDetails(
        String sku,
        UUID productId,
        UUID variantId,
        String title,
        Map<String, String> optionValues,
        String imageKey,
        long listPricePaise,
        GstCategory gstCategory,
        boolean purchasable) {

    public SkuDetails {
        optionValues = Collections.unmodifiableMap(new LinkedHashMap<>(optionValues));
    }
}
