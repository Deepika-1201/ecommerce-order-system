package com.ecommerce.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Fulfillment's settings (LLD §8.13): the defaults, blank secrets dropped, and secrets never in {@code toString}. */
class FulfillmentPropertiesTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Settings.class);

    @Test
    void defaultsLeaveTheSimulatorStillAndEveryWebhookRefused() {
        context.run(started -> {
            FulfillmentProperties properties = started.getBean(FulfillmentProperties.class);
            assertThat(properties.bookingBudget()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.webhookTolerance()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.webhookSecrets()).isEmpty();
            assertThat(properties.simulator().autoAdvance()).isFalse();
        });
    }

    @Test
    void blankSecretsAreDroppedAndNoneIsShown() {
        context.withPropertyValues("ecom.fulfillment.webhook-secrets=carrier_current, , carrier_previous,")
                .run(started -> {
                    FulfillmentProperties properties = started.getBean(FulfillmentProperties.class);
                    assertThat(properties.webhookSecrets()).containsExactly("carrier_current", "carrier_previous");
                    assertThat(properties.toString()).doesNotContain("carrier_current", "carrier_previous")
                            .contains("webhookSecrets=2");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FulfillmentProperties.class)
    static class Settings {
    }
}
