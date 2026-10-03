package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.IdempotentRequests;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The warehouse API over HTTP: receipts, adjustments, levels and movements, for the warehouse role (LLD §5.9). */
class WarehouseStockTests extends InventoryTest {

    private static final String STOCK = "/v1/warehouse/stock";

    private final String meera = TestIdentityProvider.token("meera").roles("warehouse").sign();

    @Test
    void aReceiptCreatesTheStockItemAndLaterReceiptsAddToIt() {
        JsonNode first = ok(post("/ink-1/receipts", "k-1", "{\"quantity\": 5, \"reference\": \"DN-1\"}"));
        JsonNode second = ok(post("/INK-1/receipts", "k-2", "{\"quantity\": 3}"));

        assertThat(first.get("sku").asString()).isEqualTo("INK-1");
        assertThat(first.get("location_code").asString()).isEqualTo("BLR1");
        assertThat(List.of(first.get("on_hand").asLong(), first.get("reserved").asLong(),
                first.get("available").asLong(), first.get("version").asLong())).containsExactly(5L, 0L, 5L, 1L);
        assertThat(List.of(second.get("on_hand").asLong(), second.get("version").asLong())).containsExactly(8L, 2L);
        assertThat(ok(get("/ink-1"))).isEqualTo(second);
        assertInvariants();
    }

    @Test
    void aRetriedReceiptIsReplayedAndAddsNothing() {
        HttpResponse<String> first = post("/INK-1/receipts", "k-1", "{\"quantity\": 5}");
        HttpResponse<String> retry = post("/INK-1/receipts", "k-1", "{ \"quantity\" : 5 }");

        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(json(retry)).isEqualTo(json(first));
        assertCode(post("/INK-1/receipts", "k-1", "{\"quantity\": 6}"), 422, "idempotency_key_reused");
        assertCode(post("/PEN-1/receipts", "k-1", "{\"quantity\": 5}"), 422, "idempotency_key_reused");
        assertCode(post("/INK-1/receipts", null, "{\"quantity\": 5}"), 400, "idempotency_key_required");
        assertLevels("INK-1", 5, 0);
        assertThat(ok(get("/INK-1")).get("version").asLong()).isEqualTo(1);
    }

    @Test
    void receiptsAndAdjustmentsStayWithinTheirLimits() {
        ok(post("/INK-1/receipts", "k-1", "{\"quantity\": 100000}"));

        assertCode(post("/INK-1/receipts", "k-2", "{\"quantity\": 0}"), 400, "validation_failed");
        assertCode(post("/INK-1/receipts", "k-3", "{\"quantity\": 100001}"), 400, "validation_failed");
        assertCode(post("/INK-1/receipts", "k-4", "{}"), 400, "validation_failed");
        assertCode(post("/INK-1/receipts", "k-5", "{\"quantity\": 1, \"reference\": \"" + "r".repeat(101) + "\"}"),
                400, "validation_failed");
        assertCode(adjust("INK-1", "k-6", -100_001, "LOST"), 400, "validation_failed");
        assertCode(post("/INK-1/adjustments", "k-7", "{\"quantity_change\": -1}"), 400, "validation_failed");
        assertCode(post("/INK-1/adjustments", "k-8", "{\"quantity_change\": -1, \"reason\": \"STOLEN\"}"), 400,
                "malformed_request");
        assertThat(ok(adjust("INK-1", "k-9", -100_000, "LOST")).get("on_hand").asLong()).isZero();
    }

    @Test
    void adjustmentsFollowTheSignOfTheirReason() {
        ok(post("/INK-1/receipts", "k-1", "{\"quantity\": 10}"));

        assertCode(adjust("INK-1", "k-2", 1, "DAMAGED"), 400, "invalid_adjustment");
        assertCode(adjust("INK-1", "k-3", 1, "LOST"), 400, "invalid_adjustment");
        assertCode(adjust("INK-1", "k-4", -1, "FOUND"), 400, "invalid_adjustment");
        for (String reason : List.of("DAMAGED", "LOST", "FOUND", "COUNT_CORRECTION")) {
            assertCode(adjust("INK-1", "k-zero-" + reason, 0, reason), 400, "invalid_adjustment");
        }
        assertThat(ok(adjust("INK-1", "k-6", -2, "DAMAGED")).get("on_hand").asLong()).isEqualTo(8);
        assertThat(ok(adjust("INK-1", "k-7", -1, "LOST")).get("on_hand").asLong()).isEqualTo(7);
        assertThat(ok(adjust("INK-1", "k-8", 3, "FOUND")).get("on_hand").asLong()).isEqualTo(10);
        assertThat(ok(adjust("INK-1", "k-9", -4, "COUNT_CORRECTION")).get("on_hand").asLong()).isEqualTo(6);
        assertThat(ok(adjust("INK-1", "k-10", 1, "COUNT_CORRECTION")).get("on_hand").asLong()).isEqualTo(7);
        assertInvariants();
    }

    @Test
    void anAdjustmentNeverDropsOnHandBelowTheReservedUnits() {
        ok(post("/INK-1/receipts", "k-1", "{\"quantity\": 5}"));
        reserve(UUID.randomUUID(), line("INK-1", 3));

        HttpResponse<String> refused = adjust("INK-1", "k-2", -3, "DAMAGED");
        assertCode(refused, 409, "adjustment_below_reserved");
        assertThat(json(refused).get("detail").asString()).contains("at most 2");
        assertCode(adjust("INK-1", "k-3", -6, "COUNT_CORRECTION"), 409, "adjustment_below_reserved");

        JsonNode adjusted = ok(adjust("INK-1", "k-4", -2, "DAMAGED"));
        assertThat(List.of(adjusted.get("on_hand").asLong(), adjusted.get("reserved").asLong(),
                adjusted.get("available").asLong())).containsExactly(3L, 3L, 0L);
        assertInvariants();
    }

    @Test
    void onlyAReceiptCreatesAStockItem() {
        assertCode(get("/INK-1"), 404, "not_found");
        assertCode(adjust("INK-1", "k-1", 5, "FOUND"), 404, "not_found");
        assertCode(get("/INK-1/movements"), 404, "not_found");
        assertThat(ok(get("")).get("items")).isEmpty();
    }

    @Test
    void movementsAreNewestFirstAndPaged() {
        ok(post("/INK-1/receipts", "k-1", "{\"quantity\": 5, \"reference\": \"DN-1\"}"));
        ok(adjust("INK-1", "k-2", -1, "DAMAGED", "Box crushed"));
        UUID order = UUID.randomUUID();
        reserve(order, line("INK-1", 2));
        reservations.commit(order);
        reservations.fulfill(order);
        ok(post("/INK-1/receipts", "k-3", "{\"quantity\": 4}"));
        ok(post("/PEN-1/receipts", "k-4", "{\"quantity\": 1}"));

        List<JsonNode> movements = new ArrayList<>();
        String path = STOCK + "/INK-1/movements?limit=3";
        int pages = 0;
        while (path != null) {
            JsonNode page = ok(call("GET", path, meera, null));
            page.get("items").forEach(movements::add);
            path = page.has("next_cursor")
                    ? STOCK + "/INK-1/movements?limit=3&cursor=" + page.get("next_cursor").asString() : null;
            pages++;
        }

        assertThat(pages).isEqualTo(2);
        assertThat(movements).extracting(movement -> movement.get("kind").asString() + " "
                        + movement.get("quantity").asInt() + " -> " + movement.get("on_hand_after").asLong())
                .containsExactly("RECEIPT 4 -> 6", "HANDOVER -2 -> 2", "ADJUSTMENT -1 -> 4", "RECEIPT 5 -> 5");
        assertThat(movements.get(0).has("reference")).isFalse();
        assertThat(movements.get(1).get("order_id").asString()).isEqualTo(order.toString());
        assertThat(movements.get(1).has("actor_id")).as("the saga, not staff").isFalse();
        assertThat(movements.get(2).get("reason").asString()).isEqualTo("DAMAGED");
        assertThat(movements.get(2).get("note").asString()).isEqualTo("Box crushed");
        assertThat(movements.get(2).get("actor_id").asString()).isEqualTo("meera");
        assertThat(movements.get(3).get("reference").asString()).isEqualTo("DN-1");
        assertCode(get("/INK-1/movements?cursor=bm9wZQ"), 400, "invalid_cursor");
    }

    @Test
    void stockIsListedInSkuOrder() {
        for (String sku : List.of("PEN-1", "INK-1", "CAP-1")) {
            ok(post("/" + sku + "/receipts", "k-" + sku, "{\"quantity\": 1}"));
        }
        ok(post("/PEN-1/receipts", "k-PEN-1-again", "{\"quantity\": 1}"));

        JsonNode first = ok(get("?limit=2"));
        JsonNode second = ok(get("?limit=2&cursor=" + first.get("next_cursor").asString()));

        assertThat(first.get("items")).extracting(item -> item.get("sku").asString())
                .containsExactly("CAP-1", "INK-1");
        assertThat(second.get("items")).extracting(item -> item.get("sku").asString()).containsExactly("PEN-1");
        assertThat(second.has("next_cursor")).isFalse();
        assertCode(get("?cursor=" + first.get("next_cursor").asString().substring(1)), 400, "invalid_cursor");
        String movementCursor = ok(get("/PEN-1/movements?limit=1")).get("next_cursor").asString();
        assertCode(get("?cursor=" + movementCursor), 400, "invalid_cursor");
        assertCode(get("/PEN-1/movements?cursor=" + first.get("next_cursor").asString()), 400, "invalid_cursor");
    }

    @Test
    void onlyTheWarehouseRoleReachesStock() {
        ok(post("/INK-1/receipts", "k-1", "{\"quantity\": 1}"));
        String body = "{\"quantity\": 1}";
        for (String token : List.of(TestIdentityProvider.customer("asha"), TestIdentityProvider.admin("admin"),
                TestIdentityProvider.token("sam").roles("support").sign())) {
            assertThat(call("GET", STOCK, token, null).statusCode()).isEqualTo(403);
            assertThat(call("GET", STOCK + "/INK-1", token, null).statusCode()).isEqualTo(403);
            assertThat(call("POST", STOCK + "/INK-1/receipts", token, body, IdempotentRequests.HEADER, "k-2")
                    .statusCode()).isEqualTo(403);
        }
        assertThat(call("GET", STOCK, null, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", STOCK + "/INK-1/receipts", null, body, IdempotentRequests.HEADER, "k-3")
                .statusCode()).isEqualTo(401);
        assertLevels("INK-1", 1, 0);
    }

    @Test
    void receiptsAndAdjustmentsAreAuditedWithTheirReason() {
        ok(post("/INK-1/receipts", "k-1", "{\"quantity\": 5, \"reference\": \"DN-1\"}"));
        ok(adjust("INK-1", "k-2", -1, "LOST", "Not on the shelf"));
        assertCode(adjust("INK-1", "k-3", -9, "LOST"), 409, "adjustment_below_reserved");

        List<String> entries = jdbc.sql("""
                        SELECT action, coalesce(reason, '-') AS reason, details ->> 'on_hand' AS on_hand
                        FROM platform.audit_log
                        WHERE target_type = 'stock_item' AND target_id = 'INK-1' AND actor_id = 'meera' ORDER BY id
                        """)
                .query((row, n) -> row.getString("action") + " " + row.getString("reason") + " "
                        + row.getString("on_hand"))
                .list();
        assertThat(entries).containsExactly("inventory.receipt - 5", "inventory.adjustment LOST 4");
    }

    @Test
    void aMalformedSkuIsABadRequest() {
        assertCode(get("/AB"), 400, "invalid_request");
        assertCode(get("/-INK-1"), 400, "invalid_request");
        assertCode(get("/INK_1"), 400, "invalid_request");
        assertCode(post("/" + "A".repeat(41) + "/receipts", "k-1", "{\"quantity\": 1}"), 400, "invalid_request");
        ok(post("/" + "A".repeat(40) + "/receipts", "k-2", "{\"quantity\": 1}"));
    }

    @Test
    void aChangeToAStockRowLockedTooLongIsBusyAndCanBeRetried() throws Exception {
        ok(post("/INK-1/receipts", "k-1", "{\"quantity\": 5}"));

        try (Connection gate = lockStockRows("INK-1")) {
            try {
                HttpResponse<String> busy = adjust("INK-1", "k-2", -1, "DAMAGED");
                assertCode(busy, 503, "stock_busy");
                assertThat(busy.headers().firstValue("Retry-After")).hasValue("1");
                assertCode(post("/INK-1/receipts", "k-3", "{\"quantity\": 1}"), 503, "stock_busy");
            } finally {
                gate.rollback();
            }
        }

        HttpResponse<String> retried = adjust("INK-1", "k-2", -1, "DAMAGED");
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(retried.headers().firstValue("Idempotent-Replayed")).as("the key was released").isEmpty();
        assertLevels("INK-1", 4, 0);
    }

    private HttpResponse<String> get(String path) {
        return call("GET", STOCK + path, meera, null);
    }

    private HttpResponse<String> post(String path, String key, String body) {
        return key == null
                ? call("POST", STOCK + path, meera, body)
                : call("POST", STOCK + path, meera, body, IdempotentRequests.HEADER, key);
    }

    private HttpResponse<String> adjust(String sku, String key, int change, String reason) {
        return post("/" + sku + "/adjustments", key,
                "{\"quantity_change\": " + change + ", \"reason\": \"" + reason + "\"}");
    }

    private HttpResponse<String> adjust(String sku, String key, int change, String reason, String note) {
        return post("/" + sku + "/adjustments", key,
                "{\"quantity_change\": " + change + ", \"reason\": \"" + reason + "\", \"note\": \"" + note + "\"}");
    }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }
}
