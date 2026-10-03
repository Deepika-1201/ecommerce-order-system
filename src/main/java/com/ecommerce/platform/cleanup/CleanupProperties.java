package com.ecommerce.platform.cleanup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** How long finished platform records are kept (LLD §2.12). */
@ConfigurationProperties(prefix = "ecom.cleanup")
public record CleanupProperties(
        @DefaultValue("7d") Duration deliveredMessages,
        @DefaultValue("30d") Duration processedMessages,
        @DefaultValue("30d") Duration finishedTasks,
        @DefaultValue("30d") Duration processedWebhooks) {
}
