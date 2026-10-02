package com.ecommerce.support;

import com.ecommerce.platform.ApiController;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The whole application on random ports against embedded PostgreSQL. Subclasses choose the roles. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(IntegrationTest.RoleProbeController.class)
public abstract class IntegrationTest {

    protected static final String API_PROBE = "/test/role-probe";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @LocalServerPort
    protected int port;

    @LocalManagementPort
    protected int managementPort;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    protected HttpResponse<String> get(int targetPort, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError("GET " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    /** Stands in for a module's public controller until the modules have their own. */
    @TestComponent
    @ApiController
    static class RoleProbeController {

        @GetMapping(API_PROBE)
        Map<String, Object> probe() {
            return Map.of("served", true);
        }
    }
}
