package com.ecommerce.inventory.domain;

import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.AuditEntry;
import com.ecommerce.platform.AuditLog;
import com.ecommerce.platform.Caller;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock levels, receipts and adjustments for warehouse staff (LLD §5.8, §5.9). Each change writes a movement and an
 * audit entry in the caller's transaction, which the API's idempotency key also uses. A stock row locked longer than the
 * lock timeout fails the change with {@code CannotAcquireLockException}.
 */
@Service
public class WarehouseStockService {

    private final StockRepository stock;
    private final StockLocks locks;
    private final AuditLog audit;
    private final Clock clock;

    WarehouseStockService(StockRepository stock, StockLocks locks, AuditLog audit, Clock clock) {
        this.stock = stock;
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
    }

    /** Adds received units; the first receipt of a SKU creates its stock item. */
    @Transactional
    public StockLevel receive(Caller staff, String sku, int quantity, String reference) {
        return StockLocks.translated(() -> {
            locks.limitWaits();
            Instant now = clock.instant();
            StockLevel level = stock.receive(sku, Locations.BENGALURU, quantity, now);
            stock.insertMovement(sku, level.locationCode(), MovementKind.RECEIPT, quantity, null, null, reference, null,
                    level.onHand(), staff.subject(), now);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("quantity", quantity);
            if (reference != null) {
                change.put("reference", reference);
            }
            record(staff, "inventory.receipt", level, null, change);
            return level;
        });
    }

    /** Corrects the count; never below the units reserved for orders. */
    @Transactional
    public StockLevel adjust(Caller staff, String sku, int quantityChange, AdjustmentReason reason, String note) {
        if (!reason.allows(quantityChange)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_adjustment", switch (reason) {
                case DAMAGED, LOST -> reason + " takes units away: quantity_change must be negative.";
                case FOUND -> "FOUND adds units: quantity_change must be positive.";
                case COUNT_CORRECTION -> "quantity_change must not be zero.";
            });
        }
        return StockLocks.translated(() -> {
            locks.limitWaits();
            Instant now = clock.instant();
            StockLevel level = stock.adjust(sku, Locations.BENGALURU, quantityChange, now)
                    .orElseThrow(() -> refused(sku));
            stock.insertMovement(sku, level.locationCode(), MovementKind.ADJUSTMENT, quantityChange, reason, note,
                    null, null, level.onHand(), staff.subject(), now);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("quantity_change", quantityChange);
            if (note != null) {
                change.put("note", note);
            }
            record(staff, "inventory.adjustment", level, reason.name(), change);
            return level;
        });
    }

    @Transactional(readOnly = true)
    public StockLevel level(String sku) {
        return stock.find(sku, Locations.BENGALURU).orElseThrow(WarehouseStockService::notFound);
    }

    /** In SKU order, after {@code afterSku}; one more than {@code limit}, to know whether a page follows. */
    @Transactional(readOnly = true)
    public List<StockLevel> levels(String afterSku, int limit) {
        return stock.list(Locations.BENGALURU, afterSku, limit);
    }

    /** Newest first, before {@code beforeId}; one more than {@code limit}, to know whether a page follows. */
    @Transactional(readOnly = true)
    public List<StockMovement> movements(String sku, Long beforeId, int limit) {
        level(sku);
        return stock.movements(sku, Locations.BENGALURU, beforeId, limit);
    }

    private ApiException refused(String sku) {
        return stock.find(sku, Locations.BENGALURU)
                .map(level -> new ApiException(HttpStatus.CONFLICT, "adjustment_below_reserved",
                        "Orders have reserved " + level.reserved() + " of the " + level.onHand()
                                + " units on hand: at most " + level.available() + " can be taken away."))
                .orElseGet(WarehouseStockService::notFound);
    }

    /** Audits a change with the levels after it. */
    private void record(Caller staff, String action, StockLevel after, String reason, Map<String, Object> change) {
        Map<String, Object> details = new LinkedHashMap<>(change);
        details.put("location_code", after.locationCode());
        details.put("on_hand", after.onHand());
        details.put("reserved", after.reserved());
        audit.record(new AuditEntry("staff", staff.subject(), action, "stock_item", after.sku(), reason, details));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "No stock item for this SKU. A receipt creates it.");
    }
}
