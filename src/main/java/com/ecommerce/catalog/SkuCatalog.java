package com.ecommerce.catalog;

import java.util.Collection;
import java.util.Map;

/** What Cart and Pricing need to know about SKUs (LLD §4.2). */
public interface SkuCatalog {

    /** Current details, keyed by SKU code; codes are matched case-insensitively, and unknown ones are absent. */
    Map<String, SkuDetails> find(Collection<String> skus);
}
