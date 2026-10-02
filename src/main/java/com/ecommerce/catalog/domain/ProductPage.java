package com.ecommerce.catalog.domain;

import java.util.List;
import java.util.Optional;

/** One page of products, and the cursor of the next page if there is one. */
public record ProductPage(List<ProductSummary> items, Optional<PageCursor> next) {
}
