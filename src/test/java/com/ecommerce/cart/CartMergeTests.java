package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Merging a guest cart into a customer's at sign-in (LLD §4.4). */
class CartMergeTests extends CartTest {

    @Test
    void theGuestCartMergesInAndItsTokenStopsWorking() {
        Item a = item("A", "STANDARD", 10_000);
        Item b = item("B", "STANDARD", 20_000);
        Item c = item("C", "STANDARD", 30_000);
        coupon("MINE", "\"kind\": \"FLAT\", \"amount_paise\": 1000");
        coupon("THEIRS", "\"kind\": \"FLAT\", \"amount_paise\": 2000");
        me("PUT", "/lines/" + a.sku(), asha, quantity(1));
        me("PUT", "/lines/" + b.sku(), asha, quantity(2));
        me("PUT", "/coupon", asha, "{\"code\": \"MINE\"}");
        String token = guestToken();
        guest("PUT", "/lines/" + b.sku(), token, quantity(5));
        guest("PUT", "/lines/" + c.sku(), token, quantity(1));
        guest("PUT", "/coupon", token, "{\"code\": \"THEIRS\"}");

        JsonNode merged = ok(me("POST", "/merge", asha, null, "Cart-Token", token));

        assertThat(quantities(merged)).containsExactly(a.sku() + "=1", b.sku() + "=5", c.sku() + "=1");
        assertThat(merged.get("coupon_code").asString()).isEqualTo("THEIRS");
        assertThat(merged.get("version").asLong()).isEqualTo(4);
        assertCode(guest("GET", "", token, null), 404, "not_found");
    }

    @Test
    void aRetriedMergeChangesNothing() {
        Item a = item("A", "STANDARD", 10_000);
        String token = guestToken();
        guest("PUT", "/lines/" + a.sku(), token, quantity(2));

        JsonNode first = ok(me("POST", "/merge", asha, null, "Cart-Token", token));
        JsonNode retried = ok(me("POST", "/merge", asha, null, "Cart-Token", token));

        assertThat(retried).isEqualTo(first);
        assertThat(quantities(first)).containsExactly(a.sku() + "=2");
    }

    @Test
    void aCustomerWithoutACartTakesTheGuestLines() {
        Item a = item("A", "STANDARD", 10_000);
        String token = guestToken();
        guest("PUT", "/lines/" + a.sku(), token, quantity(3));

        assertThat(quantities(ok(me("POST", "/merge", ravi, null, "Cart-Token", token))))
                .containsExactly(a.sku() + "=3");
        assertThat(quantities(ok(me("GET", "", ravi, null)))).containsExactly(a.sku() + "=3");
    }

    @Test
    void aMergeBeyondFiftyLinesIsRefusedAndChangesNothing() {
        String product = product("Socks", "APPAREL", """
                [{"name": "n", "values": [%s]}]
                """.formatted(values(30)));
        String other = product("Caps", "APPAREL", """
                [{"name": "n", "values": [%s]}]
                """.formatted(values(30)));
        for (int i = 1; i <= 30; i++) {
            variant(product, "SOCK-" + i, "{\"n\": \"V" + i + "\"}", 19_900);
            variant(other, "CAP-" + i, "{\"n\": \"V" + i + "\"}", 29_900);
        }
        activate(product);
        activate(other);
        String token = guestToken();
        for (int i = 1; i <= 30; i++) {
            me("PUT", "/lines/SOCK-" + i, asha, quantity(1));
        }
        for (int i = 1; i <= 21; i++) {
            guest("PUT", "/lines/CAP-" + i, token, quantity(1));
        }

        assertCode(me("POST", "/merge", asha, null, "Cart-Token", token), 409, "cart_full");

        assertThat(ok(me("GET", "", asha, null)).get("lines")).hasSize(30);
        assertThat(ok(guest("GET", "", token, null)).get("lines")).as("the guest cart is intact").hasSize(21);
    }

    @Test
    void mergingNeedsASignedInCustomer() {
        String token = guestToken();

        assertThat(call("POST", "/v1/me/cart/merge", null, null, "Cart-Token", token).statusCode()).isEqualTo(401);
        assertThat(ok(guest("GET", "", token, null)).get("version").asLong()).isZero();
    }

    private static List<String> quantities(JsonNode cart) {
        return StreamSupport.stream(cart.get("lines").spliterator(), false)
                .map(line -> line.get("sku").asString() + "=" + line.get("quantity").asInt())
                .toList();
    }

    private static String values(int count) {
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            values.append(i > 1 ? ", " : "").append("\"V").append(i).append('"');
        }
        return values.toString();
    }
}
