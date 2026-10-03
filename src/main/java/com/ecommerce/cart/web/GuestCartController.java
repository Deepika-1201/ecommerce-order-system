package com.ecommerce.cart.web;

import com.ecommerce.cart.domain.CartOwner;
import com.ecommerce.cart.domain.CartService;
import com.ecommerce.cart.domain.CartTokens;
import com.ecommerce.cart.web.CartApi.ApplyCoupon;
import com.ecommerce.cart.web.CartApi.CartResponse;
import com.ecommerce.cart.web.CartApi.GuestCartCreated;
import com.ecommerce.cart.web.CartApi.QuoteCart;
import com.ecommerce.cart.web.CartApi.QuoteResponse;
import com.ecommerce.cart.web.CartApi.SetQuantity;
import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.ObjectStorage;
import com.ecommerce.pricing.Quote;
import com.ecommerce.shared.IndianState;
import io.swagger.v3.oas.annotations.Operation;
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

/** Guest carts, opened by the cart token in the {@code Cart-Token} header (ADR-020). */
@ApiController
@RequestMapping("/v1/guest/cart")
@Tag(name = "Guest cart", description = "No access token: the Cart-Token header is the credential. An unknown "
        + "token is 404.")
class GuestCartController {

    private static final String CART_TOKEN = "Cart-Token";

    private final CartService carts;
    private final ObjectStorage storage;

    GuestCartController(CartService carts, ObjectStorage storage) {
        this.carts = carts;
        this.storage = storage;
    }

    @Operation(summary = "Create a guest cart; its token is in the response, this once")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<GuestCartCreated> createGuestCart() {
        CartService.GuestCart created = carts.createGuestCart();
        return ResponseEntity.status(HttpStatus.CREATED).eTag("\"" + created.cart().version() + "\"")
                .body(new GuestCartCreated(created.token(), CartResponse.of(created.cart(), storage)));
    }

    @Operation(summary = "Read the cart with current prices")
    @GetMapping
    ResponseEntity<CartResponse> getGuestCart(@RequestHeader(CART_TOKEN) String cartToken) {
        return CartApi.withEtag(carts.view(owner(cartToken)), storage);
    }

    @Operation(summary = "Set a line's quantity (1-10), adding the line if needed")
    @PutMapping("/lines/{sku}")
    ResponseEntity<CartResponse> setGuestCartLine(@RequestHeader(CART_TOKEN) String cartToken,
            @PathVariable String sku, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody SetQuantity request) {
        return CartApi.withEtag(carts.setLine(owner(cartToken), sku, request.quantity(),
                CartApi.expectedVersion(ifMatch)), storage);
    }

    @Operation(summary = "Remove a line; removing a line that is not there changes nothing")
    @DeleteMapping("/lines/{sku}")
    ResponseEntity<CartResponse> removeGuestCartLine(@RequestHeader(CART_TOKEN) String cartToken,
            @PathVariable String sku, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return CartApi.withEtag(carts.removeLine(owner(cartToken), sku, CartApi.expectedVersion(ifMatch)), storage);
    }

    @Operation(summary = "Apply a coupon, replacing any other")
    @PutMapping("/coupon")
    ResponseEntity<CartResponse> applyGuestCartCoupon(@RequestHeader(CART_TOKEN) String cartToken,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ApplyCoupon request) {
        return CartApi.withEtag(carts.applyCoupon(owner(cartToken), request.code(), CartApi.expectedVersion(ifMatch)),
                storage);
    }

    @Operation(summary = "Remove the coupon")
    @DeleteMapping("/coupon")
    ResponseEntity<CartResponse> removeGuestCartCoupon(@RequestHeader(CART_TOKEN) String cartToken,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return CartApi.withEtag(carts.removeCoupon(owner(cartToken), CartApi.expectedVersion(ifMatch)), storage);
    }

    @Operation(summary = "Quote the cart for a delivery state: prices, discount, GST and shipping, valid 10 minutes")
    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<QuoteResponse> quoteGuestCart(@RequestHeader(CART_TOKEN) String cartToken,
            @Valid @RequestBody QuoteCart request) {
        Quote quote = carts.quote(owner(cartToken), IndianState.fromCode(request.deliveryStateCode()).orElseThrow());
        return ResponseEntity.created(URI.create("/v1/guest/cart/quotes/" + quote.id()))
                .body(QuoteResponse.of(quote, storage));
    }

    @Operation(summary = "Read one of this cart's quotes")
    @GetMapping("/quotes/{id}")
    QuoteResponse getGuestCartQuote(@RequestHeader(CART_TOKEN) String cartToken, @PathVariable UUID id) {
        return QuoteResponse.of(carts.findQuote(owner(cartToken), id), storage);
    }

    private static CartOwner.Guest owner(String cartToken) {
        return new CartOwner.Guest(CartTokens.hash(cartToken));
    }
}
