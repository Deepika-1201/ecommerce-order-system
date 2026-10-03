package com.ecommerce.cart.web;

import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.HttpAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CartWebConfiguration {

    /** Guest carts need no access token: the cart token is checked by the controller (ADR-020). */
    @Bean
    HttpAccessRules cartAccessRules() {
        return rules -> rules
                .requestMatchers("/v1/me/cart", "/v1/me/cart/**").hasRole(CallerRole.CUSTOMER.name())
                .requestMatchers("/v1/guest/cart", "/v1/guest/cart/**").permitAll();
    }
}
