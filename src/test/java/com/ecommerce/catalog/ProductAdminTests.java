package com.ecommerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ProductAdminTests extends CatalogTest {

    private static final String SHIRT_OPTIONS = """
            [{"name": "color", "values": [" White ", "Blue"]}, {"name": "size", "values": ["S", "M", "L"]}]
            """;

    private String shirts;

    @BeforeEach
    void category() {
        shirts = createCategory("Shirts", "shirts", null);
    }

    @Test
    void aNewProductIsADraftAtVersionOne() {
        HttpResponse<String> response = call("POST", "/v1/admin/catalog/products", admin, """
                {"title": " Oxford shirt ", "description": "Cotton", "category_id": "%s", "gst_category": "APPAREL",
                 "options": %s}
                """.formatted(shirts, SHIRT_OPTIONS));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode product = json(response);
        assertThat(response.headers().firstValue("Location")).hasValue("/v1/admin/catalog/products/" + id(product));
        assertThat(response.headers().firstValue("ETag")).hasValue("\"1\"");
        assertThat(product.get("title").asString()).isEqualTo("Oxford shirt");
        assertThat(product.get("status").asString()).isEqualTo("DRAFT");
        assertThat(product.get("version").asLong()).isEqualTo(1);
        assertThat(product.get("currency").asString()).isEqualTo("INR");
        assertThat(product.get("category").get("slug").asString()).isEqualTo("shirts");
        assertThat(product.get("options").get(0).get("values").get(0).asString()).isEqualTo("White");
        assertThat(product.get("variants").isEmpty()).isTrue();

        HttpResponse<String> read = call("GET", "/v1/admin/catalog/products/" + id(product), admin, null);
        assertThat(read.headers().firstValue("ETag")).hasValue("\"1\"");
        assertThat(json(read)).isEqualTo(product);
    }

    @Test
    void theCategoryMustExist() {
        HttpResponse<String> response = call("POST", "/v1/admin/catalog/products", admin, """
                {"title": "Lost", "category_id": "%s", "gst_category": "STANDARD"}
                """.formatted(UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json(response).get("code").asString()).isEqualTo("unknown_category");
    }

    @Test
    void variantsNameEveryOptionOnceAndSkusAreUniqueWhateverTheCase() {
        String id = id(createProduct("Oxford shirt", "", shirts, SHIRT_OPTIONS));

        JsonNode product = addVariant(id, "ox-wht-m", """
                {"color": "white", "size": "m"}
                """, 129_900);
        HttpResponse<String> sameSku = variant(id, "OX-WHT-M", "{\"color\": \"Blue\", \"size\": \"M\"}");
        HttpResponse<String> sameCombination = variant(id, "OX-WHITE-M", "{\"size\": \"M\", \"color\": \"WHITE\"}");
        HttpResponse<String> unknownValue = variant(id, "OX-GRN-M", "{\"color\": \"Green\", \"size\": \"M\"}");
        HttpResponse<String> missingOption = variant(id, "OX-BLU", "{\"color\": \"Blue\"}");

        JsonNode variant = product.get("variants").get(0);
        assertThat(variant.get("sku").asString()).isEqualTo("OX-WHT-M");
        assertThat(variant.get("option_values").get("color").asString()).isEqualTo("White");
        assertThat(variant.get("option_values").get("size").asString()).isEqualTo("M");
        assertThat(variant.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(product.get("version").asLong()).isEqualTo(2);
        assertCode(sameSku, 409, "sku_taken");
        assertCode(sameCombination, 409, "variant_exists");
        assertCode(unknownValue, 400, "invalid_option_values");
        assertCode(missingOption, 400, "invalid_option_values");
    }

    @Test
    void anActiveProductAlwaysHasAnActiveVariant() {
        String id = id(createProduct("Oxford shirt", "", shirts, SHIRT_OPTIONS));

        assertCode(call("POST", "/v1/admin/catalog/products/" + id + "/activate", admin, null), 409,
                "product_needs_active_variant");

        String white = id(addVariant(id, "OX-WHT-M", "{\"color\": \"White\", \"size\": \"M\"}", 129_900)
                .get("variants").get(0));
        JsonNode active = activate(id);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertCode(setVariantStatus(id, white, "INACTIVE"), 409, "product_needs_active_variant");

        addVariant(id, "OX-BLU-M", "{\"color\": \"Blue\", \"size\": \"M\"}", 129_900);
        HttpResponse<String> deactivated = setVariantStatus(id, white, "INACTIVE");
        assertThat(deactivated.statusCode()).isEqualTo(200);
        assertThat(statuses(json(deactivated))).containsExactly("INACTIVE", "ACTIVE");
    }

    @Test
    void variantPricesChangeAndStayInRange() {
        String id = id(createProduct("Oxford shirt", "", shirts, SHIRT_OPTIONS));
        String variant = id(addVariant(id, "OX-WHT-M", "{\"color\": \"White\", \"size\": \"M\"}", 129_900)
                .get("variants").get(0));

        HttpResponse<String> repriced = call("PATCH", "/v1/admin/catalog/products/" + id + "/variants/" + variant,
                admin, "{\"price_paise\": 99900}");
        HttpResponse<String> free = call("PATCH", "/v1/admin/catalog/products/" + id + "/variants/" + variant,
                admin, "{\"price_paise\": 0}");
        HttpResponse<String> unknown = call("PATCH",
                "/v1/admin/catalog/products/" + id + "/variants/" + UUID.randomUUID(), admin,
                "{\"price_paise\": 100}");

        assertThat(repriced.statusCode()).isEqualTo(200);
        assertThat(json(repriced).get("variants").get(0).get("price_paise").asLong()).isEqualTo(99_900);
        assertCode(free, 400, "validation_failed");
        assertCode(unknown, 404, "not_found");
    }

    @Test
    void changesNeedTheCurrentVersion() {
        String id = id(createProduct("Oxford shirt", "", shirts, SHIRT_OPTIONS));
        String change = "{\"title\": \"Oxford shirt, slim fit\"}";

        assertCode(call("PATCH", "/v1/admin/catalog/products/" + id, admin, change), 428, "precondition_required");
        assertCode(call("PATCH", "/v1/admin/catalog/products/" + id, admin, change, "If-Match", "\"7\""), 412,
                "precondition_failed");
        assertCode(call("PATCH", "/v1/admin/catalog/products/" + id, admin, change, "If-Match", "W/\"1\""), 412,
                "precondition_failed");

        HttpResponse<String> updated = call("PATCH", "/v1/admin/catalog/products/" + id, admin, change,
                "If-Match", "\"1\"");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.headers().firstValue("ETag")).hasValue("\"2\"");
        assertThat(json(updated).get("title").asString()).isEqualTo("Oxford shirt, slim fit");
        assertThat(json(updated).get("category").get("id").asString()).as("fields left out stay").isEqualTo(shirts);

        assertCode(call("PATCH", "/v1/admin/catalog/products/" + id, admin, change, "If-Match", "\"1\""), 412,
                "precondition_failed");
    }

    @Test
    void withVariantsOptionValuesCanOnlyBeAdded() {
        String id = id(createProduct("Oxford shirt", "", shirts, SHIRT_OPTIONS));
        addVariant(id, "OX-WHT-M", "{\"color\": \"White\", \"size\": \"M\"}", 129_900);

        HttpResponse<String> removed = call("PATCH", "/v1/admin/catalog/products/" + id, admin, """
                {"options": [{"name": "color", "values": ["White"]}, {"name": "size", "values": ["S", "M", "L"]}]}
                """, "If-Match", "\"2\"");
        HttpResponse<String> added = call("PATCH", "/v1/admin/catalog/products/" + id, admin, """
                {"options": [{"name": "color", "values": ["White", "Blue", "Black"]},
                             {"name": "size", "values": ["S", "M", "L", "XL"]}]}
                """, "If-Match", "\"2\"");

        assertCode(removed, 409, "options_locked");
        assertThat(added.statusCode()).isEqualTo(200);
        assertThat(json(added).get("options").get(1).get("values")).hasSize(4);
        assertThat(addVariant(id, "OX-BLK-XL", "{\"color\": \"black\", \"size\": \"xl\"}", 139_900)
                .get("variants")).hasSize(2);
    }

    @Test
    void archivingHidesAProductUntilItIsActivatedAgain() {
        String id = activeProduct("Oxford shirt", "", shirts, 129_900);

        HttpResponse<String> archived = call("POST", "/v1/admin/catalog/products/" + id + "/archive", admin, null);
        HttpResponse<String> again = call("POST", "/v1/admin/catalog/products/" + id + "/archive", admin, null);

        assertThat(json(archived).get("status").asString()).isEqualTo("ARCHIVED");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again).get("version").asLong()).as("archiving twice changes nothing")
                .isEqualTo(json(archived).get("version").asLong());
        HttpResponse<String> list = call("GET", "/v1/admin/catalog/products?status=ARCHIVED", admin, null);
        assertThat(json(list).get("items").get(0).get("status").asString()).isEqualTo("ARCHIVED");
        assertThat(activate(id).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void changesAreAudited() {
        String id = activeProduct("Oxford shirt", "", shirts, 129_900);

        List<String> actions = jdbc.sql("""
                        SELECT action FROM platform.audit_log
                        WHERE target_type = 'product' AND target_id = ? AND actor_id = ? ORDER BY id
                        """)
                .params(id, ADMIN_SUBJECT)
                .query(String.class)
                .list();

        assertThat(actions).containsExactly("catalog.product.created", "catalog.variant.created",
                "catalog.product.activated");
    }

    @Test
    void onlyAdminsManageProducts() {
        String id = activeProduct("Oxford shirt", "", shirts, 129_900);
        String customer = TestIdentityProvider.customer("shopper");

        assertThat(call("GET", "/v1/admin/catalog/products/" + id, null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/v1/admin/catalog/products/" + id, customer, null).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/v1/admin/catalog/products/" + id + "/archive", customer, null).statusCode())
                .isEqualTo(403);
        assertThat(call("GET", "/v1/admin/catalog/products/" + UUID.randomUUID(), admin, null).statusCode())
                .isEqualTo(404);
    }

    private HttpResponse<String> variant(String productId, String sku, String optionValues) {
        return call("POST", "/v1/admin/catalog/products/" + productId + "/variants", admin, """
                {"sku": "%s", "option_values": %s, "price_paise": 129900}
                """.formatted(sku, optionValues));
    }

    private HttpResponse<String> setVariantStatus(String productId, String variantId, String status) {
        return call("PATCH", "/v1/admin/catalog/products/" + productId + "/variants/" + variantId, admin,
                "{\"status\": \"" + status + "\"}");
    }

    private static List<String> statuses(JsonNode product) {
        return StreamSupport.stream(product.get("variants").spliterator(), false)
                .map(variant -> variant.get("status").asString())
                .toList();
    }

    private static void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }
}
