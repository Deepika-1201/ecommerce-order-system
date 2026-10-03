package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A signed-in customer's cart (LLD §4.3). */
class CartTests extends CartTest {

    @Test
    void aNewCustomerHasAnEmptyCartAtVersionZero() {
        HttpResponse<String> response = me("GET", "", asha, null);

        JsonNode cart = ok(response);
        assertThat(response.headers().firstValue("ETag")).hasValue("\"0\"");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        assertThat(cart.get("version").asLong()).isZero();
        assertThat(cart.get("lines").isEmpty()).isTrue();
        assertThat(cart.get("subtotal_paise").asLong()).isZero();
        assertThat(cart.get("currency").asString()).isEqualTo("INR");
        assertThat(cart.has("expires_at")).isFalse();
    }

    @Test
    void settingALineAddsItAndSettingItAgainChangesItsQuantity() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);

        HttpResponse<String> added = me("PUT", "/lines/" + shirt.sku(), asha, quantity(2));
        HttpResponse<String> changed = me("PUT", "/lines/" + shirt.sku().toLowerCase(), asha, quantity(3));

        assertThat(added.headers().firstValue("ETag")).hasValue("\"1\"");
        JsonNode line = ok(added).get("lines").get(0);
        assertThat(line.get("sku").asString()).isEqualTo(shirt.sku());
        assertThat(line.get("title").asString()).isEqualTo("Oxford shirt");
        assertThat(line.get("quantity").asInt()).isEqualTo(2);
        assertThat(line.get("unit_price_paise").asLong()).isEqualTo(129_900);
        assertThat(line.get("line_total_paise").asLong()).isEqualTo(259_800);
        assertThat(line.get("available").asBoolean()).isTrue();
        assertThat(line.has("added_unit_price_paise")).isFalse();
        JsonNode cart = ok(changed);
        assertThat(cart.get("version").asLong()).isEqualTo(2);
        assertThat(cart.get("lines")).hasSize(1);
        assertThat(cart.get("lines").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(cart.get("subtotal_paise").asLong()).isEqualTo(389_700);
        assertThat(Instant.parse(cart.get("expires_at").asString()))
                .isCloseTo(Instant.now().plus(Duration.ofDays(90)), within(Duration.ofMinutes(1)));
    }

    @Test
    void quantitiesAreOneToTen() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);

        assertCode(me("PUT", "/lines/" + shirt.sku(), asha, quantity(0)), 400, "validation_failed");
        assertCode(me("PUT", "/lines/" + shirt.sku(), asha, quantity(11)), 400, "validation_failed");
        assertCode(me("PUT", "/lines/" + shirt.sku(), asha, "{}"), 400, "validation_failed");
        assertThat(ok(me("PUT", "/lines/" + shirt.sku(), asha, quantity(10))).get("lines").get(0)
                .get("quantity").asInt()).isEqualTo(10);
    }

    @Test
    void onlyItemsThatCanBeBoughtCanBeAdded() {
        String draft = product("Not yet", "STANDARD", "[]");
        variant(draft, "DRAFT-1", "{}", 10_000);

        assertCode(me("PUT", "/lines/NO-SUCH-SKU", asha, quantity(1)), 422, "item_unavailable");
        assertCode(me("PUT", "/lines/DRAFT-1", asha, quantity(1)), 422, "item_unavailable");
        assertThat(ok(me("GET", "", asha, null)).get("version").asLong()).as("nothing was created").isZero();
    }

    @Test
    void aCartHoldsAtMostFiftyLines() {
        String product = product("Socks", "APPAREL", """
                [{"name": "size", "values": [%s]}, {"name": "color", "values": ["Red", "Blue"]}]
                """.formatted(sizes()));
        for (int i = 1; i <= 26; i++) {
            variant(product, "SOCK-R" + i, "{\"size\": \"S" + i + "\", \"color\": \"Red\"}", 19_900);
            variant(product, "SOCK-B" + i, "{\"size\": \"S" + i + "\", \"color\": \"Blue\"}", 19_900);
        }
        activate(product);
        for (int i = 1; i <= 25; i++) {
            ok(me("PUT", "/lines/SOCK-R" + i, asha, quantity(1)));
            ok(me("PUT", "/lines/SOCK-B" + i, asha, quantity(1)));
        }

        assertCode(me("PUT", "/lines/SOCK-R26", asha, quantity(1)), 409, "cart_full");
        assertThat(ok(me("PUT", "/lines/SOCK-R1", asha, quantity(4))).get("lines")).as("changing a line is fine")
                .hasSize(50);
    }

    @Test
    void removingALineIsIdempotent() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);
        Item bottle = item("Bottle", "STANDARD", 59_900);
        me("PUT", "/lines/" + shirt.sku(), asha, quantity(1));
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));

        JsonNode removed = ok(me("DELETE", "/lines/" + shirt.sku(), asha, null));
        JsonNode again = ok(me("DELETE", "/lines/" + shirt.sku(), asha, null));

        assertThat(removed.get("lines")).hasSize(1);
        assertThat(removed.get("lines").get(0).get("sku").asString()).isEqualTo(bottle.sku());
        assertThat(removed.get("version").asLong()).isEqualTo(3);
        assertThat(again.get("version").asLong()).as("nothing changed").isEqualTo(3);
        assertThat(ok(me("DELETE", "/lines/" + shirt.sku(), ravi, null)).get("version").asLong())
                .as("a customer without a cart").isZero();
    }

    @Test
    void ifMatchMakesAWriteConditional() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);
        ok(me("PUT", "/lines/" + shirt.sku(), asha, quantity(1), "If-Match", "\"0\""));

        assertCode(me("PUT", "/lines/" + shirt.sku(), asha, quantity(2), "If-Match", "\"0\""), 412,
                "precondition_failed");
        assertCode(me("PUT", "/lines/" + shirt.sku(), asha, quantity(2), "If-Match", "W/\"1\""), 412,
                "precondition_failed");
        assertCode(me("DELETE", "/lines/" + shirt.sku(), asha, null, "If-Match", "\"5\""), 412,
                "precondition_failed");
        assertThat(ok(me("PUT", "/lines/" + shirt.sku(), asha, quantity(2), "If-Match", "\"1\""))
                .get("version").asLong()).isEqualTo(2);
    }

    @Test
    void aPriceChangeSinceAddingShowsBothPrices() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);
        me("PUT", "/lines/" + shirt.sku(), asha, quantity(2));

        reprice(shirt, 119_900);
        me("PUT", "/lines/" + shirt.sku(), asha, quantity(3));

        JsonNode cart = ok(me("GET", "", asha, null));
        JsonNode line = cart.get("lines").get(0);
        assertThat(line.get("unit_price_paise").asLong()).isEqualTo(119_900);
        assertThat(line.get("added_unit_price_paise").asLong()).as("kept from the first add").isEqualTo(129_900);
        assertThat(cart.get("subtotal_paise").asLong()).isEqualTo(359_700);
    }

    @Test
    void anItemThatCanNoLongerBeBoughtStaysButIsNotCounted() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);
        Item bottle = item("Bottle", "STANDARD", 59_900);
        me("PUT", "/lines/" + shirt.sku(), asha, quantity(1));
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));

        archive(shirt);

        JsonNode cart = ok(me("GET", "", asha, null));
        assertThat(cart.get("lines")).hasSize(2);
        assertThat(cart.get("lines").get(0).get("available").asBoolean()).isFalse();
        assertThat(cart.get("subtotal_paise").asLong()).isEqualTo(59_900);
    }

    @Test
    void aCouponIsAppliedAndRemoved() {
        coupon("WELCOME10", "\"kind\": \"PERCENT\", \"percent_bps\": 1000, \"per_customer_limit\": 1");
        coupon("ENDED", "\"kind\": \"FLAT\", \"amount_paise\": 100, \"valid_from\": \"2026-01-01T00:00:00Z\", "
                + "\"valid_until\": \"2026-02-01T00:00:00Z\"");

        JsonNode applied = ok(me("PUT", "/coupon", asha, "{\"code\": \"welcome10\"}"));
        assertCode(me("PUT", "/coupon", asha, "{\"code\": \"NOPE\"}"), 422, "coupon_not_found");
        assertCode(me("PUT", "/coupon", asha, "{\"code\": \"ENDED\"}"), 422, "coupon_expired");
        JsonNode removed = ok(me("DELETE", "/coupon", asha, null));

        assertThat(applied.get("coupon_code").asString()).isEqualTo("WELCOME10");
        assertThat(removed.has("coupon_code")).isFalse();
        assertThat(removed.get("version").asLong()).isEqualTo(2);
    }

    private static String sizes() {
        StringBuilder sizes = new StringBuilder();
        for (int i = 1; i <= 26; i++) {
            sizes.append(i > 1 ? ", " : "").append("\"S").append(i).append('"');
        }
        return sizes.toString();
    }
}
