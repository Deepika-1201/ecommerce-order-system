package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/** The schema refuses what the code must never do, so a bug fails loudly instead of corrupting stock (LLD §5.10). */
class StockSchemaTests extends InventoryTest {

    private static final String MOVEMENT = """
            INSERT INTO inventory.stock_movements (sku, location_code, kind, quantity, reason, order_id, on_hand_after,
                                                   created_at)
            VALUES ('INK-1', 'BLR1', :kind, :quantity, :reason, :orderId, 5, now())
            """;

    @BeforeEach
    void inkInStock() {
        receive("INK-1", 5);
    }

    @Test
    void reservedStaysBetweenZeroAndOnHand() {
        assertRefused("UPDATE inventory.stock_items SET reserved = on_hand + 1");
        assertRefused("UPDATE inventory.stock_items SET reserved = -1, on_hand = 0");
    }

    @Test
    void eachMovementHasTheSignOfItsKindAndReason() {
        assertRefusedMovement("RECEIPT", -1, null, null);
        assertRefusedMovement("RETURN", -1, null, UUID.randomUUID());
        assertRefusedMovement("HANDOVER", 1, null, UUID.randomUUID());
        assertRefusedMovement("ADJUSTMENT", 1, "DAMAGED", null);
        assertRefusedMovement("ADJUSTMENT", 1, "LOST", null);
        assertRefusedMovement("ADJUSTMENT", -1, "FOUND", null);
        assertRefusedMovement("ADJUSTMENT", 0, "COUNT_CORRECTION", null);
    }

    @Test
    void reasonsAreForAdjustmentsAndOrdersForHandoversAndReturns() {
        assertRefusedMovement("ADJUSTMENT", -1, null, null);
        assertRefusedMovement("RECEIPT", 1, "FOUND", null);
        assertRefusedMovement("HANDOVER", -1, null, null);
        assertRefusedMovement("RECEIPT", 1, null, UUID.randomUUID());
    }

    @Test
    void anOrderHandsOverAndReturnsEachSkuOnce() {
        UUID order = UUID.randomUUID();
        movement("HANDOVER", -1, null, order);
        movement("RETURN", 1, null, order);

        assertThatThrownBy(() -> movement("HANDOVER", -1, null, order)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> movement("RETURN", 1, null, order)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void movementsAreNeverChangedOrDeleted() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE inventory.stock_movements SET quantity = 6").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM inventory.stock_movements").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void aHoldHasAnExpiryAndOnlyARejectionNamesAShortSku() {
        jdbc.sql(reservation("HELD", "now()", "NULL", "NULL")).update();
        jdbc.sql(reservation("REJECTED", "NULL", "'INK-1'", "0")).update();

        assertRefused(reservation("HELD", "NULL", "NULL", "NULL"));
        assertRefused(reservation("HELD", "now()", "'INK-1'", "0"));
        assertRefused(reservation("REJECTED", "NULL", "NULL", "NULL"));
        assertRefused(reservation("REJECTED", "NULL", "'INK-1'", "NULL"));
        assertRefused(reservation("REJECTED", "NULL", "'INK-1'", "-1"));
    }

    private static String reservation(String status, String expiresAt, String shortSku, String shortAvailable) {
        return """
                INSERT INTO inventory.reservations (id, order_id, status, expires_at, short_sku, short_available,
                                                    version, created_at, updated_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), '%s', %s, %s, %s, 1, now(), now())
                """.formatted(status, expiresAt, shortSku, shortAvailable);
    }

    private void movement(String kind, int quantity, String reason, UUID orderId) {
        jdbc.sql(MOVEMENT)
                .param("kind", kind)
                .param("quantity", quantity)
                .param("reason", reason)
                .param("orderId", orderId)
                .update();
    }

    private void assertRefusedMovement(String kind, int quantity, String reason, UUID orderId) {
        assertThatThrownBy(() -> movement(kind, quantity, reason, orderId))
                .as("%s of %d, reason %s, order %s", kind, quantity, reason, orderId)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertRefused(String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql).isInstanceOf(DataIntegrityViolationException.class);
    }
}
