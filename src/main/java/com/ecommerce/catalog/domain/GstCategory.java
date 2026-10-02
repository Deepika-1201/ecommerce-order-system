package com.ecommerce.catalog.domain;

/** How a product is classified for GST; Pricing maps each category to its rate rule (LLD §3.5). */
public enum GstCategory {
    STANDARD,
    REDUCED,
    EXEMPT,
    APPAREL,
    FOOTWEAR,
    DEMERIT
}
