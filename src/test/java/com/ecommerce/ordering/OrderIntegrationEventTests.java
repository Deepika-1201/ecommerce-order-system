package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.platform.IntegrationEvents;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OrderIntegrationEventTests extends OrderingTest {

    @Autowired
    private JsonMapper json;

    @Autowired
    private IntegrationEvents contracts;

    @AfterEach
    void removeFailure() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_order_event ON platform.outbox").update();
    }

    @Test
    void theJourneyPublishesEachCommittedMilestoneOnceWithoutPii() {
        UUID orderId = confirmed(product("Event test", 50_000, 3), 1, null);
        shipments.handOver(orderId);
        deliver();
        shipments.deliver(orderId);
        deliver();
        publish(new ShipmentDelivered(orderId), "shipment", orderId);
        deliver();

        List<JsonNode> events = events(orderId);
        assertThat(events).extracting(event -> event.path("type").asString())
                .containsExactly("order.placed", "order.confirmed", "order.shipped", "order.delivered");
        assertThat(events).extracting(event -> event.path("sequence").asLong()).isSorted().doesNotHaveDuplicates();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.path("aggregate_id").asString()).isEqualTo(orderId.toString());
            assertThat(event.path("data").path("order_id").asString()).isEqualTo(orderId.toString());
            assertThat(event.path("data").path("customer_id").asString()).isNotBlank();
            assertThat(event.path("source").asString()).isEqualTo("ecommerce/ordering");
            assertThat(event.toString()).doesNotContain("Asha", "9876543210", "Indiranagar", "checkout_url",
                    "recipient_name", "email", "delivery_address");
        });
        assertThat(events.get(1).path("data").path("amount_paise").asLong()).isEqualTo(grandTotal(orderId));
        assertThat(events.get(2).path("data").path("awb").asString()).isNotBlank();
    }

    @Test
    void rejectedOrdersPublishTheReasonAndShortSku() {
        String sku = product("Sold out", 50_000, 0);
        UUID orderId = placeOrder(asha, sku, 1, null);
        deliver();

        List<JsonNode> events = events(orderId);
        assertThat(events).extracting(event -> event.path("type").asString())
                .containsExactly("order.placed", "order.rejected");
        assertThat(events.getLast().path("data").path("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(events.getLast().path("data").path("sku").asString()).isEqualTo(sku);
    }

    @Test
    void repeatedCancellationAndDeadlinesDoNotRepeatTheMilestone() {
        UUID orderId = awaitingPayment(product("Cancel me", 50_000, 3), 1);
        deadlinePasses(orderId);
        deliver();
        expect(202, cancel(asha, orderId));
        deliver();
        expect(202, cancel(asha, orderId));
        deliver();

        assertThat(events(orderId)).extracting(event -> event.path("type").asString())
                .containsExactly("order.placed", "order.cancelled");
        assertThat(events(orderId).getLast().path("data").path("refund_amount_paise").asLong()).isZero();
    }

    @Test
    void aReturnReportsTheRequestedRefundWithoutClaimingItAlreadySucceeded() {
        UUID orderId = confirmed(product("Return me", 50_000, 3), 1, null);
        shipments.handOver(orderId);
        deliver();
        shipments.startReturn(orderId);
        deliver();
        shipments.completeReturn(orderId);
        deliver();

        List<JsonNode> events = events(orderId);
        assertThat(events).extracting(event -> event.path("type").asString())
                .containsExactly("order.placed", "order.confirmed", "order.shipped", "order.delivery_failed",
                        "order.returned_to_origin");
        assertThat(events.getLast().path("data").path("refund_status").asString()).isEqualTo("REQUESTED");
        assertThat(events.getLast().path("data").path("refund_amount_paise").asLong()).isEqualTo(grandTotal(orderId));
    }

    @Test
    void failingToWriteTheEventRollsBackTheOrderAndItsCommands() {
        String sku = product("Rollback", 50_000, 3);
        UUID quoteId = quote(asha, KARNATAKA, null, sku, 1);
        String addressId = address(asha, KARNATAKA);
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION public.test_fail_order_event() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.type = 'order.placed' THEN
                        RAISE EXCEPTION 'simulated event write failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_order_event BEFORE INSERT ON platform.outbox
                FOR EACH ROW EXECUTE FUNCTION public.test_fail_order_event()
                """).update();

        assertThat(place(asha, quoteId, addressId, newKey()).statusCode()).isEqualTo(500);

        assertThat(jdbc.sql("SELECT count(*) FROM ordering.orders").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.outbox WHERE aggregate_type = 'order'")
                .query(Integer.class).single()).isZero();
    }

    private List<JsonNode> events(UUID orderId) {
        List<String> envelopes = jdbc.sql("""
                        SELECT envelope FROM platform.outbox
                        WHERE destination = 'kafka:ecom.ordering.order-events' AND aggregate_id = :orderId
                        ORDER BY id
                        """)
                .param("orderId", orderId.toString()).query(String.class).list();
            envelopes.forEach(envelope -> contracts.read(IntegrationEvents.ORDER_TOPIC,
                orderId.toString().getBytes(StandardCharsets.UTF_8), envelope.getBytes(StandardCharsets.UTF_8)));
            return envelopes.stream().map(json::readTree).toList();
    }
}