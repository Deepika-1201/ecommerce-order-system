package com.ecommerce.inventory.web;

import com.ecommerce.inventory.domain.AdjustmentReason;
import com.ecommerce.inventory.domain.MovementKind;
import com.ecommerce.inventory.domain.StockLevel;
import com.ecommerce.inventory.domain.StockMovement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the warehouse API (LLD §5.9). */
final class InventoryApi {

    private InventoryApi() {
    }

    record ReceiveStock(@NotNull @Min(1) @Max(100_000) Integer quantity, @Size(max = 100) String reference) {
    }

    /** DAMAGED and LOST take units away, FOUND adds them, COUNT_CORRECTION does either. */
    record AdjustStock(
            @NotNull @Min(-100_000) @Max(100_000) Integer quantityChange,
            @NotNull AdjustmentReason reason,
            @Size(max = 500) String note) {
    }

    record StockLevelResponse(
            String sku,
            String locationCode,
            long onHand,
            long reserved,
            long available,
            long version,
            Instant updatedAt) {

        static StockLevelResponse of(StockLevel level) {
            return new StockLevelResponse(level.sku(), level.locationCode(), level.onHand(), level.reserved(),
                    level.available(), level.version(), level.updatedAt());
        }
    }

    record StockList(List<StockLevelResponse> items, String nextCursor) {
    }

    record MovementResponse(
            long id,
            MovementKind kind,
            int quantity,
            AdjustmentReason reason,
            String note,
            String reference,
            UUID orderId,
            long onHandAfter,
            String actorId,
            Instant createdAt) {

        static MovementResponse of(StockMovement movement) {
            return new MovementResponse(movement.id(), movement.kind(), movement.quantity(), movement.reason(),
                    movement.note(), movement.reference(), movement.orderId(), movement.onHandAfter(),
                    movement.actorId(), movement.createdAt());
        }
    }

    record MovementList(List<MovementResponse> items, String nextCursor) {
    }
}
