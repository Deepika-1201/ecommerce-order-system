package com.ecommerce.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.payments.gateway.PaymentGateway.CheckoutSession;
import com.ecommerce.payments.gateway.PaymentGateway.NewPayment;
import com.ecommerce.payments.gateway.PaymentGateway.NewRefund;
import com.ecommerce.platform.ReceivedWebhook;
import com.ecommerce.platform.WebhookInbox;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP adapter against a stub of the gateway (LLD §7.16): every request, and every answer the stub gives, is
 * checked against a pinned copy of the gateway's OpenAPI document; each documented error maps to refused or
 * unavailable. The fake's events are held to the same document.
 */
class GatewayContractTests {

    private static final String DOCUMENT = "https://contracts.invalid/payment-gateway/openapi.yaml";
    private static final SchemaRegistry CONTRACT = SchemaRegistry.withDialect(Dialects.getOpenApi31(),
            builder -> builder.schemas(Map.of(DOCUMENT, resource("/contracts/payment-gateway-openapi.yaml"))));
    /** As the application configures it: snake case, nulls left out, unknown fields ignored. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final String API_KEY = "sk_test_contract";
    private static final UUID ORDER = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
    private static final UUID CUSTOMER = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
    private static final String PAYMENT_ID = "pay_01K5Z9V4J6Q2X8N3M7B1C0D4E5";

    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Answer answer = new Answer(500, "{}", Duration.ZERO);
    private HttpServer server;
    private HttpPaymentGateway gateway;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), body));
            Answer current = answer;
            sleep(current.delay());
            byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", current.status() < 300
                    ? "application/json" : "application/problem+json");
            exchange.sendResponseHeaders(current.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        gateway = gateway(Duration.ofSeconds(2));
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void creatingAPaymentSendsTheOrderAsTheContractAsks() {
        answer = ok(201, payment("requires_payment_method", 1));

        GatewayPayment payment = gateway.createPayment(new NewPayment(ORDER, CUSTOMER, 59_900,
                Duration.ofMinutes(15)), ORDER + ":payment");

        Recorded request = onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/payments");
        assertSigned(request, ORDER + ":payment");
        assertValid("CreatePaymentRequest", request.body());
        JsonNode body = JSON.readTree(request.body());
        assertThat(body.get("amount").asLong()).isEqualTo(59_900);
        assertThat(body.get("currency").asString()).isEqualTo("INR");
        assertThat(body.get("merchant_order_id").asString()).isEqualTo(ORDER.toString());
        assertThat(body.get("capture_method").asString()).isEqualTo("automatic");
        assertThat(body.get("customer").get("reference").asString()).isEqualTo(CUSTOMER.toString());
        assertThat(body.get("expires_in_seconds").asLong()).isEqualTo(900);
        assertThat(payment).isEqualTo(new GatewayPayment(PAYMENT_ID, ORDER.toString(), 59_900,
                GatewayPayment.Status.REQUIRES_PAYMENT_METHOD, 1));
    }

    @Test
    void aCheckoutSessionIsCreatedForThePaymentWithTheReturnUrl() {
        String session = """
                {"id": "cs_01K5Z9V4J6Q2X8N3M7B1C0D4E9", "object": "checkout_session", "payment_id": "%s",
                 "url": "http://localhost:8090/checkout/tok_abc", "return_url": "http://localhost:3000/orders/%s",
                 "expires_at": "2026-10-03T10:15:00Z", "created_at": "2026-10-03T10:00:00Z"}
                """.formatted(PAYMENT_ID, ORDER);
        answer = ok(201, session);

        CheckoutSession created = gateway.createCheckoutSession(PAYMENT_ID, "http://localhost:3000/orders/" + ORDER,
                ORDER + ":checkout");

        Recorded request = onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/checkout-sessions");
        assertSigned(request, ORDER + ":checkout");
        assertValid("CreateCheckoutSessionRequest", request.body());
        assertThat(JSON.readTree(request.body()).get("return_url").asString())
                .isEqualTo("http://localhost:3000/orders/" + ORDER);
        assertValid("CheckoutSession", session);
        assertThat(created).isEqualTo(new CheckoutSession("cs_01K5Z9V4J6Q2X8N3M7B1C0D4E9",
                "http://localhost:8090/checkout/tok_abc"));
    }

    @Test
    void aCancelNamesThePaymentAndAReasonTheContractKnows() {
        answer = ok(200, payment("cancelled", 3));

        GatewayPayment cancelled = gateway.cancelPayment(PAYMENT_ID, ORDER + ":cancel:2");

        Recorded request = onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/payments/" + PAYMENT_ID + "/cancel");
        assertSigned(request, ORDER + ":cancel:2");
        assertValid("CancelPaymentRequest", request.body());
        assertThat(cancelled.status()).isEqualTo(GatewayPayment.Status.CANCELLED);
        assertThat(cancelled.version()).isEqualTo(3);
    }

    @Test
    void readingAPaymentIsAPlainGet() {
        answer = ok(200, payment("succeeded", 4));

        GatewayPayment read = gateway.payment(PAYMENT_ID);

        Recorded request = onlyRequest();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/v1/payments/" + PAYMENT_ID);
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(request.headers().containsKey("Idempotency-Key")).isFalse();
        assertThat(request.body()).isEmpty();
        assertThat(read.status()).isEqualTo(GatewayPayment.Status.SUCCEEDED);
    }

    @Test
    void aRefundCarriesTheAmountTheReasonAndThisSystemsRefundId() {
        String refund = """
                {"id": "rfnd_01K5Z9V4J6Q2X8N3M7B1C0D4F1", "object": "refund", "payment_id": "%s",
                 "attempt_id": "att_01K5Z9V4J6Q2X8N3M7B1C0D4F2", "amount": 59900, "currency": "INR",
                 "status": "initiated", "reason": "order_cancelled", "merchant_refund_id": "%s:ORDER_CANCELLED",
                 "initiated_by": "merchant", "provider": "MOCK_ALPHA", "created_at": "2026-10-03T11:00:00Z",
                 "updated_at": "2026-10-03T11:00:00Z", "version": 1}
                """.formatted(PAYMENT_ID, ORDER);
        answer = ok(201, refund);

        GatewayRefund created = gateway.createRefund(PAYMENT_ID, new NewRefund(59_900, "order_cancelled",
                ORDER + ":ORDER_CANCELLED"), ORDER + ":refund:ORDER_CANCELLED");

        Recorded request = onlyRequest();
        assertThat(request.path()).isEqualTo("/v1/payments/" + PAYMENT_ID + "/refunds");
        assertSigned(request, ORDER + ":refund:ORDER_CANCELLED");
        assertValid("CreateRefundRequest", request.body());
        assertValid("Refund", refund);
        assertThat(created).isEqualTo(new GatewayRefund("rfnd_01K5Z9V4J6Q2X8N3M7B1C0D4F1", PAYMENT_ID, 59_900,
                GatewayRefund.Status.INITIATED, GatewayRefund.Initiator.MERCHANT, ORDER + ":ORDER_CANCELLED", 1));
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource({
        "400, validation_error, refused",
        "401, invalid_api_key, refused",
        "404, resource_not_found, refused",
        "409, payment_invalid_state, refused",
        "409, refund_already_exists, refused",
        "409, idempotency_request_in_progress, unavailable",
        "422, idempotency_key_reuse, refused",
        "422, amount_exceeds_refundable, refused",
        "429, rate_limited, unavailable",
        "500, internal_error, unavailable",
        "503, no_provider_available, unavailable"
    })
    void eachDocumentedErrorIsRefusedOrWorthRetrying(int status, String code, String kind) {
        String problem = """
                {"type": "about:blank", "title": "Error", "status": %d, "code": "%s", "detail": "As documented",
                 "request_id": "req_contract"}
                """.formatted(status, code);
        assertValid("Problem", problem);
        answer = new Answer(status, problem, Duration.ZERO);

        if (kind.equals("refused")) {
            assertThatThrownBy(() -> gateway.payment(PAYMENT_ID))
                    .isInstanceOfSatisfying(GatewayRefusedException.class, refused -> {
                        assertThat(refused.status()).isEqualTo(status);
                        assertThat(refused.code()).isEqualTo(code);
                    });
        } else {
            assertThatThrownBy(() -> gateway.payment(PAYMENT_ID)).isInstanceOf(GatewayUnavailableException.class);
        }
    }

    @Test
    void answersThatAreNotTheGatewaysAreWorthRetrying() {
        answer = new Answer(502, "<html>Bad gateway</html>", Duration.ZERO);
        assertThatThrownBy(() -> gateway.payment(PAYMENT_ID)).isInstanceOf(GatewayUnavailableException.class);

        answer = new Answer(200, "{\"id\": ", Duration.ZERO);
        assertThatThrownBy(() -> gateway.payment(PAYMENT_ID)).isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void aSlowOrUnreachableGatewayIsUnavailable() {
        HttpPaymentGateway impatient = gateway(Duration.ofMillis(300));
        answer = new Answer(200, payment("succeeded", 2), Duration.ofSeconds(2));
        assertThatThrownBy(() -> impatient.payment(PAYMENT_ID)).isInstanceOf(GatewayUnavailableException.class);

        server.stop(0);
        assertThatThrownBy(() -> gateway.payment(PAYMENT_ID)).isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void theFakesEventsFollowTheContract() {
        List<String> bodies = new ArrayList<>();
        FakePaymentGateway fake = new FakePaymentGateway(new GatewayEvents(capturing(bodies), JSON), JSON,
                Clock.systemUTC());
        UUID paid = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        GatewayPayment payment = fake.createPayment(new NewPayment(paid, CUSTOMER, 59_900, Duration.ofMinutes(15)),
                paid + ":payment");
        fake.createPayment(new NewPayment(expired, CUSTOMER, 59_900, Duration.ofMinutes(15)), expired + ":payment");

        fake.startAttempt(paid);
        fake.failAttempt(paid);
        fake.succeed(paid);
        fake.createRefund(payment.id(), new NewRefund(59_900, "order_cancelled", paid + ":ORDER_CANCELLED"),
                paid + ":refund:ORDER_CANCELLED");
        fake.succeedRefund(paid);
        fake.expire(expired);
        fake.succeed(expired);
        fake.failRefund(expired);

        assertThat(bodies).extracting(body -> JSON.readTree(body).get("type").asString()).containsExactly(
                "payment.attempt_failed", "payment.succeeded", "refund.succeeded", "payment.expired",
                "refund.failed");
        bodies.forEach(body -> assertValid("Event", body));
        assertThat(JSON.readTree(bodies.getLast()).get("data").get("object").get("initiated_by").asString())
                .isEqualTo("system_late_success");
    }

    @Test
    void theContractRejectsWhatItDoesNotDescribe() {
        Schema request = CONTRACT.getSchema(SchemaLocation.of(DOCUMENT + "#/components/schemas/CreatePaymentRequest"));
        Schema event = CONTRACT.getSchema(SchemaLocation.of(DOCUMENT + "#/components/schemas/Event"));

        assertThat(request.validate("{\"amount\": 50, \"currency\": \"INR\"}", InputFormat.JSON))
                .as("below the minimum, and no merchant_order_id").hasSizeGreaterThanOrEqualTo(2);
        assertThat(event.validate("""
                {"id": "evt_1", "type": "payment.paid", "created_at": "2026-10-03T10:00:00Z",
                 "data": {"object": {"id": "pay_1", "object": "payment"}}}
                """, InputFormat.JSON)).as("an unknown type, and a payment missing its fields").isNotEmpty();
    }

    private HttpPaymentGateway gateway(Duration readTimeout) {
        return new HttpPaymentGateway(new PaymentsProperties.Gateway("http://localhost:" + server.getAddress().getPort(),
                API_KEY, List.of("whsec_contract"), Duration.ofSeconds(1), readTimeout), JSON);
    }

    private Recorded onlyRequest() {
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    /** The bearer key, and an Idempotency-Key the contract accepts. */
    private static void assertSigned(Recorded request, String idempotencyKey) {
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(request.headers().getFirst("Idempotency-Key")).isEqualTo(idempotencyKey);
        assertThat(request.headers().getFirst("Content-Type")).startsWith("application/json");
        assertValid(CONTRACT.getSchema(SchemaLocation.of(DOCUMENT + "#/components/parameters/IdempotencyKey/schema")),
                "IdempotencyKey", JSON.writeValueAsString(idempotencyKey));
    }

    private static void assertValid(String component, String json) {
        assertValid(CONTRACT.getSchema(SchemaLocation.of(DOCUMENT + "#/components/schemas/" + component)), component,
                json);
    }

    private static void assertValid(Schema schema, String name, String json) {
        List<com.networknt.schema.Error> errors = schema.validate(json, InputFormat.JSON);
        assertThat(errors).as(name + " " + json).isEmpty();
    }

    private static String payment(String status, long version) {
        String payment = """
                {"id": "%s", "object": "payment", "merchant_order_id": "%s", "amount": 59900, "currency": "INR",
                 "status": "%s", "capture_method": "automatic", "description": "Order", "customer": {"reference": "%s"},
                 "metadata": {}, "amount_captured": 0, "amount_refunded": 0, "attempt_count": 1,
                 "latest_attempt": {"id": "att_01K5Z9V4J6Q2X8N3M7B1C0D4F2", "attempt_number": 1, "status": "initiated",
                                    "method": "upi", "provider": "MOCK_ALPHA", "created_at": "2026-10-03T10:01:00Z"},
                 "expires_at": "2026-10-03T10:15:00Z", "created_at": "2026-10-03T10:00:00Z",
                 "updated_at": "2026-10-03T10:01:00Z", "version": %d}
                """.formatted(PAYMENT_ID, ORDER, status, CUSTOMER, version);
        assertValid("Payment", payment);
        return payment;
    }

    private static Answer ok(int status, String body) {
        return new Answer(status, body, Duration.ZERO);
    }

    private static WebhookInbox capturing(List<String> bodies) {
        return new WebhookInbox() {
            @Override
            public boolean store(ReceivedWebhook webhook, String body, String taskType) {
                bodies.add(body);
                return true;
            }

            @Override
            public String body(ReceivedWebhook webhook) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void markProcessed(ReceivedWebhook webhook) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static String resource(String path) {
        try (InputStream in = GatewayContractTests.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Recorded(String method, String path, Headers headers, String body) {
    }

    private record Answer(int status, String body, Duration delay) {
    }
}
