package com.ecommerce.pricing.web;

import com.ecommerce.platform.ApiController;
import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.Caller;
import com.ecommerce.pricing.domain.Coupon;
import com.ecommerce.pricing.domain.CouponCommands.CouponChanges;
import com.ecommerce.pricing.domain.CouponCommands.NewCoupon;
import com.ecommerce.pricing.domain.CouponService;
import com.ecommerce.pricing.web.PricingApi.CouponList;
import com.ecommerce.pricing.web.PricingApi.CouponResponse;
import com.ecommerce.pricing.web.PricingApi.CreateCoupon;
import com.ecommerce.pricing.web.PricingApi.UpdateCoupon;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Coupon administration (LLD §4.9). */
@ApiController
@RequestMapping("/v1/admin/pricing/coupons")
@Tag(name = "Pricing administration", description = "Role admin. Coupons and their usage.")
@SecurityRequirement(name = ApiController.BEARER_AUTH)
class CouponAdminController {

    private final CouponService coupons;

    CouponAdminController(CouponService coupons) {
        this.coupons = coupons;
    }

    @Operation(summary = "List coupons, newest first")
    @GetMapping
    CouponList listCoupons(
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
            @RequestParam(required = false) String cursor) {
        List<Coupon> page = coupons.list(decode(cursor), limit);
        boolean more = page.size() > limit;
        List<Coupon> items = more ? page.subList(0, limit) : page;
        return new CouponList(items.stream().map(CouponResponse::of).toList(),
                more ? encode(items.getLast().id()) : null);
    }

    @Operation(summary = "Create a coupon; its rule never changes afterwards")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<CouponResponse> createCoupon(Caller admin, @Valid @RequestBody CreateCoupon request) {
        Coupon coupon = coupons.create(admin, new NewCoupon(request.code(), request.kind(), request.percentBps(),
                request.maxDiscountPaise(), request.amountPaise(), request.minOrderPaise(), request.validFrom(),
                request.validUntil(), request.totalLimit(), request.perCustomerLimit(), request.active()));
        return ResponseEntity.created(URI.create("/v1/admin/pricing/coupons/" + coupon.id()))
                .body(CouponResponse.of(coupon));
    }

    @Operation(summary = "Read a coupon with its usage")
    @GetMapping("/{id}")
    CouponResponse getCoupon(@PathVariable UUID id) {
        return CouponResponse.of(coupons.get(id));
    }

    @Operation(summary = "Switch a coupon on or off, move its end, or change its limit")
    @PatchMapping("/{id}")
    CouponResponse updateCoupon(Caller admin, @PathVariable UUID id, @Valid @RequestBody UpdateCoupon request) {
        return CouponResponse.of(coupons.update(admin, id,
                new CouponChanges(request.active(), request.validUntil(), request.totalLimit())));
    }

    private static String encode(UUID after) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(after.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static UUID decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "This cursor was not issued by this API.");
        }
    }
}
