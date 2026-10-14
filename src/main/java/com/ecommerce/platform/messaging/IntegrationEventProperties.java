package com.ecommerce.platform.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ecom.events")
record IntegrationEventProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("12") @Min(1) @Max(64) int partitions,
        @DefaultValue("1") @Min(1) short replicationFactor,
        @DefaultValue("1") @Min(1) int minInSyncReplicas) {

    IntegrationEventProperties {
        Assert.isTrue(minInSyncReplicas <= replicationFactor, "Minimum ISR cannot exceed the replication factor");
    }
}