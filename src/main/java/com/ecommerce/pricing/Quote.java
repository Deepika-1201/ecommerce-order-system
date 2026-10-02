package com.ecommerce.pricing;

import com.ecommerce.shared.IndianState;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable priced cart, valid until {@code validUntil} (LLD §4.5). Amounts are paise and include GST; every
 * total is the sum of its parts (ADR-019). Lines carry what an order snapshot needs.
 */
public record Quote(
        UUID id,
        UUID cartId,
        UUID customerId,
        UUID couponId,
        String couponCode,
        IndianState supplyState,
        IndianState deliveryState,
        TaxRegime taxRegime,
        List<Line> lines,
        Shipping shipping,
        Totals totals,
        Instant validUntil,
        Instant createdAt) {

    public Quote {
        lines = List.copyOf(lines);
    }

    /** {@code amount = gross − discount = taxable value + CGST + SGST + IGST}. */
    public record Line(
            String sku,
            UUID productId,
            UUID variantId,
            String title,
            Map<String, String> optionValues,
            String imageKey,
            int quantity,
            long unitPricePaise,
            Long previousUnitPricePaise,
            long grossPaise,
            long discountPaise,
            long amountPaise,
            long taxableValuePaise,
            int gstRateBps,
            long cgstPaise,
            long sgstPaise,
            long igstPaise) {

        public Line {
            optionValues = Collections.unmodifiableMap(new LinkedHashMap<>(optionValues));
        }
    }

    public record Shipping(long feePaise, long taxableValuePaise, int gstRateBps, long cgstPaise, long sgstPaise,
            long igstPaise) {
    }

    /** {@code grand total = goods + shipping = taxable value + tax}; {@code goods = gross − discount}. */
    public record Totals(
            long grossPaise,
            long discountPaise,
            long goodsPaise,
            long shippingPaise,
            long taxableValuePaise,
            long cgstPaise,
            long sgstPaise,
            long igstPaise,
            long grandTotalPaise) {

        public long taxPaise() {
            return cgstPaise + sgstPaise + igstPaise;
        }
    }
}
