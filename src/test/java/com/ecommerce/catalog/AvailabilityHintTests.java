package com.ecommerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.catalog.domain.AvailabilityHints;
import com.ecommerce.catalog.domain.AvailabilityHints.Hint;
import com.ecommerce.platform.IntegrationEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class AvailabilityHintTests extends CatalogTest {

    @Autowired
    private AvailabilityHints hints;

    @Autowired
    private JsonMapper json;

    @BeforeEach
    void clearHints() {
        jdbc.sql("TRUNCATE catalog.availability_hints").update();
    }

    @Test
    void onlyNewerVersionsApplyAndARepeatedMessageDoesNotApplyAgain() {
        IntegrationEvent latest = event("SKU-ONE", "BLR1", 0, 9_007_199_254_740_993L);
        hints.apply(latest);
        hints.apply(event("SKU-ONE", "BLR1", 8, 1));
        hints.apply(latest);

        assertThat(hints.forSkus(List.of("SKU-ONE"))).containsEntry("SKU-ONE", Hint.SOLD_OUT);
        assertThat(jdbc.sql("SELECT sequence FROM catalog.availability_hints").query(Long.class).single())
                .isEqualTo(9_007_199_254_740_993L);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.processed_messages").query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void locationsHaveIndependentSequencesAndUnknownSkusHaveNoInventedStock() {
        hints.apply(event("SKU-ONE", "BLR1", 0, 40));
        hints.apply(event("SKU-ONE", "DEL1", 2, 1));
        assertThat(hints.forSkus(List.of("SKU-ONE", "MISSING")))
                .containsExactlyEntriesOf(Map.of("SKU-ONE", Hint.IN_STOCK));
        hints.apply(event("SKU-ONE", "DEL1", 0, 2));
        assertThat(hints.forSkus(List.of("SKU-ONE"))).containsEntry("SKU-ONE", Hint.SOLD_OUT);
        assertThat(hints.forSkus(List.of())).isEmpty();
    }

    @Test
    void publicVariantsExposeAnExplicitHintWithoutHidingSoldOutProducts() {
        String category = createCategory("Hints", "hints", null);
        String product = activeProduct("Available?", "Projection example", category, 10000);
        var response = call("GET", "/v1/products/" + product, null, null);
        String sku = json(response).path("variants").get(0).path("sku").asString();
        assertThat(json(response).path("variants").get(0).path("availability_hint").asString()).isEqualTo("UNKNOWN");

        hints.apply(event(sku, "BLR1", 3, 1));
        assertThat(json(call("GET", "/v1/products/" + product, null, null))
                .path("variants").get(0).path("availability_hint").asString()).isEqualTo("IN_STOCK");
        hints.apply(event(sku, "BLR1", 0, 2));
        response = call("GET", "/v1/products/" + product, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("variants").get(0).path("availability_hint").asString()).isEqualTo("SOLD_OUT");
    }

    private IntegrationEvent event(String sku, String location, long available, long sequence) {
        return new IntegrationEvent(UUID.randomUUID(), "stock.level_changed",
                json.writeValueAsString(List.of(sku, location)), sequence, Instant.now(), sku,
                json.valueToTree(Map.of("sku", sku, "location_code", location, "available", available)));
    }
}