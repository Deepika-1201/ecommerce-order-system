package com.ecommerce.fulfillment.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.fulfillment.ShipmentMessages;
import com.ecommerce.fulfillment.ShipmentMessages.CancelShipment;
import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelled;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.ordering.OrderingTest;
import com.ecommerce.platform.messaging.DueMessages;
import com.ecommerce.support.TestIdentityProvider;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The warehouse's shipments over HTTP (LLD §8.7): the lists, the marks, their repeats and refusals. */
class WarehouseShipmentTests extends OrderingTest {

    private static final Class<?>[] ANSWERS = {ShipmentHandedOver.class, ShipmentCancelled.class};

    private final String meera = TestIdentityProvider.token("meera-" + UUID.randomUUID()).roles("warehouse").sign();

    @Test
    void theWarehouseListsWhatToPackThenWhatToHandOverOldestFirst() {
        UUID first = booked("SKU-ONE", 2);
        UUID second = booked("SKU-TWO", 1);

        JsonNode toPack = expect(200, call("GET", "/v1/warehouse/shipments?limit=1", meera, null));

        assertThat(toPack.get("items")).hasSize(1);
        JsonNode oldest = toPack.get("items").get(0);
        assertThat(oldest.get("order_id").asString()).isEqualTo(first.toString());
        assertThat(oldest.get("status").asString()).isEqualTo("BOOKED");
        assertThat(oldest.get("awb").asString()).matches("SIM[0-9]{10}");
        assertThat(oldest.get("lines").get(0).get("sku").asString()).isEqualTo("SKU-ONE");
        assertThat(oldest.get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(oldest.get("delivery_address").get("pin_code").asString()).isEqualTo("560038");
        JsonNode next = expect(200, call("GET", "/v1/warehouse/shipments?limit=1&cursor="
                + toPack.get("next_cursor").asString(), meera, null));
        assertThat(next.get("items").get(0).get("order_id").asString()).isEqualTo(second.toString());
        assertThat(next.has("next_cursor")).isFalse();

        expect(200, call("POST", "/v1/warehouse/shipments/" + shipmentId(first) + "/packed", meera, null));

        assertThat(orders("BOOKED")).containsExactly(second.toString());
        assertThat(orders("PACKED")).containsExactly(first.toString());
    }

    @Test
    void packingThenHandingOverShipsTheOrderOnceAndRepeatsChangeNothing() {
        UUID order = booked("SKU-ONE", 1);
        String shipment = shipmentId(order);

        JsonNode packed = expect(200, call("POST", "/v1/warehouse/shipments/" + shipment + "/packed", meera, null));
        JsonNode handedOver = expect(200, call("POST", "/v1/warehouse/shipments/" + shipment + "/handed-over", meera,
                null));
        JsonNode again = expect(200, call("POST", "/v1/warehouse/shipments/" + shipment + "/handed-over", meera,
                null));
        JsonNode packedAgain = expect(200, call("POST", "/v1/warehouse/shipments/" + shipment + "/packed", meera,
                null));

        assertThat(packed.get("status").asString()).isEqualTo("PACKED");
        assertThat(handedOver.get("status").asString()).isEqualTo("HANDED_OVER");
        assertThat(again).isEqualTo(handedOver);
        assertThat(packedAgain).isEqualTo(handedOver);
        assertThat(published(order, "fulfillment.shipment-handed-over")).isOne();
        assertThat(jdbc.sql("""
                        SELECT action FROM platform.audit_log WHERE target_type = 'shipment' AND target_id = :id
                        ORDER BY id
                        """)
                .param("id", shipment)
                .query(String.class)
                .list()).containsExactly("fulfillment.shipment.packed", "fulfillment.shipment.handed-over");
    }

    @Test
    void aMarkTheShipmentCannotTakeIsRefused() {
        UUID booked = booked("SKU-ONE", 1);
        UUID cancelled = booked("SKU-TWO", 1);
        publish(new CancelShipment(cancelled), "order", cancelled);
        deliverExcept(ANSWERS);
        UUID cancelling = booked("SKU-THREE", 1);
        publish(new CancelShipment(cancelling), "order", cancelling);
        DueMessages.deliverAllExcept(context, ANSWERS);

        assertCode(call("POST", "/v1/warehouse/shipments/" + shipmentId(booked) + "/handed-over", meera, null),
                409, "shipment_invalid_state");
        assertCode(call("POST", "/v1/warehouse/shipments/" + shipmentId(cancelled) + "/packed", meera, null),
                409, "shipment_invalid_state");
        assertCode(call("POST", "/v1/warehouse/shipments/" + shipmentId(cancelling) + "/packed", meera, null),
                409, "shipment_cancelling");
        assertCode(call("POST", "/v1/warehouse/shipments/" + UUID.randomUUID() + "/packed", meera, null),
                404, "not_found");
        assertCode(call("GET", "/v1/warehouse/shipments?status=DELIVERED", meera, null), 400, "invalid_request");
        assertCode(call("GET", "/v1/warehouse/shipments?cursor=bm9wZQ", meera, null), 400, "invalid_cursor");
    }

    @Test
    void onlyTheWarehouseWorksOnShipments() {
        String shipment = shipmentId(booked("SKU-ONE", 1));

        for (String token : List.of(asha, support)) {
            assertThat(call("GET", "/v1/warehouse/shipments", token, null).statusCode()).isEqualTo(403);
            assertThat(call("POST", "/v1/warehouse/shipments/" + shipment + "/packed", token, null).statusCode())
                    .isEqualTo(403);
        }
        assertThat(call("POST", "/v1/warehouse/shipments/" + shipment + "/packed", null, null).statusCode())
                .isEqualTo(401);
    }

    private UUID booked(String sku, int quantity) {
        UUID order = UUID.randomUUID();
        publish(new CreateShipment(order, deliverySnapshot("560038"), List.of(new ShipmentMessages.Line(sku,
                quantity))), "order", order);
        deliverExcept(ANSWERS);
        return order;
    }

    private String shipmentId(UUID order) {
        return jdbc.sql("SELECT id::text FROM fulfillment.shipments WHERE order_id = :id")
                .param("id", order)
                .query(String.class)
                .single();
    }

    private List<String> orders(String status) {
        HttpResponse<String> response = call("GET", "/v1/warehouse/shipments?status=" + status, meera, null);
        return expect(200, response).get("items").valueStream().map(item -> item.get("order_id").asString()).toList();
    }

    private int published(UUID order, String type) {
        return jdbc.sql("SELECT count(*) FROM platform.outbox WHERE type = :type AND aggregate_id = :id")
                .param("type", type)
                .param("id", order.toString())
                .query(Integer.class)
                .single();
    }
}
