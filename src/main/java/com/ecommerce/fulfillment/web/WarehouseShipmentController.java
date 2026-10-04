package com.ecommerce.fulfillment.web;

import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.fulfillment.domain.ShipmentOperations;
import com.ecommerce.fulfillment.domain.ShipmentOperations.WarehouseShipment;
import com.ecommerce.fulfillment.web.FulfillmentApi.WarehouseShipmentList;
import com.ecommerce.fulfillment.web.FulfillmentApi.WarehouseShipmentResponse;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The warehouse's shipments: what to pack and hand over, and the marks (LLD §8.7). */
@ApiController
@RequestMapping("/v1/warehouse/shipments")
@Tag(name = "Warehouse shipments", description = "Role warehouse. Shipments to pack and to hand over to the carrier, "
        + "oldest first. A mark the shipment has already passed answers 200, unchanged.")
@SecurityRequirement(name = ApiController.BEARER_AUTH)
class WarehouseShipmentController {

    private static final String CURSOR = "w:";

    private final ShipmentOperations operations;

    WarehouseShipmentController(ShipmentOperations operations) {
        this.operations = operations;
    }

    @Operation(summary = "List shipments to pack (BOOKED, the default) or to hand over (PACKED)")
    @GetMapping
    WarehouseShipmentList listWarehouseShipments(
            @Parameter(description = "BOOKED or PACKED") @RequestParam(defaultValue = "BOOKED") ShipmentStatus status,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(required = false) String cursor) {
        if (status != ShipmentStatus.BOOKED && status != ShipmentStatus.PACKED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request",
                    "The warehouse lists BOOKED or PACKED shipments.");
        }
        List<WarehouseShipment> page = operations.inStatus(status, decode(cursor), limit);
        boolean more = page.size() > limit;
        List<WarehouseShipment> items = more ? page.subList(0, limit) : page;
        return new WarehouseShipmentList(items.stream().map(WarehouseShipmentResponse::of).toList(),
                more ? encode(items.getLast().id()) : null);
    }

    @Operation(summary = "Mark a booked shipment packed")
    @PostMapping("/{id}/packed")
    WarehouseShipmentResponse markShipmentPacked(Caller staff, @PathVariable UUID id) {
        return WarehouseShipmentResponse.of(operations.pack(staff, id));
    }

    @Operation(summary = "Mark a packed shipment handed over to the carrier: the order is SHIPPED")
    @PostMapping("/{id}/handed-over")
    WarehouseShipmentResponse markShipmentHandedOver(Caller staff, @PathVariable UUID id) {
        return WarehouseShipmentResponse.of(operations.handOver(staff, id));
    }

    private static String encode(UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((CURSOR + id).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (plain.startsWith(CURSOR)) {
                return UUID.fromString(plain.substring(CURSOR.length()));
            }
        } catch (IllegalArgumentException malformed) {
            // Falls through to the same answer as any other cursor this API did not issue.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "This cursor was not issued by this API.");
    }
}
