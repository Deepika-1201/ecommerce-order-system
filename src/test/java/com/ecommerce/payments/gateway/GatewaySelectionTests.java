package com.ecommerce.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.ReceivedWebhook;
import com.ecommerce.platform.WebhookInbox;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Which gateway Payments talks to (LLD §7.10, §7.12), and the settings it refuses to start without. */
class GatewaySelectionTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class, PaymentGatewayConfiguration.class, GatewayEvents.class);

    @Test
    void withoutABaseUrlTheFakeGatewayIsUsed() {
        context.run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started).hasSingleBean(PaymentGateway.class);
            assertThat(started.getBean(PaymentGateway.class)).isInstanceOf(FakePaymentGateway.class);
        });
    }

    @Test
    void aBaseUrlWithItsSecretsSelectsTheRealGateway() {
        context.withPropertyValues("ecom.payments.gateway.base-url=http://payment-gateway:8090",
                        "ecom.payments.gateway.api-key=sk_test_selection",
                        "ecom.payments.gateway.webhook-secrets=whsec_selection")
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    assertThat(started).hasSingleBean(PaymentGateway.class);
                    assertThat(started.getBean(PaymentGateway.class)).isInstanceOf(HttpPaymentGateway.class);
                    assertThat(started.getBean(PaymentsProperties.class).gateway().toString())
                            .doesNotContain("sk_test_selection", "whsec_selection");
                });
    }

    @Test
    void aBaseUrlWithoutAnApiKeyOrAWebhookSecretStopsTheStart() {
        context.withPropertyValues("ecom.payments.gateway.base-url=http://payment-gateway:8090",
                        "ecom.payments.gateway.webhook-secrets=whsec_selection")
                .run(started -> assertThat(started).hasFailed());
        context.withPropertyValues("ecom.payments.gateway.base-url=http://payment-gateway:8090",
                        "ecom.payments.gateway.api-key=sk_test_selection")
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void blankSettingsCountAsUnset() {
        context.withPropertyValues("ecom.payments.gateway.base-url= ")
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    assertThat(started.getBean(PaymentGateway.class)).isInstanceOf(FakePaymentGateway.class);
                });
        context.withPropertyValues("ecom.payments.gateway.base-url=http://payment-gateway:8090",
                        "ecom.payments.gateway.api-key=sk_test_selection",
                        "ecom.payments.gateway.webhook-secrets=whsec_selection, ")
                .run(started -> assertThat(started.getBean(PaymentsProperties.class).gateway().webhookSecrets())
                        .containsExactly("whsec_selection"));
        context.withPropertyValues("ecom.payments.gateway.base-url=http://payment-gateway:8090",
                        "ecom.payments.gateway.api-key=sk_test_selection",
                        "ecom.payments.gateway.webhook-secrets= , ")
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void theCheckoutReturnsToTheStorefrontsPageForTheOrder() {
        context.withPropertyValues("ecom.payments.return-url=https://shop.example/orders/{order_id}/paid")
                .run(started -> {
                    UUID orderId = UUID.randomUUID();
                    assertThat(started.getBean(PaymentsProperties.class).returnUrl(orderId))
                            .isEqualTo("https://shop.example/orders/" + orderId + "/paid");
                });
        context.withPropertyValues("ecom.payments.return-url=ftp://shop.example/orders/{order_id}")
                .run(started -> assertThat(started).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentsProperties.class)
    static class Collaborators {

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        WebhookInbox webhookInbox() {
            return new WebhookInbox() {
                @Override
                public boolean store(ReceivedWebhook webhook, String body, String taskType) {
                    return true;
                }

                @Override
                public String body(ReceivedWebhook webhook) {
                    return "{}";
                }

                @Override
                public void markProcessed(ReceivedWebhook webhook) {
                    // Nothing to mark.
                }
            };
        }
    }
}
