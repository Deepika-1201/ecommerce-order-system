package com.ecommerce.customer.web;

import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.HttpAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
class CustomerAccessConfiguration {

    @Bean
    HttpAccessRules customerAccessRules() {
        return rules -> rules
                .requestMatchers(HttpMethod.GET, "/v1/states").permitAll()
                .requestMatchers("/v1/me", "/v1/me/**").hasRole(CallerRole.CUSTOMER.name());
    }
}
