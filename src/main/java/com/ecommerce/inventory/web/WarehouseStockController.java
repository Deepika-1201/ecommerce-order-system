package com.ecommerce.inventory.web;

import com.ecommerce.inventory.domain.StockLevel;
import com.ecommerce.inventory.domain.StockMovement;
import com.ecommerce.inventory.domain.WarehouseStockService;
import com.ecommerce.inventory.web.InventoryApi.AdjustStock;
import com.ecommerce.inventory.web.InventoryApi.MovementList;
import com.ecommerce.inventory.web.InventoryApi.MovementResponse;
import com.ecommerce.inventory.web.InventoryApi.ReceiveStock;
import com.ecommerce.inventory.web.InventoryApi.StockLevelResponse;
import com.ecommerce.inventory.web.InventoryApi.StockList;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.IdempotentRequest;
import com.ecommerce.platform.IdempotentRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Stock levels, receipts, adjustments and movements, for warehouse staff (LLD §5.9). */
@ApiController
@RequestMapping("/v1/warehouse/stock")
@Tag(name = "Warehouse", description = "Role warehouse. Stock levels, receipts and adjustments, and each SKU's "
        + "movements. Receipts and adjustments need an Idempotency-Key.")
@SecurityRequirement(name = ApiController.BEARER_AUTH)
class WarehouseStockController {

    private static final Pattern SKU = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{2,39}");
    private static final String STOCK_CURSOR = "s:";
    private static final String MOVEMENT_CURSOR = "m:";

    private final WarehouseStockService warehouse;
    private final IdempotentRequests idempotency;

    WarehouseStockController(WarehouseStockService warehouse, IdempotentRequests idempotency) {
        this.warehouse = warehouse;
        this.idempotency = idempotency;
    }

    @Operation(summary = "List stock items in SKU order")
    @GetMapping
    StockList listStock(
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(required = false) String cursor) {
        List<StockLevel> page = warehouse.levels(decode(cursor, STOCK_CURSOR, Function.identity()), limit);
        boolean more = page.size() > limit;
        List<StockLevel> items = more ? page.subList(0, limit) : page;
        return new StockList(items.stream().map(StockLevelResponse::of).toList(),
                more ? encode(STOCK_CURSOR, items.getLast().sku()) : null);
    }

    @Operation(summary = "Read a SKU's stock levels")
    @GetMapping("/{sku}")
    StockLevelResponse getStock(@PathVariable String sku) {
        return StockLevelResponse.of(warehouse.level(canonical(sku)));
    }

    @Operation(summary = "Receive units (1-100,000); the first receipt of a SKU creates its stock item")
    @ApiResponse(responseCode = "200", description = "The stock levels after the receipt",
            content = @Content(schema = @Schema(implementation = StockLevelResponse.class)))
    @PostMapping("/{sku}/receipts")
    ResponseEntity<?> receiveStock(Caller staff, @PathVariable String sku,
            @Parameter(required = true, description = "Unique per receipt; a retry with the same key and body "
                    + "replays the first response")
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ReceiveStock request) {
        String code = canonical(sku);
        return change(staff, idempotencyKey, "POST /v1/warehouse/stock/" + code + "/receipts", request,
                () -> warehouse.receive(staff, code, request.quantity(), request.reference()));
    }

    @Operation(summary = "Correct a SKU's count with a reason; never below the units reserved for orders")
    @ApiResponse(responseCode = "200", description = "The stock levels after the adjustment",
            content = @Content(schema = @Schema(implementation = StockLevelResponse.class)))
    @PostMapping("/{sku}/adjustments")
    ResponseEntity<?> adjustStock(Caller staff, @PathVariable String sku,
            @Parameter(required = true, description = "Unique per adjustment; a retry with the same key and body "
                    + "replays the first response")
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AdjustStock request) {
        String code = canonical(sku);
        return change(staff, idempotencyKey, "POST /v1/warehouse/stock/" + code + "/adjustments", request,
                () -> warehouse.adjust(staff, code, request.quantityChange(), request.reason(), request.note()));
    }

    @Operation(summary = "List a SKU's stock movements, newest first")
    @GetMapping("/{sku}/movements")
    MovementList listStockMovements(
            @PathVariable String sku,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(required = false) String cursor) {
        List<StockMovement> page = warehouse.movements(canonical(sku), decode(cursor, MOVEMENT_CURSOR, Long::valueOf),
                limit);
        boolean more = page.size() > limit;
        List<StockMovement> items = more ? page.subList(0, limit) : page;
        return new MovementList(items.stream().map(MovementResponse::of).toList(),
                more ? encode(MOVEMENT_CURSOR, Long.toString(items.getLast().id())) : null);
    }

    /** Runs the change once per key, answering with the levels after it (ADR-010). */
    private ResponseEntity<?> change(Caller staff, String key, String operation, Object body,
            Supplier<StockLevel> change) {
        try {
            return idempotency.execute(IdempotentRequest.of(staff.subject(), key, operation, body),
                    () -> ResponseEntity.ok(StockLevelResponse.of(change.get())));
        } catch (CannotAcquireLockException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "stock_busy",
                    "This SKU's stock is busy with other changes; try again.", Duration.ofSeconds(1));
        }
    }

    private static String canonical(String sku) {
        if (!SKU.matcher(sku).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request",
                    "A SKU is 3 to 40 letters, digits and hyphens.");
        }
        return sku.toUpperCase(Locale.ROOT);
    }

    private static String encode(String prefix, String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((prefix + value).getBytes(StandardCharsets.UTF_8));
    }

    private static <T> T decode(String cursor, String prefix, Function<String, T> parse) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (plain.startsWith(prefix)) {
                return parse.apply(plain.substring(prefix.length()));
            }
        } catch (IllegalArgumentException malformed) {
            // Falls through to the same answer as any other cursor this API did not issue.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "This cursor was not issued by this API.");
    }
}
