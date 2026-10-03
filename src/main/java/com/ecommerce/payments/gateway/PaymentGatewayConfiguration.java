package com.ecommerce.payments.gateway;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** The real gateway when its base URL is set, otherwise the fake (LLD §7.10). */
@Configuration(proxyBeanMethods = false)
class PaymentGatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfiguration.class);
    private static final String CONFIGURED = "!'${ecom.payments.gateway.base-url:}'.isBlank()";

    @Bean
    @ConditionalOnExpression(CONFIGURED)
    HttpPaymentGateway httpPaymentGateway(PaymentsProperties properties, JsonMapper json) {
        log.info("Payments use the gateway at {}", properties.gateway().baseUrl());
        return new HttpPaymentGateway(properties.gateway(), json);
    }

    @Bean
    @ConditionalOnExpression("!(" + CONFIGURED + ")")
    FakePaymentGateway fakePaymentGateway(GatewayEvents events, JsonMapper json, Clock clock) {
        log.warn("Payments use the in-memory fake gateway: no money moves (set ecom.payments.gateway.base-url)");
        return new FakePaymentGateway(events, json, clock);
    }
}
