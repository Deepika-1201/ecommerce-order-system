package com.ecommerce.cart.web;

import com.ecommerce.cart.domain.CartOwner;
import com.ecommerce.cart.domain.CartService;
import com.ecommerce.cart.domain.CartTokens;
import com.ecommerce.cart.web.CartApi.ApplyCoupon;
import com.ecommerce.cart.web.CartApi.CartResponse;
import com.ecommerce.cart.web.CartApi.QuoteCart;
import com.ecommerce.cart.web.CartApi.QuoteResponse;
import com.ecommerce.cart.web.CartApi.SetQuantity;
import com.ecommerce.customer.Customers;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.ObjectStorage;
import com.ecommerce.pricing.Quote;
import com.ecommerce.shared.IndianState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The signed-in customer's cart and its quotes; the cart is found from the token (ADR-017). */
@ApiController
@RequestMapping("/v1/me/cart")
@Tag(name = "My cart", description = "The signed-in customer's cart and its quotes. Responses carry the version "
        + "as ETag; If-Match makes a write conditional.")
@SecurityRequirement(name = ApiController.BEARER_AUTH)
class MyCartController {

    private final CartService carts;
    private final Customers customers;
    private final ObjectStorage storage;

    MyCartController(CartService carts, Customers customers, ObjectStorage storage) {
        this.carts = carts;
        this.customers = customers;
        this.storage = storage;
    }

    @Operation(summary = "Read the cart with current prices; empty at version 0 before the first item")
    @GetMapping
    ResponseEntity<CartResponse> getMyCart(Caller caller) {
        return CartApi.withEtag(carts.view(owner(caller)), storage);
    }

    @Operation(summary = "Set a line's quantity (1-10), adding the line if needed")
    @PutMapping("/lines/{sku}")
    ResponseEntity<CartResponse> setMyCartLine(Caller caller, @PathVariable String sku,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody SetQuantity request) {
        return CartApi.withEtag(carts.setLine(owner(caller), sku, request.quantity(),
                CartApi.expectedVersion(ifMatch)), storage);
    }

    @Operation(summary = "Remove a line; removing a line that is not there changes nothing")
    @DeleteMapping("/lines/{sku}")
    ResponseEntity<CartResponse> removeMyCartLine(Caller caller, @PathVariable String sku,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return CartApi.withEtag(carts.removeLine(owner(caller), sku, CartApi.expectedVersion(ifMatch)), storage);
    }

    @Operation(summary = "Apply a coupon, replacing any other")
    @PutMapping("/coupon")
    ResponseEntity<CartResponse> applyMyCartCoupon(Caller caller,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ApplyCoupon request) {
        return CartApi.withEtag(carts.applyCoupon(owner(caller), request.code(), CartApi.expectedVersion(ifMatch)),
                storage);
    }

    @Operation(summary = "Remove the coupon")
    @DeleteMapping("/coupon")
    ResponseEntity<CartResponse> removeMyCartCoupon(Caller caller,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return CartApi.withEtag(carts.removeCoupon(owner(caller), CartApi.expectedVersion(ifMatch)), storage);
    }

    @Operation(summary = "Merge the guest cart named by Cart-Token into this cart, after signing in")
    @PostMapping("/merge")
    ResponseEntity<CartResponse> mergeGuestCart(Caller caller, @RequestHeader("Cart-Token") String cartToken) {
        return CartApi.withEtag(carts.merge(owner(caller), new CartOwner.Guest(CartTokens.hash(cartToken))),
                storage);
    }

    @Operation(summary = "Quote the cart for a delivery state: prices, discount, GST and shipping, valid 10 minutes")
    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<QuoteResponse> quoteMyCart(Caller caller, @Valid @RequestBody QuoteCart request) {
        Quote quote = carts.quote(owner(caller), IndianState.fromCode(request.deliveryStateCode()).orElseThrow());
        return ResponseEntity.created(URI.create("/v1/me/cart/quotes/" + quote.id()))
                .body(QuoteResponse.of(quote, storage));
    }

    @Operation(summary = "Read one of the customer's quotes")
    @GetMapping("/quotes/{id}")
    QuoteResponse getMyCartQuote(Caller caller, @PathVariable UUID id) {
        return QuoteResponse.of(carts.findQuote(owner(caller), id), storage);
    }

    private CartOwner.Customer owner(Caller caller) {
        return new CartOwner.Customer(customers.idOf(caller));
    }
}
