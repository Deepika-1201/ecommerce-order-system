package com.ecommerce.platform.tasks;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Task runner settings (LLD §2.11). A task's lease must outlast its handler, or another worker runs it too. */
@Validated
@ConfigurationProperties(prefix = "ecom.tasks")
public record TaskProperties(
        @DefaultValue("4") @Min(1) int concurrency,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("5m") Duration defaultLease,
        @DefaultValue("10s") Duration initialBackoff,
        @DefaultValue("1h") Duration maxBackoff) {
}
