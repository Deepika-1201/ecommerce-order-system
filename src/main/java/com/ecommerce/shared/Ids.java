package com.ecommerce.shared;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Identifiers for entities and messages: UUIDv7 (RFC 9562), ordered by creation time, with 74 random bits so they
 * cannot be guessed (ADR-005).
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long unixMillis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        long mostSignificant = (unixMillis << 16) | (0x7L << 12) | randA;
        long leastSignificant = (0x2L << 62) | randB;
        return new UUID(mostSignificant, leastSignificant);
    }
}
