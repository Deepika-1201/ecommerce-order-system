package com.ecommerce.pricing.domain;

import com.ecommerce.pricing.CouponRedemptions;
import com.ecommerce.pricing.CouponReservation;
import com.ecommerce.pricing.CouponReservation.Reason;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coupon uses held, committed and released per order, with the counters of stock reservations (LLD §4.10, ADR-009).
 * Reservations of one coupon serialize on its row: the conditional update locks it until commit.
 */
@Service
class CouponRedemptionService implements CouponRedemptions {

    private final CouponRepository coupons;
    private final Clock clock;

    CouponRedemptionService(CouponRepository coupons, Clock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CouponReservation reserve(UUID orderId, UUID couponId, UUID customerId) {
        Optional<String> existing = coupons.redemptionStatus(orderId);
        if (existing.isPresent()) {
            return existing.get().equals("RELEASED") ? new CouponReservation.Unavailable(Reason.RELEASED)
                    : new CouponReservation.Held();
        }
        Instant now = clock.instant();
        if (!coupons.takeUse(couponId, now)) {
            return new CouponReservation.Unavailable(reasonUnavailable(couponId, now));
        }
        Coupon coupon = coupons.find(couponId).orElseThrow();
        if (coupon.perCustomerLimit() != null) {
            if (customerId == null) {
                throw new IllegalArgumentException("A coupon with a per-customer limit needs a customer");
            }
            if (coupons.usesBy(couponId, customerId) >= coupon.perCustomerLimit()) {
                coupons.giveBackReserved(couponId, now);
                return new CouponReservation.Unavailable(Reason.ALREADY_USED);
            }
        }
        coupons.insertRedemption(Ids.newId(), couponId, orderId, customerId, now);
        return new CouponReservation.Held();
    }

    @Override
    @Transactional
    public void commit(UUID orderId) {
        Instant now = clock.instant();
        coupons.transition(orderId, List.of("HELD"), "COMMITTED", now)
                .ifPresent(held -> coupons.moveReservedToRedeemed(held.couponId(), now));
    }

    @Override
    @Transactional
    public void release(UUID orderId) {
        Instant now = clock.instant();
        coupons.transition(orderId, List.of("HELD", "COMMITTED"), "RELEASED", now).ifPresent(released -> {
            if (released.previousStatus().equals("HELD")) {
                coupons.giveBackReserved(released.couponId(), now);
            } else {
                coupons.giveBackRedeemed(released.couponId(), now);
            }
        });
    }

    private Reason reasonUnavailable(UUID couponId, Instant now) {
        Coupon coupon = coupons.find(couponId).orElseThrow(() ->
                new IllegalArgumentException("No coupon " + couponId));
        if (!coupon.active()) {
            return Reason.INACTIVE;
        }
        if (coupon.notYetValid(now) || coupon.expired(now)) {
            return Reason.OUTSIDE_WINDOW;
        }
        return Reason.EXHAUSTED;
    }
}
