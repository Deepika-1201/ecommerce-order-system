package com.ecommerce.cart.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A cart as shoppers see it: current prices, which are indicative (only a quote binds), and whether each line can
 * still be bought. The subtotal counts only lines that can be.
 */
public record CartView(long version, List<Line> lines, String couponCode, long subtotalPaise, Instant expiresAt) {

    public CartView {
        lines = List.copyOf(lines);
    }

    static CartView empty() {
        return new CartView(0, List.of(), null, 0, null);
    }

    /** {@code addedUnitPricePaise} is set only when the price changed since the line was added. */
    public record Line(
            String sku,
            UUID productId,
            String title,
            Map<String, String> optionValues,
            String imageKey,
            int quantity,
            long unitPricePaise,
            Long addedUnitPricePaise,
            long lineTotalPaise,
            boolean available) {

        public Line {
            optionValues = Collections.unmodifiableMap(new LinkedHashMap<>(optionValues));
        }
    }
}
