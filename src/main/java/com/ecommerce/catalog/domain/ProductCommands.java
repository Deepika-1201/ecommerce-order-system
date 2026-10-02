package com.ecommerce.catalog.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Inputs to the product commands; a {@code null} in a change leaves the field as it is. */
public final class ProductCommands {

    private ProductCommands() {
    }

    public record NewProduct(String title, String description, UUID categoryId, GstCategory gstCategory,
            List<ProductOption> options) {
    }

    public record ProductChanges(String title, String description, UUID categoryId, GstCategory gstCategory,
            List<ProductOption> options) {
    }

    public record NewVariant(String sku, Map<String, String> optionValues, long pricePaise) {
    }

    public record VariantChanges(Long pricePaise, VariantStatus status) {
    }
}
