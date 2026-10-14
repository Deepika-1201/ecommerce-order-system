package com.ecommerce.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
    "ecom.security.rate-limits.enabled=true",
    "ecom.security.rate-limits.ip-per-minute=4",
    "ecom.security.rate-limits.principal-writes-per-minute=1",
    "ecom.security.rate-limits.webhook-ip-per-minute=4",
    "ecom.security.rate-limits.instance-per-minute=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitHttpTests extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void authenticatedWritesAreLimitedPerSubjectBeforeIdempotencyButReadsStillWork() {
        String first = TestIdentityProvider.customer("rate-first");
        String second = TestIdentityProvider.customer("rate-second");
        int before = jdbc.sql("SELECT count(*) FROM platform.idempotency_keys").query(Integer.class).single();

        assertThat(call("POST", "/v1/me/orders", first, "{}", "Idempotency-Key", "rate-first").statusCode())
                .isEqualTo(400);
        HttpResponse<String> rejected = call("POST", "/v1/me/orders", first, "{}",
                "Idempotency-Key", "rate-second");

        assertThrottled(rejected);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.idempotency_keys").query(Integer.class).single())
                .isEqualTo(before);
        assertThat(call("POST", "/v1/me/orders", second, "{}", "Idempotency-Key", "rate-other").statusCode())
                .isEqualTo(400);
        assertThat(call("GET", "/v1/products", first, null).statusCode()).isEqualTo(200);
    }

    @Test
    void invalidTokensConsumeTheEarlyQuotaAndForwardedHeadersCannotResetIt() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(call("GET", "/v1/products", "invalid-token", null,
                    "X-Forwarded-For", "203.0.113." + attempt,
                    "Forwarded", "for=203.0.113." + attempt).statusCode()).isEqualTo(401);
        }

        assertThrottled(call("GET", "/v1/products", "another-invalid-token", null,
                "X-Forwarded-For", "198.51.100.9", "Forwarded", "for=198.51.100.9"));
    }

    @Test
    void aSaturatedApiStillAllowsProbesAndManagement() {
        exhaustApi();

        assertThrottled(get(port, "/v1/products"));
        assertThat(get(port, "/livez").statusCode()).isEqualTo(200);
        assertThat(get(port, "/readyz").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    @Test
    void webhookAuthenticationIsStillReachedWhenTheOrdinaryApiIsSaturated() {
        exhaustApi();
        assertThrottled(get(port, "/v1/products"));

        HttpResponse<String> webhook = post(port, "/v1/webhooks/payment-gateway", "{}");

        assertThat(webhook.statusCode()).isEqualTo(401);
        assertThat(webhook.headers().firstValue("Retry-After")).isEmpty();
    }

    @Test
    void encodedPathCharactersCannotBypassTheApplicationQuota() {
        exhaustApi();

        assertThrottled(get(port, "/%76%31/products"));
    }

    private void exhaustApi() {
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(get(port, "/v1/products").statusCode()).isEqualTo(200);
        }
    }

    private void assertThrottled(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(json(response).path("code").asString()).isEqualTo("rate_limited");
        assertThat(json(response).path("request_id").asString()).isNotBlank();
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())).isPositive();
    }
}