package com.ecommerce.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.platform.ApiException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ProductOptionsTests {

    private static final ProductOption COLOR = new ProductOption("color", List.of("Red", "Blue"));
    private static final ProductOption SIZE = new ProductOption("size", List.of("S", "M"));

    @Test
    void valuesAreTrimmed() {
        assertThat(ProductOptions.normalize(List.of(new ProductOption("color", List.of(" Red ", "Blue")))))
                .containsExactly(COLOR);
        assertThat(ProductOptions.normalize(null)).isEmpty();
    }

    @Test
    void malformedOptionsAreRefused() {
        assertInvalidOptions(List.of(COLOR, SIZE, new ProductOption("fit", List.of("Slim")),
                new ProductOption("sleeve", List.of("Full"))));
        assertInvalidOptions(List.of(new ProductOption("Color", List.of("Red"))));
        assertInvalidOptions(List.of(new ProductOption("1size", List.of("S"))));
        assertInvalidOptions(List.of(COLOR, new ProductOption("color", List.of("Green"))));
        assertInvalidOptions(List.of(new ProductOption("color", List.of())));
        assertInvalidOptions(List.of(new ProductOption("color", List.of("Red", " red"))));
        assertInvalidOptions(List.of(new ProductOption("color", List.of("  "))));
        assertInvalidOptions(List.of(new ProductOption("color", List.of("x".repeat(41)))));
        assertInvalidOptions(List.of(new ProductOption("size",
                IntStream.rangeClosed(1, 31).mapToObj(Integer::toString).toList())));
    }

    @Test
    void withVariantsValuesCanOnlyBeAppended() {
        ProductOptions.checkChangeAllowed(List.of(COLOR, SIZE),
                List.of(new ProductOption("color", List.of("Red", "Blue", "Green")), SIZE));

        assertOptionsLocked(List.of(COLOR, SIZE), List.of(COLOR));
        assertOptionsLocked(List.of(COLOR, SIZE), List.of(SIZE, COLOR));
        assertOptionsLocked(List.of(COLOR), List.of(new ProductOption("colour", COLOR.values())));
        assertOptionsLocked(List.of(COLOR), List.of(new ProductOption("color", List.of("Red"))));
        assertOptionsLocked(List.of(COLOR), List.of(new ProductOption("color", List.of("Blue", "Red"))));
        assertOptionsLocked(List.of(COLOR), List.of(new ProductOption("color", List.of("Red", "Navy"))));
    }

    @Test
    void variantValuesMatchCaseInsensitivelyAndTakeTheProductsSpelling() {
        Map<String, String> requested = new LinkedHashMap<>();
        requested.put("size", " m ");
        requested.put("color", "RED");

        Map<String, String> resolved = ProductOptions.resolve(List.of(COLOR, SIZE), requested);

        assertThat(resolved).containsExactly(Map.entry("color", "Red"), Map.entry("size", "M"));
        assertThat(ProductOptions.combinationKey(resolved)).isEqualTo("color=red;size=m");
        assertThat(ProductOptions.resolve(List.of(), null)).isEmpty();
    }

    @Test
    void aVariantNamesEveryDimensionWithAKnownValue() {
        assertInvalidValues(List.of(COLOR, SIZE), Map.of("color", "Red"));
        assertInvalidValues(List.of(COLOR), Map.of("color", "Red", "size", "M"));
        assertInvalidValues(List.of(COLOR), Map.of("color", "Green"));
        assertInvalidValues(List.of(COLOR), Collections.singletonMap("color", null));
        assertInvalidValues(List.of(), Map.of("color", "Red"));
    }

    private static void assertInvalidOptions(List<ProductOption> options) {
        assertThatThrownBy(() -> ProductOptions.normalize(options))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_options"));
    }

    private static void assertOptionsLocked(List<ProductOption> current, List<ProductOption> proposed) {
        assertThatThrownBy(() -> ProductOptions.checkChangeAllowed(current, proposed))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("options_locked"));
    }

    private static void assertInvalidValues(List<ProductOption> options, Map<String, String> requested) {
        assertThatThrownBy(() -> ProductOptions.resolve(options, requested))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("invalid_option_values"));
    }
}
