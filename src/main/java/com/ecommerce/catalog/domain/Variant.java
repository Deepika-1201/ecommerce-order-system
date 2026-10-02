package com.ecommerce.catalog.domain;

import java.util.Map;
import java.util.UUID;

/** A sellable SKU: one value per option dimension, in the product's dimension order. */
public record Variant(UUID id, String sku, Map<String, String> optionValues, long pricePaise, VariantStatus status) {
}
