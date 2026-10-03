package com.ecommerce.payments.gateway;

import com.ecommerce.payments.gateway.GatewayWire.CancelPaymentJson;
import com.ecommerce.payments.gateway.GatewayWire.CheckoutSessionJson;
import com.ecommerce.payments.gateway.GatewayWire.CreateCheckoutSessionJson;
import com.ecommerce.payments.gateway.GatewayWire.CreatePaymentJson;
import com.ecommerce.payments.gateway.GatewayWire.CreateRefundJson;
import com.ecommerce.payments.gateway.GatewayWire.CustomerJson;
import com.ecommerce.payments.gateway.GatewayWire.PaymentJson;
import com.ecommerce.payments.gateway.GatewayWire.ProblemJson;
import com.ecommerce.payments.gateway.GatewayWire.RefundJson;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway's merchant API over HTTP (LLD §7.3): bearer API key, an {@code Idempotency-Key} on every {@code POST},
 * snake-case JSON. Neither the key nor the {@code Authorization} header is logged.
 */
class HttpPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

    private final RestClient client;
    private final JsonMapper json;

    HttpPaymentGateway(PaymentsProperties.Gateway properties, JsonMapper json) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.json = json;
    }

    @Override
    public GatewayPayment createPayment(NewPayment payment, String idempotencyKey) {
        CreatePaymentJson body = new CreatePaymentJson(payment.amountPaise(), "INR", payment.orderId().toString(),
                "automatic", new CustomerJson(payment.customerId().toString()), payment.expiresIn().toSeconds());
        return GatewayWire.payment(call(HttpMethod.POST, "/v1/payments", body, idempotencyKey, PaymentJson.class));
    }

    @Override
    public CheckoutSession createCheckoutSession(String paymentId, String returnUrl, String idempotencyKey) {
        CheckoutSessionJson session = call(HttpMethod.POST, "/v1/checkout-sessions",
                new CreateCheckoutSessionJson(paymentId, returnUrl), idempotencyKey, CheckoutSessionJson.class);
        return new CheckoutSession(session.id(), session.url());
    }

    @Override
    public GatewayPayment cancelPayment(String paymentId, String idempotencyKey) {
        return GatewayWire.payment(call(HttpMethod.POST, "/v1/payments/" + paymentId + "/cancel",
                new CancelPaymentJson("requested_by_customer"), idempotencyKey, PaymentJson.class));
    }

    @Override
    public GatewayPayment payment(String paymentId) {
        return GatewayWire.payment(call(HttpMethod.GET, "/v1/payments/" + paymentId, null, null, PaymentJson.class));
    }

    @Override
    public GatewayRefund createRefund(String paymentId, NewRefund refund, String idempotencyKey) {
        CreateRefundJson body = new CreateRefundJson(refund.amountPaise(), refund.reason(), refund.merchantRefundId());
        return GatewayWire.refund(call(HttpMethod.POST, "/v1/payments/" + paymentId + "/refunds", body,
                idempotencyKey, RefundJson.class));
    }

    private <T> T call(HttpMethod method, String path, Object body, String idempotencyKey, Class<T> type) {
        RestClient.RequestBodySpec request = client.method(method).uri(path);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
        }
        try {
            return request.exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                if (response.getStatusCode().is2xxSuccessful()) {
                    return read(text, type, method, path);
                }
                throw failure(status, text, method, path);
            });
        } catch (ResourceAccessException e) {
            log.warn("Gateway {} {} failed: {}", method, path, e.getMessage());
            throw new GatewayUnavailableException("Gateway " + method + " " + path + " failed", e);
        }
    }

    private <T> T read(String text, Class<T> type, HttpMethod method, String path) {
        try {
            return json.readValue(text, type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new GatewayUnavailableException("Gateway " + method + " " + path + " answered unreadable JSON", e);
        }
    }

    /** {@code 429}, {@code 5xx} and a key still in progress are worth retrying with the same key; other answers stay. */
    private RuntimeException failure(int status, String text, HttpMethod method, String path) {
        String code = problemCode(text);
        log.warn("Gateway {} {} answered {} {}", method, path, status, code);
        if (status == 429 || status >= 500 || "idempotency_request_in_progress".equals(code)) {
            return new GatewayUnavailableException("Gateway " + method + " " + path + " answered " + status + " "
                    + code);
        }
        return new GatewayRefusedException(status, code, null);
    }

    private String problemCode(String text) {
        try {
            String code = json.readValue(text, ProblemJson.class).code();
            return code == null ? "unknown" : code;
        } catch (JacksonException e) {
            return "unknown";
        }
    }
}
