package com.ecommerce.ordering.web;

import com.ecommerce.customer.AddressSnapshot;
import com.ecommerce.ordering.domain.CancelCode;
import com.ecommerce.ordering.domain.Order;
import com.ecommerce.ordering.domain.OrderReason;
import com.ecommerce.ordering.domain.OrderStatus;
import com.ecommerce.ordering.domain.OrderSummary;
import com.ecommerce.ordering.domain.OrderView;
import com.ecommerce.ordering.domain.RefundStatus;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.ObjectStorage;
import com.ecommerce.pricing.Quote;
import com.ecommerce.pricing.TaxRegime;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Request and response bodies of orders (LLD §6.9). Money is paise, GST included. */
final class OrderingApi {

    static final String CURRENCY = "INR";
    private static final String CURSOR_PREFIX = "o:";

    private OrderingApi() {
    }

    /** {@code billing_address_id} defaults to the delivery address. */
    record PlaceOrder(@NotNull UUID quoteId, @NotNull UUID deliveryAddressId, UUID billingAddressId) {
    }

    /** {@code note} is required with {@code OTHER}. */
    record SupportCancellation(@NotNull CancelCode reasonCode, @Size(max = 500) String note) {
    }

    record OrderLineResponse(
            String sku,
            UUID productId,
            UUID variantId,
            String title,
            Map<String, String> optionValues,
            URI imageUrl,
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
    }

    record OrderShippingResponse(long feePaise, long taxableValuePaise, int gstRateBps, long cgstPaise,
            long sgstPaise, long igstPaise) {
    }

    record OrderTotalsResponse(
            long grossPaise,
            long discountPaise,
            long goodsPaise,
            long shippingPaise,
            long taxableValuePaise,
            long cgstPaise,
            long sgstPaise,
            long igstPaise,
            long taxPaise,
            long grandTotalPaise) {
    }

    /** An address snapshot, as the order was placed with it (ADR-023). */
    record OrderAddressResponse(
            String recipientName,
            String phone,
            String line1,
            String line2,
            String landmark,
            String city,
            String stateCode,
            String stateName,
            String pinCode) {

        static OrderAddressResponse of(AddressSnapshot address) {
            return new OrderAddressResponse(address.recipientName(), address.phone(), address.line1(),
                    address.line2(), address.landmark(), address.city(), address.state().code(),
                    address.state().displayName(), address.pinCode());
        }
    }

    /** The refund's progress: {@code REQUESTED} when the order asked for it, {@code INITIATED} when Payments did. */
    record OrderRefundResponse(long amountPaise, RefundStatus status) {
    }

    /**
     * {@code reason} with {@code REJECTED} and {@code CANCELLED}; {@code unavailable_sku} with {@code OUT_OF_STOCK};
     * {@code checkout_url} while {@code AWAITING_PAYMENT}; {@code customer_id} for support only.
     */
    record OrderResponse(
            UUID id,
            String number,
            UUID customerId,
            OrderStatus status,
            OrderReason reason,
            String unavailableSku,
            String couponCode,
            String supplyStateCode,
            String deliveryStateCode,
            TaxRegime taxRegime,
            String currency,
            List<OrderLineResponse> lines,
            OrderShippingResponse shipping,
            OrderTotalsResponse totals,
            OrderAddressResponse deliveryAddress,
            OrderAddressResponse billingAddress,
            URI checkoutUrl,
            OrderRefundResponse refund,
            Instant placedAt,
            Instant updatedAt) {

        static OrderResponse forCustomer(OrderView view, ObjectStorage storage) {
            return of(view, null, storage);
        }

        static OrderResponse forSupport(OrderView view, ObjectStorage storage) {
            return of(view, view.order().customerId(), storage);
        }

        private static OrderResponse of(OrderView view, UUID customerId, ObjectStorage storage) {
            Order order = view.order();
            Quote.Shipping shipping = order.shipping();
            Quote.Totals totals = order.totals();
            return new OrderResponse(order.id(), order.number(), customerId, order.status(), order.reason(),
                    order.shortSku(), order.couponCode(), order.supplyState().code(), order.deliveryState().code(),
                    order.taxRegime(), CURRENCY,
                    order.lines().stream().map(line -> new OrderLineResponse(line.sku(), line.productId(),
                            line.variantId(), line.title(), line.optionValues(),
                            line.imageKey() == null ? null : storage.publicUrl(line.imageKey()), line.quantity(),
                            line.unitPricePaise(), line.grossPaise(), line.discountPaise(), line.amountPaise(),
                            line.taxableValuePaise(), line.gstRateBps(), line.cgstPaise(), line.sgstPaise(),
                            line.igstPaise())).toList(),
                    new OrderShippingResponse(shipping.feePaise(), shipping.taxableValuePaise(),
                            shipping.gstRateBps(), shipping.cgstPaise(), shipping.sgstPaise(), shipping.igstPaise()),
                    new OrderTotalsResponse(totals.grossPaise(), totals.discountPaise(), totals.goodsPaise(),
                            totals.shippingPaise(), totals.taxableValuePaise(), totals.cgstPaise(), totals.sgstPaise(),
                            totals.igstPaise(), totals.taxPaise(), totals.grandTotalPaise()),
                    OrderAddressResponse.of(view.deliveryAddress()), OrderAddressResponse.of(view.billingAddress()),
                    order.status() == OrderStatus.AWAITING_PAYMENT && order.checkoutUrl() != null
                            ? URI.create(order.checkoutUrl())
                            : null,
                    order.refundStatus() == null ? null
                            : new OrderRefundResponse(order.refundAmountPaise(), order.refundStatus()),
                    order.placedAt(), order.updatedAt());
        }
    }

    record OrderSummaryResponse(
            UUID id,
            String number,
            OrderStatus status,
            OrderReason reason,
            int itemCount,
            long grandTotalPaise,
            String currency,
            Instant placedAt) {

        static OrderSummaryResponse of(OrderSummary order) {
            return new OrderSummaryResponse(order.id(), order.number(), order.status(), order.reason(),
                    order.itemCount(), order.grandTotalPaise(), CURRENCY, order.placedAt());
        }
    }

    /** {@code next_cursor} is absent on the last page. */
    record OrderList(List<OrderSummaryResponse> items, String nextCursor) {
    }

    static String cursor(UUID lastOrderId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((CURSOR_PREFIX + lastOrderId).getBytes(StandardCharsets.UTF_8));
    }

    /** The order a cursor points after; {@code null} for none. */
    static UUID afterCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (plain.startsWith(CURSOR_PREFIX)) {
                return UUID.fromString(plain.substring(CURSOR_PREFIX.length()));
            }
        } catch (IllegalArgumentException malformed) {
            // Falls through to the same answer as any other cursor this API did not issue.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "This cursor was not issued by this API.");
    }
}
