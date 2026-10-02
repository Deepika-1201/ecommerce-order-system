package com.ecommerce.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.HttpAccessRules;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.JsonNode;

/** Every token check, through the production decoder, against the test identity provider (LLD §3.2). */
@Import({TokenValidationTests.SecuredFixture.class, TokenValidationTests.FixtureAccess.class})
class TokenValidationTests extends IntegrationTest {

    private static final String CUSTOMER_ONLY = "/test/secured/customer";
    private static final String WITHOUT_RULE = "/test/secured/unlisted";

    @Test
    void aValidCustomerTokenReachesTheHandlerAsACaller() {
        HttpResponse<String> response = call("GET", CUSTOMER_ONLY,
                TestIdentityProvider.token("user-1").roles("customer", "offline_access").email("asha@example.test")
                        .name("Asha").sign(),
                null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode caller = json(response);
        assertThat(caller.get("subject").asString()).isEqualTo("user-1");
        assertThat(caller.get("roles").asString()).as("unknown roles are dropped").isEqualTo("[CUSTOMER]");
        assertThat(caller.get("email").asString()).isEqualTo("asha@example.test");
        assertThat(caller.get("name").asString()).isEqualTo("Asha");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value ->
                assertThat(value).contains("no-store"));
    }

    @Test
    void aRequestWithoutATokenIsUnauthorized() {
        HttpResponse<String> response = call("GET", CUSTOMER_ONLY, null, null);

        assertUnauthorized(response);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
    }

    @Test
    void anExpiredTokenIsUnauthorized() {
        assertInvalidToken(TestIdentityProvider.token("user-1").roles("customer")
                .expiresAt(Instant.now().minus(Duration.ofMinutes(2))).sign());
    }

    @Test
    void aTokenNotYetValidIsUnauthorized() {
        assertInvalidToken(TestIdentityProvider.token("user-1").roles("customer")
                .notBefore(Instant.now().plus(Duration.ofMinutes(2))).sign());
    }

    @Test
    void aTokenFromAnotherIssuerIsUnauthorized() {
        assertInvalidToken(TestIdentityProvider.token("user-1").roles("customer")
                .issuer("https://idp.test/realms/other").sign());
    }

    @Test
    void aTokenForAnotherAudienceIsUnauthorized() {
        assertInvalidToken(TestIdentityProvider.token("user-1").roles("customer").audience("account").sign());
    }

    @Test
    void aTokenSignedWithAnotherKeyIsUnauthorized() {
        assertInvalidToken(TestIdentityProvider.token("user-1").roles("customer").forged().sign());
    }

    @Test
    void anUnsignedTokenIsUnauthorized() {
        assertInvalidToken(TestIdentityProvider.token("user-1").roles("customer").unsigned());
    }

    @Test
    void aTokenWithoutASubjectIsUnauthorized() {
        assertInvalidToken(TestIdentityProvider.token(null).roles("customer").sign());
    }

    /** Spring Security rejects a missing claim by itself; a blank one only our validator catches. */
    @Test
    void aTokenWithABlankSubjectIsUnauthorized() {
        assertInvalidToken(TestIdentityProvider.token("  ").roles("customer").sign());
    }

    @Test
    void somethingThatIsNotATokenIsUnauthorized() {
        assertInvalidToken("not-a-token");
    }

    @Test
    void aValidTokenWithoutTheRoleIsForbidden() {
        HttpResponse<String> response = call("GET", CUSTOMER_ONLY, TestIdentityProvider.admin("admin-1"), null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertProblem(response, "forbidden");
    }

    @Test
    void anEndpointWithoutAnAccessRuleIsDenied() {
        assertThat(call("GET", WITHOUT_RULE, TestIdentityProvider.customer("user-1"), null).statusCode())
                .isEqualTo(403);
        assertThat(call("GET", WITHOUT_RULE, null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void managementEndpointsNeedNoToken() {
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/info").statusCode()).isEqualTo(200);
    }

    private void assertInvalidToken(String token) {
        HttpResponse<String> response = call("GET", CUSTOMER_ONLY, token, null);

        assertUnauthorized(response);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
    }

    private static void assertUnauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertProblem(response, "unauthorized");
        assertThat(json(response).get("detail").asString()).isEqualTo("A valid access token is required.");
    }

    private static void assertProblem(HttpResponse<String> response, String code) {
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        JsonNode problem = json(response);
        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(problem.get("request_id").asString()).isNotBlank();
        assertThat(problem.get("status").asInt()).isEqualTo(response.statusCode());
    }

    @TestComponent
    @ApiController
    static class SecuredFixture {

        @GetMapping(CUSTOMER_ONLY)
        Map<String, Object> customerOnly(Caller caller) {
            return Map.of("subject", caller.subject(), "roles", new TreeSet<>(caller.roles()).toString(),
                    "email", caller.email(), "name", caller.name());
        }

        @GetMapping(WITHOUT_RULE)
        Map<String, Object> withoutRule() {
            return Map.of("reached", true);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureAccess {

        @Bean
        HttpAccessRules securedFixtureAccess() {
            return rules -> rules.requestMatchers(CUSTOMER_ONLY).hasRole("CUSTOMER");
        }
    }
}
