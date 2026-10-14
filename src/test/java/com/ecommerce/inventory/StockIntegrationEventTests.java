package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.IntegrationEvents;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StockIntegrationEventTests extends InventoryTest {

    @Autowired
    private JsonMapper json;

    @Autowired
    private IntegrationEvents contracts;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void receiptsReservationsAndReleasesPublishVersionedSnapshots() {
        UUID order = UUID.randomUUID();
        receive("EVENT-SKU", 5);
        reserve(order, line("EVENT-SKU", 2));
        reservations.release(order);
        reservations.release(order);

        List<JsonNode> events = events();
        assertThat(events).hasSize(3);
        assertThat(events).extracting(event -> event.path("data").path("available").asLong())
                .containsExactly(5L, 3L, 5L);
        assertThat(events).extracting(event -> event.path("sequence").asLong()).containsExactly(1L, 2L, 3L);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.path("aggregate_id").asString()).isEqualTo("[\"EVENT-SKU\",\"BLR1\"]");
            assertThat(event.path("aggregate_type").asString()).isEqualTo("stock");
            assertThat(event.path("source").asString()).isEqualTo("ecommerce/inventory");
            assertThat(event.path("version").asInt()).isEqualTo(1);
            assertThat(event.path("data").propertyNames()).containsExactlyInAnyOrder("sku", "location_code", "available");
        });
        assertInvariants();
    }

    @Test
    void handoverDoesNotChangeAvailabilityButReturningStockDoes() {
        UUID order = UUID.randomUUID();
        receive("EVENT-SKU", 3);
        reserve(order, line("EVENT-SKU", 2));
        reservations.commit(order);
        reservations.fulfill(order);
        assertThat(events()).hasSize(2);

        reservations.restockReturn(order);
        reservations.restockReturn(order);

        assertThat(events()).extracting(event -> event.path("data").path("available").asLong())
                .containsExactly(3L, 1L, 3L);
        assertThat(events()).extracting(event -> event.path("sequence").asLong()).containsExactly(1L, 2L, 4L);
        assertInvariants();
    }

    @Test
    void aRejectedReservationDoesNotPublishAnAvailabilityChange() {
        receive("EVENT-SKU", 1);
        reserve(UUID.randomUUID(), line("EVENT-SKU", 2));

        assertThat(events()).hasSize(1);
        assertInvariants();
    }

    @Test
    void aRolledBackStockChangeLeavesNeitherStockNorAnEvent() {
        transactions.executeWithoutResult(transaction -> {
            receive("EVENT-SKU", 3);
            assertThat(events()).hasSize(1);
            transaction.setRollbackOnly();
        });

        assertThat(events()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_items").query(Integer.class).single()).isZero();
    }

    private List<JsonNode> events() {
        List<String> envelopes = jdbc.sql("""
                        SELECT envelope FROM platform.outbox
                        WHERE destination = 'kafka:ecom.inventory.stock-events' ORDER BY id
                        """)
                .query(String.class).list();
            envelopes.forEach(envelope -> contracts.read(IntegrationEvents.STOCK_TOPIC,
                json.readTree(envelope).path("aggregate_id").asString().getBytes(StandardCharsets.UTF_8),
                envelope.getBytes(StandardCharsets.UTF_8)));
            return envelopes.stream().map(json::readTree).toList();
    }
}