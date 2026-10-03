package com.ecommerce.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed OpenAPI document matches the code (ADR-016). {@code ./gradlew updateOpenApi} runs this test with
 * {@code -Dopenapi.update=true}, which rewrites the file instead of failing.
 */
class OpenApiDocumentTests extends IntegrationTest {

    private static final Path COMMITTED = Path.of("docs/api/openapi.json");
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void theCommittedDocumentMatchesTheCode() throws IOException {
        HttpResponse<String> response = get(managementPort, "/actuator/openapi");
        assertThat(response.statusCode()).isEqualTo(200);
        String generated = CANONICAL.writeValueAsString(CANONICAL.readValue(response.body(), Map.class)) + "\n";

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, generated);
        }

        assertThat(COMMITTED).as("Run ./gradlew updateOpenApi").exists();
        assertThat(generated).as("docs/api/openapi.json is stale: run ./gradlew updateOpenApi and review the diff")
                .isEqualTo(Files.readString(COMMITTED));
    }

    @Test
    void theDocumentIsNotServedOnThePublicPort() {
        assertThat(get(port, "/actuator/openapi").statusCode()).isIn(401, 404);
        assertThat(get(port, "/v3/api-docs").statusCode()).isIn(401, 404);
    }

    /** springdoc numbers clashing method names ({@code product_1}) in scan order, which would make the file flap. */
    @Test
    void operationIdsAreUniqueWithoutNumbering() {
        Map<String, Object> document = CANONICAL.readValue(get(managementPort, "/actuator/openapi").body(),
                new TypeReference<Map<String, Object>>() { });
        List<String> ids = ((Map<?, ?>) document.get("paths")).values().stream()
                .flatMap(path -> ((Map<?, ?>) path).values().stream())
                .map(operation -> (String) ((Map<?, ?>) operation).get("operationId"))
                .toList();

        assertThat(ids).doesNotHaveDuplicates().noneMatch(id -> id.matches(".*_\\d+"));
    }

    /** springdoc names a schema after its class's simple name: two web records with one name would share a schema. */
    @Test
    void requestAndResponseRecordsHaveNamesOfTheirOwn() {
        Map<String, List<String>> byName = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ecommerce")
                .stream()
                .filter(type -> type.isRecord() && type.getPackageName().endsWith(".web"))
                .collect(Collectors.groupingBy(JavaClass::getSimpleName,
                        Collectors.mapping(JavaClass::getName, Collectors.toList())));

        assertThat(byName).isNotEmpty().allSatisfy((name, records) -> assertThat(records).as(name).hasSize(1));
    }
}
