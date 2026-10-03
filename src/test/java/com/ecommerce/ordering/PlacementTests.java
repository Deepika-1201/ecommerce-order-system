package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Placing an order from a quote (LLD §6.3). */
class PlacementTests extends OrderingTest {

    @Test
    void aPlacedOrderCopiesTheQuoteAndStartsByReservingTheStock() {
        String sku = product("Steel bottle", 59_900, 5);
        coupon("WELCOME", "");
        UUID quoteId = quote(asha, KARNATAKA, "WELCOME", sku, 2);
        JsonNode quote = expect(200, call("GET", "/v1/me/cart/quotes/" + quoteId, asha, null));
        String addressId = address(asha, KARNATAKA);

        HttpResponse<String> placed = place(asha, quoteId, addressId, newKey());

        JsonNode order = expect(202, placed);
        assertThat(placed.headers().firstValue("Location")).hasValue("/v1/me/orders/" + id(order));
        assertThat(order.get("status").asString()).isEqualTo("PLACED");
        assertThat(order.get("number").asString()).matches("EC\\d{9}");
        assertThat(order.has("reason")).isFalse();
        assertThat(order.has("checkout_url")).isFalse();
        assertThat(order.has("customer_id")).as("support only").isFalse();
        assertThat(order.get("coupon_code").asString()).isEqualTo("WELCOME");
        assertThat(order.get("tax_regime").asString()).isEqualTo("INTRA_STATE");
        assertThat(order.get("delivery_state_code").asString()).isEqualTo(KARNATAKA);
        assertThat(order.get("currency").asString()).isEqualTo("INR");
        assertThat(order.get("totals")).isEqualTo(quote.get("totals"));
        assertThat(order.get("shipping")).isEqualTo(quote.get("shipping"));
        JsonNode line = order.get("lines").get(0);
        JsonNode quoted = quote.get("lines").get(0);
        for (String field : new String[] {"sku", "product_id", "variant_id", "title", "quantity", "unit_price_paise",
            "gross_paise", "discount_paise", "amount_paise", "taxable_value_paise", "gst_rate_bps", "cgst_paise",
            "sgst_paise", "igst_paise"}) {
            assertThat(line.get(field)).as(field).isEqualTo(quoted.get(field));
        }
        assertThat(order.get("delivery_address").get("city").asString()).isEqualTo("Bengaluru");
        assertThat(order.get("billing_address")).as("defaults to the delivery address")
                .isEqualTo(order.get("delivery_address"));
        assertThat(order(asha, id(order))).isEqualTo(order);
        assertThat(pending(id(order))).containsExactly("inventory.reserve-stock");
    }

    @Test
    void theSameKeyReplaysTheOrderAndAnotherRequestWithItIsRefused() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID quoteId = quote(asha, KARNATAKA, null, sku, 1);
        String addressId = address(asha, KARNATAKA);
        String key = newKey();

        JsonNode first = expect(202, place(asha, quoteId, addressId, key));
        HttpResponse<String> replay = place(asha, quoteId, addressId, key);

        assertThat(expect(202, replay)).isEqualTo(first);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(replay.headers().firstValue("Location")).hasValue("/v1/me/orders/" + id(first));
        assertCode(place(asha, quoteId, address(asha, KARNATAKA), key), 422, "idempotency_key_reused");
        assertCode(call("POST", "/v1/me/orders", asha, "{\"quote_id\": \"" + quoteId
                + "\", \"delivery_address_id\": \"" + addressId + "\"}"), 400, "idempotency_key_required");
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.orders").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aQuoteIsOrderedOnce() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID quoteId = quote(asha, KARNATAKA, null, sku, 1);
        String addressId = address(asha, KARNATAKA);
        expect(202, place(asha, quoteId, addressId, newKey()));

        assertCode(place(asha, quoteId, addressId, newKey()), 409, "quote_already_ordered");
    }

    @Test
    void anotherCustomersQuoteIsNotFound() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID ashasQuote = quote(asha, KARNATAKA, null, sku, 1);

        assertCode(place(ravi, ashasQuote, address(ravi, KARNATAKA), newKey()), 404, "not_found");
        assertCode(place(asha, UUID.randomUUID(), address(asha, KARNATAKA), newKey()), 404, "not_found");
    }

    @Test
    void anExpiredQuoteMustBeQuotedAgain() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID quoteId = quote(asha, KARNATAKA, null, sku, 1);
        jdbc.sql("UPDATE pricing.quotes SET valid_until = :past WHERE id = :id")
                .param("past", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
                .param("id", quoteId)
                .update();

        assertCode(place(asha, quoteId, address(asha, KARNATAKA), newKey()), 409, "quote_expired");
    }

    @Test
    void theDeliveryAddressMustBeTheCustomersAndInTheQuotesState() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID quoteId = quote(asha, KARNATAKA, null, sku, 1);

        assertCode(place(asha, quoteId, address(ravi, KARNATAKA), newKey()), 422, "address_not_found");
        assertCode(place(asha, quoteId, UUID.randomUUID().toString(), newKey()), 422, "address_not_found");
        assertCode(place(asha, quoteId, address(asha, MAHARASHTRA), newKey()), 422, "address_state_mismatch");
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.orders").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.outbox").query(Integer.class).single()).isZero();
    }

    @Test
    void theOrderKeepsItsAddressesAsTheyWereWhenItWasPlaced() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID quoteId = quote(asha, KARNATAKA, null, sku, 1);
        String home = address(asha, KARNATAKA);
        String office = address(asha, MAHARASHTRA);

        JsonNode order = expect(202, call("POST", "/v1/me/orders", asha, """
                {"quote_id": "%s", "delivery_address_id": "%s", "billing_address_id": "%s"}
                """.formatted(quoteId, home, office), "Idempotency-Key", newKey()));
        expect(200, call("PUT", "/v1/me/addresses/" + home, asha, """
                {"recipient_name": "Asha R", "phone": "9876543210", "line1": "Flat 3B, Lake View",
                 "city": "Mysuru", "state_code": "29", "pin_code": "570001"}
                """));
        assertThat(call("DELETE", "/v1/me/addresses/" + office, asha, null).statusCode()).isEqualTo(204);

        JsonNode read = order(asha, id(order));
        assertThat(read.get("delivery_address").get("city").asString()).isEqualTo("Bengaluru");
        assertThat(read.get("billing_address").get("state_code").asString()).isEqualTo(MAHARASHTRA);
        assertThat(read.get("billing_address").get("state_name").asString()).isEqualTo("Maharashtra");
    }

    @Test
    void placementDoesNotCheckStock() {
        String sku = product("Steel bottle", 59_900, 0);

        UUID orderId = placeOrder(asha, sku, 1, null);

        assertThat(status(orderId)).isEqualTo("PLACED");
        deliver();
        JsonNode order = order(asha, orderId);
        assertThat(order.get("status").asString()).isEqualTo("REJECTED");
        assertThat(order.get("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(order.get("unavailable_sku").asString()).isEqualTo(sku);
    }
}
