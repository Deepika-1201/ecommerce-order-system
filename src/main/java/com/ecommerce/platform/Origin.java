package com.ecommerce.platform;

import java.util.UUID;
import org.springframework.util.Assert;

/**
 * The aggregate a message comes from. {@code sequence} is the aggregate's version after the change, assigned under its
 * row lock, so it orders the aggregate's messages (LLD §2.3).
 */
public record Origin(String aggregateType, String aggregateId, long sequence) {

    public Origin {
        Assert.hasText(aggregateType, "aggregateType must not be empty");
        Assert.hasText(aggregateId, "aggregateId must not be empty");
        Assert.isTrue(sequence >= 0, "sequence must not be negative");
    }

    public Origin(String aggregateType, UUID aggregateId, long sequence) {
        this(aggregateType, aggregateId.toString(), sequence);
    }
}
