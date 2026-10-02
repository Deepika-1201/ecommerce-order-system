package com.ecommerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ProductBrowsingTests extends CatalogTest {

    private String fashion;
    private String men;
    private String shirts;
    private String books;

    @BeforeEach
    void categories() {
        fashion = createCategory("Fashion", "fashion", null);
        men = createCategory("Men", "men", fashion);
        shirts = createCategory("Shirts", "shirts", men);
        books = createCategory("Books", "books", null);
    }

    @Test
    void onlyActiveProductsAreVisible() {
        String active = activeProduct("Linen shirt", "Breathable", shirts, 149_900);
        String draft = id(createProduct("Draft shirt", "", shirts, "[]"));
        String archived = activeProduct("Old shirt", "", shirts, 99_900);
        call("POST", "/v1/admin/catalog/products/" + archived + "/archive", admin, null);

        JsonNode list = json(call("GET", "/v1/products", null, null));

        assertThat(ids(list)).containsExactly(active);
        JsonNode item = list.get("items").get(0);
        assertThat(item.get("title").asString()).isEqualTo("Linen shirt");
        assertThat(item.get("category").get("slug").asString()).isEqualTo("shirts");
        assertThat(item.get("min_price_paise").asLong()).isEqualTo(149_900);
        assertThat(item.get("currency").asString()).isEqualTo("INR");
        assertThat(item.has("status")).isFalse();
        assertThat(list.has("next_cursor")).isFalse();
        assertThat(call("GET", "/v1/products/" + active, null, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/v1/products/" + draft, null, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/v1/products/" + archived, null, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/v1/products/" + UUID.randomUUID(), null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void theDetailShowsOnlyWhatShoppersMaySee() {
        String id = id(createProduct("Oxford shirt", "Cotton", shirts, """
                [{"name": "size", "values": ["M", "L"]}]
                """));
        addVariant(id, "OX-M", "{\"size\": \"M\"}", 129_900);
        String large = id(addVariant(id, "OX-L", "{\"size\": \"L\"}", 99_900).get("variants").get(1));
        activate(id);
        call("PATCH", "/v1/admin/catalog/products/" + id + "/variants/" + large, admin, "{\"status\": \"INACTIVE\"}");

        JsonNode product = json(call("GET", "/v1/products/" + id, null, null));
        JsonNode item = json(call("GET", "/v1/products", null, null)).get("items").get(0);

        assertThat(product.get("variants")).hasSize(1);
        assertThat(product.get("variants").get(0).get("sku").asString()).isEqualTo("OX-M");
        assertThat(product.get("variants").get(0).has("status")).isFalse();
        assertThat(product.get("options").get(0).get("name").asString()).isEqualTo("size");
        assertThat(product.get("images").isEmpty()).isTrue();
        assertThat(product.has("version")).isFalse();
        assertThat(product.has("gst_category")).isFalse();
        assertThat(item.get("min_price_paise").asLong()).as("the cheaper variant is inactive").isEqualTo(129_900);
        assertThat(item.has("image_url")).isFalse();
    }

    @Test
    void aCategoryIncludesItsSubcategories() {
        String shirt = activeProduct("Linen shirt", "", shirts, 149_900);
        String jacket = activeProduct("Denim jacket", "", men, 349_900);
        activeProduct("Monsoon stories", "", books, 39_900);

        assertThat(ids(json(call("GET", "/v1/products?category=fashion", null, null))))
                .containsExactlyInAnyOrder(shirt, jacket);
        assertThat(ids(json(call("GET", "/v1/products?category=shirts", null, null)))).containsExactly(shirt);
        assertThat(ids(json(call("GET", "/v1/products?category=toys", null, null)))).isEmpty();
    }

    @Test
    void searchRanksTitleMatchesAboveDescriptionMatches() {
        // Created first, so only ranking can put it ahead of the newer description match.
        String titled = activeProduct("Linen shirt", "Breathable", shirts, 149_900);
        String described = activeProduct("Summer kurta", "A light linen weave for hot days", fashion, 89_900);
        activeProduct("Monsoon stories", "Short fiction", books, 39_900);

        assertThat(ids(json(call("GET", "/v1/products?q=linen", null, null)))).containsExactly(titled, described);
        assertThat(ids(json(call("GET", "/v1/products?q=shirts", null, null)))).as("stemmed").containsExactly(titled);
        assertThat(ids(json(call("GET", "/v1/products?q=linen&category=shirts", null, null))))
                .containsExactly(titled);
        assertThat(ids(json(call("GET", "/v1/products?q=linen%20-kurta", null, null)))).containsExactly(titled);
    }

    @Test
    void browsingPagesHaveNoGapsOrRepeats() {
        List<String> created = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            created.add(activeProduct("Shirt " + i, "", shirts, 100_000 + i));
        }

        List<JsonNode> pages = pages("/v1/products?limit=3");

        assertThat(pages).hasSize(3);
        assertThat(pages.stream().flatMap(page -> ids(page).stream()).toList()).as("newest first")
                .containsExactlyElementsOf(newestFirst(created));
    }

    @Test
    void searchPagesHaveTheirOwnCursors() {
        List<String> created = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            created.add(activeProduct("Linen shirt " + i, "", shirts, 100_000 + i));
        }
        String browsingCursor = json(call("GET", "/v1/products?limit=2", null, null)).get("next_cursor").asString();

        List<JsonNode> pages = pages("/v1/products?q=linen&limit=2");
        HttpResponse<String> mixed = call("GET", "/v1/products?q=linen&cursor=" + browsingCursor, null, null);
        HttpResponse<String> forged = call("GET", "/v1/products?cursor=bm90LWEtY3Vyc29y", null, null);

        assertThat(pages).hasSize(3);
        assertThat(pages.stream().flatMap(page -> ids(page).stream()).toList())
                .containsExactlyInAnyOrderElementsOf(created);
        assertThat(mixed.statusCode()).isEqualTo(400);
        assertThat(json(mixed).get("code").asString()).isEqualTo("invalid_cursor");
        assertThat(forged.statusCode()).isEqualTo(400);
        assertThat(json(forged).get("code").asString()).isEqualTo("invalid_cursor");
    }

    @Test
    void queryParametersAreChecked() {
        for (String query : List.of("limit=0", "limit=51", "q=" + "a".repeat(101))) {
            HttpResponse<String> response = call("GET", "/v1/products?" + query, null, null);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json(response).get("code").asString()).isEqualTo("validation_failed");
        }
    }

    @Test
    void publicReadsCanBeCachedAndRevalidated() {
        String id = activeProduct("Linen shirt", "", shirts, 149_900);
        String variant = id(json(call("GET", "/v1/admin/catalog/products/" + id, admin, null)).get("variants").get(0));

        HttpResponse<String> first = call("GET", "/v1/products/" + id, null, null);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        HttpResponse<String> unchanged = call("GET", "/v1/products/" + id, null, null, "If-None-Match", etag);
        call("PATCH", "/v1/admin/catalog/products/" + id + "/variants/" + variant, admin, "{\"price_paise\": 139900}");
        HttpResponse<String> changed = call("GET", "/v1/products/" + id, null, null, "If-None-Match", etag);

        assertThat(first.headers().firstValue("Cache-Control")).hasValue("max-age=30, public");
        assertThat(unchanged.statusCode()).isEqualTo(304);
        assertThat(unchanged.body()).isEmpty();
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.headers().firstValue("ETag")).isPresent().get().isNotEqualTo(etag);
        for (String path : List.of("/v1/products", "/v1/categories")) {
            HttpResponse<String> response = call("GET", path, null, null);
            assertThat(response.headers().firstValue("Cache-Control")).as(path).hasValue("max-age=30, public");
            assertThat(response.headers().firstValue("ETag")).as(path).isPresent();
        }
        assertThat(call("GET", "/v1/admin/catalog/products/" + id, admin, null).headers().firstValue("Cache-Control"))
                .as("admin reads are never cached").hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }

    private List<JsonNode> pages(String firstPage) {
        List<JsonNode> pages = new ArrayList<>();
        String path = firstPage;
        while (path != null) {
            HttpResponse<String> response = call("GET", path, null, null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode page = json(response);
            pages.add(page);
            path = page.has("next_cursor") ? firstPage + "&cursor=" + page.get("next_cursor").asString() : null;
        }
        return pages;
    }

    private static List<String> ids(JsonNode list) {
        return StreamSupport.stream(list.get("items").spliterator(), false).map(item -> item.get("id").asString())
                .toList();
    }

    /** UUIDv7 ids sort by creation time, as PostgreSQL compares them. */
    private static List<String> newestFirst(List<String> ids) {
        return ids.stream().map(UUID::fromString).sorted(Comparator.reverseOrder()).map(UUID::toString).toList();
    }
}
