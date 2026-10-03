package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.StockReservation.Held;
import com.ecommerce.inventory.domain.AdjustmentReason;
import com.ecommerce.inventory.domain.StockLevel;
import com.ecommerce.platform.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.TestPropertySource;

/**
 * After every step of random operations, in sequence and concurrently, {@code 0 ≤ reserved ≤ on_hand}, the reservations
 * account for {@code reserved}, and the movements for {@code on_hand} (LLD §5.3, §5.8). The lock timeout is that of
 * {@link LastUnitTests}, whose context these tests share: callers queue on a locked row before it is released.
 */
@TestPropertySource(properties = "ecom.inventory.lock-timeout=10s")
class StockInvariantTests extends InventoryTest {

    private static final List<String> RECEIVED = List.of("INV-A", "INV-B", "INV-C");

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {7, 42, 2026})
    void theInvariantsHoldAfterEveryStepOfARandomSequence(long seed) {
        Random random = new Random(seed);
        List<UUID> orders = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (int step = 0; step < 150; step++) {
            String operation = randomStep(random, orders, HOLD, true);
            try {
                assertInvariants();
            } catch (AssertionError broken) {
                throw new AssertionError("Seed " + seed + ", step " + step + ": " + operation, broken);
            }
            visited.addAll(statuses());
        }
        assertEveryStatusAndMovementKindOccurred(visited);
    }

    @Test
    void theInvariantsHoldAfterConcurrentRandomOperations() throws Exception {
        RECEIVED.forEach(sku -> receive(sku, 20));
        List<UUID> orders = new CopyOnWriteArrayList<>();
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> workers = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (int worker = 0; worker < 6; worker++) {
                Random random = new Random(worker);
                workers.add(executor.submit(() -> {
                    for (int step = 0; step < 60; step++) {
                        try {
                            randomStep(random, orders, Duration.ofMillis(1 + random.nextInt(50)), false);
                        } catch (CannotAcquireLockException busy) {
                            // Allowed: the operation changed nothing.
                        } catch (RuntimeException e) {
                            unexpected.add(e.toString());
                        }
                    }
                }));
            }
            for (Future<?> worker : workers) {
                worker.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(unexpected).isEmpty();
        sweep();
        assertInvariants();
        assertEveryStatusAndMovementKindOccurred(statuses());
    }

    @Test
    void adjustmentsRacingReservationsNeverTakeOnHandBelowReserved() throws Exception {
        receive("RACE-1", 6);

        List<Object> outcomes = racing(List.of("RACE-1"), 8, i -> {
            if (i % 2 == 0) {
                return reserve(UUID.randomUUID(), line("RACE-1", 1));
            }
            try {
                return warehouse.adjust(MEERA, "RACE-1", -2, AdjustmentReason.DAMAGED, null);
            } catch (ApiException refused) {
                return refused.code();
            }
        });

        long held = outcomes.stream().filter(Held.class::isInstance).count();
        long adjusted = outcomes.stream().filter(StockLevel.class::isInstance).count();
        assertThat(held + 2 * adjusted).as("every available unit is taken once, whatever the order").isEqualTo(6);
        assertThat(outcomes).filteredOn(String.class::isInstance).as("8 units asked of 6 by adjustments alone")
                .isNotEmpty().containsOnly("adjustment_below_reserved");
        assertLevels("RACE-1", 6 - 2 * adjusted, held);
        assertInvariants();
    }

    /** The random operations reached every state, so the invariants were checked along every path. */
    private void assertEveryStatusAndMovementKindOccurred(Collection<String> statuses) {
        assertThat(statuses).containsExactlyInAnyOrder("HELD", "REJECTED", "COMMITTED", "RELEASED", "EXPIRED",
                "FULFILLED", "RETURNED");
        assertThat(jdbc.sql("SELECT DISTINCT kind FROM inventory.stock_movements").query(String.class).list())
                .containsExactlyInAnyOrder("RECEIPT", "ADJUSTMENT", "HANDOVER", "RETURN");
    }

    private List<String> statuses() {
        return jdbc.sql("SELECT DISTINCT status FROM inventory.reservations").query(String.class).list();
    }

    /**
     * One random operation; transitions a reservation cannot make, and adjustments the stock refuses, change nothing.
     * {@code expireBySweep} lets an expiry step run the sweep; otherwise expired holds wait to be reclaimed.
     */
    private String randomStep(Random random, List<UUID> orders, Duration hold, boolean expireBySweep) {
        int roll = random.nextInt(100);
        String sku = RECEIVED.get(random.nextInt(RECEIVED.size()));
        try {
            if (roll < 15) {
                int quantity = 1 + random.nextInt(8);
                warehouse.receive(MEERA, sku, quantity, null);
                return "receive " + quantity + " " + sku;
            }
            if (roll < 25) {
                AdjustmentReason reason = AdjustmentReason.values()[random.nextInt(4)];
                int change = (1 + random.nextInt(3)) * switch (reason) {
                    case DAMAGED, LOST -> -1;
                    case FOUND -> 1;
                    case COUNT_CORRECTION -> random.nextBoolean() ? 1 : -1;
                };
                warehouse.adjust(MEERA, sku, change, reason, null);
                return "adjust " + sku + " by " + change + " (" + reason + ")";
            }
            if (roll < 50) {
                UUID order = UUID.randomUUID();
                List<String> skus = new ArrayList<>(RECEIVED);
                Collections.shuffle(skus, random);
                List<StockReservations.Line> lines = new ArrayList<>();
                skus.subList(0, 1 + random.nextInt(2)).forEach(ordered -> lines.add(line(ordered,
                        1 + random.nextInt(2))));
                if (random.nextInt(10) == 0) {
                    lines.add(line("INV-NEVER", 1));
                }
                orders.add(order);
                return "reserve " + lines + " for " + order + ": " + reservations.reserve(order, lines, hold);
            }
            if (roll < 65) {
                UUID order = pick(random, orders, "HELD", "EXPIRED");
                return "commit " + order + ": " + reservations.commit(order);
            }
            if (roll < 73) {
                UUID order = pick(random, orders, "HELD", "COMMITTED");
                reservations.release(order);
                return "release " + order;
            }
            if (roll < 85) {
                UUID order = pick(random, orders, "COMMITTED");
                reservations.fulfill(order);
                return "fulfill " + order;
            }
            if (roll < 93) {
                UUID order = pick(random, orders, "FULFILLED");
                reservations.restockReturn(order);
                return "restock " + order;
            }
            UUID order = pick(random, orders, "HELD");
            expire(order);
            if (expireBySweep && random.nextBoolean()) {
                sweep();
                return "expire " + order + " and sweep";
            }
            return "expire " + order;
        } catch (IllegalStateException | ApiException refused) {
            return "refused (roll " + roll + "): " + refused.getMessage();
        }
    }

    /** Usually an order in one of these statuses, so the walk goes deep; else any order, to try what cannot happen. */
    private UUID pick(Random random, List<UUID> orders, String... statuses) {
        if (random.nextInt(5) > 0) {
            List<UUID> fitting = jdbc.sql("""
                            SELECT order_id FROM inventory.reservations WHERE status = ANY(:statuses) ORDER BY id
                            """)
                    .param("statuses", statuses)
                    .query(UUID.class)
                    .list();
            if (!fitting.isEmpty()) {
                return fitting.get(random.nextInt(fitting.size()));
            }
        }
        return orders.isEmpty() ? UUID.randomUUID() : orders.get(random.nextInt(orders.size()));
    }
}
