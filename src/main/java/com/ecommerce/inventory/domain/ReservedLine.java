package com.ecommerce.inventory.domain;

import java.util.Comparator;

/** Units of a SKU at a location, as a reservation holds them. */
record ReservedLine(String sku, String locationCode, long quantity) {

    /** Stock rows are locked in this order, so transactions never wait for each other in a cycle (LLD §5.7). */
    static final Comparator<ReservedLine> LOCK_ORDER =
            Comparator.comparing(ReservedLine::sku).thenComparing(ReservedLine::locationCode);
}
