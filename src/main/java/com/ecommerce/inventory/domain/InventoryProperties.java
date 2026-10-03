package com.ecommerce.inventory.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code lockTimeout}: the longest wait for a stock row's lock (ADR-009, LLD §5.7). */
@ConfigurationProperties(prefix = "ecom.inventory")
record InventoryProperties(@DefaultValue("500ms") Duration lockTimeout) {
}
