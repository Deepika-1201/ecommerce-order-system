package com.ecommerce.platform.messaging;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Delivery settings (LLD §2.11). */
@Validated
@ConfigurationProperties(prefix = "ecom.messaging")
public record MessagingProperties(
        @DefaultValue("4") @Min(1) int dispatcherConcurrency,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("10") @Min(1) int maxAttempts,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("100") @Min(1) int relayBatchSize,
        @DefaultValue("10s") Duration relaySendTimeout) {
}
