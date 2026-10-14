package com.ecommerce.platform.web;

import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.Caller;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The parts of the OpenAPI document that controllers cannot say (ADR-016). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static {
        // Resolved from the token, never sent by clients.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Caller.class);
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("E-commerce Order System API")
                        .version("v1")
                        .description("Errors are application/problem+json with a stable code; see docs/api.md."))
                // A fixed server, so the committed document does not depend on where it was generated.
                .servers(List.of(new Server().url("http://localhost:8080")
                        .description("Local: docker compose or ./gradlew bootTestRun")))
                .components(new Components().addSecuritySchemes(ApiController.BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("An access token from the identity provider (Keycloak).")));
    }

    @Bean
    OpenApiCustomizer rateLimitResponses() {
        return api -> {
            Schema<Object> problem = new Schema<>();
            problem.setType("object");
            problem.setRequired(List.of("type", "title", "status", "detail", "instance", "code"));
            problem.setProperties(Map.of(
                "type", new StringSchema(),
                "title", new StringSchema(),
                "status", new IntegerSchema()._enum(List.of(429)),
                "detail", new StringSchema(),
                "instance", new StringSchema(),
                "code", new StringSchema()._enum(List.of("rate_limited")),
                "request_id", new StringSchema()));
            api.getComponents().addSchemas("RateLimitProblem", problem);
            api.getComponents().addResponses("RateLimited", new ApiResponse()
                    .description("Application request quota exhausted; retry after the indicated delay.")
                    .addHeaderObject("Retry-After", new Header().description("Whole seconds until a token is available")
                            .schema(new IntegerSchema().minimum(BigDecimal.ONE)))
                    .content(new Content().addMediaType("application/problem+json",
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/RateLimitProblem")))));
            api.getPaths().forEach((path, item) -> {
                if (path.startsWith("/v1/")) {
                    item.readOperations().forEach(operation -> operation.getResponses().addApiResponse("429",
                            new ApiResponse().$ref("#/components/responses/RateLimited")));
                }
            });
        };
    }

    /**
     * Schemas as the API writes them. swagger-core introspects models with its own Jackson 2 mapper, which knows
     * nothing of {@code spring.jackson.property-naming-strategy}.
     */
    @Bean
    ModelResolver snakeCaseModels() {
        return new ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }
}
