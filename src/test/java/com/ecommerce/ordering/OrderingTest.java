package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.fulfillment.ShipmentSimulator;
import com.ecommerce.inventory.domain.WarehouseStockService;
import com.ecommerce.payments.PaymentSimulator;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import com.ecommerce.platform.messaging.DueMessages;
import com.ecommerce.platform.tasks.DueTasks;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Orders through the public API, with the real Inventory, Pricing and Payments (against the fake gateway, LLD §7.10)
 * and the simulated Fulfillment (ADR-022). Each test starts with empty stores and new customers; worker loops are
 * stopped, so tests deliver the outbox's messages and run due tasks on their own thread.
 */
public abstract class OrderingTest extends IntegrationTest {

    protected static final String KARNATAKA = "29";
    protected static final String MAHARASHTRA = "27";
    protected static final String SWEEP = "ordering.sweep-deadlines";
    private static final Caller MEERA = new Caller("meera", Set.of(CallerRole.WAREHOUSE), null, null);

    protected final String asha = TestIdentityProvider.customer("asha-" + UUID.randomUUID());
    protected final String ravi = TestIdentityProvider.customer("ravi-" + UUID.randomUUID());
    protected final String admin = TestIdentityProvider.admin("orders-admin");
    protected final String supportSubject = "sunita-" + UUID.randomUUID();
    protected final String support = TestIdentityProvider.token(supportSubject).roles("support").sign();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected PaymentSimulator payments;

    @Autowired
    protected ShipmentSimulator shipments;

    @Autowired
    private WarehouseStockService warehouse;

    @Autowired
    private Messages messages;

    @Autowired
    private TransactionTemplate transactions;

    private String categoryId;
    private int keys;

    @BeforeEach
    void emptyStores() {
        jdbc.sql("""
                TRUNCATE ordering.order_processes, ordering.order_lines, ordering.orders,
                         payments.refunds, payments.payment_records, fulfillment.tracking_events,
                         fulfillment.shipment_lines, fulfillment.shipments, fulfillment.simulated_parcels,
                         inventory.reservation_lines, inventory.reservations, inventory.stock_movements,
                         inventory.stock_items, cart.cart_lines, cart.carts, pricing.quote_lines, pricing.quotes,
                         pricing.coupon_redemptions, pricing.coupons, catalog.product_images, catalog.variants,
                         catalog.products, catalog.categories
                """).update();
        PlatformTables.clear(jdbc);
        categoryId = expect(201, call("POST", "/v1/admin/catalog/categories", admin,
                "{\"name\": \"Everything\", \"slug\": \"everything\"}")).get("id").asString();
    }

    // The catalog, stock and coupons.

    /** An active product at this GST-inclusive price, with {@code units} received into the warehouse; its SKU. */
    protected String product(String title, long pricePaise, int units) {
        String productId = expect(201, call("POST", "/v1/admin/catalog/products", admin, """
                {"title": "%s", "category_id": "%s", "gst_category": "STANDARD", "options": []}
                """.formatted(title, categoryId))).get("id").asString();
        String sku = "SKU-" + productId.substring(24).toUpperCase(Locale.ROOT);
        expect(201, call("POST", "/v1/admin/catalog/products/" + productId + "/variants", admin, """
                {"sku": "%s", "option_values": {}, "price_paise": %d}
                """.formatted(sku, pricePaise)));
        expect(200, call("POST", "/v1/admin/catalog/products/" + productId + "/activate", admin, null));
        if (units > 0) {
            warehouse.receive(MEERA, sku, units, null);
        }
        return sku;
    }

    /** A flat coupon of ₹50 with these extra JSON fields; its id. */
    protected UUID coupon(String code, String fields) {
        return UUID.fromString(expect(201, call("POST", "/v1/admin/pricing/coupons", admin,
                "{\"code\": \"" + code + "\", \"kind\": \"FLAT\", \"amount_paise\": 5000"
                        + (fields.isEmpty() ? "" : ", " + fields) + "}")).get("id").asString());
    }

    // Customers, carts and quotes.

    protected String address(String token, String stateCode) {
        return address(token, stateCode, "560038");
    }

    /** An address with this PIN code, whose last digit picks the carrier simulator's scenario (LLD §8.9). */
    protected String address(String token, String stateCode, String pinCode) {
        return expect(201, call("POST", "/v1/me/addresses", token, """
                {"recipient_name": "Asha Rao", "phone": "9876543210", "line1": "12, 4th Cross, Indiranagar",
                 "city": "Bengaluru", "state_code": "%s", "pin_code": "%s"}
                """.formatted(stateCode, pinCode))).get("id").asString();
    }

    /** A delivery address snapshot in Karnataka with this PIN code, as placement takes one (ADR-023); its id. */
    protected UUID deliverySnapshot(String pinCode) {
        UUID customer = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer.customers (id, subject, created_at, updated_at) "
                        + "VALUES (:id, :subject, now(), now())")
                .param("id", customer)
                .param("subject", "snapshot-owner-" + customer)
                .update();
        UUID snapshot = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer.address_snapshots (id, customer_id, recipient_name, phone, line1, city,
                                                                state_code, pin_code, created_at)
                        VALUES (:id, :customer, 'Asha Rao', '+919876543210', '12, 4th Cross, Indiranagar', 'Bengaluru',
                                '29', :pinCode, now())
                        """)
                .param("id", snapshot)
                .param("customer", customer)
                .param("pinCode", pinCode)
                .update();
        return snapshot;
    }

    /** Sets the cart's line for the SKU, applies the coupon if there is one, and quotes for the state. */
    protected UUID quote(String token, String stateCode, String couponCode, String sku, int quantity) {
        expect(200, call("PUT", "/v1/me/cart/lines/" + sku, token, "{\"quantity\": " + quantity + "}"));
        if (couponCode != null) {
            expect(200, call("PUT", "/v1/me/cart/coupon", token, "{\"code\": \"" + couponCode + "\"}"));
        }
        return UUID.fromString(expect(201, call("POST", "/v1/me/cart/quotes", token,
                "{\"delivery_state_code\": \"" + stateCode + "\"}")).get("id").asString());
    }

    // Orders.

    protected HttpResponse<String> place(String token, UUID quoteId, String deliveryAddressId, String key) {
        return call("POST", "/v1/me/orders", token, """
                {"quote_id": "%s", "delivery_address_id": "%s"}
                """.formatted(quoteId, deliveryAddressId), "Idempotency-Key", key);
    }

    /** Places an order for the customer, delivered in Karnataka; its id. */
    protected UUID placeOrder(String token, String sku, int quantity, String couponCode) {
        UUID quoteId = quote(token, KARNATAKA, couponCode, sku, quantity);
        return id(expect(202, place(token, quoteId, address(token, KARNATAKA), newKey())));
    }

    /** Asha's order, without a coupon, taken as far as {@code AWAITING_PAYMENT}. */
    protected UUID awaitingPayment(String sku, int quantity) {
        return awaitingPayment(sku, quantity, null);
    }

    protected UUID awaitingPayment(String sku, int quantity, String couponCode) {
        UUID orderId = placeOrder(asha, sku, quantity, couponCode);
        deliver();
        assertThat(status(orderId)).isEqualTo("AWAITING_PAYMENT");
        return orderId;
    }

    /** Paid, committed and booked for shipping. */
    protected UUID confirmed(String sku, int quantity, String couponCode) {
        UUID orderId = awaitingPayment(sku, quantity, couponCode);
        payments.succeed(orderId);
        deliver();
        assertThat(status(orderId)).isEqualTo("CONFIRMED");
        return orderId;
    }

    protected HttpResponse<String> cancel(String token, UUID orderId) {
        return call("POST", "/v1/me/orders/" + orderId + "/cancel", token, null, "Idempotency-Key", newKey());
    }

    protected JsonNode order(String token, UUID orderId) {
        return expect(200, call("GET", "/v1/me/orders/" + orderId, token, null));
    }

    protected String newKey() {
        return "key-" + ++keys + "-" + UUID.randomUUID();
    }

    // Messages, tasks and deadlines.

    /** Delivers due messages and runs due tasks, as the worker loops would, until neither has anything left. */
    protected void deliver() {
        deliverExcept();
    }

    /** As {@link #deliver}, leaving every message of these payload types in the outbox. */
    protected void deliverExcept(Class<?>... heldBack) {
        for (int round = 1; DueMessages.deliverAllExcept(context, heldBack) + DueTasks.runAll(context) > 0; round++) {
            if (round > 100) {
                throw new AssertionError("Messages and tasks kept coming: more than 100 rounds");
            }
        }
    }

    /** Publishes a message as {@code aggregateType} would: a duplicate, or a reply to a command sent again. */
    protected void publish(Object message, String aggregateType, UUID orderId) {
        transactions.executeWithoutResult(status -> messages.publish(message, new Origin(aggregateType, orderId, 0),
                Correlation.start(orderId.toString())));
    }

    /** The types of the order flow's messages still in the outbox, oldest first. */
    protected List<String> pending(UUID orderId) {
        return jdbc.sql("""
                        SELECT type FROM platform.outbox
                        WHERE aggregate_id = :orderId AND delivered_at IS NULL AND destination LIKE 'handler:%'
                        ORDER BY id
                        """)
                .param("orderId", orderId.toString())
                .query(String.class)
                .list();
    }

    /** Moves the process's deadline into the past and runs the sweep, as the scheduler would every minute. */
    protected void deadlinePasses(UUID orderId) {
        jdbc.sql("UPDATE ordering.order_processes SET deadline_at = :past WHERE order_id = :orderId")
                .param("past", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
                .param("orderId", orderId)
                .update();
        DueTasks.runRecurring(context, SWEEP);
    }

    // State, read straight from the modules' tables.

    protected String status(UUID orderId) {
        return column("SELECT status FROM ordering.orders WHERE id = :id", orderId);
    }

    protected String reason(UUID orderId) {
        return column("SELECT reason FROM ordering.orders WHERE id = :id", orderId);
    }

    protected String step(UUID orderId) {
        return column("SELECT step FROM ordering.order_processes WHERE order_id = :id", orderId);
    }

    protected int attempts(UUID orderId) {
        return jdbc.sql("SELECT attempts FROM ordering.order_processes WHERE order_id = :id")
                .param("id", orderId)
                .query(Integer.class)
                .single();
    }

    protected long version(UUID orderId) {
        return jdbc.sql("SELECT version FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(Long.class)
                .single();
    }

    protected String reservation(UUID orderId) {
        return column("SELECT status FROM inventory.reservations WHERE order_id = :id", orderId);
    }

    protected String redemption(UUID orderId) {
        return column("SELECT status FROM pricing.coupon_redemptions WHERE order_id = :id", orderId);
    }

    /** What Payments knows of the order's payment: the gateway's status once created, else how its creation stands. */
    protected String payment(UUID orderId) {
        return column("""
                SELECT CASE WHEN creation = 'FAILED' THEN 'CREATION_FAILED' ELSE coalesce(status, creation) END
                FROM payments.payment_records WHERE order_id = :id
                """, orderId);
    }

    protected String shipment(UUID orderId) {
        return column("SELECT status FROM fulfillment.shipments WHERE order_id = :id", orderId);
    }

    /** The order's refunds that have a reason: reason to amount. */
    protected Map<String, Long> refunds(UUID orderId) {
        return jdbc.sql("SELECT reason, amount_paise FROM payments.refunds WHERE order_id = :id AND reason IS NOT NULL")
                .param("id", orderId)
                .query((row, n) -> Map.entry(row.getString("reason"), row.getLong("amount_paise")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    protected long grandTotal(UUID orderId) {
        return jdbc.sql("SELECT grand_total_paise FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(Long.class)
                .single();
    }

    protected void assertStock(String sku, long onHand, long reserved) {
        assertThat(jdbc.sql("SELECT on_hand, reserved FROM inventory.stock_items WHERE sku = :sku")
                .param("sku", sku)
                .query((row, n) -> List.of(row.getLong("on_hand"), row.getLong("reserved")))
                .single()).as(sku + ": on hand, reserved").containsExactly(onHand, reserved);
    }

    protected void assertCoupon(UUID couponId, int reserved, int redeemed) {
        assertThat(jdbc.sql("SELECT reserved, redeemed FROM pricing.coupons WHERE id = :id")
                .param("id", couponId)
                .query((row, n) -> List.of(row.getInt("reserved"), row.getInt("redeemed")))
                .single()).as("coupon: reserved, redeemed").containsExactly(reserved, redeemed);
    }

    private String column(String sql, UUID orderId) {
        return jdbc.sql(sql).param("id", orderId).query(String.class).optional().orElse(null);
    }

    protected static UUID id(JsonNode order) {
        return UUID.fromString(order.get("id").asString());
    }

    protected static JsonNode expect(int status, HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json(response);
    }

    protected static void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }
}
