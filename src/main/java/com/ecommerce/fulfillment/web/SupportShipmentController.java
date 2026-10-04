package com.ecommerce.fulfillment.web;

import com.ecommerce.fulfillment.domain.ShipmentOperations;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Support's re-drive of a booking the carrier refused, or that ran out of time (LLD §8.5, S8). */
@ApiController
@RequestMapping("/v1/support/shipments")
@Tag(name = "Support shipments", description = "Role support. Bookings that failed, booked again; audit-logged.")
@SecurityRequirement(name = ApiController.BEARER_AUTH)
class SupportShipmentController {

    private final ShipmentOperations operations;

    SupportShipmentController(ShipmentOperations operations) {
        this.operations = operations;
    }

    @Operation(summary = "Book a failed booking again, with a new 24-hour budget")
    @ApiResponse(responseCode = "202", description = "The booking is pending again")
    @PostMapping("/{id}/booking")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void redriveShipmentBooking(Caller staff, @PathVariable UUID id) {
        operations.redriveBooking(staff, id);
    }
}
