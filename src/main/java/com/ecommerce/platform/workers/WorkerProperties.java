package com.ecommerce.platform.workers;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code autostart=false} keeps worker loops stopped so tests can drive them step by step. */
@ConfigurationProperties(prefix = "ecom.workers")
public record WorkerProperties(@DefaultValue("true") boolean autostart) {
}
