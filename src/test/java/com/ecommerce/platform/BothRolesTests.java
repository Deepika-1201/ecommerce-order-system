package com.ecommerce.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class BothRolesTests extends IntegrationTest {

    @Test
    void infoReportsBothRoles() {
        HttpResponse<String> info = get(managementPort, "/actuator/info");

        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(json(info).get("roles").valueStream().map(node -> node.asString()))
                .containsExactly("api", "worker");
    }

    @Test
    void livenessAndReadinessAreUpOnBothPorts() {
        for (String path : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            HttpResponse<String> probe = get(managementPort, path);
            assertThat(probe.statusCode()).as(path).isEqualTo(200);
            assertThat(json(probe).get("status").asString()).as(path).isEqualTo("UP");
        }
        assertThat(get(port, "/livez").statusCode()).isEqualTo(200);
        assertThat(get(port, "/readyz").statusCode()).isEqualTo(200);
    }

    @Test
    void servesThePublicApi() {
        assertThat(get(port, API_PROBE).statusCode()).isEqualTo(200);
    }
}
