package com.ecommerce.pricing.domain;

import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.AuditEntry;
import com.ecommerce.platform.AuditLog;
import com.ecommerce.platform.Caller;
import com.ecommerce.pricing.Coupons;
import com.ecommerce.pricing.domain.CouponCommands.CouponChanges;
import com.ecommerce.pricing.domain.CouponCommands.Kind;
import com.ecommerce.pricing.domain.CouponCommands.NewCoupon;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coupon administration, and the checks made when a coupon is applied or quoted (LLD §4.9). */
@Service
public class CouponService implements Coupons {

    private final CouponRepository coupons;
    private final AuditLog audit;
    private final Clock clock;

    CouponService(CouponRepository coupons, AuditLog audit, Clock clock) {
        this.coupons = coupons;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Coupon create(Caller admin, NewCoupon request) {
        Instant now = clock.instant();
        Coupon coupon = new Coupon(Ids.newId(), request.code().toUpperCase(Locale.ROOT), rule(request),
                request.minOrderPaise() == null ? 0 : request.minOrderPaise(),
                request.validFrom() == null ? now : request.validFrom(), request.validUntil(), request.totalLimit(),
                request.perCustomerLimit(), request.active() == null || request.active(), 0, 0, 1, now, now);
        if (coupon.validUntil() != null && !coupon.validUntil().isAfter(coupon.validFrom())) {
            throw invalidRule("valid_until must be after valid_from.");
        }
        coupons.insert(coupon);
        record(admin, "pricing.coupon.created", coupon.id(), details("code", coupon.code(), "kind",
                request.kind(), "total_limit", coupon.totalLimit(), "per_customer_limit", coupon.perCustomerLimit()));
        return coupons.find(coupon.id()).orElseThrow();
    }

    /** {@code null} leaves a field unchanged; a limit can never go below current usage. */
    @Transactional
    public Coupon update(Caller admin, UUID id, CouponChanges changes) {
        Coupon coupon = coupons.lock(id).orElseThrow(CouponService::notFound);
        boolean active = changes.active() != null ? changes.active() : coupon.active();
        Instant validUntil = changes.validUntil() != null ? changes.validUntil() : coupon.validUntil();
        Integer totalLimit = changes.totalLimit() != null ? changes.totalLimit() : coupon.totalLimit();
        if (validUntil != null && !validUntil.isAfter(coupon.validFrom())) {
            throw invalidRule("valid_until must be after valid_from.");
        }
        if (totalLimit != null && totalLimit < coupon.reserved() + coupon.redeemed()) {
            throw new ApiException(HttpStatus.CONFLICT, "limit_below_usage", "The coupon is already used "
                    + (coupon.reserved() + coupon.redeemed()) + " times.");
        }
        coupons.update(id, active, validUntil, totalLimit, clock.instant());
        record(admin, "pricing.coupon.updated", id, details("active", active, "valid_until", validUntil,
                "total_limit", totalLimit));
        return coupons.find(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Coupon get(UUID id) {
        return coupons.find(id).orElseThrow(CouponService::notFound);
    }

    @Transactional(readOnly = true)
    public List<Coupon> list(UUID after, int limit) {
        return coupons.list(after, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public String checkApplicable(String code, boolean signedIn) {
        return applicable(code, signedIn).code();
    }

    /** The apply-time checks: exists, active, in its window, and the sign-in rule. */
    Coupon applicable(String code, boolean signedIn) {
        Instant now = clock.instant();
        Coupon coupon = coupons.findByCode(code.strip().toUpperCase(Locale.ROOT))
                .filter(Coupon::active)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "coupon_not_found",
                        "No such coupon."));
        if (coupon.notYetValid(now)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "coupon_not_yet_valid",
                    "This coupon is not valid yet.");
        }
        if (coupon.expired(now)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "coupon_expired", "This coupon has expired.");
        }
        if (coupon.perCustomerLimit() != null && !signedIn) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "coupon_requires_sign_in",
                    "Sign in to use this coupon.");
        }
        return coupon;
    }

    /** The quote-time checks: the apply-time ones, the minimum order and both limits. */
    Coupon quotable(String code, UUID customerId, long grossPaise) {
        Coupon coupon = applicable(code, customerId != null);
        if (grossPaise < coupon.minOrderPaise()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "coupon_minimum_not_met",
                    "This coupon needs an order of at least " + rupees(coupon.minOrderPaise()) + ".");
        }
        if (coupon.exhausted()) {
            throw new ApiException(HttpStatus.CONFLICT, "coupon_exhausted", "This coupon has been used up.");
        }
        if (coupon.perCustomerLimit() != null && coupons.usesBy(coupon.id(), customerId) >= coupon.perCustomerLimit()) {
            throw new ApiException(HttpStatus.CONFLICT, "coupon_already_used",
                    "You have already used this coupon as often as it allows.");
        }
        return coupon;
    }

    private static DiscountRule rule(NewCoupon request) {
        if (request.kind() == Kind.PERCENT) {
            if (request.percentBps() == null || request.amountPaise() != null) {
                throw invalidRule("A PERCENT coupon has percent_bps and no amount_paise.");
            }
            return new DiscountRule.Percent(request.percentBps(), request.maxDiscountPaise());
        }
        if (request.amountPaise() == null || request.percentBps() != null || request.maxDiscountPaise() != null) {
            throw invalidRule("A FLAT coupon has amount_paise and neither percent_bps nor max_discount_paise.");
        }
        return new DiscountRule.Flat(request.amountPaise());
    }

    private void record(Caller admin, String action, UUID couponId, Map<String, Object> details) {
        audit.record(new AuditEntry("staff", admin.subject(), action, "coupon", couponId.toString(), null, details));
    }

    private static Map<String, Object> details(Object... keysAndValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            details.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return details;
    }

    private static String rupees(long paise) {
        return paise % 100 == 0 ? "₹" + paise / 100 : String.format(Locale.ROOT, "₹%d.%02d", paise / 100, paise % 100);
    }

    private static ApiException invalidRule(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_coupon_rule", detail);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such coupon.");
    }
}
