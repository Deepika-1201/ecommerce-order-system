package com.ecommerce.payments.gateway;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Payments' settings (LLD §7.12). Without a gateway base URL, the fake gateway is used (§7.10). */
@ConfigurationProperties(prefix = "ecom.payments")
public record PaymentsProperties(
        @DefaultValue("http://localhost:3000/orders/{order_id}") String returnUrl,
        @DefaultValue("60s") Duration creationBudget,
        @DefaultValue("5m") Duration webhookTolerance,
        @DefaultValue Gateway gateway) {

    public PaymentsProperties {
        if (!returnUrl.startsWith("https://") && !returnUrl.startsWith("http://")) {
            throw new IllegalArgumentException("ecom.payments.return-url must be an http(s) URL");
        }
    }

    /** The storefront's page for the order, where the hosted checkout sends the customer back. */
    public String returnUrl(UUID orderId) {
        return returnUrl.replace("{order_id}", orderId.toString());
    }

    /** The gateway's address and this merchant's secrets, which are never logged. */
    public record Gateway(
            String baseUrl,
            String apiKey,
            List<String> webhookSecrets,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {

        public Gateway {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.strip();
            webhookSecrets = webhookSecrets == null ? List.of()
                    : webhookSecrets.stream().map(String::strip).filter(secret -> !secret.isEmpty()).toList();
            if (baseUrl != null && (apiKey == null || apiKey.isBlank() || webhookSecrets.isEmpty())) {
                throw new IllegalArgumentException("ecom.payments.gateway.base-url needs an api-key and at least one "
                        + "webhook secret");
            }
        }

        @Override
        public String toString() {
            return "Gateway[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "unset" : "***")
                    + ", webhookSecrets=" + webhookSecrets.size() + ", connectTimeout=" + connectTimeout
                    + ", readTimeout=" + readTimeout + "]";
        }
    }
}
