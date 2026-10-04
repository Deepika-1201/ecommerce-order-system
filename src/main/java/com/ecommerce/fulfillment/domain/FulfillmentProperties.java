package com.ecommerce.fulfillment.domain;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Fulfillment's settings (LLD §8.13). The webhook secrets are never logged. */
@ConfigurationProperties(prefix = "ecom.fulfillment")
public record FulfillmentProperties(
        @DefaultValue("24h") Duration bookingBudget,
        List<String> webhookSecrets,
        @DefaultValue("5m") Duration webhookTolerance,
        @DefaultValue Simulator simulator) {

    public FulfillmentProperties {
        webhookSecrets = webhookSecrets == null ? List.of()
                : webhookSecrets.stream().map(String::strip).filter(secret -> !secret.isEmpty()).toList();
    }

    /** The carrier simulator (LLD §8.9): whether parcels advance on their own, as in the compose stacks. */
    public record Simulator(@DefaultValue("false") boolean autoAdvance) {
    }

    @Override
    public String toString() {
        return "FulfillmentProperties[bookingBudget=" + bookingBudget + ", webhookSecrets=" + webhookSecrets.size()
                + ", webhookTolerance=" + webhookTolerance + ", simulator=" + simulator + "]";
    }
}
