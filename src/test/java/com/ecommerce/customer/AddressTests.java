package com.ecommerce.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AddressTests extends IntegrationTest {

    static final String HOME = """
            {"recipient_name": "Asha Rao", "phone": "9876543210", "line1": "12, 4th Cross, Indiranagar",
             "landmark": "Near the metro", "city": "Bengaluru", "state_code": "29", "pin_code": "560038"}
            """;

    private final String token = TestIdentityProvider.customer("user-" + UUID.randomUUID());

    @Test
    void anAddedAddressCanBeReadBack() {
        HttpResponse<String> created = call("POST", "/v1/me/addresses", token, HOME);

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode address = json(created);
        assertThat(created.headers().firstValue("Location")).hasValue("/v1/me/addresses/" + idOf(address));
        assertThat(address.get("phone").asString()).isEqualTo("+919876543210");
        assertThat(address.get("state_code").asString()).isEqualTo("29");
        assertThat(address.get("state_name").asString()).isEqualTo("Karnataka");
        assertThat(address.get("is_default").asBoolean()).as("the first address is the default").isTrue();
        assertThat(address.has("line2")).isFalse();

        HttpResponse<String> read = call("GET", "/v1/me/addresses/" + idOf(address), token, null);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json(read)).isEqualTo(address);
    }

    @Test
    void anAddressCanBeReplacedAndDeleted() {
        String id = idOf(json(call("POST", "/v1/me/addresses", token, HOME)));

        HttpResponse<String> replaced = call("PUT", "/v1/me/addresses/" + id, token, """
                {"recipient_name": "Asha Rao", "phone": "+91 91234 56789", "line1": "Flat 3B, Lake View",
                 "city": "Mumbai", "state_code": "27", "pin_code": "400050"}
                """);

        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(json(replaced).get("city").asString()).isEqualTo("Mumbai");
        assertThat(json(replaced).get("state_name").asString()).isEqualTo("Maharashtra");
        assertThat(json(replaced).has("landmark")).as("a replacement drops fields left out").isFalse();
        assertThat(json(replaced).get("is_default").asBoolean()).as("replacing keeps the default").isTrue();

        assertThat(call("DELETE", "/v1/me/addresses/" + id, token, null).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/v1/me/addresses/" + id, token, null).statusCode()).isEqualTo(404);
    }

    @Test
    void exactlyOneAddressIsTheDefault() {
        String first = idOf(json(call("POST", "/v1/me/addresses", token, HOME)));
        String second = idOf(json(call("POST", "/v1/me/addresses", token, HOME)));
        String third = idOf(json(call("POST", "/v1/me/addresses", token, HOME)));
        assertThat(defaults()).containsExactly(first);

        HttpResponse<String> made = call("POST", "/v1/me/addresses/" + second + "/default", token, null);
        assertThat(made.statusCode()).isEqualTo(200);
        assertThat(json(made).get("is_default").asBoolean()).isTrue();
        assertThat(defaults()).containsExactly(second);
        assertThat(ids().getFirst()).as("the default is listed first").isEqualTo(second);

        call("DELETE", "/v1/me/addresses/" + second, token, null);
        assertThat(defaults()).as("the newest remaining address takes over").containsExactly(third);

        call("DELETE", "/v1/me/addresses/" + third, token, null);
        call("DELETE", "/v1/me/addresses/" + first, token, null);
        assertThat(ids()).isEmpty();
    }

    @Test
    void aCustomerKeepsAtMostTenAddresses() {
        for (int i = 0; i < 10; i++) {
            assertThat(call("POST", "/v1/me/addresses", token, HOME).statusCode()).isEqualTo(201);
        }

        HttpResponse<String> eleventh = call("POST", "/v1/me/addresses", token, HOME);

        assertThat(eleventh.statusCode()).isEqualTo(409);
        assertThat(json(eleventh).get("code").asString()).isEqualTo("address_limit_reached");
        assertThat(ids()).hasSize(10);
    }

    @Test
    void invalidFieldsAreReportedTogether() {
        HttpResponse<String> response = call("POST", "/v1/me/addresses", token, """
                {"recipient_name": "", "phone": "12345", "line1": "12, 4th Cross",
                 "city": "Bengaluru", "state_code": "25", "pin_code": "060038"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("validation_failed");
        assertThat(StreamSupport.stream(json(response).get("errors").spliterator(), false)
                .map(error -> error.get("field").asString()))
                .containsExactlyInAnyOrder("recipient_name", "phone", "state_code", "pin_code");
        assertThat(ids()).isEmpty();
    }

    @Test
    void statesAreListedForEveryoneAndCached() {
        HttpResponse<String> response = call("GET", "/v1/states", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("items")).hasSize(36);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("max-age=86400, public");
    }

    private List<String> ids() {
        return StreamSupport.stream(json(call("GET", "/v1/me/addresses", token, null)).get("items").spliterator(),
                false).map(AddressTests::idOf).toList();
    }

    private List<String> defaults() {
        return StreamSupport.stream(json(call("GET", "/v1/me/addresses", token, null)).get("items").spliterator(),
                false).filter(address -> address.get("is_default").asBoolean()).map(AddressTests::idOf).toList();
    }

    static String idOf(JsonNode address) {
        return address.get("id").asString();
    }
}
