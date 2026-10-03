package com.ecommerce.cart.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** How long a cart lives without activity (Q6, LLD §4.13). */
@ConfigurationProperties(prefix = "ecom.cart")
record CartProperties(@DefaultValue("30d") Duration guestLifetime, @DefaultValue("90d") Duration customerLifetime) {

    Duration lifetime(CartOwner owner) {
        return owner instanceof CartOwner.Customer ? customerLifetime : guestLifetime;
    }
}
