package com.ecommerce.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The phase 3 exit criterion: one customer can never reach another's addresses (ADR-017). */
class CrossCustomerAccessTests extends IntegrationTest {

    private final String asha = TestIdentityProvider.customer("asha-" + UUID.randomUUID());
    private final String ravi = TestIdentityProvider.customer("ravi-" + UUID.randomUUID());

    private String ashasAddress;
    private JsonNode ashasAddressBefore;

    @BeforeEach
    void ashaHasTwoAddresses() {
        call("POST", "/v1/me/addresses", asha, AddressTests.HOME);
        HttpResponse<String> second = call("POST", "/v1/me/addresses", asha, AddressTests.HOME);
        ashasAddress = AddressTests.idOf(json(second));
        ashasAddressBefore = json(second);
        call("POST", "/v1/me/addresses", ravi, AddressTests.HOME);
    }

    @Test
    void anotherCustomerCannotReadIt() {
        assertNotFound(call("GET", "/v1/me/addresses/" + ashasAddress, ravi, null));
        assertThat(json(call("GET", "/v1/me/addresses", ravi, null)).get("items")).hasSize(1);
    }

    @Test
    void anotherCustomerCannotReplaceIt() {
        assertNotFound(call("PUT", "/v1/me/addresses/" + ashasAddress, ravi, """
                {"recipient_name": "Ravi", "phone": "9123456789", "line1": "Somewhere else",
                 "city": "Chennai", "state_code": "33", "pin_code": "600001"}
                """));
        assertUnchanged();
    }

    @Test
    void anotherCustomerCannotDeleteIt() {
        assertNotFound(call("DELETE", "/v1/me/addresses/" + ashasAddress, ravi, null));
        assertUnchanged();
    }

    @Test
    void anotherCustomerCannotMakeItTheirDefault() {
        assertNotFound(call("POST", "/v1/me/addresses/" + ashasAddress + "/default", ravi, null));
        assertUnchanged();
    }

    @Test
    void notFoundLooksTheSameForSomeoneElsesAddressAndForNoAddress() {
        JsonNode someoneElses = json(call("GET", "/v1/me/addresses/" + ashasAddress, ravi, null));
        JsonNode nobodys = json(call("GET", "/v1/me/addresses/" + UUID.randomUUID(), ravi, null));

        assertThat(someoneElses.get("detail")).isEqualTo(nobodys.get("detail"));
        assertThat(someoneElses.get("code")).isEqualTo(nobodys.get("code"));
    }

    @Test
    void staffCannotUseTheCustomerEndpoints() {
        String admin = TestIdentityProvider.admin("admin-" + UUID.randomUUID());
        String support = TestIdentityProvider.token("support-" + UUID.randomUUID()).roles("support").sign();

        assertThat(call("GET", "/v1/me/addresses/" + ashasAddress, admin, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/v1/me/addresses/" + ashasAddress, support, null).statusCode()).isEqualTo(403);
    }

    private void assertNotFound(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("not_found");
    }

    private void assertUnchanged() {
        HttpResponse<String> read = call("GET", "/v1/me/addresses/" + ashasAddress, asha, null);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json(read)).isEqualTo(ashasAddressBefore);
    }
}
