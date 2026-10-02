package com.ecommerce.pricing;

import java.util.Optional;
import java.util.UUID;

/** Quotes, for Cart and, from phase 6, Ordering (LLD §4.5). */
public interface Quotes {

    /**
     * Prices the cart and stores the quote. Fails with {@code cart_has_unavailable_items} or a coupon error
     * (LLD §4.9) as an {@code ApiException}.
     */
    Quote create(QuoteRequest request);

    /** The quote, only if it belongs to this customer. */
    Optional<Quote> findForCustomer(UUID quoteId, UUID customerId);

    /** The quote, only if it belongs to this guest cart. */
    Optional<Quote> findForGuestCart(UUID quoteId, UUID cartId);
}
