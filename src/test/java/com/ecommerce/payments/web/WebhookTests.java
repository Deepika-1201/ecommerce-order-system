package com.ecommerce.payments.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.ordering.OrderingTest;
import com.ecommerce.payments.PaymentMessages.CreatePayment;
import com.ecommerce.payments.PaymentMessages.PaymentCreated;
import com.ecommerce.payments.PaymentMessages.PaymentSucceeded;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gateway's webhooks over HTTP (LLD §7.7): only a body signed with a configured secret, recently, is stored, byte
 * for byte, and applied by a task; anything else is refused before it is stored.
 */
class WebhookTests extends OrderingTest {

    private static final String PATH = "/v1/webhooks/payment-gateway";
    private static final Class<?>[] ANSWERS = {PaymentCreated.class, PaymentSucceeded.class};

    private final UUID orderId = UUID.randomUUID();
    private String gatewayPaymentId;

    @BeforeEach
    void aPaymentAtTheGateway() {
        publish(new CreatePayment(orderId, UUID.randomUUID(), 123_400, Instant.now().plus(Duration.ofMinutes(15))),
                "order", orderId);
        deliverExcept(ANSWERS);
        gatewayPaymentId = jdbc.sql("SELECT gateway_payment_id FROM payments.payment_records WHERE order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    @Test
    void aSignedEventIsStoredByteForByteAndApplied() {
        String event = succeeded("evt_signed");

        HttpResponse<String> response = send(event, signature(now(), event, WEBHOOK_SECRET));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT body FROM platform.webhook_inbox WHERE event_id = 'evt_signed'")
                .query(String.class).single()).isEqualTo(event);
        deliverExcept(ANSWERS);
        assertThat(payment(orderId)).isEqualTo("SUCCEEDED");
        assertThat(published("payments.payment-succeeded")).isOne();
    }

    @Test
    void eitherSecretOfARotationIsAccepted() {
        String event = succeeded("evt_rotated");
        long t = now();

        HttpResponse<String> response = send(event, "t=" + t + ",v1=" + hmac("whsec_retired", t, event) + ",v1="
                + hmac(OLD_WEBHOOK_SECRET, t, event));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @Test
    void aWrongSecretOrAChangedBodyIsRefused() {
        String event = succeeded("evt_forged");
        long t = now();

        assertRefused(send(event, signature(t, event, "whsec_not_ours")));
        assertRefused(send(event.replace("123400", "123401"), signature(t, event, WEBHOOK_SECRET)));
        assertRefused(send(event, signature(t + 1, event, WEBHOOK_SECRET).replace("t=" + (t + 1), "t=" + t)));
        assertThat(stored()).isZero();
    }

    @Test
    void onlyARecentSignatureIsAccepted() {
        String event = succeeded("evt_old");

        assertRefused(send(event, signature(now() - 301, event, WEBHOOK_SECRET)));
        assertRefused(send(event, signature(now() + 301, event, WEBHOOK_SECRET)));
        assertThat(stored()).isZero();
        HttpResponse<String> recent = send(event, signature(now() - 290, event, WEBHOOK_SECRET));
        assertThat(recent.statusCode()).as(recent.body()).isEqualTo(200);
    }

    @Test
    void malformedSignaturesAreRefused() {
        String event = succeeded("evt_malformed");
        long t = now();
        String v1 = hmac(WEBHOOK_SECRET, t, event);

        assertRefused(post(port, PATH, event));
        for (String header : new String[] {"garbage", "t=" + t, "v1=" + v1, "t=soon,v1=" + v1,
            "t=" + t + ",v1=" + v1.substring(1), "t=" + t + ",v1=zz" + v1.substring(2), "t=" + t + ";v1=" + v1}) {
            assertRefused(send(event, header));
        }
        assertThat(stored()).isZero();
    }

    @Test
    void aRepeatedEventIsAcknowledgedWithoutEffect() {
        String event = succeeded("evt_twice");

        assertThat(send(event, signature(now(), event, WEBHOOK_SECRET)).statusCode()).isEqualTo(200);
        assertThat(send(event, signature(now(), event, OLD_WEBHOOK_SECRET)).statusCode()).isEqualTo(200);
        deliverExcept(ANSWERS);

        assertThat(stored()).isOne();
        assertThat(published("payments.payment-succeeded")).isOne();
    }

    @Test
    void aBodyOverSixtyFourKilobytesIsRefused() {
        String event = succeeded("evt_large").replace("\"version\"", "\"padding\": \"" + "x".repeat(65_536)
                + "\", \"version\"");

        HttpResponse<String> response = send(event, signature(now(), event, WEBHOOK_SECRET));

        assertCode(response, 413, "payload_too_large");
        assertThat(stored()).isZero();
    }

    @Test
    void aSignedBodyThatIsNotAnEventIsRefused() {
        for (String body : new String[] {"{}", "[]", "{\"id\": \"evt_x\"}", "{\"type\": \"payment.succeeded\"}",
            "{\"id\": \" \", \"type\": \"payment.succeeded\"}", "not json"}) {
            assertCode(send(body, signature(now(), body, WEBHOOK_SECRET)), 400, "malformed_request");
        }
        assertThat(stored()).isZero();
    }

    private String succeeded(String eventId) {
        return """
                {"id":"%s","type":"payment.succeeded","created_at":"2026-10-03T10:00:00Z","data":{"object":{"id":"%s",\
                "object":"payment","merchant_order_id":"%s","amount":123400,"currency":"INR","status":"succeeded",\
                "capture_method":"automatic","amount_captured":123400,"amount_refunded":0,"attempt_count":1,\
                "expires_at":"2026-10-03T10:15:00Z","created_at":"2026-10-03T10:00:00Z",\
                "updated_at":"2026-10-03T10:05:00Z","version":2}}}""".formatted(eventId, gatewayPaymentId, orderId);
    }

    private HttpResponse<String> send(String body, String signature) {
        return post(port, PATH, body, "PG-Signature", signature, "PG-Event-Id", "evt_header",
                "PG-Event-Type", "payment.succeeded");
    }

    private static void assertRefused(HttpResponse<String> response) {
        assertCode(response, 401, "invalid_signature");
    }

    private static String signature(long timestamp, String body, String secret) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp, body);
    }

    private static String hmac(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
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
