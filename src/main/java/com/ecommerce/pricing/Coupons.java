package com.ecommerce.pricing;

/** Coupon checks for carts (LLD §4.9). */
public interface Coupons {

    /**
     * Checks that a code exists, is active and in its window, and that a per-customer coupon has a signed-in
     * customer. Returns the code as stored (upper-cased); otherwise fails with a coupon error code.
     */
    String checkApplicable(String code, boolean signedIn);
}
