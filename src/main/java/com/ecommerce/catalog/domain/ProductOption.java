package com.ecommerce.catalog.domain;

import java.util.List;

/** One option dimension of a product, such as {@code size} with its values in display order. */
public record ProductOption(String name, List<String> values) {

    public ProductOption {
        values = List.copyOf(values);
    }
}
