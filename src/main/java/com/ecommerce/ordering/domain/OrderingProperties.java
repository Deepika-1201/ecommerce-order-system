package com.ecommerce.ordering.domain;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The payment window, the gateway's grace and the margin (LLD §6.11, ADR-009). Stock is held for all three; the
 * payment expires when the window ends, so both count from the moment the stock was reserved (FR-PAY1).
 */
@ConfigurationProperties(prefix = "ecom.ordering")
record OrderingProperties(
        @DefaultValue("15m") Duration paymentWindow,
        @DefaultValue("30m") Duration gatewayGrace,
        @DefaultValue("5m") Duration holdMargin) {

    Duration hold() {
        return paymentWindow.plus(gatewayGrace).plus(holdMargin);
    }

    /** When the payment of a hold that expires at {@code holdExpiresAt} expires. */
    Instant paymentExpiry(Instant holdExpiresAt) {
        return holdExpiresAt.minus(gatewayGrace).minus(holdMargin);
    }
}
