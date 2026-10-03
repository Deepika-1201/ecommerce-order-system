package com.ecommerce.ordering.web;

import com.ecommerce.ordering.domain.OrderService;
import com.ecommerce.ordering.domain.OrderSummary;
import com.ecommerce.ordering.domain.OrderView;
import com.ecommerce.ordering.web.OrderingApi.OrderList;
import com.ecommerce.ordering.web.OrderingApi.OrderResponse;
import com.ecommerce.ordering.web.OrderingApi.OrderSummaryResponse;
import com.ecommerce.ordering.web.OrderingApi.PlaceOrder;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The signed-in customer's orders (ADR-017, LLD §6.9); another customer's order is not found. */
@ApiController
@RequestMapping("/v1/me/orders")
@Tag(name = "My orders", description = "The signed-in customer's orders. Placing and cancelling need an "
        + "Idempotency-Key; both answer 202 and the order moves on by itself, so follow its status.")
@SecurityRequirement(name = ApiController.BEARER_AUTH)
class MyOrdersController {

    private final OrderService orders;
    private final IdempotentRequests idempotency;
    private final ObjectStorage storage;

    MyOrdersController(OrderService orders, IdempotentRequests idempotency, ObjectStorage storage) {
        this.orders = orders;
        this.idempotency = idempotency;
        this.storage = storage;
    }

    @Operation(summary = "Place an order from a valid quote, at its prices; stock is reserved next, then payment "
            + "is set up")
    @ApiResponse(responseCode = "202", description = "The order, PLACED",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<?> placeOrder(Caller caller,
            @Parameter(required = true, description = "Unique per order; a retry with the same key and body "
                    + "replays the first response")
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrder request) {
        return idempotency.execute(IdempotentRequest.of(caller.subject(), idempotencyKey, "POST /v1/me/orders",
                request), () -> {
                    OrderView placed = orders.place(caller, request.quoteId(), request.deliveryAddressId(),
                            request.billingAddressId());
                    return ResponseEntity.accepted()
                            .location(URI.create("/v1/me/orders/" + placed.order().id()))
                            .body(OrderResponse.forCustomer(placed, storage));
                });
    }

    @Operation(summary = "List the caller's orders, newest first")
    @GetMapping
    OrderList listMyOrders(Caller caller,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(required = false) String cursor) {
        List<OrderSummary> page = orders.customerOrders(caller, OrderingApi.afterCursor(cursor), limit + 1);
        boolean more = page.size() > limit;
        List<OrderSummary> items = more ? page.subList(0, limit) : page;
        return new OrderList(items.stream().map(OrderSummaryResponse::of).toList(),
                more ? OrderingApi.cursor(items.getLast().id()) : null);
    }

    @Operation(summary = "Read one of the caller's orders: status and reason, lines, totals, addresses, the checkout "
            + "URL while awaiting payment, and any refund")
    @GetMapping("/{id}")
    OrderResponse getMyOrder(Caller caller, @PathVariable UUID id) {
        return OrderResponse.forCustomer(orders.customerOrder(caller, id), storage);
    }

    @Operation(summary = "Cancel an order before it ships; a paid order is refunded")
    @ApiResponse(responseCode = "202", description = "The order, CANCELLING or already CANCELLED",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<?> cancelMyOrder(Caller caller, @PathVariable UUID id,
            @Parameter(required = true, description = "Unique per cancellation request; a retry with the same key "
                    + "replays the first response")
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) String idempotencyKey) {
        return idempotency.execute(IdempotentRequest.of(caller.subject(), idempotencyKey,
                "POST /v1/me/orders/" + id + "/cancel", null),
                () -> ResponseEntity.accepted()
                        .body(OrderResponse.forCustomer(orders.cancelForCustomer(caller, id), storage)));
    }

    @Operation(summary = "Check the payment now, on returning from the hosted checkout, rather than waiting for the "
            + "gateway's webhook; repeats are harmless")
    @ApiResponse(responseCode = "202", description = "The order; it moves on once the payment's outcome is known",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @PostMapping("/{id}/payment-check")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OrderResponse checkMyPayment(Caller caller, @PathVariable UUID id) {
        return OrderResponse.forCustomer(orders.checkPayment(caller, id), storage);
    }
}
