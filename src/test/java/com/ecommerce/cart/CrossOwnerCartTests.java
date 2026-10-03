package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.TestIdentityProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Carts and quotes are reachable only by their owner (ADR-017, ADR-020). */
class CrossOwnerCartTests extends CartTest {

    private static final String KARNATAKA = "{\"delivery_state_code\": \"29\"}";

    @Test
    void anotherCustomerNeverReachesTheCartOrItsQuotes() {
        Item bottle = item("Bottle", "STANDARD", 59_900);
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(2));
        String quote = created(me("POST", "/quotes", asha, KARNATAKA)).get("id").asString();

        assertThat(ok(me("GET", "", ravi, null)).get("lines").isEmpty()).isTrue();
        assertCode(me("GET", "/quotes/" + quote, ravi, null), 404, "not_found");
        ok(me("PUT", "/lines/" + bottle.sku(), ravi, quantity(5)));
        assertThat(ok(me("GET", "", asha, null)).get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void aGuestTokenOpensOnlyItsOwnCart() {
        Item bottle = item("Bottle", "STANDARD", 59_900);
        String first = guestToken();
        String second = guestToken();
        guest("PUT", "/lines/" + bottle.sku(), first, quantity(1));
        String quote = created(guest("POST", "/quotes", first, KARNATAKA)).get("id").asString();

        assertCode(guest("GET", "/quotes/" + quote, second, null), 404, "not_found");
        JsonNode secondCart = ok(guest("GET", "", second, null));
        assertThat(secondCart.get("lines").isEmpty()).isTrue();
    }

    @Test
    void guestAndCustomerPathsNeverMix() {
        Item bottle = item("Bottle", "STANDARD", 59_900);
        me("PUT", "/lines/" + bottle.sku(), asha, quantity(1));
        String ashasQuote = created(me("POST", "/quotes", asha, KARNATAKA)).get("id").asString();
        String token = guestToken();
        guest("PUT", "/lines/" + bottle.sku(), token, quantity(1));
        String guestQuote = created(guest("POST", "/quotes", token, KARNATAKA)).get("id").asString();

        assertCode(guest("GET", "/quotes/" + ashasQuote, token, null), 404, "not_found");
        assertCode(me("GET", "/quotes/" + guestQuote, asha, null), 404, "not_found");
        assertThat(call("GET", "/v1/me/cart", null, null, "Cart-Token", token).statusCode()).isEqualTo(401);
        assertThat(me("GET", "", admin, null).statusCode()).as("staff are not customers").isEqualTo(403);
        assertThat(me("GET", "", TestIdentityProvider.token("agent").roles("support").sign(), null).statusCode())
                .isEqualTo(403);
    }
}
