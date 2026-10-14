package com.ecommerce.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.platform.IntegrationInbox;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class IntegrationInboxTests extends IntegrationTest {

    @Autowired
    private IntegrationInbox inbox;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void clear() {
        PlatformTables.clear(jdbc);
    }

    @Test
    void duplicatesDoNotRunTheEffectAgain() {
        UUID messageId = UUID.randomUUID();
        AtomicInteger effects = new AtomicInteger();

        assertThat(inbox.receive("notifications", messageId, effects::incrementAndGet)).isTrue();
        assertThat(inbox.receive("notifications", messageId, effects::incrementAndGet)).isFalse();

        assertThat(effects.get()).isEqualTo(1);
        assertThat(markers()).isEqualTo(1);
    }

    @Test
    void consumersDeduplicateIndependently() {
        UUID messageId = UUID.randomUUID();
        AtomicInteger effects = new AtomicInteger();

        assertThat(inbox.receive("first", messageId, effects::incrementAndGet)).isTrue();
        assertThat(inbox.receive("second", messageId, effects::incrementAndGet)).isTrue();

        assertThat(effects.get()).isEqualTo(2);
        assertThat(markers()).isEqualTo(2);
    }

    @Test
    void aFailedEffectRollsBackItsWritesAndMarkerAndCanBeRetried() {
        UUID messageId = UUID.randomUUID();
        UUID effectId = UUID.randomUUID();

        assertThatThrownBy(() -> inbox.receive("notifications", messageId, () -> {
            outbox.markProcessed("database-effect", effectId);
            throw new IllegalStateException("crash before commit");
        })).isInstanceOf(IllegalStateException.class).hasMessage("crash before commit");

        assertThat(markers()).isZero();
        assertThat(inbox.receive("notifications", messageId,
                () -> outbox.markProcessed("database-effect", effectId))).isTrue();
        assertThat(markers()).isEqualTo(2);
    }

    @Test
    void returningMeansCommittedEvenIfTheCallersTransactionRollsBack() {
        UUID messageId = UUID.randomUUID();

        transactions.executeWithoutResult(transaction -> {
            assertThat(inbox.receive("notifications", messageId, () -> { })).isTrue();
            transaction.setRollbackOnly();
        });

        assertThat(markers()).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesHaveOneEffect() throws Exception {
        UUID messageId = UUID.randomUUID();
        AtomicInteger effects = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> deliveries = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int delivery = 0; delivery < 12; delivery++) {
                deliveries.add(executor.submit(() -> {
                    start.await();
                    return inbox.receive("notifications", messageId, effects::incrementAndGet);
                }));
            }
            start.countDown();
            int applied = 0;
            for (Future<Boolean> delivery : deliveries) {
                if (delivery.get(10, TimeUnit.SECONDS)) {
                    applied++;
                }
            }
            assertThat(applied).isEqualTo(1);
        }
        assertThat(effects.get()).isEqualTo(1);
        assertThat(markers()).isEqualTo(1);
    }

    @Test
    void invalidArgumentsCannotLeaveMarkers() {
        UUID messageId = UUID.randomUUID();
        assertThatThrownBy(() -> inbox.receive(" ", messageId, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.receive("notifications", null, () -> { }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> inbox.receive("notifications", messageId, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(markers()).isZero();
    }

    private int markers() {
        return jdbc.sql("SELECT count(*) FROM platform.processed_messages").query(Integer.class).single();
    }
}