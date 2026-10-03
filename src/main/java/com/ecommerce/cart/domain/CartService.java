package com.ecommerce.cart.domain;

import com.ecommerce.catalog.SkuCatalog;
import com.ecommerce.catalog.SkuDetails;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.TaskExecution;
import com.ecommerce.pricing.Coupons;
import com.ecommerce.pricing.Quote;
import com.ecommerce.pricing.QuoteRequest;
import com.ecommerce.pricing.Quotes;
import com.ecommerce.shared.Ids;
import com.ecommerce.shared.IndianState;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carts for customers and guests (LLD §4.3–4.5). Every write locks the cart row, checks the optional expected
 * version, changes one thing and increments the version.
 */
@Service
public class CartService {

    static final int MAX_LINES = 50;
    static final String EXPIRE_TASK = "cart.expire-carts";
    private static final int EXPIRY_BATCH = 1_000;
    private static final int MAX_EXPIRY_BATCHES = 50;

    private final CartRepository carts;
    private final SkuCatalog skus;
    private final Coupons coupons;
    private final Quotes quotes;
    private final CartProperties properties;
    private final Clock clock;

    CartService(CartRepository carts, SkuCatalog skus, Coupons coupons, Quotes quotes, CartProperties properties,
            Clock clock) {
        this.carts = carts;
        this.skus = skus;
        this.coupons = coupons;
        this.quotes = quotes;
        this.properties = properties;
        this.clock = clock;
    }

    /** A customer without a cart sees an empty one at version 0; an unknown guest token is not found. */
    @Transactional(readOnly = true)
    public CartView view(CartOwner owner) {
        return carts.find(owner).map(this::view).orElseGet(() -> emptyFor(owner));
    }

    @Transactional
    public GuestCart createGuestCart() {
        String token = CartTokens.newToken();
        Instant now = clock.instant();
        UUID id = Ids.newId();
        carts.insertGuest(id, CartTokens.hash(token), now, now.plus(properties.guestLifetime()));
        return new GuestCart(token, view(carts.findById(id).orElseThrow()));
    }

    /** Adds the line, or sets its quantity. */
    @Transactional
    public CartView setLine(CartOwner owner, String sku, int quantity, Long expectedVersion) {
        String code = sku.toUpperCase(Locale.ROOT);
        SkuDetails details = skus.find(List.of(code)).get(code);
        if (details == null || !details.purchasable()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "item_unavailable", "This item cannot be bought.");
        }
        Cart cart = lockForWrite(owner);
        checkVersion(cart.version(), expectedVersion);
        if (cart.lines().stream().noneMatch(line -> line.sku().equals(code)) && cart.lines().size() >= MAX_LINES) {
            throw cartFull();
        }
        Instant now = clock.instant();
        carts.setLine(cart.id(), code, quantity, details.listPricePaise(), now);
        return changed(cart, owner, now);
    }

    /** Removing a line that is not there changes nothing. */
    @Transactional
    public CartView removeLine(CartOwner owner, String sku, Long expectedVersion) {
        Optional<Cart> locked = carts.lock(owner);
        if (locked.isEmpty()) {
            checkVersion(0, expectedVersion);
            return emptyFor(owner);
        }
        Cart cart = locked.get();
        checkVersion(cart.version(), expectedVersion);
        if (!carts.deleteLine(cart.id(), sku.toUpperCase(Locale.ROOT))) {
            return view(cart);
        }
        return changed(cart, owner, clock.instant());
    }

    @Transactional
    public CartView applyCoupon(CartOwner owner, String code, Long expectedVersion) {
        String applicable = coupons.checkApplicable(code, owner instanceof CartOwner.Customer);
        Cart cart = lockForWrite(owner);
        checkVersion(cart.version(), expectedVersion);
        carts.setCoupon(cart.id(), applicable);
        return changed(cart, owner, clock.instant());
    }

    @Transactional
    public CartView removeCoupon(CartOwner owner, Long expectedVersion) {
        Optional<Cart> locked = carts.lock(owner);
        if (locked.isEmpty()) {
            checkVersion(0, expectedVersion);
            return emptyFor(owner);
        }
        Cart cart = locked.get();
        checkVersion(cart.version(), expectedVersion);
        if (cart.couponCode() == null) {
            return view(cart);
        }
        carts.setCoupon(cart.id(), null);
        return changed(cart, owner, clock.instant());
    }

    /**
     * Merges a guest cart into the customer's (LLD §4.4): the guest's lines and coupon win, and the guest cart is
     * deleted with its token. Without a guest cart, as on a retry, nothing changes.
     */
    @Transactional
    public CartView merge(CartOwner.Customer owner, CartOwner.Guest guest) {
        Instant now = clock.instant();
        Cart mine = carts.lockOrCreate(owner, Ids.newId(), now, now.plus(properties.customerLifetime()));
        Optional<Cart> theirs = carts.lock(guest);
        if (theirs.isEmpty()) {
            return view(mine);
        }
        Set<String> merged = new LinkedHashSet<>();
        mine.lines().forEach(line -> merged.add(line.sku()));
        theirs.get().lines().forEach(line -> merged.add(line.sku()));
        if (merged.size() > MAX_LINES) {
            throw cartFull();
        }
        theirs.get().lines().forEach(line -> carts.replaceLine(mine.id(), line, now));
        if (theirs.get().couponCode() != null) {
            carts.setCoupon(mine.id(), theirs.get().couponCode());
        }
        carts.delete(theirs.get().id());
        return changed(mine, owner, now);
    }

    /** Prices the cart. Not one transaction: Pricing stores the quote in its own (LLD §4.2). */
    public Quote quote(CartOwner owner, IndianState deliveryState) {
        Cart cart = carts.find(owner).orElse(null);
        if (cart == null && owner instanceof CartOwner.Guest) {
            throw notFound("No such cart.");
        }
        if (cart == null || cart.lines().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "cart_empty", "The cart is empty.");
        }
        UUID customerId = owner instanceof CartOwner.Customer customer ? customer.customerId() : null;
        Quote quote = quotes.create(new QuoteRequest(cart.id(), customerId, cart.lines().stream()
                .map(line -> new QuoteRequest.Line(line.sku(), line.quantity(), line.addedPricePaise()))
                .toList(), cart.couponCode(), deliveryState));
        carts.extendExpiry(cart.id(), clock.instant().plus(properties.lifetime(owner)));
        return quote;
    }

    public Quote findQuote(CartOwner owner, UUID quoteId) {
        Optional<Quote> quote = switch (owner) {
            case CartOwner.Customer customer -> quotes.findForCustomer(quoteId, customer.customerId());
            case CartOwner.Guest guest -> carts.find(guest).flatMap(cart -> quotes.findForGuestCart(quoteId,
                    cart.id()));
        };
        return quote.orElseThrow(() -> notFound("No such quote."));
    }

    /** Carts without activity for their lifetime are deleted; at most 50 batches per run. */
    @HandlesTask(type = EXPIRE_TASK, every = "1h")
    void expireCarts(TaskExecution<Void> task) {
        int batches = 0;
        while (carts.deleteExpired(clock.instant(), EXPIRY_BATCH) == EXPIRY_BATCH && ++batches < MAX_EXPIRY_BATCHES) {
            // Each batch is its own statement, so a backlog never holds locks for long.
        }
    }

    private Cart lockForWrite(CartOwner owner) {
        return switch (owner) {
            case CartOwner.Customer customer -> {
                Instant now = clock.instant();
                yield carts.lockOrCreate(customer, Ids.newId(), now, now.plus(properties.customerLifetime()));
            }
            case CartOwner.Guest guest -> carts.lock(guest).orElseThrow(() -> notFound("No such cart."));
        };
    }

    private CartView changed(Cart cart, CartOwner owner, Instant now) {
        carts.changed(cart.id(), now, now.plus(properties.lifetime(owner)));
        return view(carts.findById(cart.id()).orElseThrow());
    }

    private CartView view(Cart cart) {
        Map<String, SkuDetails> details = skus.find(cart.lines().stream().map(Cart.Line::sku).toList());
        List<CartView.Line> lines = new ArrayList<>();
        long subtotal = 0;
        for (Cart.Line line : cart.lines()) {
            SkuDetails sku = details.get(line.sku());
            boolean available = sku != null && sku.purchasable();
            long unitPrice = sku != null ? sku.listPricePaise() : line.addedPricePaise();
            long total = unitPrice * line.quantity();
            if (available) {
                subtotal += total;
            }
            lines.add(new CartView.Line(line.sku(), sku == null ? null : sku.productId(),
                    sku == null ? line.sku() : sku.title(), sku == null ? Map.of() : sku.optionValues(),
                    sku == null ? null : sku.imageKey(), line.quantity(), unitPrice,
                    unitPrice != line.addedPricePaise() ? line.addedPricePaise() : null, total, available));
        }
        return new CartView(cart.version(), lines, cart.couponCode(), subtotal, cart.expiresAt());
    }

    private static CartView emptyFor(CartOwner owner) {
        if (owner instanceof CartOwner.Guest) {
            throw notFound("No such cart.");
        }
        return CartView.empty();
    }

    private static void checkVersion(long current, Long expected) {
        if (expected != null && expected != current) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "precondition_failed",
                    "The cart changed since it was read; read it again.");
        }
    }

    private static ApiException cartFull() {
        return new ApiException(HttpStatus.CONFLICT, "cart_full", "A cart holds at most " + MAX_LINES + " lines.");
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", detail);
    }

    /** A new guest cart and its token, which is shown this once. */
    public record GuestCart(String token, CartView cart) {
    }
}
