package com.ecommerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CategoryTests extends CatalogTest {

    @Test
    void everyoneSeesTheTreeSortedByName() {
        String fashion = createCategory("Fashion", "fashion", null);
        createCategory("Women", "women", fashion);
        createCategory("Men", "men", fashion);
        createCategory("Books", "books", null);

        HttpResponse<String> response = call("GET", "/v1/categories", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode items = json(response).get("items");
        assertThat(names(items)).containsExactly("Books", "Fashion");
        assertThat(names(items.get(1).get("children"))).containsExactly("Men", "Women");
        assertThat(items.get(1).get("slug").asString()).isEqualTo("fashion");
        assertThat(items.get(0).get("children").isEmpty()).isTrue();
    }

    @Test
    void slugsAreUniqueAndWellFormed() {
        createCategory("Fashion", "fashion", null);

        HttpResponse<String> duplicate = call("POST", "/v1/admin/catalog/categories", admin, """
                {"name": "Fashion again", "slug": "fashion"}
                """);
        HttpResponse<String> malformed = call("POST", "/v1/admin/catalog/categories", admin, """
                {"name": "Kids", "slug": "Kids Wear"}
                """);

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json(duplicate).get("code").asString()).isEqualTo("category_slug_taken");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).get("code").asString()).isEqualTo("validation_failed");
    }

    @Test
    void theParentMustExist() {
        HttpResponse<String> response = call("POST", "/v1/admin/catalog/categories", admin, """
                {"name": "Orphan", "slug": "orphan", "parent_id": "%s"}
                """.formatted(UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json(response).get("code").asString()).isEqualTo("unknown_category");
    }

    @Test
    void theTreeIsAtMostFourLevelsDeep() {
        String level1 = createCategory("Fashion", "fashion", null);
        String level2 = createCategory("Men", "men", level1);
        String level3 = createCategory("Shirts", "shirts", level2);
        String level4 = createCategory("Formal", "formal", level3);

        HttpResponse<String> level5 = call("POST", "/v1/admin/catalog/categories", admin, """
                {"name": "Slim fit", "slug": "slim-fit", "parent_id": "%s"}
                """.formatted(level4));

        assertThat(level5.statusCode()).isEqualTo(409);
        assertThat(json(level5).get("code").asString()).isEqualTo("category_too_deep");
    }

    @Test
    void movesKeepTheTreeFreeOfCyclesAndShallow() {
        String fashion = createCategory("Fashion", "fashion", null);
        String men = createCategory("Men", "men", fashion);
        String shirts = createCategory("Shirts", "shirts", men);
        String home = createCategory("Home", "home", null);
        String decor = createCategory("Decor", "decor", home);
        String lamps = createCategory("Lamps", "lamps", decor);

        HttpResponse<String> cycle = move(fashion, shirts);
        HttpResponse<String> tooDeep = move(men, lamps);
        HttpResponse<String> moved = move(men, home);
        HttpResponse<String> toTop = move(shirts, null);

        assertThat(cycle.statusCode()).isEqualTo(409);
        assertThat(json(cycle).get("code").asString()).isEqualTo("category_cycle");
        assertThat(tooDeep.statusCode()).isEqualTo(409);
        assertThat(json(tooDeep).get("code").asString()).isEqualTo("category_too_deep");
        assertThat(moved.statusCode()).isEqualTo(200);
        assertThat(json(moved).get("parent_id").asString()).isEqualTo(home);
        assertThat(toTop.statusCode()).isEqualTo(200);
        assertThat(json(toTop).has("parent_id")).isFalse();
        JsonNode roots = json(call("GET", "/v1/categories", null, null)).get("items");
        assertThat(names(roots)).containsExactly("Fashion", "Home", "Shirts");
        assertThat(names(roots.get(1).get("children"))).containsExactly("Decor", "Men");
    }

    @Test
    void renamingChangesTheNameAndSlug() {
        String id = createCategory("Mens", "mens", null);

        HttpResponse<String> renamed = call("PATCH", "/v1/admin/catalog/categories/" + id, admin, """
                {"name": "Men", "slug": "men"}
                """);

        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(json(renamed).get("name").asString()).isEqualTo("Men");
        assertThat(json(renamed).get("slug").asString()).isEqualTo("men");
        assertThat(call("PATCH", "/v1/admin/catalog/categories/" + UUID.randomUUID(), admin, "{\"name\": \"X\"}")
                .statusCode()).isEqualTo(404);
    }

    @Test
    void changesAreAudited() {
        String id = createCategory("Fashion", "fashion", null);
        move(id, null);

        List<String> actions = jdbc.sql("""
                        SELECT action FROM platform.audit_log
                        WHERE target_type = 'category' AND target_id = ? AND actor_id = ? ORDER BY id
                        """)
                .params(id, ADMIN_SUBJECT)
                .query(String.class)
                .list();

        assertThat(actions).containsExactly("catalog.category.created", "catalog.category.moved");
    }

    @Test
    void onlyAdminsChangeCategories() {
        String body = """
                {"name": "Fashion", "slug": "fashion"}
                """;
        HttpResponse<String> anonymous = call("POST", "/v1/admin/catalog/categories", null, body);
        HttpResponse<String> customer = call("POST", "/v1/admin/catalog/categories",
                TestIdentityProvider.customer("shopper"), body);
        HttpResponse<String> support = call("POST", "/v1/admin/catalog/categories",
                TestIdentityProvider.token("agent").roles("support").sign(), body);

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(customer.statusCode()).isEqualTo(403);
        assertThat(support.statusCode()).isEqualTo(403);
        assertThat(json(call("GET", "/v1/categories", null, null)).get("items").isEmpty()).isTrue();
    }

    private HttpResponse<String> move(String id, String parentId) {
        return call("POST", "/v1/admin/catalog/categories/" + id + "/move", admin, """
                {"parent_id": %s}
                """.formatted(parentId == null ? "null" : '"' + parentId + '"'));
    }

    private static List<String> names(JsonNode categories) {
        return StreamSupport.stream(categories.spliterator(), false).map(node -> node.get("name").asString()).toList();
    }
}
