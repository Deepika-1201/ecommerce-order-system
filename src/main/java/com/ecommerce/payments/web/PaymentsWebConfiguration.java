package com.ecommerce.payments.web;

import com.ecommerce.platform.HttpAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
class PaymentsWebConfiguration {

    /** The gateway's webhooks carry no token: their signature authenticates them (ADR-002, LLD §7.7). */
    @Bean
    HttpAccessRules paymentsAccessRules() {
        return rules -> rules.requestMatchers(HttpMethod.POST, GatewayWebhookController.PATH).permitAll();
    }
}
