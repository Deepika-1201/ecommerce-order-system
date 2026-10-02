package com.ecommerce.platform;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.Assert;

/** One audited action; {@code reason} and {@code details} are optional. */
public record AuditEntry(
        String actorType,
        String actorId,
        String action,
        String targetType,
        String targetId,
        String reason,
        Map<String, Object> details) {

    public AuditEntry {
        Assert.hasText(actorType, "actorType must not be empty");
        Assert.hasText(actorId, "actorId must not be empty");
        Assert.hasText(action, "action must not be empty");
        Assert.hasText(targetType, "targetType must not be empty");
        Assert.hasText(targetId, "targetId must not be empty");
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
