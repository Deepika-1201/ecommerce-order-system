package com.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTests {

    @Test
    void isAVersion7UuidWithTheRfcVariant() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void startsWithItsCreationTimeInMilliseconds() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();

        assertThat(id.getMostSignificantBits() >>> 16).isBetween(before, after);
    }

    @Test
    void laterIdsSortAfterEarlierOnesAsPostgresComparesThem() throws InterruptedException {
        UUID earlier = Ids.newId();
        Thread.sleep(2);
        UUID later = Ids.newId();

        // PostgreSQL compares uuid bytes unsigned, which is the order of the hex strings.
        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void idsCreatedInTheSameMillisecondDiffer() {
        Set<UUID> ids = new HashSet<>();
        for (int index = 0; index < 100_000; index++) {
            ids.add(Ids.newId());
        }

        assertThat(ids).hasSize(100_000);
    }
}
