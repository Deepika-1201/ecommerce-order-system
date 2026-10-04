package com.ecommerce.fulfillment.web;

import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.HttpAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
class FulfillmentWebConfiguration {

    /** The carrier's webhook carries no token: its signature authenticates it (LLD §8.8). */
    @Bean
    HttpAccessRules fulfillmentAccessRules() {
        return rules -> rules
                .requestMatchers(HttpMethod.POST, CarrierWebhookController.PATH).permitAll()
                .requestMatchers("/v1/warehouse/shipments/**").hasRole(CallerRole.WAREHOUSE.name())
                .requestMatchers("/v1/support/shipments/**").hasRole(CallerRole.SUPPORT.name());
    }
}
