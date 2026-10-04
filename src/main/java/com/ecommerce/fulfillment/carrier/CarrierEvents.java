package com.ecommerce.fulfillment.carrier;

import com.ecommerce.platform.ReceivedWebhook;
import com.ecommerce.platform.WebhookInbox;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The carrier's events: verified webhooks and the simulator's scans enter the inbox here, and the apply task reads them
 * back (LLD §8.8).
 */
@Component
public class CarrierEvents {

    public static final String SOURCE = "carrier";
    public static final String APPLY_TASK = "fulfillment.apply-carrier-event";
    static final String SCAN = "tracking.scan";

    private final WebhookInbox inbox;
    private final JsonMapper json;

    CarrierEvents(WebhookInbox inbox, JsonMapper json) {
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

    /** What an event in the inbox reports: a scan, or nothing this system acts on. */
    public CarrierEvent read(ReceivedWebhook webhook) {
        if (!webhook.type().equals(SCAN)) {
            return new CarrierEvent.Ignored(webhook.type());
        }
        ScanJson scan = json.treeToValue(json.readTree(inbox.body(webhook)).path("data"), ScanJson.class);
        return new CarrierEvent.Scanned(webhook.eventId(), scan.reference(), scan.awb(), scan.scan(),
                scan.occurredAt(), scan.location());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() || value.asString().isBlank() ? null : value.asString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScanJson(UUID reference, String awb, String scan, Instant occurredAt, String location) {
    }

    /** An event in the inbox, read. */
    public sealed interface CarrierEvent {

        /** A scan of a parcel, by the carrier's code, at the carrier's time. */
        record Scanned(String eventId, UUID reference, String awb, String scan, Instant occurredAt, String location)
                implements CarrierEvent {
        }

        /** Any other type: kept, not acted on. */
        record Ignored(String type) implements CarrierEvent {
        }
    }

    /** A signed body that is not an event, answered with {@code 400}. */
    public static class MalformedEventException extends RuntimeException {

        MalformedEventException(String message) {
            super(message);
        }
    }
}
