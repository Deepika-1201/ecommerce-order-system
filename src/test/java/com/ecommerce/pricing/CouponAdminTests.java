package com.ecommerce.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class CouponAdminTests extends IntegrationTest {

    private static final String COUPONS = "/v1/admin/pricing/coupons";

    private final String admin = TestIdentityProvider.admin("pricing-admin");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private CouponRedemptions redemptions;

    @BeforeEach
    void noCoupons() {
        jdbc.sql("TRUNCATE pricing.coupon_redemptions, pricing.coupons").update();
        PlatformTables.clear(jdbc);
    }

    @Test
    void aPercentCouponKeepsItsRuleAndStartsUnused() {
        HttpResponse<String> response = call("POST", COUPONS, admin, """
                {"code": "welcome-10", "kind": "PERCENT", "percent_bps": 1000, "max_discount_paise": 50000,
                 "min_order_paise": 99900, "total_limit": 100, "per_customer_limit": 1}
                """);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode coupon = json(response);
        assertThat(response.headers().firstValue("Location")).hasValue(COUPONS + "/" + coupon.get("id").asString());
        assertThat(coupon.get("code").asString()).isEqualTo("WELCOME-10");
        assertThat(coupon.get("kind").asString()).isEqualTo("PERCENT");
        assertThat(coupon.get("percent_bps").asInt()).isEqualTo(1_000);
        assertThat(coupon.get("max_discount_paise").asLong()).isEqualTo(50_000);
        assertThat(coupon.has("amount_paise")).isFalse();
        assertThat(coupon.get("active").asBoolean()).isTrue();
        assertThat(coupon.get("reserved").asInt()).isZero();
        assertThat(coupon.get("redeemed").asInt()).isZero();
        assertThat(coupon.has("valid_from")).as("defaults to now").isTrue();
        assertThat(coupon.has("valid_until")).as("open-ended").isFalse();
    }

    @Test
    void theRuleMustMatchItsKind() {
        assertCode(call("POST", COUPONS, admin, """
                {"code": "HALF-OFF", "kind": "PERCENT", "amount_paise": 5000}
                """), 400, "invalid_coupon_rule");
        assertCode(call("POST", COUPONS, admin, """
                {"code": "BOTH", "kind": "PERCENT", "percent_bps": 1000, "amount_paise": 5000}
                """), 400, "invalid_coupon_rule");
        assertCode(call("POST", COUPONS, admin, """
                {"code": "FIFTY", "kind": "FLAT", "amount_paise": 5000, "percent_bps": 10}
                """), 400, "invalid_coupon_rule");
        assertCode(call("POST", COUPONS, admin, """
                {"code": "x", "kind": "FLAT", "amount_paise": 5000}
                """), 400, "validation_failed");
        assertCode(call("POST", COUPONS, admin, """
                {"code": "TOO-MUCH", "kind": "PERCENT", "percent_bps": 10001}
                """), 400, "validation_failed");
        assertCode(call("POST", COUPONS, admin, """
                {"code": "BACKWARDS", "kind": "FLAT", "amount_paise": 5000,
                 "valid_from": "2026-11-01T00:00:00Z", "valid_until": "2026-10-01T00:00:00Z"}
                """), 400, "invalid_coupon_rule");
    }

    @Test
    void codesAreUniqueWhateverTheCase() {
        assertThat(create("FLAT50", "\"kind\": \"FLAT\", \"amount_paise\": 5000").statusCode()).isEqualTo(201);

        assertCode(create("flat50", "\"kind\": \"FLAT\", \"amount_paise\": 7000"), 409, "coupon_code_taken");
    }

    @Test
    void onlyTheSwitchTheEndAndTheLimitCanChange() {
        String id = id(create("SPRING", "\"kind\": \"FLAT\", \"amount_paise\": 5000, \"total_limit\": 5"));
        Instant end = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        HttpResponse<String> changed = call("PATCH", COUPONS + "/" + id, admin, """
                {"active": false, "valid_until": "%s", "total_limit": 7, "amount_paise": 1}
                """.formatted(end));

        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(json(changed).get("active").asBoolean()).isFalse();
        assertThat(Instant.parse(json(changed).get("valid_until").asString())).isEqualTo(end);
        assertThat(json(changed).get("total_limit").asInt()).isEqualTo(7);
        assertThat(json(changed).get("amount_paise").asLong()).as("the rule is fixed").isEqualTo(5_000);
    }

    @Test
    void aLimitCannotGoBelowCurrentUsage() {
        String id = id(create("THREE", "\"kind\": \"FLAT\", \"amount_paise\": 5000, \"total_limit\": 3"));
        UUID coupon = UUID.fromString(id);
        redemptions.reserve(UUID.randomUUID(), coupon, null);
        redemptions.reserve(UUID.randomUUID(), coupon, null);

        assertCode(call("PATCH", COUPONS + "/" + id, admin, "{\"total_limit\": 1}"), 409, "limit_below_usage");
        assertThat(call("PATCH", COUPONS + "/" + id, admin, "{\"total_limit\": 2}").statusCode()).isEqualTo(200);
        JsonNode coupon2 = json(call("GET", COUPONS + "/" + id, admin, null));
        assertThat(coupon2.get("reserved").asInt()).isEqualTo(2);
    }

    @Test
    void couponsArePagedNewestFirst() {
        List<String> created = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            created.add(id(create("PAGE-" + i, "\"kind\": \"FLAT\", \"amount_paise\": 100")));
        }
        List<String> seen = new ArrayList<>();
        String path = COUPONS + "?limit=2";
        int pages = 0;
        while (path != null) {
            JsonNode page = json(call("GET", path, admin, null));
            page.get("items").forEach(item -> seen.add(item.get("id").asString()));
            path = page.has("next_cursor") ? COUPONS + "?limit=2&cursor=" + page.get("next_cursor").asString() : null;
            pages++;
        }

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(created.reversed());
        assertCode(call("GET", COUPONS + "?cursor=bm9wZQ", admin, null), 400, "invalid_cursor");
    }

    @Test
    void onlyAdminsManageCouponsAndChangesAreAudited() {
        String body = "{\"code\": \"STAFF\", \"kind\": \"FLAT\", \"amount_paise\": 100}";
        assertThat(call("POST", COUPONS, null, body).statusCode()).isEqualTo(401);
        assertThat(call("POST", COUPONS, TestIdentityProvider.customer("shopper"), body).statusCode()).isEqualTo(403);

        String id = id(create("AUDITED", "\"kind\": \"FLAT\", \"amount_paise\": 100"));
        call("PATCH", COUPONS + "/" + id, admin, "{\"active\": false}");

        List<String> actions = jdbc.sql("""
                        SELECT action FROM platform.audit_log
                        WHERE target_type = 'coupon' AND target_id = ? AND actor_id = 'pricing-admin' ORDER BY id
                        """)
                .param(id)
                .query(String.class)
                .list();
        assertThat(actions).containsExactly("pricing.coupon.created", "pricing.coupon.updated");
    }

    private HttpResponse<String> create(String code, String rule) {
        return call("POST", COUPONS, admin, "{\"code\": \"" + code + "\", " + rule + "}");
    }

    private static String id(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    private static void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).isEqualTo(code);
    }
}
