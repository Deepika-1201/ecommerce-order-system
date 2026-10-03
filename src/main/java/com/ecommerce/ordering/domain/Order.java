package com.ecommerce.ordering.domain;

import com.ecommerce.pricing.Quote;
import com.ecommerce.pricing.TaxRegime;
import com.ecommerce.shared.IndianState;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An order as customers and support see it (LLD §6.4): its status and reason, the copies taken from the quote at
 * placement, which never change, the payment and the refund. The addresses are snapshot ids (ADR-023).
 */
public record Order(
        UUID id,
        String number,
        UUID customerId,
        UUID quoteId,
        OrderStatus status,
        OrderReason reason,
        String shortSku,
        TaxRegime taxRegime,
        IndianState supplyState,
        IndianState deliveryState,
        UUID couponId,
        String couponCode,
        List<Line> lines,
        Quote.Shipping shipping,
        Quote.Totals totals,
        UUID deliveryAddressId,
        UUID billingAddressId,
        UUID paymentId,
        String checkoutUrl,
        Long refundAmountPaise,
        RefundStatus refundStatus,
        Instant placedAt,
        Instant updatedAt) {

    public Order {
        lines = List.copyOf(lines);
    }

    /** A quote line as the order keeps it. */
    public record Line(
            String sku,
            UUID productId,
            UUID variantId,
            String title,
            Map<String, String> optionValues,
            String imageKey,
            int quantity,
            long unitPricePaise,
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

        static Line copyOf(Quote.Line line) {
            return new Line(line.sku(), line.productId(), line.variantId(), line.title(), line.optionValues(),
                    line.imageKey(), line.quantity(), line.unitPricePaise(), line.grossPaise(), line.discountPaise(),
                    line.amountPaise(), line.taxableValuePaise(), line.gstRateBps(), line.cgstPaise(),
                    line.sgstPaise(), line.igstPaise());
        }
    }
}
