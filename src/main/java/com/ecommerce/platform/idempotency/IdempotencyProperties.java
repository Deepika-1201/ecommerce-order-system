package com.ecommerce.platform.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code keyTtl}: how long a key is remembered. {@code lockTimeout}: how long a request waits for another request
 * with the same key before getting {@code 409} (LLD §2.8).
 */
@ConfigurationProperties(prefix = "ecom.idempotency")
public record IdempotencyProperties(
        @DefaultValue("24h") Duration keyTtl,
        @DefaultValue("200ms") Duration lockTimeout) {
}
