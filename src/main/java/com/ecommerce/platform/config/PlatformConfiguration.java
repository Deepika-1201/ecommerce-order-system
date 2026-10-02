package com.ecommerce.platform.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {

    /** UTC, in microseconds: the precision PostgreSQL stores, so values read back equal values written (LLD §2.10). */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
