package com.ecommerce.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ecom.roles=worker")
class WorkerRoleTests extends IntegrationTest {

    @Test
    void infoReportsOnlyTheWorkerRole() {
        HttpResponse<String> info = get(managementPort, "/actuator/info");

        assertThat(json(info).get("roles").valueStream().map(node -> node.asString())).containsExactly("worker");
    }

    @Test
    void doesNotServeThePublicApi() {
        HttpResponse<String> response = get(port, API_PROBE);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("not_found");
    }

    @Test
    void isReadyForTraffic() {
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }
}
