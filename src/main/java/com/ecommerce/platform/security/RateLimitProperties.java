package com.ecommerce.platform.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ecom.security.rate-limits")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3000") @Min(1) @Max(1000000) int ipPerMinute,
        @DefaultValue("120") @Min(1) @Max(1000000) int principalWritesPerMinute,
        @DefaultValue("6000") @Min(1) @Max(1000000) int webhookIpPerMinute,
        @DefaultValue("20000") @Min(1) @Max(1000000) int instancePerMinute,
        @DefaultValue("10000") @Min(1) @Max(100000) int maxKeys) {
}