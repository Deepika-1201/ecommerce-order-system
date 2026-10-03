package com.ecommerce.payments.gateway;

import com.ecommerce.platform.ReceivedWebhook;
import com.ecommerce.platform.WebhookInbox;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway's events: verified webhooks, and the fake's events, enter the inbox here; the apply task reads them back
 * (LLD §7.7).
 */
@Component
public class GatewayEvents {

    public static final String SOURCE = "payment-gateway";
    public static final String APPLY_TASK = "payments.apply-gateway-event";

    private final WebhookInbox inbox;
    private final JsonMapper json;

    GatewayEvents(WebhookInbox inbox, JsonMapper json) {
        this.inbox = inbox;
        this.json = json;
    }

    /**
     * Stores an event whose signature was verified, unless its id is known; returns whether it was new.
     *
     * @throws MalformedEventException if the body has no event id and type
     */
    @Transactional
    public boolean receive(String body) {
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (JacksonException e) {
            throw new MalformedEventException("The event is not JSON");
        }
        String id = text(event, "id");
        String type = text(event, "type");
        if (id == null || type == null) {
            throw new MalformedEventException("The event has no id or type");
        }
        return inbox.store(new ReceivedWebhook(SOURCE, id, type), body, APPLY_TASK);
    }

    /** What an event in the inbox reports: a payment, a refund, or nothing this system acts on. */
    public GatewayEvent read(ReceivedWebhook webhook) {
        JsonNode object = json.readTree(inbox.body(webhook)).path("data").path("object");
        if (webhook.type().startsWith("payment.")) {
            return new GatewayEvent.PaymentChanged(GatewayWire.payment(
                    json.treeToValue(object, GatewayWire.PaymentJson.class)));
        }
        if (webhook.type().startsWith("refund.")) {
            return new GatewayEvent.RefundChanged(GatewayWire.refund(
                    json.treeToValue(object, GatewayWire.RefundJson.class)));
        }
        return new GatewayEvent.Ignored(webhook.type());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() || value.asString().isBlank() ? null : value.asString();
    }

    /** An event in the inbox, read. */
    public sealed interface GatewayEvent {

        record PaymentChanged(GatewayPayment payment) implements GatewayEvent {
        }

        record RefundChanged(GatewayRefund refund) implements GatewayEvent {
        }

        /** Dispute events, and any type added later: kept, not acted on (LLD §7.7). */
        record Ignored(String type) implements GatewayEvent {
        }
    }

    /** A signed body that is not an event: a bug at the gateway, answered with {@code 400}. */
    public static class MalformedEventException extends RuntimeException {

        MalformedEventException(String message) {
            super(message);
        }
    }
}
