package com.ecommerce.catalog.domain;

/** {@code DRAFT → ACTIVE ⇄ ARCHIVED} and {@code DRAFT → ARCHIVED}; only active products are public. */
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
