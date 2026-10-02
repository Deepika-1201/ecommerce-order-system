package com.ecommerce.pricing.domain;

import com.ecommerce.shared.IndianState;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Pricing settings (LLD §4.13). An unknown warehouse state code fails startup. */
@Validated
@ConfigurationProperties(prefix = "ecom.pricing")
record PricingProperties(
        @DefaultValue("29") String shipFromState,
        @DefaultValue("10m") Duration quoteValidity,
        @DefaultValue @NotNull Shipping shipping) {

    PricingProperties {
        IndianState.fromCode(shipFromState).orElseThrow(() ->
                new IllegalArgumentException("ecom.pricing.ship-from-state is not a GST state code: " + shipFromState));
    }

    IndianState supplyState() {
        return IndianState.fromCode(shipFromState).orElseThrow();
    }

    /** A GST-inclusive fee, waived from a goods total after discount. */
    record Shipping(@DefaultValue("4900") @PositiveOrZero long feePaise,
            @DefaultValue("49900") @PositiveOrZero long freeFromPaise) {
    }
}
