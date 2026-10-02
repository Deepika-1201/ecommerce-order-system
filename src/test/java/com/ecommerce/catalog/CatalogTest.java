package com.ecommerce.catalog;

import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Starts each test with an empty catalog and builds products through the admin API. */
abstract class CatalogTest extends IntegrationTest {

    protected static final String ADMIN_SUBJECT = "catalog-admin";

    protected final String admin = TestIdentityProvider.admin(ADMIN_SUBJECT);

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void emptyCatalog() {
        jdbc.sql("TRUNCATE catalog.product_images, catalog.variants, catalog.products, catalog.categories").update();
        PlatformTables.clear(jdbc);
    }

    protected String createCategory(String name, String slug, String parentId) {
        HttpResponse<String> response = call("POST", "/v1/admin/catalog/categories", admin, """
                {"name": "%s", "slug": "%s", "parent_id": %s}
                """.formatted(name, slug, parentId == null ? "null" : '"' + parentId + '"'));
        return created(response).get("id").asString();
    }

    protected JsonNode createProduct(String title, String description, String categoryId, String optionsJson) {
        return created(call("POST", "/v1/admin/catalog/products", admin, """
                {"title": "%s", "description": "%s", "category_id": "%s", "gst_category": "STANDARD",
                 "options": %s}
                """.formatted(title, description, categoryId, optionsJson)));
    }

    protected JsonNode addVariant(String productId, String sku, String optionValuesJson, long pricePaise) {
        return created(call("POST", "/v1/admin/catalog/products/" + productId + "/variants", admin, """
                {"sku": "%s", "option_values": %s, "price_paise": %d}
                """.formatted(sku, optionValuesJson, pricePaise)));
    }

    protected JsonNode activate(String productId) {
        HttpResponse<String> response = call("POST", "/v1/admin/catalog/products/" + productId + "/activate", admin,
                null);
        if (response.statusCode() != 200) {
            throw new AssertionError("Activation failed: " + response.body());
        }
        return json(response);
    }

    /** A product without options, with one variant at this price, visible to everyone. */
    protected String activeProduct(String title, String description, String categoryId, long pricePaise) {
        String id = createProduct(title, description, categoryId, "[]").get("id").asString();
        addVariant(id, "SKU-" + id.substring(24), "{}", pricePaise);
        activate(id);
        return id;
    }

    protected static String id(JsonNode node) {
        return node.get("id").asString();
    }

    private static JsonNode created(HttpResponse<String> response) {
        if (response.statusCode() != 201) {
            throw new AssertionError("Expected 201 but got " + response.statusCode() + ": " + response.body());
        }
        return json(response);
    }
}
