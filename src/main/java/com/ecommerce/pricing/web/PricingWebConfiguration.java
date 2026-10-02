package com.ecommerce.pricing.web;

import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.HttpAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PricingWebConfiguration {

    @Bean
    HttpAccessRules pricingAccessRules() {
        return rules -> rules.requestMatchers("/v1/admin/pricing/**").hasRole(CallerRole.ADMIN.name());
    }
}
