package com.ecommerce.ordering.web;

import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.HttpAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OrderingWebConfiguration {

    /** Customers see their own orders; support sees any order. Other staff roles get 403 (LLD §6.9). */
    @Bean
    HttpAccessRules orderingAccessRules() {
        return rules -> rules
                .requestMatchers("/v1/me/orders", "/v1/me/orders/**").hasRole(CallerRole.CUSTOMER.name())
                .requestMatchers("/v1/support/orders/**").hasRole(CallerRole.SUPPORT.name());
    }
}
