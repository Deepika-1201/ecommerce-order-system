package com.ecommerce.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ecom.roles=api")
class ApiRoleTests extends IntegrationTest {

    @Test
    void infoReportsOnlyTheApiRole() {
        HttpResponse<String> info = get(managementPort, "/actuator/info");

        assertThat(json(info).get("roles").valueStream().map(node -> node.asString())).containsExactly("api");
    }

    @Test
    void servesThePublicApi() {
        assertThat(get(port, API_PROBE).statusCode()).isEqualTo(200);
    }
}
