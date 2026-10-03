package com.ecommerce.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.ordering.OrderingTest;
import com.ecommerce.platform.tasks.DueTasks;
import com.ecommerce.support.Eventually;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The deadline sweep (LLD §6.7): it only ever asks again or alerts, and two sweeps at once act once. */
@ExtendWith(OutputCaptureExtension.class)
class DeadlineTests extends OrderingTest {

    @Autowired
    private DeadlineSweep sweep;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void aCommandSentAgainIsAnsweredTheSameAndItsSecondReplyIsIgnored() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 2, null);

        deadlinePasses(orderId);

        assertThat(pending(orderId)).containsExactly("inventory.reserve-stock", "inventory.reserve-stock");
        assertThat(attempts(orderId)).isEqualTo(2);
        deliver();
        assertThat(status(orderId)).isEqualTo("AWAITING_PAYMENT");
        assertThat(processed("ordering.stock-reserved")).as("both replies handled, the second ignored").isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.reservations").query(Integer.class).single()).isOne();
        assertStock(sku, 5, 2);
    }

    @Test
    void aPaymentWhoseExpiryWasMissedIsFoundExpiredWhenTheHoldExpires() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);
        payments.expire(orderId);
        jdbc.sql("DELETE FROM platform.scheduled_tasks WHERE type = 'payments.apply-gateway-event'").update();

        deadlinePasses(orderId);

        assertThat(pending(orderId)).containsExactly("payments.check-payment");
        deliver();
        assertThat(payment(orderId)).isEqualTo("EXPIRED");
        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(reason(orderId)).isEqualTo("PAYMENT_EXPIRED");
        assertStock(sku, 5, 0);
    }

    @Test
    void aPaymentBeingPaidWhenTheHoldExpiresIsCheckedAgainInFiveMinutes() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);
        payments.startAttempt(orderId);

        deadlinePasses(orderId);
        long checked = version(orderId);
        deliver();

        assertThat(step(orderId)).isEqualTo("AWAITING_PAYMENT");
        assertThat(processed("ordering.payment-pending")).isOne();
        assertThat(version(orderId)).as("the pending payment moved the deadline").isEqualTo(checked + 1);
        assertThat(Duration.between(Instant.now(), deadline(orderId)))
                .isBetween(Duration.ofMinutes(4), OrderProcess.RECHECK_INTERVAL);
    }

    @Test
    void aSweepEveryMinuteActsOnEveryProcessPastItsDeadline() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID first = placeOrder(asha, sku, 1, null);
        UUID second = placeOrder(ravi, sku, 1, null);
        UUID notDue = placeOrder(asha, sku, 1, null);
        overdue(first, 2);
        overdue(second, 1);

        DueTasks.runRecurring(context, SWEEP);

        assertThat(attempts(first)).isEqualTo(2);
        assertThat(attempts(second)).isEqualTo(2);
        assertThat(attempts(notDue)).isOne();
        assertThat(jdbc.sql("SELECT every_seconds FROM platform.scheduled_tasks WHERE type = :type")
                .param("type", SWEEP)
                .query(Long.class)
                .single()).as("the sweep runs every minute").isEqualTo(60);
    }

    @Test
    void theSweepTakesTheLongestOverdueFirst() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID recent = placeOrder(asha, sku, 1, null);
        UUID oldest = placeOrder(ravi, sku, 1, null);
        UUID notDue = placeOrder(asha, sku, 1, null);
        overdue(recent, 1);
        overdue(oldest, 60);

        assertThat(orderRepository.overdue(Instant.now(), 1)).containsExactly(oldest);
        assertThat(orderRepository.overdue(Instant.now(), 5)).containsExactly(oldest, recent).doesNotContain(notDue);
    }

    @Test
    void anOrderWhoseDeadlineFailsDoesNotHoldBackTheOthers(CapturedOutput output) {
        String sku = product("Steel bottle", 59_900, 5);
        UUID broken = placeOrder(asha, sku, 1, null);
        UUID healthy = placeOrder(ravi, sku, 1, null);
        // A state no decision produces: refunding with no refund to ask for again.
        jdbc.sql("UPDATE ordering.order_processes SET step = 'REFUNDING' WHERE order_id = :id")
                .param("id", broken)
                .update();
        overdue(broken, 2);
        overdue(healthy, 1);

        DueTasks.runRecurring(context, SWEEP);

        assertThat(output).contains("The deadline of order " + broken + " failed");
        assertThat(attempts(broken)).as("rolled back").isOne();
        assertThat(attempts(healthy)).isEqualTo(2);
    }

    @Test
    void aStepAlertsFromItsThirdAttempt(CapturedOutput output) {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);
        String alert = "order_process_overdue: order " + orderId + " in step RESERVING_STOCK";

        deadlinePasses(orderId);
        assertThat(output).doesNotContain(alert);

        deadlinePasses(orderId);
        assertThat(attempts(orderId)).isEqualTo(3);
        assertThat(output).contains(alert + " after 3 attempts");
        assertThat(pending(orderId)).hasSize(3);
    }

    @Test
    void aMissingHandoverAlertsAndWaitsAnotherDay(CapturedOutput output) {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = confirmed(sku, 1, null);

        deadlinePasses(orderId);

        assertThat(output).contains("order_process_overdue: order " + orderId + " in step AWAITING_HANDOVER after 1 "
                + "attempts");
        assertThat(pending(orderId)).isEmpty();
        assertThat(Duration.between(Instant.now(), deadline(orderId)))
                .isBetween(Duration.ofHours(23), OrderProcess.HANDOVER_TIMEOUT);
        assertThat(status(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void twoSweepsAtOnceActOnce() throws Exception {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);
        overdue(orderId, 1);

        try (Connection gate = dataSource.getConnection();
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            gate.setAutoCommit(false);
            try (PreparedStatement lock = gate.prepareStatement(
                    "SELECT 1 FROM ordering.orders WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, orderId);
                lock.executeQuery().close();
            }
            Future<?> first = executor.submit(() -> sweep.sweep(null));
            Future<?> second = executor.submit(() -> sweep.sweep(null));
            try {
                Eventually.await(Duration.ofSeconds(10), "both sweeps waiting for the order", () -> jdbc.sql("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE datname = current_database() AND wait_event_type = 'Lock'
                                """).query(Integer.class).single() >= 2);
            } finally {
                gate.commit();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(attempts(orderId)).isEqualTo(2);
        assertThat(pending(orderId)).containsExactly("inventory.reserve-stock", "inventory.reserve-stock");
    }

    /** Moves the process's deadline this many seconds into the past, without running the sweep. */
    private void overdue(UUID orderId, int seconds) {
        jdbc.sql("UPDATE ordering.order_processes SET deadline_at = :past WHERE order_id = :id")
                .param("past", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(seconds))
                .param("id", orderId)
                .update();
    }

    private int processed(String consumer) {
        return jdbc.sql("SELECT count(*) FROM platform.processed_messages WHERE consumer = :consumer")
                .param("consumer", consumer)
                .query(Integer.class)
                .single();
    }

    private Instant deadline(UUID orderId) {
        return jdbc.sql("SELECT deadline_at FROM ordering.order_processes WHERE order_id = :id")
                .param("id", orderId)
                .query((row, n) -> row.getObject("deadline_at", OffsetDateTime.class).toInstant())
                .single();
    }
}
