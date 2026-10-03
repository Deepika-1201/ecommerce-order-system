package com.ecommerce.ordering.web;

import com.ecommerce.ordering.domain.OrderService;
import com.ecommerce.ordering.web.OrderingApi.OrderResponse;
import com.ecommerce.ordering.web.OrderingApi.SupportCancellation;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.IdempotentRequest;
import com.ecommerce.platform.IdempotentRequests;
import com.ecommerce.platform.ObjectStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Any order, for support staff (LLD §6.9): read it as its customer does, and cancel it with a reason code. */
@ApiController
@RequestMapping("/v1/support/orders")
@Tag(name = "Support", description = "Role support. Any order, as its customer sees it plus the customer id; "
        + "cancellations take a reason code and an Idempotency-Key, and are audit-logged.")
@SecurityRequirement(name = ApiController.BEARER_AUTH)
class SupportOrdersController {

    private final OrderService orders;
    private final IdempotentRequests idempotency;
    private final ObjectStorage storage;

    SupportOrdersController(OrderService orders, IdempotentRequests idempotency, ObjectStorage storage) {
        this.orders = orders;
        this.idempotency = idempotency;
        this.storage = storage;
    }

    @Operation(summary = "Read any order, with its customer id")
    @GetMapping("/{id}")
    OrderResponse getOrderForSupport(@PathVariable UUID id) {
        return OrderResponse.forSupport(orders.supportOrder(id), storage);
    }

    @Operation(summary = "Cancel an order before it ships, with a reason code; OTHER needs a note")
    @ApiResponse(responseCode = "202", description = "The order, CANCELLING or already CANCELLED",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<?> cancelOrderForSupport(Caller staff, @PathVariable UUID id,
            @Parameter(required = true, description = "Unique per cancellation request; a retry with the same key "
                    + "and body replays the first response")
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody SupportCancellation request) {
        return idempotency.execute(IdempotentRequest.of(staff.subject(), idempotencyKey,
                "POST /v1/support/orders/" + id + "/cancel", request),
                () -> ResponseEntity.accepted().body(OrderResponse.forSupport(
                        orders.cancelForSupport(staff, id, request.reasonCode(), request.note()), storage)));
    }
}
