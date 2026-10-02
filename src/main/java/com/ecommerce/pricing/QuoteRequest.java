package com.ecommerce.pricing;

import com.ecommerce.shared.IndianState;
import java.util.List;
import java.util.UUID;

/**
 * A cart to price. {@code customerId} is {@code null} for a guest cart; {@code addedPricePaise}, the list price when
 * the line was added, lets the quote show price changes (FR-CHK1).
 */
public record QuoteRequest(UUID cartId, UUID customerId, List<Line> lines, String couponCode,
        IndianState deliveryState) {

    public QuoteRequest {
        lines = List.copyOf(lines);
    }

    public record Line(String sku, int quantity, Long addedPricePaise) {
    }
}
