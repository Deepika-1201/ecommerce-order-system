package com.ecommerce.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class CustomerProfileTests extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theFirstCallCreatesTheProfileFromTheToken() {
        String subject = newSubject();

        HttpResponse<String> response = call("GET", "/v1/me",
                TestIdentityProvider.token(subject).roles("customer").email("asha@example.test").name("Asha Rao")
                        .sign(),
                null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode profile = json(response);
        assertThat(profile.get("email").asString()).isEqualTo("asha@example.test");
        assertThat(profile.get("name").asString()).isEqualTo("Asha Rao");
        assertThat(profile.has("phone")).isFalse();
        assertThat(customersWithSubject(subject)).isEqualTo(1);
    }

    @Test
    void concurrentFirstCallsCreateOneProfile() throws Exception {
        String subject = newSubject();
        String token = TestIdentityProvider.customer(subject);

        List<Future<Integer>> calls = new ArrayList<>();
        try (ExecutorService callers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                calls.add(callers.submit((Callable<Integer>) () -> call("GET", "/v1/me", token, null).statusCode()));
            }
            for (Future<Integer> status : calls) {
                assertThat(status.get()).isEqualTo(200);
            }
        }

        assertThat(customersWithSubject(subject)).isEqualTo(1);
    }

    @Test
    void theEmailFollowsTheIdentityProvider() {
        String subject = newSubject();
        call("GET", "/v1/me", TestIdentityProvider.token(subject).roles("customer").email("old@example.test").sign(),
                null);

        HttpResponse<String> response = call("GET", "/v1/me",
                TestIdentityProvider.token(subject).roles("customer").email("new@example.test").sign(), null);

        assertThat(json(response).get("email").asString()).isEqualTo("new@example.test");
    }

    @Test
    void theCustomerCanChangeNameAndPhone() {
        String token = TestIdentityProvider.customer(newSubject());

        HttpResponse<String> updated = call("PATCH", "/v1/me", token,
                "{\"name\": \"Asha R.\", \"phone\": \"98765 43210\"}");

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json(updated).get("name").asString()).isEqualTo("Asha R.");
        assertThat(json(updated).get("phone").asString()).isEqualTo("+919876543210");

        HttpResponse<String> phoneOnly = call("PATCH", "/v1/me", token, "{\"phone\": \"+91 91234 56789\"}");
        assertThat(json(phoneOnly).get("name").asString()).as("left unchanged").isEqualTo("Asha R.");
        assertThat(json(phoneOnly).get("phone").asString()).isEqualTo("+919123456789");
    }

    @Test
    void anInvalidPhoneIsRefusedWithTheField() {
        HttpResponse<String> response = call("PATCH", "/v1/me", TestIdentityProvider.customer(newSubject()),
                "{\"phone\": \"12345\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("validation_failed");
        assertThat(json(response).get("errors").get(0).get("field").asString()).isEqualTo("phone");
    }

    @Test
    void onlyCustomersHaveAProfile() {
        assertThat(call("GET", "/v1/me", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/v1/me", TestIdentityProvider.admin(newSubject()), null).statusCode()).isEqualTo(403);
    }

    private int customersWithSubject(String subject) {
        return jdbc.sql("SELECT count(*) FROM customer.customers WHERE subject = ?")
                .param(subject)
                .query(Integer.class)
                .single();
    }

    private static String newSubject() {
        return "user-" + UUID.randomUUID();
    }
}
