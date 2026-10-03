package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Guest carts and their tokens (LLD §4.4, ADR-020). */
class GuestCartTests extends CartTest {

    @Test
    void aGuestCartComesWithItsTokenOnce() throws Exception {
        JsonNode created = created(call("POST", "/v1/guest/cart", null, null));
        String token = created.get("cart_token").asString();

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(created.get("cart").get("version").asLong()).isZero();
        assertThat(ok(guest("GET", "", token, null)).has("cart_token")).as("shown only once").isFalse();
        byte[] stored = jdbc.sql("SELECT guest_token_hash FROM cart.carts").query(byte[].class).single();
        assertThat(stored).isEqualTo(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        List<String> text = jdbc.sql("SELECT row_to_json(c)::text FROM cart.carts c").query(String.class).list();
        assertThat(text).noneMatch(row -> row.contains(token));
    }

    @Test
    void theTokenIsRequiredAndAnUnknownOneIsNotFound() {
        assertCode(call("GET", "/v1/guest/cart", null, null), 400, "invalid_request");
        assertCode(guest("GET", "", "x".repeat(43), null), 404, "not_found");
    }

    @Test
    void guestsFillCartsAsCustomersDo() {
        Item shirt = item("Oxford shirt", "APPAREL", 129_900);
        String token = guestToken();

        JsonNode cart = ok(guest("PUT", "/lines/" + shirt.sku(), token, quantity(2)));
        assertCode(guest("PUT", "/lines/" + shirt.sku(), "y".repeat(43), quantity(2)), 404, "not_found");

        assertThat(cart.get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(cart.get("version").asLong()).isEqualTo(1);
        assertThat(Instant.parse(cart.get("expires_at").asString()))
                .isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(Duration.ofMinutes(1)));
        assertThat(ok(guest("DELETE", "/lines/" + shirt.sku(), token, null)).get("lines").isEmpty()).isTrue();
    }

    @Test
    void couponsWithAPerCustomerLimitNeedASignedInCustomer() {
        coupon("ONCE", "\"kind\": \"FLAT\", \"amount_paise\": 5000, \"per_customer_limit\": 1");
        coupon("ANYONE", "\"kind\": \"FLAT\", \"amount_paise\": 5000");
        String token = guestToken();

        assertCode(guest("PUT", "/coupon", token, "{\"code\": \"ONCE\"}"), 422, "coupon_requires_sign_in");
        assertThat(ok(guest("PUT", "/coupon", token, "{\"code\": \"anyone\"}")).get("coupon_code").asString())
                .isEqualTo("ANYONE");
    }
}
