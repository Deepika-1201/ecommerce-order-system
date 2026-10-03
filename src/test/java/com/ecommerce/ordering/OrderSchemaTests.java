package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The ordering schema refuses what no decision produces (database.md §8): a reason that does not fit the status,
 * totals that do not add up, a deadline on a finished process, a cancellation without what it needs.
 */
class OrderSchemaTests extends OrderingTest {

    @Test
    void aReasonFitsItsStatusAndOnlyAnOrderShortOfStockNamesASku() {
        UUID orderId = placed();

        assertRejected("orders_reason", "UPDATE ordering.orders SET status = 'REJECTED' WHERE id = :id", orderId);
        assertRejected("orders_reason", "UPDATE ordering.orders SET reason = 'CUSTOMER' WHERE id = :id", orderId);
        assertRejected("orders_reason",
                "UPDATE ordering.orders SET status = 'CANCELLED', reason = 'OUT_OF_STOCK' WHERE id = :id", orderId);
        assertRejected("orders_short_sku",
                "UPDATE ordering.orders SET status = 'REJECTED', reason = 'OUT_OF_STOCK' WHERE id = :id", orderId);
        assertRejected("orders_short_sku", "UPDATE ordering.orders SET short_sku = 'SKU-1' WHERE id = :id", orderId);
    }

    @Test
    void anOrdersTotalsAndLinesAddUp() {
        UUID orderId = placed();

        assertRejected("orders_add_up",
                "UPDATE ordering.orders SET grand_total_paise = grand_total_paise + 1 WHERE id = :id", orderId);
        assertRejected("orders_add_up",
                "UPDATE ordering.orders SET discount_paise = discount_paise + 1 WHERE id = :id", orderId);
        assertRejected("order_lines_add_up",
                "UPDATE ordering.order_lines SET amount_paise = amount_paise + 1 WHERE order_id = :id", orderId);
    }

    @Test
    void aCouponHasItsCodeAndARefundItsStatus() {
        UUID orderId = placed();

        assertRejected("orders_coupon", "UPDATE ordering.orders SET coupon_code = 'WELCOME' WHERE id = :id", orderId);
        assertRejected("orders_refund", "UPDATE ordering.orders SET refund_amount_paise = 100 WHERE id = :id",
                orderId);
    }

    @Test
    void aProcessHasADeadlineExactlyUntilItIsDone() {
        UUID orderId = placed();

        assertRejected("order_processes_deadline",
                "UPDATE ordering.order_processes SET deadline_at = NULL WHERE order_id = :id", orderId);
        assertRejected("order_processes_deadline",
                "UPDATE ordering.order_processes SET step = 'DONE' WHERE order_id = :id", orderId);
    }

    @Test
    void aCancellationHasWhatItsRequesterMustGive() {
        UUID orderId = placed();

        assertRejected("order_processes_cancellation", """
                UPDATE ordering.order_processes SET cancel_reason = 'CUSTOMER' WHERE order_id = :id
                """, orderId);
        assertRejected("order_processes_cancellation", """
                UPDATE ordering.order_processes SET cancel_reason = 'CUSTOMER', cancel_code = 'OTHER',
                    cancel_requested_at = now() WHERE order_id = :id
                """, orderId);
        assertRejected("order_processes_cancellation", """
                UPDATE ordering.order_processes SET cancel_reason = 'SUPPORT', cancel_requested_at = now()
                WHERE order_id = :id
                """, orderId);
        assertRejected("order_processes_cancellation", """
                UPDATE ordering.order_processes SET cancel_reason = 'SUPPORT', cancel_code = 'OTHER',
                    cancel_requested_at = now() WHERE order_id = :id
                """, orderId);
        assertRejected("order_processes_cancellation", """
                UPDATE ordering.order_processes SET cancel_requested_at = now() WHERE order_id = :id
                """, orderId);
    }

    private UUID placed() {
        return placeOrder(asha, product("Steel bottle", 59_900, 5), 1, null);
    }

    private void assertRejected(String constraint, String sql, UUID orderId) {
        assertThatThrownBy(() -> jdbc.sql(sql).param("id", orderId).update())
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
