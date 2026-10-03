package com.ecommerce.ordering.domain;

import com.ecommerce.inventory.StockReservations;
import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.pricing.Quote;
import com.ecommerce.pricing.TaxRegime;
import com.ecommerce.shared.IndianState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL for orders, their lines and their processes (LLD §6.10). Every change locks the order's row first, then its
 * process's, so the order and its process change together, one change at a time.
 */
@Repository
class OrderRepository {

    private static final TypeReference<List<Map<String, String>>> OPTION_VALUES = new TypeReference<>() { };

    private static final String PROCESS_COLUMNS = """
            o.id, o.customer_id, o.coupon_id, o.grand_total_paise, o.delivery_address_id, o.status, o.reason,
            o.short_sku, o.payment_id, o.checkout_url, o.refund_amount_paise, o.refund_status, p.step,
            p.cancel_reason, p.cancel_code, p.cancel_note, p.cancel_requested_at, p.payment_succeeded,
            p.hold_expires_at, p.deadline_at, p.attempts, p.refund_reason, p.version
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OrderRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** {@code EC} and 9 digits, for display. */
    String nextNumber() {
        long next = jdbc.sql("SELECT nextval('ordering.order_numbers')").query(Long.class).single();
        return "EC%09d".formatted(next);
    }

    boolean quoteOrdered(UUID quoteId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ordering.orders WHERE quote_id = :quoteId)")
                .param("quoteId", quoteId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Inserts a placed order, its lines and its started process. {@code quote_id} is unique: a concurrent order from
     * the same quote fails with {@code DuplicateKeyException} once the other commits.
     */
    void insert(Order order, OrderProcess process, Instant now) {
        Quote.Totals totals = order.totals();
        Quote.Shipping shipping = order.shipping();
        ProcessState state = process.state();
        jdbc.sql("""
                        INSERT INTO ordering.orders (id, number, customer_id, quote_id, status, tax_regime,
                            supply_state_code, delivery_state_code, coupon_id, coupon_code, gross_paise,
                            discount_paise, goods_paise, shipping_paise, shipping_gst_rate_bps, shipping_cgst_paise,
                            shipping_sgst_paise, shipping_igst_paise, taxable_value_paise, cgst_paise, sgst_paise,
                            igst_paise, grand_total_paise, delivery_address_id, billing_address_id, version, placed_at,
                            updated_at)
                        VALUES (:id, :number, :customerId, :quoteId, :status, :taxRegime, :supplyState,
                            :deliveryState, :couponId, :couponCode, :gross, :discount, :goods, :shipping,
                            :shippingRate, :shippingCgst, :shippingSgst, :shippingIgst, :taxable, :cgst, :sgst, :igst,
                            :grandTotal, :deliveryAddressId, :billingAddressId, :version, :now, :now)
                        """)
                .param("id", order.id())
                .param("number", order.number())
                .param("customerId", order.customerId())
                .param("quoteId", order.quoteId())
                .param("status", state.status().name())
                .param("taxRegime", order.taxRegime().name())
                .param("supplyState", order.supplyState().code())
                .param("deliveryState", order.deliveryState().code())
                .param("couponId", order.couponId())
                .param("couponCode", order.couponCode())
                .param("gross", totals.grossPaise())
                .param("discount", totals.discountPaise())
                .param("goods", totals.goodsPaise())
                .param("shipping", totals.shippingPaise())
                .param("shippingRate", shipping.gstRateBps())
                .param("shippingCgst", shipping.cgstPaise())
                .param("shippingSgst", shipping.sgstPaise())
                .param("shippingIgst", shipping.igstPaise())
                .param("taxable", totals.taxableValuePaise())
                .param("cgst", totals.cgstPaise())
                .param("sgst", totals.sgstPaise())
                .param("igst", totals.igstPaise())
                .param("grandTotal", totals.grandTotalPaise())
                .param("deliveryAddressId", order.deliveryAddressId())
                .param("billingAddressId", order.billingAddressId())
                .param("version", process.sequence())
                .param("now", utc(now))
                .update();
        for (int i = 0; i < order.lines().size(); i++) {
            insertLine(order.id(), i + 1, order.lines().get(i));
        }
        jdbc.sql("""
                        INSERT INTO ordering.order_processes (order_id, step, payment_succeeded, deadline_at, attempts,
                            version, created_at, updated_at)
                        VALUES (:orderId, :step, false, :deadlineAt, :attempts, :version, :now, :now)
                        """)
                .param("orderId", order.id())
                .param("step", state.step().name())
                .param("deadlineAt", utc(state.deadlineAt()))
                .param("attempts", state.attempts())
                .param("version", process.sequence())
                .param("now", utc(now))
                .update();
    }

    /** Locks the order and its process until the transaction ends. */
    Optional<OrderProcess> lockProcess(UUID orderId, OrderingProperties settings) {
        return jdbc.sql("SELECT " + PROCESS_COLUMNS + """
                        FROM ordering.orders o JOIN ordering.order_processes p ON p.order_id = o.id
                        WHERE o.id = :id
                        FOR UPDATE
                        """)
                .param("id", orderId)
                .query((row, rowNumber) -> process(row, settings))
                .optional();
    }

    /** Saves the process's change, with the version it was decided at plus one. */
    void save(OrderProcess process, Instant now) {
        ProcessState state = process.state();
        Cancellation cancellation = state.cancellation();
        jdbc.sql("""
                        UPDATE ordering.orders
                        SET status = :status, reason = :reason, short_sku = :shortSku, payment_id = :paymentId,
                            checkout_url = :checkoutUrl, refund_amount_paise = :refundAmount,
                            refund_status = :refundStatus, version = :version, updated_at = :now
                        WHERE id = :id
                        """)
                .param("status", state.status().name())
                .param("reason", name(state.reason()))
                .param("shortSku", state.shortSku())
                .param("paymentId", state.paymentId())
                .param("checkoutUrl", state.checkoutUrl())
                .param("refundAmount", state.refundAmountPaise())
                .param("refundStatus", name(state.refundStatus()))
                .param("version", process.sequence())
                .param("now", utc(now))
                .param("id", process.orderId())
                .update();
        jdbc.sql("""
                        UPDATE ordering.order_processes
                        SET step = :step, cancel_reason = :cancelReason, cancel_code = :cancelCode,
                            cancel_note = :cancelNote, cancel_requested_at = :cancelRequestedAt,
                            payment_succeeded = :paymentSucceeded, hold_expires_at = :holdExpiresAt,
                            deadline_at = :deadlineAt, attempts = :attempts, refund_reason = :refundReason,
                            version = :version, updated_at = :now
                        WHERE order_id = :orderId
                        """)
                .param("step", state.step().name())
                .param("cancelReason", cancellation == null ? null : cancellation.reason().name())
                .param("cancelCode", cancellation == null ? null : name(cancellation.code()))
                .param("cancelNote", cancellation == null ? null : cancellation.note())
                .param("cancelRequestedAt", cancellation == null ? null : utc(cancellation.requestedAt()))
                .param("paymentSucceeded", state.paymentSucceeded())
                .param("holdExpiresAt", utc(state.holdExpiresAt()))
                .param("deadlineAt", utc(state.deadlineAt()))
                .param("attempts", state.attempts())
                .param("refundReason", name(state.refundReason()))
                .param("version", process.sequence())
                .param("now", utc(now))
                .param("orderId", process.orderId())
                .update();
    }

    /** Up to {@code limit} processes whose deadline has passed, the longest overdue first (LLD §6.7). */
    List<UUID> overdue(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT order_id FROM ordering.order_processes
                        WHERE step <> 'DONE' AND deadline_at <= :now
                        ORDER BY deadline_at
                        LIMIT :limit
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    Optional<Order> find(UUID orderId) {
        return jdbc.sql("SELECT * FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query((row, rowNumber) -> order(row, lines(orderId)))
                .optional();
    }

    /** A customer's orders, newest first, after the order {@code before} if there is one. */
    List<OrderSummary> summaries(UUID customerId, UUID before, int limit) {
        return jdbc.sql("""
                        SELECT o.id, o.number, o.status, o.reason, o.grand_total_paise, o.placed_at,
                               (SELECT sum(quantity) FROM ordering.order_lines l WHERE l.order_id = o.id) AS items
                        FROM ordering.orders o
                        WHERE o.customer_id = :customerId
                        """ + (before == null ? "" : "AND o.id < :before\n") + """
                        ORDER BY o.id DESC
                        LIMIT :limit
                        """)
                .params(before == null
                        ? Map.of("customerId", customerId, "limit", limit)
                        : Map.of("customerId", customerId, "before", before, "limit", limit))
                .query((row, rowNumber) -> new OrderSummary(
                        row.getObject("id", UUID.class),
                        row.getString("number"),
                        OrderStatus.valueOf(row.getString("status")),
                        enumOrNull(OrderReason.class, row.getString("reason")),
                        row.getInt("items"),
                        row.getLong("grand_total_paise"),
                        row.getObject("placed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private void insertLine(UUID orderId, int lineNo, Order.Line line) {
        jdbc.sql("""
                        INSERT INTO ordering.order_lines (order_id, line_no, sku, product_id, variant_id, title,
                            option_values, image_key, quantity, unit_price_paise, gross_paise, discount_paise,
                            amount_paise, taxable_value_paise, gst_rate_bps, cgst_paise, sgst_paise, igst_paise)
                        VALUES (:orderId, :lineNo, :sku, :productId, :variantId, :title, CAST(:optionValues AS jsonb),
                            :imageKey, :quantity, :unitPrice, :gross, :discount, :amount, :taxable, :rate, :cgst,
                            :sgst, :igst)
                        """)
                .param("orderId", orderId)
                .param("lineNo", lineNo)
                .param("sku", line.sku())
                .param("productId", line.productId())
                .param("variantId", line.variantId())
                .param("title", line.title())
                .param("optionValues", json.writeValueAsString(line.optionValues().entrySet().stream()
                        .map(entry -> Map.of("name", entry.getKey(), "value", entry.getValue()))
                        .toList()))
                .param("imageKey", line.imageKey())
                .param("quantity", line.quantity())
                .param("unitPrice", line.unitPricePaise())
                .param("gross", line.grossPaise())
                .param("discount", line.discountPaise())
                .param("amount", line.amountPaise())
                .param("taxable", line.taxableValuePaise())
                .param("rate", line.gstRateBps())
                .param("cgst", line.cgstPaise())
                .param("sgst", line.sgstPaise())
                .param("igst", line.igstPaise())
                .update();
    }

    private List<Order.Line> lines(UUID orderId) {
        return jdbc.sql("SELECT * FROM ordering.order_lines WHERE order_id = :orderId ORDER BY line_no")
                .param("orderId", orderId)
                .query((row, rowNumber) -> new Order.Line(
                        row.getString("sku"),
                        row.getObject("product_id", UUID.class),
                        row.getObject("variant_id", UUID.class),
                        row.getString("title"),
                        optionValues(row.getString("option_values")),
                        row.getString("image_key"),
                        row.getInt("quantity"),
                        row.getLong("unit_price_paise"),
                        row.getLong("gross_paise"),
                        row.getLong("discount_paise"),
                        row.getLong("amount_paise"),
                        row.getLong("taxable_value_paise"),
                        row.getInt("gst_rate_bps"),
                        row.getLong("cgst_paise"),
                        row.getLong("sgst_paise"),
                        row.getLong("igst_paise")))
                .list();
    }

    private List<StockReservations.Line> stockLines(UUID orderId) {
        return jdbc.sql("SELECT sku, quantity FROM ordering.order_lines WHERE order_id = :orderId ORDER BY line_no")
                .param("orderId", orderId)
                .query((row, rowNumber) -> new StockReservations.Line(row.getString("sku"), row.getInt("quantity")))
                .list();
    }

    private Map<String, String> optionValues(String stored) {
        Map<String, String> values = new LinkedHashMap<>();
        json.readValue(stored, OPTION_VALUES).forEach(pair -> values.put(pair.get("name"), pair.get("value")));
        return values;
    }

    private OrderProcess process(ResultSet row, OrderingProperties settings) throws SQLException {
        UUID orderId = row.getObject("id", UUID.class);
        OrderFacts facts = new OrderFacts(orderId, row.getObject("customer_id", UUID.class),
                row.getObject("coupon_id", UUID.class), row.getLong("grand_total_paise"), stockLines(orderId),
                row.getObject("delivery_address_id", UUID.class));
        String cancelReason = row.getString("cancel_reason");
        Cancellation cancellation = cancelReason == null ? null : new Cancellation(OrderReason.valueOf(cancelReason),
                enumOrNull(CancelCode.class, row.getString("cancel_code")), row.getString("cancel_note"),
                instant(row, "cancel_requested_at"));
        ProcessState state = new ProcessState(
                OrderStatus.valueOf(row.getString("status")),
                enumOrNull(OrderReason.class, row.getString("reason")),
                row.getString("short_sku"),
                row.getObject("payment_id", UUID.class),
                row.getString("checkout_url"),
                row.getObject("refund_amount_paise", Long.class),
                enumOrNull(RefundStatus.class, row.getString("refund_status")),
                enumOrNull(RefundReason.class, row.getString("refund_reason")),
                Step.valueOf(row.getString("step")),
                cancellation,
                row.getBoolean("payment_succeeded"),
                instant(row, "hold_expires_at"),
                instant(row, "deadline_at"),
                row.getInt("attempts"),
                row.getLong("version"));
        return new OrderProcess(facts, state, settings);
    }

    private static Order order(ResultSet row, List<Order.Line> lines) throws SQLException {
        long shipping = row.getLong("shipping_paise");
        long shippingCgst = row.getLong("shipping_cgst_paise");
        long shippingSgst = row.getLong("shipping_sgst_paise");
        long shippingIgst = row.getLong("shipping_igst_paise");
        return new Order(
                row.getObject("id", UUID.class),
                row.getString("number"),
                row.getObject("customer_id", UUID.class),
                row.getObject("quote_id", UUID.class),
                OrderStatus.valueOf(row.getString("status")),
                enumOrNull(OrderReason.class, row.getString("reason")),
                row.getString("short_sku"),
                TaxRegime.valueOf(row.getString("tax_regime")),
                IndianState.fromCode(row.getString("supply_state_code")).orElseThrow(),
                IndianState.fromCode(row.getString("delivery_state_code")).orElseThrow(),
                row.getObject("coupon_id", UUID.class),
                row.getString("coupon_code"),
                lines,
                new Quote.Shipping(shipping, shipping - shippingCgst - shippingSgst - shippingIgst,
                        row.getInt("shipping_gst_rate_bps"), shippingCgst, shippingSgst, shippingIgst),
                new Quote.Totals(row.getLong("gross_paise"), row.getLong("discount_paise"), row.getLong("goods_paise"),
                        shipping, row.getLong("taxable_value_paise"), row.getLong("cgst_paise"),
                        row.getLong("sgst_paise"), row.getLong("igst_paise"), row.getLong("grand_total_paise")),
                row.getObject("delivery_address_id", UUID.class),
                row.getObject("billing_address_id", UUID.class),
                row.getObject("payment_id", UUID.class),
                row.getString("checkout_url"),
                row.getObject("refund_amount_paise", Long.class),
                enumOrNull(RefundStatus.class, row.getString("refund_status")),
                instant(row, "placed_at"),
                instant(row, "updated_at"));
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        return name == null ? null : Enum.valueOf(type, name);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
