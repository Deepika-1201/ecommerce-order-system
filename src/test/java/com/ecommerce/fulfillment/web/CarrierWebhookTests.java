package com.ecommerce.fulfillment.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.fulfillment.ShipmentMessages;
import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.ordering.OrderingTest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The carrier's tracking webhook over HTTP (LLD §8.8): signed scans are stored and applied by their tasks, out of order
 * and duplicated, and never move the shipment backwards; anything else is refused before it is stored.
 */
class CarrierWebhookTests extends OrderingTest {

    private static final String PATH = "/v1/webhooks/carrier";
    private static final Class<?>[] ANSWERS = {ShipmentHandedOver.class, ShipmentDelivered.class};
    private static final Instant PICKUP = Instant.parse("2026-10-04T08:00:00Z");

    private final UUID orderId = UUID.randomUUID();
    private String shipmentId;

    @BeforeEach
    void aBookedShipment() {
        publish(new CreateShipment(orderId, deliverySnapshot("560038"), List.of(new ShipmentMessages.Line("SKU-A", 1))),
                "order", orderId);
        deliverExcept(ANSWERS);
        shipmentId = jdbc.sql("SELECT id::text FROM fulfillment.shipments WHERE order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    @Test
    void scansOutOfOrderAndRepeatedMoveTheShipmentOnlyForward() {
        String inTransit = scan("evt_1", "in_transit", PICKUP.plusSeconds(600));
        String delivered = scan("evt_3", "delivered", PICKUP.plusSeconds(1800));
        String outForDelivery = scan("evt_2", "out_for_delivery", PICKUP.plusSeconds(1200));

        for (String event : List.of(scan("evt_0", "picked_up", PICKUP), delivered, inTransit, delivered,
                outForDelivery)) {
            HttpResponse<String> response = send(event, signature(now(), event, CARRIER_SECRET));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
        deliverExcept(ANSWERS);

        assertThat(shipment(orderId)).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("""
                        SELECT status || CASE WHEN applied THEN '' ELSE ' (late)' END FROM fulfillment.tracking_events
                        WHERE shipment_id = CAST(:id AS uuid) ORDER BY received_at
                        """)
                .param("id", shipmentId)
                .query(String.class)
                .list()).containsExactly("HANDED_OVER", "DELIVERED", "IN_TRANSIT (late)", "OUT_FOR_DELIVERY (late)");
        assertThat(published("fulfillment.shipment-handed-over")).isOne();
        assertThat(published("fulfillment.shipment-delivered")).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM platform.webhook_inbox WHERE source = 'carrier'")
                .query(Integer.class).single()).as("the repeated event stored once").isEqualTo(4);
    }

    @Test
    void anUnsignedOldOrForgedScanIsRefused() {
        String event = scan("evt_forged", "delivered", PICKUP);
        long t = now();

        assertCode(post(port, PATH, event), 401, "invalid_signature");
        assertCode(send(event, signature(t, event, "not_the_carriers")), 401, "invalid_signature");
        assertCode(send(event, signature(t - 301, event, CARRIER_SECRET)), 401, "invalid_signature");
        assertCode(send(event.replace("delivered", "rto_delivered"), signature(t, event, CARRIER_SECRET)), 401,
                "invalid_signature");
        assertCode(send(event, signature(t, event, WEBHOOK_SECRET)), 401, "invalid_signature");
        assertThat(stored()).isZero();
    }

    @Test
    void aSignedBodyThatIsNotAnEventOrTooLargeIsRefused() {
        for (String body : new String[] {"{}", "{\"id\": \"evt_x\"}", "{\"type\": \"tracking.scan\"}", "not json"}) {
            assertCode(send(body, signature(now(), body, CARRIER_SECRET)), 400, "malformed_request");
        }
        String large = scan("evt_large", "delivered", PICKUP).replace("\"location\"", "\"padding\": \""
                + "x".repeat(65_536) + "\", \"location\"");
        assertCode(send(large, signature(now(), large, CARRIER_SECRET)), 413, "payload_too_large");
        assertThat(stored()).isZero();
    }

    private String scan(String eventId, String scan, Instant occurredAt) {
        return """
                {"id":"%s","type":"tracking.scan","created_at":"%s","data":{"reference":"%s","awb":"SIM0000000001",\
                "scan":"%s","occurred_at":"%s","location":"Bengaluru sort centre"}}""".formatted(eventId,
                occurredAt, shipmentId, scan, occurredAt);
    }

    private HttpResponse<String> send(String body, String signature) {
        return post(port, PATH, body, "Carrier-Signature", signature);
    }

    private static String signature(long timestamp, String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "t=" + timestamp + ",v1="
                    + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private int stored() {
        return jdbc.sql("SELECT count(*) FROM platform.webhook_inbox").query(Integer.class).single();
    }

    private int published(String type) {
        return jdbc.sql("SELECT count(*) FROM platform.outbox WHERE type = :type AND aggregate_id = :id")
                .param("type", type)
                .param("id", orderId.toString())
                .query(Integer.class)
                .single();
    }
}
