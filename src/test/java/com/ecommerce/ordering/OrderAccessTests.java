package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.TestIdentityProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Who sees which orders (LLD §6.9, ADR-017): customers their own, support any; and the list of a customer's orders. */
class OrderAccessTests extends OrderingTest {

    @Test
    void anotherCustomersOrderIsNotFound() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);

        assertCode(call("GET", "/v1/me/orders/" + orderId, ravi, null), 404, "not_found");
        assertCode(cancel(ravi, orderId), 404, "not_found");
        assertCode(call("GET", "/v1/me/orders/" + UUID.randomUUID(), asha, null), 404, "not_found");
        assertThat(status(orderId)).isEqualTo("PLACED");
        assertThat(order(asha, orderId).get("status").asString()).isEqualTo("PLACED");
    }

    @Test
    void supportSeesAnyOrderWithItsCustomer() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);

        JsonNode seen = expect(200, call("GET", "/v1/support/orders/" + orderId, support, null));

        JsonNode customerView = order(asha, orderId);
        assertThat(seen.get("customer_id").asString()).isNotBlank();
        ((ObjectNode) seen).remove("customer_id");
        assertThat(seen).isEqualTo(customerView);
        assertCode(call("GET", "/v1/support/orders/" + UUID.randomUUID(), support, null), 404, "not_found");
    }

    @Test
    void supportPathsAreForSupportAndCustomerPathsForCustomers() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);
        String warehouse = TestIdentityProvider.token("meera").roles("warehouse").sign();
        String cancel = "{\"reason_code\": \"CUSTOMER_REQUEST\"}";

        for (String staffOrCustomer : List.of(asha, admin, warehouse)) {
            assertThat(call("GET", "/v1/support/orders/" + orderId, staffOrCustomer, null).statusCode())
                    .isEqualTo(403);
            assertThat(call("POST", "/v1/support/orders/" + orderId + "/cancel", staffOrCustomer, cancel,
                    "Idempotency-Key", newKey()).statusCode()).isEqualTo(403);
        }
        for (String staff : List.of(support, admin, warehouse)) {
            assertThat(call("GET", "/v1/me/orders", staff, null).statusCode()).isEqualTo(403);
        }
        assertThat(call("GET", "/v1/me/orders/" + orderId, null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/v1/support/orders/" + orderId, null, null).statusCode()).isEqualTo(401);
        assertThat(status(orderId)).isEqualTo("PLACED");
    }

    @Test
    void aCustomersOrdersAreListedNewestFirstInPages() {
        String sku = product("Steel bottle", 59_900, 50);
        String home = address(asha, KARNATAKA);
        List<UUID> placed = new ArrayList<>();
        for (int quantity = 1; quantity <= 3; quantity++) {
            UUID quoteId = quote(asha, KARNATAKA, null, sku, quantity);
            placed.add(id(expect(202, place(asha, quoteId, home, newKey()))));
        }
        placeOrder(ravi, sku, 1, null);

        JsonNode first = expect(200, call("GET", "/v1/me/orders?limit=2", asha, null));
        JsonNode second = expect(200, call("GET", "/v1/me/orders?limit=2&cursor="
                + first.get("next_cursor").asString(), asha, null));

        assertThat(ids(first)).containsExactly(placed.get(2), placed.get(1));
        assertThat(ids(second)).containsExactly(placed.get(0));
        assertThat(second.has("next_cursor")).isFalse();
        JsonNode newest = first.get("items").get(0);
        assertThat(newest.get("status").asString()).isEqualTo("PLACED");
        assertThat(newest.get("item_count").asInt()).isEqualTo(3);
        assertThat(newest.get("number").asString()).matches("EC\\d{9}");
        assertThat(newest.get("grand_total_paise").asLong()).isEqualTo(grandTotal(placed.get(2)));
        assertThat(expect(200, call("GET", "/v1/me/orders", ravi, null)).get("items")).hasSize(1);
        assertCode(call("GET", "/v1/me/orders?cursor=bm90LWEtY3Vyc29y", asha, null), 400, "invalid_cursor");
        assertThat(call("GET", "/v1/me/orders?limit=0", asha, null).statusCode()).isEqualTo(400);
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("id").asString())));
        return ids;
    }
}
