package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Starts each test with empty carts, quotes, coupons and catalog, and builds them through the public APIs. */
abstract class CartTest extends IntegrationTest {

    protected final String ashaSubject = "asha-" + UUID.randomUUID();
    protected final String asha = TestIdentityProvider.customer(ashaSubject);
    protected final String ravi = TestIdentityProvider.customer("ravi-" + UUID.randomUUID());
    protected final String admin = TestIdentityProvider.admin("cart-admin");

    @Autowired
    protected JdbcClient jdbc;

    private String categoryId;

    @BeforeEach
    void emptyStores() {
        jdbc.sql("""
                TRUNCATE cart.cart_lines, cart.carts, pricing.quote_lines, pricing.quotes,
                         pricing.coupon_redemptions, pricing.coupons, catalog.product_images, catalog.variants,
                         catalog.products, catalog.categories
                """).update();
        PlatformTables.clear(jdbc);
        categoryId = created(call("POST", "/v1/admin/catalog/categories", admin,
                "{\"name\": \"Everything\", \"slug\": \"everything\"}")).get("id").asString();
    }

    /** An active product with one variant at this GST-inclusive price. */
    protected Item item(String title, String gstCategory, long pricePaise) {
        String productId = product(title, gstCategory, "[]");
        Item item = variant(productId, "SKU-" + productId.substring(24).toUpperCase(), "{}", pricePaise);
        activate(productId);
        return item;
    }

    protected String product(String title, String gstCategory, String optionsJson) {
        return created(call("POST", "/v1/admin/catalog/products", admin, """
                {"title": "%s", "category_id": "%s", "gst_category": "%s", "options": %s}
                """.formatted(title, categoryId, gstCategory, optionsJson))).get("id").asString();
    }

    protected Item variant(String productId, String sku, String optionValuesJson, long pricePaise) {
        JsonNode product = created(call("POST", "/v1/admin/catalog/products/" + productId + "/variants", admin, """
                {"sku": "%s", "option_values": %s, "price_paise": %d}
                """.formatted(sku, optionValuesJson, pricePaise)));
        JsonNode variant = product.get("variants").get(product.get("variants").size() - 1);
        return new Item(variant.get("sku").asString(), productId, variant.get("id").asString());
    }

    protected void activate(String productId) {
        ok(call("POST", "/v1/admin/catalog/products/" + productId + "/activate", admin, null));
    }

    protected void archive(Item item) {
        ok(call("POST", "/v1/admin/catalog/products/" + item.productId() + "/archive", admin, null));
    }

    protected void reprice(Item item, long pricePaise) {
        ok(call("PATCH", "/v1/admin/catalog/products/" + item.productId() + "/variants/" + item.variantId(), admin,
                "{\"price_paise\": " + pricePaise + "}"));
    }

    /** Creates a coupon from the given JSON fields; returns its id. */
    protected UUID coupon(String code, String fields) {
        return UUID.fromString(created(call("POST", "/v1/admin/pricing/coupons", admin,
                "{\"code\": \"" + code + "\", " + fields + "}")).get("id").asString());
    }

    protected HttpResponse<String> me(String method, String path, String token, String body, String... headers) {
        return call(method, "/v1/me/cart" + path, token, body, headers);
    }

    protected String guestToken() {
        return created(call("POST", "/v1/guest/cart", null, null)).get("cart_token").asString();
    }

    protected HttpResponse<String> guest(String method, String path, String cartToken, String body,
            String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "Cart-Token";
        all[1] = cartToken;
        System.arraycopy(headers, 0, all, 2, headers.length);
        return call(method, "/v1/guest/cart" + path, null, body, all);
    }

    protected static String quantity(int quantity) {
        return "{\"quantity\": " + quantity + "}";
    }

    protected static JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response);
    }

    protected static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    protected static void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }

    protected record Item(String sku, String productId, String variantId) {
    }
}
