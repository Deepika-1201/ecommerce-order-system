package com.ecommerce.pricing.domain;

import com.ecommerce.catalog.GstCategory;
import com.ecommerce.shared.IndianState;
import java.util.List;

/** Everything a quote's arithmetic depends on; prices include GST. */
record PricingInput(
        List<Line> lines,
        DiscountRule discount,
        ShippingRule shipping,
        IndianState supplyState,
        IndianState deliveryState) {

    PricingInput {
        lines = List.copyOf(lines);
    }

    record Line(int quantity, long unitPricePaise, GstCategory gstCategory) {
    }

    /** A GST-inclusive fee, waived when the goods total after discount reaches {@code freeFromPaise}. */
    record ShippingRule(long feePaise, long freeFromPaise) {
    }
}
